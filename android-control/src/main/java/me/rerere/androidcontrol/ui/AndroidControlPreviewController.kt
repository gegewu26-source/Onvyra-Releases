// Overlay placement, focus and drag lifecycle adapted from Mika (wangzan101/Mika).
// Original revision 688f59d5a18d99d0231504598da4059f3feb226e. Apache-2.0.
package me.rerere.androidcontrol.ui

import android.app.KeyguardManager
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import me.rerere.androidcontrol.accessibility.PreviewCaptureRetryPolicy
import me.rerere.androidcontrol.accessibility.PreviewNativeRetryPolicy

/** The provider transfers ownership of [bitmap] to the preview; it is never sent to a model or disk. */
data class AndroidControlPreviewFrame(
    val bitmap: Bitmap? = null,
    val message: String = "等待后台画面…",
    val skipped: Boolean = false,
    val captureFailure: String? = null,
)

data class AndroidControlPreviewState(
    val displayId: Int? = null,
    val bitmap: Bitmap? = null,
    val message: String = "",
    val action: String = "",
    val expanded: Boolean = true,
    val visible: Boolean = false,
    val nativeStreaming: Boolean = false,
    val renderedFrames: Long = 0,
    val windowBounds: Rect? = null,
    val contentBounds: Rect? = null,
)

/**
 * A focusless, display-scoped preview hosted by the user's already-authorized accessibility service.
 * Native preview renders the owned NONSECURE display into a TextureView after a protected-window
 * screenshot authorizes visibility. Protected source layers remain system-redacted; Agent/OCR
 * screenshots still use the protected accessibility API. A bitmap path supports older helpers.
 * UI callbacks run on Main. Call show/hide/updateAction from any thread.
 */
class AndroidControlPreviewController(context: Context, private val onStop: () -> Unit) {
    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        // An unavailable vendor overlay is optional; never crash the chat/Agent process.
        mainHandler.post { runCatching { closeOnMain() } }
    })
    private val epoch = AtomicLong()
    private val mutableState = MutableStateFlow(AndroidControlPreviewState())
    val state: StateFlow<AndroidControlPreviewState> = mutableState.asStateFlow()
    private var session: PreviewSession? = null
    private var frameJob: Job? = null
    private var host: PreviewHost? = null
    private var recoveryDisplayId: Int? = null
    private var recoveryAttempts = 0
    private var action = "正在操作"
    private val displays = app.getSystemService(DisplayManager::class.java)

    private data class PreviewSession(
        val token: Long,
        val displayId: Int,
        val width: Int,
        val height: Int,
        var appPackage: String?,
        var capture: suspend () -> AndroidControlPreviewFrame,
        var nativeOutput: (suspend (Surface?) -> Boolean)?,
        val alive: AtomicBoolean = AtomicBoolean(true),
        val surfaceLock: Mutex = Mutex(),
        var boundSurface: Surface? = null,
        @Volatile var nativeAllowed: Boolean = false,
        val nativeRetry: PreviewNativeRetryPolicy = PreviewNativeRetryPolicy(),
    )

    /** Call only for this controller's owned, successfully opened virtual display. */
    fun show(displayId: Int, width: Int, height: Int,
             appPackage: String? = null,
             nativeOutput: (suspend (Surface?) -> Boolean)? = null,
             capture: suspend () -> AndroidControlPreviewFrame) {
        if (displayId <= 0 || width <= 0 || height <= 0 || Build.VERSION.SDK_INT < 34) return
        val calledAt = epoch.get()
        scope.launch {
            if (epoch.get() != calledAt) return@launch
            session?.takeIf { it.displayId == displayId }?.let {
                it.capture = capture
                it.nativeOutput = nativeOutput
                it.appPackage = appPackage
                host?.setSourceApp(appPackage)
                return@launch
            }
            closeOnMain()
            val service = AndroidControlAccessibilityService.instance ?: return@launch
            if (recoveryDisplayId != displayId) {
                recoveryDisplayId = displayId
                recoveryAttempts = 0
            }
            val next = PreviewSession(epoch.incrementAndGet(), displayId, width, height, appPackage, capture, nativeOutput)
            val window = PreviewHost(service, width, height, appPackage, enableNative = nativeOutput != null,
                onRetireSurface = { surface, texture -> retireSurface(next, surface, texture) },
                onGeometryChanged = { bounds, content ->
                    if (session === next) mutableState.value = mutableState.value.copy(
                        windowBounds = bounds, contentBounds = content)
                },
                onCollapse = { expanded ->
                next.nativeAllowed = false
                if (!expanded) scope.launch(Dispatchers.IO) { detachNative(next) }
                mutableState.value = mutableState.value.copy(bitmap = null, expanded = expanded, nativeStreaming = false)
            }, onStop = {
                // Hide first; a late screenshot cannot redraw a stopped task.
                hide()
                onStop()
            })
            if (!window.open()) return@launch
            session = next
            host = window
            mutableState.value = AndroidControlPreviewState(displayId = displayId,
                message = "等待后台画面…", action = action, visible = true,
                windowBounds = window.windowBounds, contentBounds = window.contentBounds)
            frameJob = launch { collectFrames(next, service) }
        }
    }

    fun updateAction(label: String) {
        scope.launch {
            action = label.take(48)
            host?.setAction(action)
            mutableState.value = mutableState.value.copy(action = action)
        }
    }

    /** Removes the window and any image when stopped, taken over, disabled or disconnected. */
    fun hide() {
        val stoppedBefore = epoch.incrementAndGet()
        scope.launch {
            // A new task may be queued by another thread before this close reaches Main.
            // Retire only an older window, never that newer task's window.
            if ((session?.token ?: Long.MIN_VALUE) < stoppedBefore) {
                closeOnMain()
                recoveryDisplayId = null
                recoveryAttempts = 0
            }
        }
    }

    private fun closeOnMain() {
        val previous = session
        previous?.alive?.set(false)
        previous?.nativeAllowed = false
        frameJob?.cancel()
        frameJob = null
        session = null
        if (previous != null) scope.launch(Dispatchers.IO) { detachNative(previous) }
        host?.close()
        host = null
        mutableState.value = AndroidControlPreviewState()
    }

    /** Binder calls and Surface release share one per-session lock, including late close callbacks. */
    private suspend fun detachNative(expected: PreviewSession, only: Surface? = null): Boolean =
        expected.surfaceLock.withLock {
            if (expected.boundSurface != null && (only == null || expected.boundSurface === only)) {
                val restored = runCatching { expected.nativeOutput?.invoke(null) == true }.getOrDefault(false)
                if (!restored) return@withLock false
                expected.boundSurface = null
            }
            true
        }

    private fun retireSurface(expected: PreviewSession, surface: Surface, texture: SurfaceTexture) {
        scope.launch(Dispatchers.IO) {
            // A failed Binder restore is not an acknowledgment. Retain the consumer until
            // output is restored or the integration confirms that this display/producer died.
            while (!detachNative(expected, surface)) delay(250)
            surface.release()
            texture.release()
        }
    }

    private suspend fun attachNative(expected: PreviewSession, surface: Surface): Boolean = withContext(Dispatchers.IO) {
        expected.surfaceLock.withLock {
            if (!expected.alive.get() || !expected.nativeAllowed || session !== expected ||
                epoch.get() != expected.token || !surface.isValid) return@withLock false
            if (expected.boundSurface === surface) return@withLock true
            val accepted = runCatching { expected.nativeOutput?.invoke(surface) == true }.getOrDefault(false)
            if (accepted) expected.boundSurface = surface
            accepted
        }
    }

    private suspend fun collectFrames(expected: PreviewSession, service: AndroidControlAccessibilityService) {
        var lastAttempt = 0L
        var lastAllowed = 0L
        var authorized = false
        var captureMessage = "正在等待应用窗口…"
        var captureFailure: String? = null
        val captureRetry = PreviewCaptureRetryPolicy()
        while (scope.isActive && session === expected && epoch.get() == expected.token) {
            val window = host ?: break
            // Collapse can be followed by expansion between two polls. Its invalidation must
            // survive that round-trip and force a fresh protected-window authorization.
            if (authorized && !expected.nativeAllowed) { authorized = false; lastAttempt = 0L }
            if (AndroidControlAccessibilityService.instance !== service || !window.isAttached) {
                recoverPreview(expected)
                return
            }
            // The target rotates independently of the phone/overlay Configuration. Poll the
            // owned display alongside visibility, including while native streaming is active.
            displays?.getDisplay(expected.displayId)?.takeIf { it.isValid }?.let { display ->
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                if (size.x > 0 && size.y > 0) window.setDisplayGeometry(size.x, size.y, display.rotation)
            }
            val mayShow = screenMayShow()
            window.setVisible(mayShow)
            if (!mayShow || !window.expanded) {
                expected.nativeAllowed = false
                withContext(Dispatchers.IO) { detachNative(expected) }
                authorized = false
                lastAttempt = 0L
                window.clearPicture(if (mayShow) "预览已收起" else "屏幕已锁定")
                mutableState.value = mutableState.value.copy(bitmap = null, visible = mayShow,
                    expanded = window.expanded, nativeStreaming = false)
                delay(POLL_INTERVAL_MS)
                continue
            }
            val output = window.nativeSurface
            val now = SystemClock.elapsedRealtime()
            val wantsNative = expected.nativeOutput != null && output?.isValid == true && expected.nativeRetry.retryReady(now)
            // Initial windows and later refreshes each get a bounded transient-recovery episode.
            // During native-bind cooldown, successful bitmaps remain the normal preview fallback.
            val retryInterval = captureRetry.intervalMs(authorized, captureFailure, now)
            val interval = if (!authorized || wantsNative) retryInterval else FRAME_INTERVAL_MS
            if (now - lastAttempt >= interval) {
                lastAttempt = now
                val frame = try { expected.capture() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { AndroidControlPreviewFrame(message = "画面暂不可用") }
                if (session !== expected || epoch.get() != expected.token || !expected.alive.get()) {
                    frame.bitmap?.recycle()
                    return
                }
                if (!screenMayShow() || !window.expanded) {
                    frame.bitmap?.recycle()
                    expected.nativeAllowed = false
                    withContext(Dispatchers.IO) { detachNative(expected) }
                    window.clearPicture("预览已隐藏")
                    authorized = false
                    continue
                }
                if (!frame.skipped) {
                    captureMessage = frame.message
                    captureFailure = frame.captureFailure
                    authorized = frame.bitmap != null
                    val capturedAt = SystemClock.elapsedRealtime()
                    captureRetry.onCaptureResult(authorized, capturedAt)
                    if (authorized) {
                        lastAllowed = capturedAt
                        expected.nativeRetry.onProtectedCaptureSucceeded()
                    }
                    if (wantsNative) {
                        // A static display may not queue a new buffer after the Surface swap.
                        // Keep one protected, bounded poster until the first real native frame;
                        // once streaming, guards are recycled without making per-frame bitmaps.
                        if (authorized && !window.hasNativeFrame && mutableState.value.bitmap == null) {
                            val poster = frame.bitmap?.let { scaleForPreview(it) }
                            if (session !== expected || epoch.get() != expected.token || !screenMayShow() || !window.expanded) {
                                poster?.recycle()
                                expected.nativeAllowed = false
                                authorized = false
                                continue
                            }
                            window.setPicture(poster, frame.message)
                            mutableState.value = mutableState.value.copy(bitmap = poster, message = frame.message)
                        } else frame.bitmap?.recycle()
                        if (!authorized) window.clearPicture(frame.message)
                    } else {
                        val bitmap = frame.bitmap?.let { scaleForPreview(it) }
                        if (session !== expected || epoch.get() != expected.token || !screenMayShow() || !window.expanded) {
                            bitmap?.recycle()
                            continue
                        }
                        window.setPicture(bitmap, frame.message)
                        mutableState.value = mutableState.value.copy(bitmap = bitmap, message = frame.message)
                    }
                }
            }
            // A busy/disconnected screenshot service cannot leave a stale native authorization open forever.
            if (SystemClock.elapsedRealtime() - lastAllowed > MAX_GUARD_AGE_MS) authorized = false
            expected.nativeAllowed = authorized
            val streaming = if (wantsNative && expected.nativeRetry.canAttempt(SystemClock.elapsedRealtime(), authorized, output?.isValid == true)) {
                attachNative(expected, requireNotNull(output)).also { accepted ->
                    if (accepted) expected.nativeRetry.onAttachResult(true, SystemClock.elapsedRealtime())
                    else if (expected.alive.get() && expected.nativeAllowed && window.expanded &&
                        output === window.nativeSurface && output?.isValid == true)
                        expected.nativeRetry.onAttachResult(false, SystemClock.elapsedRealtime())
                }
            } else false
            if (!streaming) {
                window.showNative(false)
                if (!authorized) window.clearPicture(captureMessage)
                withContext(Dispatchers.IO) { detachNative(expected) }
            }
            if (session !== expected || epoch.get() != expected.token) return
            // The Binder round-trip can outlive a collapse/lock callback on Main. Revalidate
            // after it returns so a late attach cannot reveal or republish that retired view.
            if (!screenMayShow() || !window.expanded || (authorized && !expected.nativeAllowed)) {
                expected.nativeAllowed = false
                withContext(Dispatchers.IO) { detachNative(expected) }
                if (session !== expected || epoch.get() != expected.token) return
                val visible = screenMayShow()
                window.setVisible(visible)
                window.clearPicture(if (visible) "预览已收起或正在重新检查" else "屏幕已锁定")
                mutableState.value = mutableState.value.copy(bitmap = null, nativeStreaming = false,
                    visible = visible, expanded = window.expanded)
                authorized = false
                lastAttempt = 0L
                delay(POLL_INTERVAL_MS)
                continue
            }
            window.showNative(streaming)
            if (!authorized) window.clearPicture(captureMessage)
            mutableState.value = mutableState.value.copy(
                bitmap = if ((streaming && window.hasNativeFrame) || !authorized) null else mutableState.value.bitmap,
                nativeStreaming = streaming, renderedFrames = window.renderedFrames,
                visible = true, expanded = true, action = action,
                message = captureMessage,
            )
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Rebuild only the overlay after Android replaces its service/context; retain the task VD. */
    private fun recoverPreview(expected: PreviewSession) {
        // This is a sibling of frameJob: closing the old host cancels that collector, never this wait.
        scope.launch {
            if (session !== expected || epoch.get() != expected.token) return@launch
            val attempt = ++recoveryAttempts
            closeOnMain() // Removes stale pixels and restores the helper's drain before reconnecting.
            if (attempt > 3) return@launch
            val restartEpoch = epoch.get()
            // Synchronize with the old consumer's retirement before a new host can attach.
            // A late old detach must never replace the replacement's surface with the drain.
            if (!withContext(Dispatchers.IO) { detachNative(expected) }) return@launch
            val connected = AndroidControlAccessibilityService.awaitConnected(3_000) ?: return@launch
            if (epoch.get() != restartEpoch || session != null ||
                AndroidControlAccessibilityService.instance !== connected) return@launch
            show(expected.displayId, expected.width, expected.height,
                appPackage = expected.appPackage, nativeOutput = expected.nativeOutput, capture = expected.capture)
        }
    }

    private suspend fun scaleForPreview(source: Bitmap): Bitmap? = try {
        withContext(Dispatchers.Default) {
            val size = previewBitmapSize(source.width, source.height, MAX_BITMAP_WIDTH)
            if (size.first == source.width && size.second == source.height) source.copy(Bitmap.Config.ARGB_8888, false)
            else Bitmap.createScaledBitmap(source, size.first, size.second, true)
        }
    } finally { source.recycle() }

    private fun screenMayShow(): Boolean {
        if (app.getSystemService(PowerManager::class.java)?.isInteractive != true) return false
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceLocked != true && keyguard?.isKeyguardLocked != true
    }

    private class PreviewHost(
        private val service: AndroidControlAccessibilityService,
        private var displayWidth: Int,
        private var displayHeight: Int,
        appPackage: String?,
        private val enableNative: Boolean,
        private val onRetireSurface: (Surface, SurfaceTexture) -> Unit,
        private val onGeometryChanged: (Rect?, Rect?) -> Unit,
        private val onCollapse: (Boolean) -> Unit,
        private val onStop: () -> Unit,
    ) {
        private val nativeBufferWidth = displayWidth
        private val nativeBufferHeight = displayHeight
        private var displayRotation = 0
        private val manager = service.getSystemService(WindowManager::class.java)
        private val density = service.resources.displayMetrics.density
        private val prefs = service.getSharedPreferences("android_control_preview_layout", Context.MODE_PRIVATE)
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            fitInsetsTypes = 0
            // Centered resizing moves the origin on every frame. A separate system
            // position animation would chase those updates instead of following the drag.
            if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
        }
        // The compositor owns one fixed main-display surface. Dragging and resizing happen
        // inside it; changing the WM surface bounds on every MOVE can desynchronise its
        // position, buffer and native child projection on vendor window managers.
        private val hostParams = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
        }
        private var hostBounds: Rect? = null
        private var touchableBounds: Rect? = null
        private var firstRegionDrawn = false
        private var touchActivationPosted = false
        private var hostLayoutUpdates = 0
        private val root = object : FrameLayout(service) {
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                // addView starts NOT_TOUCHABLE. Never expose a full-screen input region,
                // including the first frame while the panel has not been laid out yet.
                if (!updateTouchableRegion()) post {
                    if (attached && !updateTouchableRegion()) close()
                }
            }
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                post {
                    // Some vendors dispatch a window-size Configuration while the overlay
                    // itself is being resized. It must not restore the last saved frame or
                    // cancel a live drag. Only a changed main-display area needs migration.
                    if (floatingArea != area() || hostBounds != mainDisplayBounds()) {
                        cancelResizeGesture()
                        cancelIconIdle()
                        updateGeometry()
                        if (!expanded) armIconIdle()
                    }
                }
            }
            override fun onDetachedFromWindow() {
                super.onDetachedFromWindow()
                attached = false
                touchableBounds = null
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val panel = FrameLayout(service).apply {
            contentDescription = "手机操控预览窗口"
            background = GradientDrawable().apply { setColor(Color.rgb(29, 31, 37)); cornerRadius = dp(16).toFloat() }
            clipToOutline = true
            elevation = dp(8).toFloat()
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    // The panel may deliberately be parked partly beyond the screen. Report
                    // its full frame, as the former WM window did, not the canvas's frame.
                    measuredBounds(host)?.let(info::setBoundsInScreen)
                }
            }
        }
        private val expandedBody = FrameLayout(service)
        private val header = FrameLayout(service).apply {
            contentDescription = "窗口菜单"
            val threeDots = TextView(service).apply {
                text = "···"
                textSize = 20f
                setTextColor(Color.rgb(210, 210, 210))
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
            }
            addView(threeDots, FrameLayout.LayoutParams(dp(64), dp(28), Gravity.CENTER))
        }
        private val image = ImageView(service).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "后台操作实时预览"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private var currentTexture: SurfaceTexture? = null
        var nativeSurface: Surface? = null
            private set
        var renderedFrames = 0L
            private set
        private var displayingNative = false
        private var nativeFrameBaseline = 0L
        val hasNativeFrame: Boolean get() = renderedFrames > nativeFrameBaseline
        private val retiredTextures = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SurfaceTexture, Boolean>())
        private val texture = if (enableNative) TextureView(service).apply {
            isOpaque = true
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val target = this
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                    value.setDefaultBufferSize(nativeBufferWidth, nativeBufferHeight)
                    fitNativeSurface(target, width, height)
                    currentTexture = value
                    nativeSurface = Surface(value)
                    renderedFrames = 0L
                    nativeFrameBaseline = 0L
                }
                override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) {
                    // TextureView.onSizeChanged resets the producer's default buffer to the
                    // view size. The virtual display still renders at its original resolution.
                    // Keep that producer geometry fixed while scaling only the consumer view.
                    value.setDefaultBufferSize(nativeBufferWidth, nativeBufferHeight)
                    fitNativeSurface(target, width, height)
                }
                override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                    retire(value)
                    return false
                }
                override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                    if (value === currentTexture) {
                        renderedFrames++
                        if (displayingNative && hasNativeFrame && image.visibility != View.GONE) {
                            image.setImageDrawable(null)
                            image.visibility = View.GONE
                            placeholder.visibility = View.GONE
                        }
                    }
                }
            }
        } else null
        private val placeholder = text("等待后台画面…", 12f).apply { gravity = Gravity.CENTER; setPadding(dp(12),dp(12),dp(12),dp(12)) }
        private val picture = object : FrameLayout(service) {
            override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
                super.onSizeChanged(width, height, oldWidth, oldHeight)
                scaleNativeConsumer(width, height)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            // Keep TextureView's logical size stable during an overlay drag. Changing its
            // layout size would first resize SurfaceTexture's default buffer to the view,
            // then restore the source buffer in the listener, on every MOVE. Scale only
            // the consumer view; its producer Surface and native buffer remain unchanged.
            texture?.let { addView(it, FrameLayout.LayoutParams(displayWidth, displayHeight, Gravity.TOP or Gravity.LEFT)) }
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        private val bottomBar = FrameLayout(service).apply {
            contentDescription = "调整预览大小"
            val handleView = View(service).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(180, 180, 180))
                    cornerRadius = dp(2).toFloat()
                }
            }
            addView(handleView, FrameLayout.LayoutParams(dp(64), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        private val menuContent = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        private val menuPopup = ScrollView(service).apply {
            isFillViewport = false
            addView(menuContent)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 225))
            }
            elevation = dp(14).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                dp(130),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(30)
            }
            visibility = View.GONE
        }
        private val collapsedBody = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        private val handle = ImageView(service).apply {
            contentDescription = "展开操作预览"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_DISMISS, "停止手机操作"))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_DISMISS) { onStop(); return true }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        }
        private val collapsedStop = stopButton().apply { visibility = View.GONE }
        private var dockSide = PreviewDockSide.RIGHT
        private var dockCenterY: Int? = null
        private var iconHalfHidden = false
        private var collapsedMenuOpen = false
        private var iconSlide: ValueAnimator? = null
        private val hideIcon = Runnable { slideIconHalfOutside() }
        private var attached = false
        var expanded = true
            private set
        val isAttached: Boolean get() = attached
        private var isFullScreen = false
        private var lastDragEvent = "none"
        private var preFullScreenBounds: Rect? = null
        private var publishedBounds: Rect? = null
        private var customWidth = runCatching { prefs.getInt("custom_width", 0) }.getOrDefault(0)
        private var floatingOutsideX = runCatching { prefs.getFloat("outside_x_fraction", Float.NaN) }.getOrNull()
            ?.takeIf { it.isFinite() && it != 0f && it in -0.5f..0.5f }
        private var manuallyResized = false
        private val fullScreenItem: View
        private val requestedBounds: Rect get() = Rect(params.x, params.y, params.x + params.width, params.y + params.height)
        val windowBounds: Rect? get() = measuredBounds(panel)
        val contentBounds: Rect? get() = if (expanded) measuredBounds(picture) else null

        private fun measuredBounds(view: View): Rect? {
            if (!attached || !view.isLaidOut || view.width <= 0 || view.height <= 0) return null
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }

        init {
            expandedBody.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Draw only the resize grip over the picture. A separate full-width strip
            // makes the window taller and leaves a dark band below the source display.
            expandedBody.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28), Gravity.TOP))
            expandedBody.addView(bottomBar, FrameLayout.LayoutParams(dp(64), dp(20), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

            fullScreenItem = createMenuItem("全屏", PreviewMenuIcon.FULLSCREEN) { toggleFullScreen() }
            menuContent.addView(fullScreenItem)
            val divider = View(service).apply {
                setBackgroundColor(Color.rgb(238, 238, 238))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    setMargins(dp(12), 0, dp(12), 0)
                }
            }
            menuContent.addView(divider)
            menuContent.addView(createMenuItem("最小化", PreviewMenuIcon.MINIMIZE) { setExpanded(false); hideMenu() })
            menuContent.addView(createMenuItem("关闭", PreviewMenuIcon.CLOSE) { onStop() })

            setSourceApp(appPackage)
            collapsedBody.addView(handle, LinearLayout.LayoutParams(dp(56), dp(56)))
            collapsedBody.addView(collapsedStop, LinearLayout.LayoutParams(dp(56), dp(38)))

            panel.addView(expandedBody, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            panel.addView(menuPopup)
            panel.addView(collapsedBody)
            root.addView(panel, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
            collapsedBody.visibility = View.GONE

            draggable(header, click = { toggleMenu() })
            draggable(handle, click = { setExpanded(true) }, longClick = { showCollapsedControls() })
            picture.setOnClickListener { hideMenu() }
            root.viewTreeObserver.addOnGlobalLayoutListener {
                if (attached) {
                    applyPanelGeometry()
                    if (!updateTouchableRegion()) close()
                    else onGeometryChanged(windowBounds, contentBounds)
                }
            }
            root.viewTreeObserver.addOnPreDrawListener {
                if (attached && !firstRegionDrawn && !touchActivationPosted && touchableBounds != null) {
                    val drawnRegion = Rect(requireNotNull(touchableBounds))
                    touchActivationPosted = true
                    // setTouchableRegion requests ViewRoot's insets traversal. Keep the WM
                    // window inert until that traversal has completed, rather than clearing
                    // NOT_TOUCHABLE in onAttached before restricted insets reach the server.
                    root.post {
                        touchActivationPosted = false
                        if (attached && root.isAttachedToWindow && touchableBounds == drawnRegion) {
                            firstRegionDrawn = true
                            if (!updateTouchableRegion()) close()
                        }
                    }
                }
                true
            }
            setupResizeHandle()
        }

        private fun createMenuItem(label: String, icon: PreviewMenuIcon, action: () -> Unit): View {
            val item = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = when (label) {
                    "关闭" -> "停止手机操作"
                    "最小化" -> "收起操作预览"
                    else -> label
                }
                setOnClickListener { action() }
            }
            val iconView = ImageView(service).apply {
                setImageDrawable(PreviewMenuIconDrawable(icon))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
            }
            val labelView = TextView(service).apply {
                text = label
                textSize = 14f
                setTextColor(Color.rgb(30, 30, 30))
            }
            item.addView(iconView)
            item.addView(labelView)
            return item
        }

        private fun hideMenu() {
            if (menuPopup.visibility == View.VISIBLE) {
                menuPopup.visibility = View.GONE
            }
        }

        private fun showMenu() {
            sizeMenu()
            menuPopup.visibility = View.VISIBLE
            menuPopup.bringToFront()
        }

        private fun sizeMenu() {
            val width = minOf(dp(130), params.width).coerceAtLeast(1)
            val top = minOf(dp(30), params.height / 4).coerceAtLeast(0)
            menuContent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            menuPopup.layoutParams = FrameLayout.LayoutParams(width,
                minOf(menuContent.measuredHeight, params.height - top).coerceAtLeast(1),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top }
        }

        private fun toggleMenu() {
            if (menuPopup.visibility == View.VISIBLE) hideMenu() else showMenu()
        }

        private fun toggleFullScreen() {
            hideMenu()
            if (!isFullScreen) {
                saveFloatingPosition()
                preFullScreenBounds = requestedBounds
                isFullScreen = true
            } else {
                isFullScreen = false
                preFullScreenBounds?.let { floatingBounds = Rect(it) }
            }
            updateGeometry()
            setFullScreenLabel()
        }

        private fun setFullScreenLabel() {
            val label = if (isFullScreen) "退出全屏" else "全屏"
            ((fullScreenItem as LinearLayout).getChildAt(1) as TextView).text = label
            fullScreenItem.contentDescription = label
        }

        private var cancelResizeGesture: () -> Unit = {}

        @Suppress("ClickableViewAccessibility")
        private fun setupResizeHandle() {
            val slop = ViewConfiguration.get(service).scaledTouchSlop
            var startY = 0f
            var startWidth = 0
            var startHeight = 0
            var startX = 0
            var startTop = 0
            var startPreferredWidth = 0
            var gestureArea = Rect()
            var gestureDisplayWidth = 0
            var gestureDisplayHeight = 0
            var pointer = -1
            var tracking = false
            var resizing = false
            var frameQueued = false
            var pendingGeometry: PreviewResizeGeometry? = null
            val applyResizeFrame = Runnable {
                frameQueued = false
                val next = pendingGeometry
                pendingGeometry = null
                if (next != null && tracking && attached && expanded && !isFullScreen) {
                    params.width = next.width
                    params.height = next.height
                    params.x = next.x
                    params.y = next.y
                    // A configuration/layout callback must see this live frame, not the
                    // persisted pre-drag size. Preferences are still saved only on release.
                    floatingBounds = requestedBounds
                    floatingArea = Rect(gestureArea)
                    customWidth = next.width
                    val travelX = gestureArea.width() - next.width
                    val travelY = gestureArea.height() - next.height
                    if (travelX > 0) floatingAnchorX = previewPositionFraction(next.x, gestureArea.left, travelX)
                    if (travelY > 0) floatingAnchorY = previewPositionFraction(next.y, gestureArea.top, travelY)
                    floatingOutsideX = previewOutsideFraction(next.x, next.width, gestureArea.left, gestureArea.right)
                    publishGeometry()
                }
            }
            fun flushResizeFrame() {
                root.removeCallbacks(applyResizeFrame)
                applyResizeFrame.run()
            }
            fun updateResize(rawY: Float) {
                pendingGeometry = resizePreviewFromBottom(startX, startTop, startWidth, startHeight,
                    rawY - startY, gestureDisplayWidth, gestureDisplayHeight,
                    gestureArea.left, gestureArea.top, gestureArea.right, gestureArea.bottom,
                    minimumWidth())
                // Input may arrive faster than the renderer. Commit one coherent latest
                // window/picture geometry per frame instead of interleaving many relayouts.
                if (!frameQueued) {
                    frameQueued = true
                    root.postOnAnimation(applyResizeFrame)
                }
            }
            val activate = Runnable { if (tracking && attached && expanded && !isFullScreen) resizing = true }
            cancelResizeGesture = {
                tracking = false
                resizing = false
                pointer = -1
                bottomBar.removeCallbacks(activate)
                root.removeCallbacks(applyResizeFrame)
                frameQueued = false
                pendingGeometry = null
            }
            bottomBar.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        cancelResizeGesture()
                        if (isFullScreen) return@setOnTouchListener true
                        startY = event.rawY
                        startWidth = params.width
                        startHeight = params.height
                        startX = params.x
                        startTop = params.y
                        startPreferredWidth = customWidth
                        gestureArea = area()
                        gestureDisplayWidth = displayWidth
                        gestureDisplayHeight = displayHeight
                        pointer = event.getPointerId(0)
                        tracking = true
                        hideMenu()
                        bottomBar.postDelayed(activate, ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!tracking || event.findPointerIndex(pointer) < 0) return@setOnTouchListener true
                        val delta = event.rawY - startY
                        // A long press arms resizing; an ordinary vertical drag remains supported.
                        if (!resizing && kotlin.math.abs(delta) > slop) resizing = true
                        if (resizing) {
                            manuallyResized = true
                            updateResize(event.rawY)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (tracking && resizing) {
                            updateResize(event.rawY)
                            flushResizeFrame()
                            val bounds = area()
                            val minimum = floatingSize(1, bounds)
                            val shrinkToMinimum = event.rawY < startY - slop &&
                                params.width <= minimum.first && params.height <= minimum.second
                            if (shrinkToMinimum) {
                                // Shrinking fully is the same compact mode as the menu action.
                                // Restore the pre-gesture floating geometry before parking, so
                                // tapping AI expands to that useful size rather than the tiny end.
                                applyFloatingSize(startWidth, bounds)
                                params.x = startX
                                params.y = startTop
                                customWidth = startPreferredWidth
                                clamp(bounds)
                                setExpanded(false)
                            } else saveFloatingPosition(saveSize = true)
                        }
                        cancelResizeGesture()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (tracking && resizing) {
                            flushResizeFrame()
                            saveFloatingPosition(saveSize = true)
                        }
                        cancelResizeGesture()
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                        if (tracking && resizing) {
                            flushResizeFrame()
                            saveFloatingPosition(saveSize = true)
                        }
                        cancelResizeGesture()
                        true
                    }
                    else -> false
                }
            }
        }

        private var floatingBounds: Rect? = null
        private var floatingArea: Rect? = null
        private var floatingAnchorX = savedFraction("x_fraction", 1f)
        private var floatingAnchorY = savedFraction("y_fraction", .2f)

        fun setSourceApp(packageName: String?) {
            val packages = service.packageManager
            handle.setImageDrawable(runCatching {
                if (!packageName.isNullOrBlank()) packages.getApplicationIcon(packageName)
                else service.applicationInfo.loadIcon(packages)
            }.getOrElse { service.applicationInfo.loadIcon(packages) })
        }

        private fun cancelIconIdle() {
            root.removeCallbacks(hideIcon)
            iconSlide?.cancel()
            iconSlide = null
        }

        private fun armIconIdle() {
            cancelIconIdle()
            if (attached && !expanded && !collapsedMenuOpen) root.postDelayed(hideIcon, 1_800)
        }

        private fun slideIconHalfOutside() {
            if (!attached || expanded || collapsedMenuOpen) return
            val bounds = area()
            val target = previewIconX(dockSide, params.width, bounds.left, bounds.right, halfHidden = true)
            iconHalfHidden = true
            iconSlide = ValueAnimator.ofInt(params.x, target).apply {
                duration = 220
                addUpdateListener {
                    if (attached && !expanded) { params.x = it.animatedValue as Int; publishGeometry() }
                }
                start()
            }
        }

        private fun showCollapsedControls() {
            if (expanded) return
            cancelIconIdle()
            iconHalfHidden = false
            collapsedMenuOpen = true
            collapsedStop.visibility = View.VISIBLE
            updateGeometry()
        }

        fun open(): Boolean {
            val bounds = area()
            // A previous full-height custom width must not reopen as a giant portrait strip.
            // Keep its relative position and any smaller size; explicit dragging in this host
            // may still enlarge it normally. Every new host starts within half the usable height.
            if (customWidth > 0) customWidth = compactWindowSize(customWidth, bounds).first
            applyFloatingSize(expandedWidth(bounds), bounds)
            val travelX = (bounds.width() - params.width).coerceAtLeast(0)
            val travelY = (bounds.height() - params.height).coerceAtLeast(0)
            params.x = bounds.left + (travelX * savedFraction("x_fraction", 1f)).roundToInt()
            floatingOutsideX?.let { params.x = previewOutsideX(it, params.width, bounds.left, bounds.right) }
            val verticalRange = if (runCatching { prefs.getInt("position_version", 0) }.getOrDefault(0) >= 2)
                travelY else bounds.height()
            params.y = bounds.top + (verticalRange * savedFraction("y_fraction", .2f)).roundToInt()
            clamp(bounds)
            if (travelX > 0) floatingAnchorX = previewPositionFraction(params.x, bounds.left, travelX)
            if (travelY > 0) floatingAnchorY = previewPositionFraction(params.y, bounds.top, travelY)
            floatingBounds = requestedBounds
            floatingArea = Rect(bounds)
            sizeControls()
            updateHostGeometry()
            applyPanelGeometry()
            val opened = runCatching { manager.addView(root, hostParams); attached = true; true }.getOrDefault(false)
            if (opened) {
                publishedBounds = requestedBounds
                onGeometryChanged(windowBounds, contentBounds)
            }
            return opened
        }

        fun close() {
            val wasAttached = attached
            cancelIconIdle()
            cancelResizeGesture()
            attached = false
            publishedBounds = null
            touchableBounds = null
            image.setImageDrawable(null)
            if (wasAttached || root.isAttachedToWindow) runCatching { manager.removeViewImmediate(root) }
            currentTexture?.let(::retire)
        }

        private fun retire(value: SurfaceTexture) {
            if (!retiredTextures.add(value)) return
            val surface = if (value === currentTexture) nativeSurface else null
            if (value === currentTexture) { currentTexture = null; nativeSurface = null }
            if (surface == null) value.release() else onRetireSurface(surface, value)
        }
        fun setDisplayGeometry(width: Int, height: Int, rotation: Int) {
            if (width <= 0 || height <= 0 || rotation !in 0..3 ||
                (width == displayWidth && height == displayHeight && rotation == displayRotation)) return
            cancelResizeGesture()
            displayWidth = width
            displayHeight = height
            displayRotation = rotation
            if (!manuallyResized) customWidth = 0
            // Preserve the user's floating anchor, compact/fullscreen mode and task. Rotating
            // the consumer must not resize the virtual display's natural buffer (which would
            // change its natural orientation and can make landscape apps oscillate).
            updateGeometry()
            texture?.let { fitNativeSurface(it, it.width, it.height) }
        }

        private fun fitNativeSurface(target: TextureView, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val source = floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat())
            val destination = nativePreviewDestinationPoints(width, height,
                PreviewDisplayGeometry(displayWidth, displayHeight, displayRotation))
            target.setTransform(Matrix().apply { setPolyToPoly(source, 0, destination, 0, 3) })
        }
        private fun scaleNativeConsumer(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            texture?.apply {
                pivotX = 0f
                pivotY = 0f
                scaleX = width.toFloat() / displayWidth
                scaleY = height.toFloat() / displayHeight
            }
        }
        fun showNative(value: Boolean) {
            displayingNative = value
            if (value && hasNativeFrame) {
                if (image.visibility != View.GONE) {
                    image.setImageDrawable(null)
                    image.visibility = View.GONE
                    placeholder.visibility = View.GONE
                }
            } else {
                image.visibility = View.VISIBLE
                if (value) placeholder.visibility = if (image.drawable != null) View.GONE else View.VISIBLE
            }
        }

        fun setVisible(visible: Boolean) {
            root.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (attached && !updateTouchableRegion()) close()
        }
        fun setAction(action: String) = Unit
        fun setPicture(bitmap: Bitmap?, message: String) {
            showNative(false)
            image.setImageBitmap(bitmap)
            if (placeholder.text != message) placeholder.text = message
            placeholder.visibility = if (bitmap == null) View.VISIBLE else View.GONE
        }
        fun clearPicture(message: String) {
            showNative(false)
            nativeFrameBaseline = renderedFrames
            image.setImageDrawable(null)
            if (placeholder.text != message) placeholder.text = message
            placeholder.visibility = View.VISIBLE
        }

        private fun setExpanded(value: Boolean, side: PreviewDockSide = PreviewDockSide.RIGHT,
                                centerY: Int? = null, restoreBounds: Rect? = null) {
            if (expanded == value) return
            cancelIconIdle()
            cancelResizeGesture()
            hideMenu()
            if (!value) {
                if (isFullScreen) {
                    isFullScreen = false
                    preFullScreenBounds?.let { floatingBounds = Rect(it) }
                    setFullScreenLabel()
                } else if (restoreBounds != null) {
                    // The edge gesture is temporary. Restore the last useful floating window,
                    // including its exact pre-drag coordinates, when the icon is tapped.
                    params.x = restoreBounds.left
                    params.y = restoreBounds.top
                    saveFloatingPosition()
                } else saveFloatingPosition()
                dockSide = side
                dockCenterY = centerY
            }
            iconHalfHidden = false
            collapsedMenuOpen = false
            collapsedStop.visibility = View.GONE
            expanded = value
            (panel.background as? GradientDrawable)?.setColor(if (value) Color.rgb(29, 31, 37) else Color.TRANSPARENT)
            clearPicture(if (value) "等待后台画面…" else "预览已收起")
            expandedBody.visibility = if (value) View.VISIBLE else View.GONE
            collapsedBody.visibility = if (value) View.GONE else View.VISIBLE
            updateGeometry()
            onCollapse(value)
            if (!value) armIconIdle()
        }

        private fun updateGeometry() {
            updateHostGeometry()
            val bounds = area()
            if (expanded) {
                if (isFullScreen) {
                    val size = fitPreviewWindowSize(bounds.width(), displayWidth, displayHeight,
                        bounds.width(), bounds.height())
                    params.width = size.first
                    params.height = size.second
                    params.x = bounds.left + (bounds.width() - params.width) / 2
                    params.y = bounds.top + (bounds.height() - params.height) / 2
                } else {
                    val old = floatingBounds
                    applyFloatingSize(expandedWidth(bounds), bounds)
                    if (old != null) {
                        val oldArea = floatingArea
                        if (floatingOutsideX != null && (oldArea != bounds || old.width() != params.width)) {
                            params.x = previewOutsideX(requireNotNull(floatingOutsideX), params.width, bounds.left, bounds.right)
                            params.y = if (oldArea != null && oldArea != bounds) bounds.top +
                                ((bounds.height() - params.height).coerceAtLeast(0) * floatingAnchorY).roundToInt() else old.top
                        } else if (oldArea != null && oldArea != bounds) {
                            params.x = bounds.left + ((bounds.width() - params.width).coerceAtLeast(0) *
                                floatingAnchorX).roundToInt()
                            params.y = bounds.top + ((bounds.height() - params.height).coerceAtLeast(0) *
                                floatingAnchorY).roundToInt()
                        } else { params.x = old.left; params.y = old.top }
                    }
                }
            } else {
                params.width = minOf(dp(56), bounds.width()).coerceAtLeast(1)
                params.height = minOf(dp(if (collapsedMenuOpen) 94 else 56), bounds.height()).coerceAtLeast(1)
                params.x = previewIconX(dockSide, params.width, bounds.left, bounds.right, iconHalfHidden)
                params.y = (dockCenterY?.minus(params.height / 2) ?: bounds.top)
                    .coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - params.height))
            }
            if (expanded) clamp(bounds)
            if (expanded && !isFullScreen) {
                floatingBounds = requestedBounds
                floatingArea = Rect(bounds)
            }
            publishGeometry()
        }

        private fun savedFraction(key: String, fallback: Float): Float = runCatching { prefs.getFloat(key, fallback) }
            .getOrDefault(fallback).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback

        private fun expandedWidth(bounds: Rect): Int =
            if (customWidth > 0) customWidth else compactSize(bounds).first

        private fun compactSize(bounds: Rect): Pair<Int, Int> = compactWindowSize(
            (bounds.width() * if (displayWidth > displayHeight) .81f else .59f).roundToInt().coerceAtLeast(1),
            bounds,
        )

        private fun compactWindowSize(width: Int, bounds: Rect): Pair<Int, Int> = fitPreviewWindowSize(
            width, displayWidth, displayHeight, bounds.width(), (bounds.height() / 2).coerceAtLeast(1),
            minimumWidth(),
        )

        private fun minimumWidth(): Int = maxOf(dp(100), kotlin.math.ceil(dp(52).toDouble() * displayWidth / displayHeight)
            .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt())

        private fun applyFloatingSize(width: Int, bounds: Rect) {
            // Even a wide virtual display must leave distinct top and bottom touch regions.
            val size = floatingSize(width, bounds)
            params.width = size.first
            params.height = size.second
        }

        private fun floatingSize(width: Int, bounds: Rect): Pair<Int, Int> =
            fitPreviewWindowSize(width, displayWidth, displayHeight, bounds.width(), bounds.height(), minimumWidth())

        private fun mainDisplayBounds(): Rect = runCatching {
            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            val mainDisplay = requireNotNull(service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
            mainDisplay.getRealMetrics(real)
            Rect(0, 0, real.widthPixels.coerceAtLeast(1), real.heightPixels.coerceAtLeast(1))
        }.getOrElse { Rect(0, 0, service.resources.displayMetrics.widthPixels.coerceAtLeast(1), service.resources.displayMetrics.heightPixels.coerceAtLeast(1)) }

        private fun area(): Rect = runCatching {
            val metrics = manager.currentWindowMetrics
            // A service can acquire a different current WindowMetrics after another display
            // receives accessibility focus. Placement always belongs to the main display.
            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            val mainDisplay = requireNotNull(service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
            mainDisplay.getRealMetrics(real)
            val width = real.widthPixels.coerceAtLeast(1)
            val height = real.heightPixels.coerceAtLeast(1)
            val insetSource = if (metrics.bounds.width() == width && metrics.bounds.height() == height) metrics
                else service.createDisplayContext(mainDisplay).getSystemService(WindowManager::class.java).maximumWindowMetrics
            val insets = insetSource.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(insets.left, insets.top, width - insets.right, height - insets.bottom)
                .takeIf { it.width() > 0 && it.height() > 0 } ?: Rect(0, 0, width, height)
        }.getOrElse { Rect(0, 0, service.resources.displayMetrics.widthPixels.coerceAtLeast(1), service.resources.displayMetrics.heightPixels.coerceAtLeast(1)) }

        private fun clamp(bounds: Rect) {
            // Partially off-screen is a valid floating placement. A release at the
            // half-window threshold is handled by the drag gesture, not by layout.
            params.x = if (expanded && !isFullScreen) previewDragX(params.x, params.width, bounds.left, bounds.right)
                else params.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - params.width))
            params.y = params.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - params.height))
        }

        private fun sizeControls() {
            if (expanded) {
                val pictureHeight = params.height.coerceAtLeast(1)
                val bottomHeight = minOf(dp(20), pictureHeight)
                val headerHeight = minOf(dp(28), pictureHeight)
                setFrameSize(header, ViewGroup.LayoutParams.MATCH_PARENT, headerHeight, Gravity.TOP)
                setFrameSize(bottomBar, minOf(dp(64), params.width), bottomHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                setFrameSize(picture, ViewGroup.LayoutParams.MATCH_PARENT, pictureHeight, Gravity.TOP)
                // Only an actual source-display rotation/geometry change resizes this view.
                texture?.let { setFrameSize(it, displayWidth, displayHeight, Gravity.TOP or Gravity.LEFT) }
                // Use the container's measured frame, never the next requested window
                // dimensions. onSizeChanged applies its next scale in that same layout.
                scaleNativeConsumer(picture.width, picture.height)
                (header.getChildAt(0) as TextView).apply {
                    setFrameSize(this, minOf(dp(64), params.width), headerHeight, Gravity.CENTER)
                    val size = minOf(dp(20).toFloat(), headerHeight * .8f)
                    if (textSize != size) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
                }
            }
        }

        private fun setFrameSize(view: View, width: Int, height: Int, gravity: Int) {
            val previous = view.layoutParams as? FrameLayout.LayoutParams
            if (previous == null || previous.width != width || previous.height != height || previous.gravity != gravity)
                view.layoutParams = FrameLayout.LayoutParams(width, height, gravity)
        }

        private fun publishGeometry() {
            sizeControls()
            if (menuPopup.visibility == View.VISIBLE) sizeMenu()
            val next = requestedBounds
            if (next != publishedBounds) applyPanelGeometry()
            if (attached) {
                if (!updateTouchableRegion()) close()
                else onGeometryChanged(windowBounds, contentBounds)
            }
        }

        /** A real primary-display resize is the only geometric change sent to WindowManager. */
        private fun updateHostGeometry() {
            val bounds = mainDisplayBounds()
            if (hostBounds == bounds) return
            hostBounds = Rect(bounds)
            hostParams.x = bounds.left
            hostParams.y = bounds.top
            hostParams.width = bounds.width()
            hostParams.height = bounds.height()
            if (attached) {
                runCatching {
                    manager.updateViewLayout(root, hostParams)
                    hostLayoutUpdates++
                }.onFailure { close() }
            }
        }

        private fun applyPanelGeometry() {
            setFrameSize(panel, params.width.coerceAtLeast(1), params.height.coerceAtLeast(1), Gravity.TOP or Gravity.LEFT)
            val origin = intArrayOf(hostParams.x, hostParams.y)
            if (root.isLaidOut) root.getLocationOnScreen(origin)
            panel.translationX = (params.x - origin[0]).toFloat()
            panel.translationY = (params.y - origin[1]).toFloat()
            publishedBounds = requestedBounds
        }

        /** Public API 33 input regions keep every pixel outside the visible panel pass-through. */
        private fun updateTouchableRegion(): Boolean {
            if (!root.isAttachedToWindow) {
                // addView schedules the first traversal asynchronously. It is safe to wait
                // only while the new canvas still has its inert NOT_TOUCHABLE flag.
                return hostParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            }
            val surface = root.rootSurfaceControl ?: return false
            val region = if (root.visibility != View.VISIBLE) Rect() else {
                val width = if (panel.isLaidOut) panel.width else params.width
                val height = if (panel.isLaidOut) panel.height else params.height
                Rect(panel.translationX.roundToInt(), panel.translationY.roundToInt(),
                    panel.translationX.roundToInt() + width, panel.translationY.roundToInt() + height).apply {
                    if (!intersect(0, 0, hostParams.width, hostParams.height)) setEmpty()
                }
            }
            return runCatching {
                if (touchableBounds != region) {
                    surface.setTouchableRegion(Region(region))
                    touchableBounds = Rect(region)
                    if (hostParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
                        firstRegionDrawn = false
                    }
                }
                if (firstRegionDrawn && hostParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
                    hostParams.flags = hostParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    manager.updateViewLayout(root, hostParams)
                    hostLayoutUpdates++
                }
                true
            }.getOrElse {
                // No broad input fallback: leave the host inert until its owner closes it.
                hostParams.flags = hostParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                firstRegionDrawn = false
                runCatching { manager.updateViewLayout(root, hostParams) }
                false
            }
        }

        private fun saveFloatingPosition(saveSize: Boolean = false) {
            if (!expanded || isFullScreen) return
            val bounds = area()
            clamp(bounds)
            floatingBounds = requestedBounds
            floatingArea = Rect(bounds)
            if (saveSize) customWidth = params.width
            val travelX = bounds.width() - params.width
            val travelY = bounds.height() - params.height
            // A temporarily constrained landscape dimension cannot replace a user's free anchor.
            if (travelX > 0) floatingAnchorX = previewPositionFraction(params.x, bounds.left, travelX)
            if (travelY > 0) floatingAnchorY = previewPositionFraction(params.y, bounds.top, travelY)
            floatingOutsideX = previewOutsideFraction(params.x, params.width, bounds.left, bounds.right)
            val edit = prefs.edit().putInt("custom_width", customWidth).putInt("position_version", 2)
                .putFloat("x_fraction", floatingAnchorX)
                .putFloat("y_fraction", floatingAnchorY)
            if (floatingOutsideX != null) edit.putFloat("outside_x_fraction", requireNotNull(floatingOutsideX))
                else edit.remove("outside_x_fraction")
            edit.apply()
            publishGeometry()
        }

        private fun savePosition() {
            if (expanded) saveFloatingPosition()
            else {
                val bounds = area()
                clamp(bounds)
                publishGeometry()
            }
        }

        @Suppress("ClickableViewAccessibility")
        private fun draggable(view: View, click: (() -> Unit)? = null, longClick: (() -> Unit)? = null) {
            val slop = ViewConfiguration.get(service).scaledTouchSlop
            var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0; var dragging = false
            var tracking = false
            var longPressed = false
            var beforeDrag: Rect? = null
            val activateLongPress = Runnable {
                if (attached && tracking && !dragging && !expanded && longClick != null) {
                    longPressed = true
                    view.performLongClick()
                }
            }
            if (click != null) view.setOnClickListener { click() }
            if (longClick != null) view.setOnLongClickListener { longClick(); true }
            view.setOnTouchListener { _, event ->
                lastDragEvent = "${MotionEvent.actionToString(event.actionMasked)} raw=${event.rawX},${event.rawY} requested=${params.x},${params.y} size=${params.width}x${params.height}"
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.removeCallbacks(activateLongPress)
                        if (!expanded) {
                            cancelIconIdle()
                            iconHalfHidden = false
                            params.x = previewIconX(dockSide, params.width, area().left, area().right, false)
                            publishGeometry()
                        }
                        downX = event.rawX; downY = event.rawY; baseX = params.x; baseY = params.y; dragging = false
                        beforeDrag = if (expanded && !isFullScreen) Rect(requestedBounds) else null
                        longPressed = false
                        tracking = true
                        if (longClick != null) view.postDelayed(activateLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!tracking) return@setOnTouchListener true
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (hypot(dx, dy) > slop) { dragging = true; view.removeCallbacks(activateLongPress) }
                        if (dragging && !isFullScreen) {
                            hideMenu()
                            params.x = baseX + dx.roundToInt(); params.y = baseY + dy.roundToInt()
                            val bounds = area()
                            params.x = previewDragX(params.x, params.width, bounds.left, bounds.right)
                            params.y = params.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - params.height))
                            publishGeometry()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (tracking) {
                            if (dragging) {
                                val bounds = area()
                                if (expanded) {
                                    val side = previewDockSide(params.x, params.width, bounds.left, bounds.right)
                                    if (side != null) setExpanded(false, side, params.y + params.height / 2, beforeDrag)
                                    else savePosition()
                                } else {
                                    dockSide = if (params.x + params.width / 2 < bounds.centerX()) PreviewDockSide.LEFT else PreviewDockSide.RIGHT
                                    dockCenterY = params.y + params.height / 2
                                    updateGeometry()
                                    armIconIdle()
                                }
                            } else if (!longPressed && click != null) view.performClick()
                        }
                        view.removeCallbacks(activateLongPress)
                        tracking = false
                        if (!expanded) armIconIdle()
                        true
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                        view.removeCallbacks(activateLongPress)
                        // Cancellation cannot turn a moving live window into an icon.
                        if (tracking && dragging) {
                            if (expanded) savePosition()
                            else {
                                dockCenterY = params.y + params.height / 2
                                updateGeometry()
                            }
                        }
                        tracking = false
                        if (!expanded) armIconIdle()
                        true
                    }
                    else -> false
                }
            }
        }
        private fun stopButton() = text("停止",11f).apply {
            gravity=Gravity.CENTER;setTextColor(Color.rgb(255,165,165));contentDescription="停止手机操作";setOnClickListener {onStop()}
        }
        private fun text(value: String,size: Float) = TextView(service).apply {text=value;textSize=size;setTextColor(Color.WHITE)}
        private fun dp(value: Int) = (value*density).roundToInt()
    }

    private companion object {
        const val MAX_BITMAP_WIDTH = 360
        const val FRAME_INTERVAL_MS = 500L
        const val NATIVE_GUARD_INTERVAL_MS = 1_000L
        const val INITIAL_GUARD_RETRY_MS = 350L
        const val MAX_GUARD_AGE_MS = 2_500L
        const val POLL_INTERVAL_MS = 100L
    }
}

/** Preserve the whole window; never crop text or controls to fill the preview. */
internal fun previewBitmapSize(width: Int, height: Int, maxWidth: Int): Pair<Int,Int> {
    require(width>0 && height>0 && maxWidth>0)
    val targetWidth=minOf(width,maxWidth)
    return targetWidth to maxOf(1,(height.toLong()*targetWidth/width).toInt())
}
