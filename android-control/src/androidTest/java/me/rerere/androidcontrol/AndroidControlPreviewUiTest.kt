package me.rerere.androidcontrol

import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import me.rerere.androidcontrol.ui.AndroidControlPreviewController
import me.rerere.androidcontrol.ui.AndroidControlPreviewFrame
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Preview host only: synthetic frames access no other display; touches target only the own overlay. */
@RunWith(AndroidJUnit4::class)
class AndroidControlPreviewUiTest {
    private var geometryCheck = 0
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
            // UiAutomation is a separate service: the app service's window flags do not
            // grant this observer access to the non-focused accessibility overlay.
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }
    private val packageName get() = instrumentation.targetContext.packageName

    @Test fun continuousCenteredResizeKeepsNativeSurfaceStableAcrossConfiguration() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "CodexReaderQA")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        val service = awaitAuthorizedLabService()
        val context = instrumentation.targetContext
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitMainFocus()
        val prefs = context.getSharedPreferences("android_control_preview_layout", Context.MODE_PRIVATE)
        val savedPreferences = prefs.all.toMap()
        assertTrue(prefs.edit().clear().putFloat("x_fraction", .5f).putFloat("y_fraction", .3f)
            .putInt("custom_width", dp(context, 150)).putInt("position_version", 2).commit())
        val output = AtomicReference<Surface?>()
        val producedSize = AtomicReference<Pair<Int, Int>?>()
        val attachments = AtomicInteger()
        val sizeCallbacks = AtomicInteger()
        val recreated = AtomicInteger()
        val destroyed = AtomicInteger()
        val stopped = AtomicInteger()
        val phase = AtomicInteger()
        val samples = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Rect>>()
        val controller = AndroidControlPreviewController(context) { stopped.incrementAndGet() }
        val observer = launch(Dispatchers.Unconfined) { controller.state.collect { state ->
            val currentPhase = phase.get()
            val bounds = state.windowBounds
            if (currentPhase != 0 && bounds != null && samples.lastOrNull()?.second != bounds)
                samples += currentPhase to Rect(bounds)
        } }
        val producer = launch(Dispatchers.IO) {
            val paint = Paint()
            while (isActive) {
                output.get()?.let { surface -> runCatching {
                    val canvas = surface.lockCanvas(null)
                    try {
                        producedSize.set(canvas.width to canvas.height)
                        paint.color = Color.RED; canvas.drawRect(0f, 0f, 180f, 320f, paint)
                        paint.color = Color.GREEN; canvas.drawRect(180f, 0f, 360f, 320f, paint)
                        paint.color = Color.BLUE; canvas.drawRect(0f, 320f, 180f, 640f, paint)
                        paint.color = Color.YELLOW; canvas.drawRect(180f, 320f, 360f, 640f, paint)
                    } finally { surface.unlockCanvasAndPost(canvas) }
                } }
                delay(16)
            }
        }
        try {
            controller.show(96, 360, 640, nativeOutput = { surface ->
                if (surface != null) attachments.incrementAndGet()
                output.set(surface)
                true
            }) { AndroidControlPreviewFrame(Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }) }
            withTimeout(5000) { while (!controller.state.value.nativeStreaming || controller.state.value.renderedFrames < 3) delay(25) }
            val initial = awaitStableExpandedBounds(controller)
            val initialSurface = requireNotNull(output.get())
            val initialAttachments = attachments.get()
            val initialFrames = controller.state.value.renderedFrames
            lateinit var root: View
            lateinit var texture: TextureView
            lateinit var host: Any
            lateinit var initialHostBounds: Rect
            var initialHostLayoutUpdates = 0
            instrumentation.runOnMainSync {
                host = AndroidControlPreviewController::class.java.getDeclaredField("host")
                    .apply { isAccessible = true }.get(controller)!!
                val layout = host.javaClass.getDeclaredField("hostParams").apply { isAccessible = true }
                    .get(host) as WindowManager.LayoutParams
                assertFalse("The fixed compositor host must not animate position changes",
                    layout.canPlayMoveAnimation())
                initialHostBounds = Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height)
                initialHostLayoutUpdates = host.javaClass.getDeclaredField("hostLayoutUpdates")
                    .apply { isAccessible = true }.getInt(host)
                root = host.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(host) as View
                texture = host.javaClass.getDeclaredField("texture").apply { isAccessible = true }.get(host) as TextureView
                val original = requireNotNull(texture.surfaceTextureListener)
                texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        recreated.incrementAndGet(); original.onSurfaceTextureAvailable(surface, width, height)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        sizeCallbacks.incrementAndGet(); original.onSurfaceTextureSizeChanged(surface, width, height)
                    }
                    override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                        destroyed.incrementAndGet(); return original.onSurfaceTextureDestroyed(surface)
                    }
                    override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = original.onSurfaceTextureUpdated(surface)
                }
            }
            suspend fun exerciseResize(dy: Int, direction: Int, injectConfiguration: Boolean) {
                val handle = controlBounds("调整预览大小")
                val down = SystemClock.uptimeMillis()
                val startX = handle.exactCenterX()
                val startY = handle.exactCenterY()
                fun inject(action: Int, y: Float) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, startX, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                phase.set(direction)
                samples += direction to Rect(requireNotNull(controller.state.value.windowBounds))
                inject(MotionEvent.ACTION_DOWN, startY)
                delay(ViewConfiguration.getLongPressTimeout().toLong() + 70)
                repeat(32) { step ->
                    val y = startY + dy * (step + 1) / 32f
                    inject(MotionEvent.ACTION_MOVE, y)
                    delay(20)
                    if (step == 11 || step == 23) {
                        assertRenderedNativePicture(controller, producedSize, "continuous-$direction-$step")
                        if (injectConfiguration && step == 11) {
                            val before = awaitStableExpandedBounds(controller)
                            instrumentation.runOnMainSync {
                                root.dispatchConfigurationChanged(android.content.res.Configuration(service.resources.configuration))
                            }
                            delay(100)
                            assertEquals("A same-screen Configuration during a held resize must not restore the pre-drag size",
                                before, awaitStableExpandedBounds(controller))
                        }
                    }
                }
                // Include a final position which was not in the last MOVE: UP must flush it.
                inject(MotionEvent.ACTION_UP, startY + dy + direction * dp(context, 2))
                awaitStableExpandedBounds(controller)
                phase.set(0)
            }
            exerciseResize(dp(context, 24), 1, injectConfiguration = true)
            val grown = awaitStableExpandedBounds(controller)
            assertTrue(grown.width() > initial.width())
            assertTrue("UP must apply its final coordinate rather than leaving the previous MOVE frame",
                abs(grown.height() - initial.height() - (dp(context, 24) + dp(context, 2))) <= 2)
            assertTrue(grown.left < initial.left && grown.right > initial.right && grown.bottom > initial.bottom)
            assertEquals("The top edge must stay fixed throughout bottom-handle resizing", initial.top, grown.top)
            assertTrue(abs(grown.exactCenterX() - initial.exactCenterX()) <= 1f)
            exerciseResize(-dp(context, 18), -1, injectConfiguration = true)
            val shrunk = awaitStableExpandedBounds(controller)
            assertTrue(shrunk.width() < grown.width())
            for (direction in listOf(1, -1)) {
                val frames = samples.filter { it.first == direction }.map { it.second }
                assertTrue("The held gesture must produce several measured frames", frames.size >= 4)
                frames.zipWithNext().forEach { (before, after) ->
                    assertTrue("Measured widths must follow the finger without reversals: direction=$direction $before -> $after",
                        direction * (after.width() - before.width()) >= -1)
                    assertTrue("Measured heights must follow the finger without reversals: direction=$direction $before -> $after",
                        direction * (after.height() - before.height()) >= -1)
                }
            }
            assertEquals("Consumer resizing must not reconfigure the producer buffer during a drag", 0, sizeCallbacks.get())
            assertEquals("The same native consumer must survive the whole drag", 0, recreated.get())
            assertEquals(0, destroyed.get())
            assertEquals(initialAttachments, attachments.get())
            assertSame(initialSurface, output.get())
            instrumentation.runOnMainSync {
                assertEquals(360, texture.width)
                assertEquals(640, texture.height)
                val layout = host.javaClass.getDeclaredField("hostParams").apply { isAccessible = true }
                    .get(host) as WindowManager.LayoutParams
                assertEquals("The WM canvas must remain fixed while only the internal panel resizes",
                    initialHostBounds, Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height))
                assertEquals("Dragging must not submit any WM surface-layout updates", initialHostLayoutUpdates,
                    host.javaClass.getDeclaredField("hostLayoutUpdates").apply { isAccessible = true }.getInt(host))
            }
            assertTrue(controller.state.value.renderedFrames > initialFrames + 10)
            assertEquals(360 to 640, producedSize.get())
            assertEquals(0, stopped.get())
            assertFrameless(controller, shrunk, 360, 640)
            assertRenderedNativePicture(controller, producedSize, "continuous-final")
            println("PREVIEW_CONTINUOUS_RESIZE=PASS; native_buffer_reconfigs=0; surface_recreations=0; same_screen_configuration_retains_live_bounds=true; monotonic_measured_frames=${samples.size}; top_fixed=true; wm_surface_relayouts=0; real_screen_pixels=true")
        } finally {
            phase.set(0)
            controller.hide()
            producer.cancelAndJoin()
            observer.cancelAndJoin()
            val restore = prefs.edit().clear()
            savedPreferences.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            } }
            assertTrue(restore.commit())
        }
    }

    @Test fun fixedCanvasPassesOutsideTouchesToTheMainFixture() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "CodexReaderQA")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        awaitAuthorizedLabService()
        val context = instrumentation.targetContext
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val focus = awaitMainFocus()
        val preferences = context.getSharedPreferences("android_control_preview_layout", Context.MODE_PRIVATE)
        val saved = preferences.all.toMap()
        assertTrue(preferences.edit().clear().putFloat("x_fraction", 1f).putFloat("y_fraction", 1f)
            .putInt("custom_width", dp(context, 120)).putInt("position_version", 2).commit())
        val controller = AndroidControlPreviewController(context) {}
        try {
            controller.show(97, 360, 640) {
                AndroidControlPreviewFrame(Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.CYAN)
                })
            }
            val panel = awaitStableExpandedBounds(controller)
            val counter = withTimeout(3000) {
                var found: AccessibilityNodeInfo? = null
                while (found == null) {
                    found = ui.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == MAIN }
                        .flatMap { it.findAccessibilityNodeInfosByText("MAIN_COUNTER_") }
                        .firstOrNull { it.text?.toString()?.startsWith("MAIN_COUNTER_") == true }
                    if (found == null) delay(30)
                }
                requireNotNull(found)
            }
            val before = counter.text.toString().removePrefix("MAIN_COUNTER_").toInt()
            val button = Rect().also(counter::getBoundsInScreen)
            assertFalse("The fixture button must be outside the small panel", panel.contains(button.centerX(), button.centerY()))
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    button.exactCenterX(), button.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            withTimeout(3000) {
                while (ui.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == MAIN }
                        .flatMap { it.findAccessibilityNodeInfosByText("MAIN_COUNTER_${before + 1}") }
                        .none { it.text?.toString() == "MAIN_COUNTER_${before + 1}" }) delay(30)
            }
            assertEquals("The overlay must not take main-screen focus", focus, mainFocus())
            assertEquals(panel, awaitStableExpandedBounds(controller))
            println("PREVIEW_FIXED_CANVAS_TOUCH_PASS_THROUGH=PASS; outside_real_touch_incremented_main_counter=true; main_focus_unchanged=true")
        } finally {
            controller.hide()
            val restore = preferences.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            } }
            assertTrue(restore.commit())
        }
    }

    @Test fun livePreviewMinimizesWithoutCapturingAndStopsWithoutStealingMainFocus() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "StatsUsageTest")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        val service = awaitAuthorizedLabService()
        val context=instrumentation.targetContext
        val mainIntent=requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN))
        context.startActivity(mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val before=awaitMainFocus()
        val captures=AtomicInteger()
        val stopped=CompletableDeferred<Unit>()
        val controller=AndroidControlPreviewController(context) {stopped.complete(Unit)}
        try {
            // Queue a show from a worker while Main is busy, then STOP before Main can open it.
            // The retired request must not reopen a blank/live window after its stop.
            instrumentation.runOnMainSync {
                val worker=Thread {
                    controller.show(98,720,1280) {captures.incrementAndGet();AndroidControlPreviewFrame(message="retired")}
                    controller.hide()
                }.apply {start()}
                worker.join(1000)
                assertFalse("Preview API must never wait synchronously for Main",worker.isAlive)
            }
            delay(100)
            assertNull(controller.state.value.displayId)
            assertEquals(0,captures.get())
            controller.show(99,720,1280) {
                val number=captures.incrementAndGet()
                val bitmap=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply {eraseColor(if(number==1)Color.RED else Color.BLUE)}
                AndroidControlPreviewFrame(bitmap,"测试画面")
            }
            controller.updateAction("点击按钮")
            withTimeout(5000) {while(captures.get()<2 || controller.state.value.bitmap?.getPixel(0,0)!=Color.BLUE)delay(50)}
            assertEquals(360,controller.state.value.bitmap!!.width)
            assertEquals(640,controller.state.value.bitmap!!.height)
            assertEquals("点击按钮",controller.state.value.action)
            assertTrue(controller.state.value.visible)
            assertEquals(before,mainFocus())
            val expandedBeforeMinimize = awaitStableExpandedBounds(controller)
            minimizePreview()
            withTimeout(2000) {while(controller.state.value.expanded)delay(20)}
            val safe = safeArea(service)
            val compact = awaitWindowPosition(controller,
                safe.right - minOf(dp(context, 56), safe.width()), safe.top)
            assertEquals(safe.right, compact.right)
            assertEquals(safe.top, compact.top)
            assertFalse("Minimize must keep the task running", stopped.isCompleted)
            assertNull(controller.state.value.bitmap)
            val pausedAt=captures.get()
            delay(1_100)
            assertEquals("A minimized preview must not call its screenshot provider",pausedAt,captures.get())
            clickOwn("展开操作预览")
            withTimeout(3000) {while(!controller.state.value.expanded || captures.get()<=pausedAt)delay(30)}
            assertEquals("One tap restores the original picture size and position",
                expandedBeforeMinimize, awaitStableExpandedBounds(controller))
            assertFalse("Expanding a minimized preview must not stop its task", stopped.isCompleted)
            println("PREVIEW_MINIMIZE_CORNER=PASS; safe_top_right=true; restores_original_bounds=true; task_kept_running=true")
            assertEquals(before,mainFocus())
            stopExpandedPreview()
            withTimeout(2000) {stopped.await();while(controller.state.value.displayId!=null)delay(20)}
            assertNull(controller.state.value.bitmap)
            assertEquals(before,mainFocus())
        } finally {controller.hide()}
    }

    @Test fun nativeOutputNeedsAProtectedGateAndStopsBeforeItsConsumerIsReleased() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "StatsUsageTest")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        awaitAuthorizedLabService()
        val context = instrumentation.targetContext
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val before = awaitMainFocus()
        val authorized = AtomicBoolean(false)
        val output = AtomicReference<Surface?>()
        val attachments = AtomicInteger()
        val detachments = AtomicInteger()
        val refusedRestore = AtomicBoolean(false)
        val firstAttachEntered = CompletableDeferred<Unit>()
        val allowFirstAttach = CompletableDeferred<Unit>()
        val captures = AtomicInteger()
        val stopped = CompletableDeferred<Unit>()
        val controller = AndroidControlPreviewController(context) { stopped.complete(Unit) }
        val producer = launch(Dispatchers.IO) {
            var frame = 0
            while (isActive) {
                val surface = output.get()
                if (surface != null) runCatching {
                    val canvas = surface.lockCanvas(null)
                    try { canvas.drawColor(if (frame++ % 2 == 0) Color.RED else Color.BLUE) }
                    finally { surface.unlockCanvasAndPost(canvas) }
                }
                delay(16)
            }
        }
        try {
            controller.show(97, 320, 480, nativeOutput = native@ { surface ->
                if (surface == null) {
                    if (output.get() != null && refusedRestore.compareAndSet(false, true)) {
                        assertTrue("A failed restore must retain its still-live consumer for retry", output.get()!!.isValid)
                        return@native false
                    }
                    val previous = output.getAndSet(null)
                    if (previous != null) {
                        assertTrue("Restore the original output before destroying the TextureView consumer", previous.isValid)
                        detachments.incrementAndGet()
                    }
                } else {
                    if (attachments.incrementAndGet() == 1) {
                        firstAttachEntered.complete(Unit)
                        allowFirstAttach.await()
                    }
                    output.set(surface)
                }
                true
            }) {
                captures.incrementAndGet()
                if (!authorized.get()) AndroidControlPreviewFrame(message = "受保护页面")
                else AndroidControlPreviewFrame(Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888))
            }
            delay(1_200)
            assertEquals("A failed first protected-window check cannot enable native output", 0, attachments.get())
            authorized.set(true)
            withTimeout(5000) { firstAttachEntered.await() }
            // Simulate a slow Binder call, then collapse before its successful return.
            minimizePreview()
            val republishedExpanded = AtomicBoolean(false)
            val observer = launch(Dispatchers.Unconfined) { controller.state.collect {
                if (it.displayId != null && (it.expanded || it.nativeStreaming)) republishedExpanded.set(true)
            } }
            try {
                allowFirstAttach.complete(Unit)
                withTimeout(3000) { while (controller.state.value.expanded || controller.state.value.nativeStreaming || output.get() != null || detachments.get() < 1) delay(30) }
                delay(250)
                assertFalse("A late Binder response must not republish a collapsed preview", republishedExpanded.get())
                assertTrue("Failed output restore must be retried before releasing its consumer", refusedRestore.get())
            } finally { observer.cancelAndJoin() }
            clickOwn("展开操作预览")
            withTimeout(5000) { while (!controller.state.value.nativeStreaming || controller.state.value.renderedFrames < 2) delay(50) }
            val frames = controller.state.value.renderedFrames
            delay(2_000)
            assertTrue("TextureView must receive continuous buffers rather than two screenshots/second", controller.state.value.renderedFrames - frames >= 20)
            assertNull(controller.state.value.bitmap)
            assertEquals(before, mainFocus())
            minimizePreview()
            withTimeout(3000) { while (controller.state.value.expanded || output.get() != null) delay(30) }
            val minimizedCaptures = captures.get()
            delay(1_100)
            assertEquals(minimizedCaptures, captures.get())
            assertTrue(detachments.get() >= 1)
            clickOwn("展开操作预览")
            withTimeout(5000) { while (!controller.state.value.nativeStreaming || output.get() == null) delay(30) }
            authorized.set(false)
            withTimeout(3000) { while (controller.state.value.nativeStreaming || output.get() != null) delay(30) }
            assertNull("A failed later protection check must blank the native preview", controller.state.value.bitmap)
            assertEquals(before, mainFocus())
            stopExpandedPreview()
            withTimeout(3000) { stopped.await(); while (controller.state.value.displayId != null) delay(30) }
            assertNull(output.get())
        } finally { allowFirstAttach.complete(Unit); controller.hide(); producer.cancelAndJoin() }
    }

    @Test fun transientEmptyAndUnavailableFramesRecoverOnlyAfterAFreshProtectedCapture() = runBlocking {
        val context = awaitPreviewRecoveryLabContext()
        val manager = context.getSystemService(DisplayManager::class.java)
        val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 2)
        val display = manager.createVirtualDisplay("onvyra-own-preview-capture-recovery", 360, 640, 160,
            reader.surface, 1 /* PUBLIC */ or 8 /* OWN_CONTENT_ONLY */)
        val displayId = display.display.displayId
        assertTrue(displayId > 0)
        val beforeFocus = awaitMainFocus()
        val output = AtomicReference<Surface?>()
        val producedSize = AtomicReference<Pair<Int, Int>?>()
        val failure = AtomicReference<String?>()
        val captures = AtomicInteger()
        val failedCaptures = AtomicInteger()
        val attachments = AtomicInteger()
        val stopped = AtomicInteger()
        val controller = AndroidControlPreviewController(context) { stopped.incrementAndGet() }
        val producer = launch(Dispatchers.IO) {
            while (isActive) {
                output.get()?.let { drawPreviewRecoveryFrame(it, producedSize) }
                delay(16)
            }
        }
        try {
            // Only the real, test-owned VD supplies identity/geometry. Canvas buffers are synthetic;
            // no other app's content, screenshot permission or protected layer is accessed here.
            controller.show(displayId, 360, 640, nativeOutput = { surface ->
                if (surface != null) attachments.incrementAndGet()
                output.set(surface)
                true
            }) {
                val code = failure.get()
                if (code != null) {
                    failedCaptures.incrementAndGet()
                    if (code == "empty_frame") {
                        // Simulate the service's already-classified transparent-buffer result.
                        // This host test does not invoke or replace the protected screenshot API.
                        val transparent = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                        try {
                            assertTrue((0..16).all { row -> (0..16).all { col ->
                                Color.alpha(transparent.getPixel(col * (transparent.width - 1) / 16,
                                    row * (transparent.height - 1) / 16)) == 0
                            } })
                        } finally { transparent.recycle() }
                    }
                    AndroidControlPreviewFrame(message = "合成捕获失败:$code", captureFailure = code)
                } else {
                    captures.incrementAndGet()
                    AndroidControlPreviewFrame(Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                        .apply { eraseColor(Color.MAGENTA) }, "合成有效画面")
                }
            }
            withTimeout(10_000) {
                while (!controller.state.value.nativeStreaming || controller.state.value.renderedFrames < 2) delay(25)
            }
            val originalBounds = awaitStableExpandedBounds(controller)
            val originalHost = previewRecoveryHost(controller)
            assertRenderedNativePicture(controller, producedSize, "before capture recovery")

            for (code in listOf("empty_frame", "screenshot_unavailable")) {
                val previousFailures = failedCaptures.get()
                failure.set(code)
                withTimeout(10_000) {
                    while (failedCaptures.get() <= previousFailures || controller.state.value.nativeStreaming ||
                        controller.state.value.bitmap != null || output.get() != null ||
                        controller.state.value.message != "合成捕获失败:$code") delay(25)
                }
                val capturesAtFailure = captures.get()
                val attachesAtFailure = attachments.get()
                // Wait for another failed capture, rather than trusting one transient state emission.
                withTimeout(10_000) { while (failedCaptures.get() < previousFailures + 2) delay(25) }
                assertFalse("A failed capture cannot retain native authorization", controller.state.value.nativeStreaming)
                assertNull(controller.state.value.bitmap)
                assertNull(output.get())
                assertEquals("Old authorization must not trigger a reattach", attachesAtFailure, attachments.get())
                assertEquals(capturesAtFailure, captures.get())
                assertPreviewRecoveryUnavailable(controller)

                failure.set(null)
                withTimeout(10_000) {
                    while (captures.get() <= capturesAtFailure || attachments.get() <= attachesAtFailure ||
                        !controller.state.value.nativeStreaming || output.get() == null) delay(25)
                }
                assertRenderedNativePicture(controller, producedSize, "after $code recovery")
                assertEquals(displayId, controller.state.value.displayId)
                assertTrue("Recovery must retain the owned virtual display", manager.getDisplay(displayId)?.isValid == true)
                assertEquals("Recovery must preserve the user's window geometry", originalBounds,
                    awaitStableExpandedBounds(controller))
                assertSame("Recovery must not recreate the overlay host", originalHost, previewRecoveryHost(controller))
                assertEquals("Recoverable capture failures must not stop the task", 0, stopped.get())
                assertEquals(beforeFocus, mainFocus())
            }
            println("PREVIEW_CAPTURE_RECOVERY=PASS; empty_frame_and_unavailable=true; fresh_protected_capture_required=true; real_native_pixels=true; task_display_geometry_retained=true")
        } finally {
            controller.hide()
            producer.cancelAndJoin()
            withTimeout(5_000) { while (controller.state.value.displayId != null) delay(25) }
            display.release()
            reader.close()
        }
    }

    @Test fun oneRejectedNativeBindingRecoversAfterAFreshProtectedCaptureWithoutReplacingTheTask() = runBlocking {
        val context = awaitPreviewRecoveryLabContext()
        val manager = context.getSystemService(DisplayManager::class.java)
        val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 2)
        val display = manager.createVirtualDisplay("onvyra-own-preview-binding-recovery", 360, 640, 160,
            reader.surface, 1 /* PUBLIC */ or 8 /* OWN_CONTENT_ONLY */)
        val displayId = display.display.displayId
        assertTrue(displayId > 0)
        val beforeFocus = awaitMainFocus()
        val output = AtomicReference<Surface?>()
        val producedSize = AtomicReference<Pair<Int, Int>?>()
        val captureAllowed = AtomicBoolean(true)
        val withholdCapture = AtomicBoolean(false)
        val captures = AtomicInteger()
        val failedCaptures = AtomicInteger()
        val attempts = AtomicInteger()
        val stopped = AtomicInteger()
        val rejectedCaptureCount = AtomicInteger()
        val acceptedCaptureCount = AtomicInteger()
        val rejectedAt = AtomicLong()
        val rejected = CompletableDeferred<Unit>()
        val controller = AndroidControlPreviewController(context) { stopped.incrementAndGet() }
        val producer = launch(Dispatchers.IO) {
            while (isActive) {
                output.get()?.let { drawPreviewRecoveryFrame(it, producedSize) }
                delay(16)
            }
        }
        try {
            controller.show(displayId, 360, 640, nativeOutput = { surface ->
                if (surface == null) {
                    output.set(null)
                    true
                } else if (attempts.incrementAndGet() == 1) {
                    rejectedCaptureCount.set(captures.get())
                    captureAllowed.set(false)
                    withholdCapture.set(true)
                    rejectedAt.set(SystemClock.elapsedRealtime())
                    rejected.complete(Unit)
                    false
                } else {
                    acceptedCaptureCount.set(captures.get())
                    output.set(surface)
                    true
                }
            }) {
                if (withholdCapture.get()) {
                    AndroidControlPreviewFrame(skipped = true)
                } else if (!captureAllowed.get()) {
                    failedCaptures.incrementAndGet()
                    AndroidControlPreviewFrame(message = "等待新的保护捕获", captureFailure = "screenshot_unavailable")
                } else {
                    captures.incrementAndGet()
                    AndroidControlPreviewFrame(Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                        .apply { eraseColor(Color.MAGENTA) }, "合成有效画面")
                }
            }
            withTimeout(10_000) {
                rejected.await()
                while (controller.state.value.nativeStreaming || controller.state.value.bitmap == null) delay(25)
            }
            val originalBounds = awaitStableExpandedBounds(controller)
            val originalHost = previewRecoveryHost(controller)
            assertPreviewRecoveryFallback(controller)
            // The initial poster stays visible when the bind is rejected. Even after its
            // 500 ms cooldown, skipped captures cannot authorize another bind of that Surface.
            delay((rejectedAt.get() + 650L - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            assertEquals(1, attempts.get())
            assertEquals(rejectedCaptureCount.get(), captures.get())
            assertNull(output.get())
            assertPreviewRecoveryFallback(controller)

            withholdCapture.set(false)
            withTimeout(10_000) {
                while (failedCaptures.get() < 2 || controller.state.value.nativeStreaming ||
                    controller.state.value.bitmap != null) delay(25)
            }
            assertEquals("The rejected binding cannot be retried using its previous capture", 1, attempts.get())
            assertNull(output.get())
            assertPreviewRecoveryUnavailable(controller)

            captureAllowed.set(true)
            withTimeout(10_000) {
                while (attempts.get() < 2 || !controller.state.value.nativeStreaming ||
                    controller.state.value.renderedFrames < 2 || output.get() == null) delay(25)
            }
            assertTrue("A new successful protected capture must precede recovery",
                acceptedCaptureCount.get() > rejectedCaptureCount.get())
            assertRenderedNativePicture(controller, producedSize, "after one rejected native binding")
            assertEquals(displayId, controller.state.value.displayId)
            assertTrue(manager.getDisplay(displayId)?.isValid == true)
            assertEquals(originalBounds, awaitStableExpandedBounds(controller))
            assertSame(originalHost, previewRecoveryHost(controller))
            assertEquals(0, stopped.get())
            assertEquals(beforeFocus, mainFocus())
            println("PREVIEW_BINDING_RECOVERY=PASS; first_native_bind_rejected=true; fallback_visible=true; fresh_protected_capture_required=true; subsequent_native_pixels=true; task_display_geometry_retained=true")
        } finally {
            controller.hide()
            producer.cancelAndJoin()
            withTimeout(5_000) { while (controller.state.value.displayId != null) delay(25) }
            display.release()
            reader.close()
        }
    }

    private suspend fun awaitPreviewRecoveryLabContext(): Context {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "CodexReaderQA")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        awaitAuthorizedLabService()
        return instrumentation.targetContext.also { context ->
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitMainFocus()
        }
    }

    private fun drawPreviewRecoveryFrame(surface: Surface, producedSize: AtomicReference<Pair<Int, Int>?>) {
        runCatching {
            val canvas = surface.lockCanvas(null)
            try {
                producedSize.set(canvas.width to canvas.height)
                val paint = Paint()
                val halfWidth = canvas.width / 2f
                val halfHeight = canvas.height / 2f
                paint.color = Color.RED; canvas.drawRect(0f, 0f, halfWidth, halfHeight, paint)
                paint.color = Color.GREEN; canvas.drawRect(halfWidth, 0f, canvas.width.toFloat(), halfHeight, paint)
                paint.color = Color.BLUE; canvas.drawRect(0f, halfHeight, halfWidth, canvas.height.toFloat(), paint)
                paint.color = Color.YELLOW; canvas.drawRect(halfWidth, halfHeight, canvas.width.toFloat(), canvas.height.toFloat(), paint)
            } finally { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private fun previewRecoveryHost(controller: AndroidControlPreviewController): Any {
        var host: Any? = null
        instrumentation.runOnMainSync {
            host = AndroidControlPreviewController::class.java.getDeclaredField("host")
                .apply { isAccessible = true }.get(controller)
        }
        return requireNotNull(host)
    }

    private fun assertPreviewRecoveryUnavailable(controller: AndroidControlPreviewController) {
        instrumentation.runOnMainSync {
            val host = AndroidControlPreviewController::class.java.getDeclaredField("host")
                .apply { isAccessible = true }.get(controller)!!
            val image = host.javaClass.getDeclaredField("image").apply { isAccessible = true }.get(host) as ImageView
            val placeholder = host.javaClass.getDeclaredField("placeholder").apply { isAccessible = true }.get(host) as TextView
            assertNull("A failed protected capture must clear the old bitmap", image.drawable)
            assertEquals("The failure placeholder must remain visible", View.VISIBLE, placeholder.visibility)
        }
    }

    private fun assertPreviewRecoveryFallback(controller: AndroidControlPreviewController) {
        instrumentation.runOnMainSync {
            val host = AndroidControlPreviewController::class.java.getDeclaredField("host")
                .apply { isAccessible = true }.get(controller)!!
            val image = host.javaClass.getDeclaredField("image").apply { isAccessible = true }.get(host) as ImageView
            val placeholder = host.javaClass.getDeclaredField("placeholder").apply { isAccessible = true }.get(host) as TextView
            assertFalse("A rejected binding must use the bitmap fallback", controller.state.value.nativeStreaming)
            assertNotNull("The successful protected poster must remain available", controller.state.value.bitmap)
            assertNotNull("The fallback must have a displayed image", image.drawable)
            assertEquals(View.VISIBLE, image.visibility)
            assertEquals(View.GONE, placeholder.visibility)
        }
    }

    @Test fun nativePixelsCoverTheWholePictureThroughRepeatedGrowShrinkAndFullscreen() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "StatsUsageTest")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        val service = awaitAuthorizedLabService()
        val context = instrumentation.targetContext
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val beforeFocus = awaitMainFocus()
        val prefs = context.getSharedPreferences("android_control_preview_layout", Context.MODE_PRIVATE)
        val savedPreferences = prefs.all.toMap()
        assertTrue(prefs.edit().clear().commit())
        val output = AtomicReference<Surface?>()
        val producedSize = AtomicReference<Pair<Int, Int>?>()
        val produced = AtomicInteger()
        val firstProducedAt = AtomicLong()
        val boundAt = AtomicLong()
        val stopped = AtomicInteger()
        val controller = AndroidControlPreviewController(context) { stopped.incrementAndGet() }
        // This is an entirely test-owned source. getBitmap below reads its own TextureView;
        // it never captures another app or bypasses protected-window screenshot checks.
        val producer = launch(Dispatchers.IO) {
            val paint = Paint()
            while (isActive) {
                output.get()?.let { surface -> runCatching {
                    val canvas = surface.lockCanvas(null)
                    try {
                        producedSize.set(canvas.width to canvas.height)
                        canvas.drawColor(Color.MAGENTA)
                        paint.color = Color.RED; canvas.drawRect(0f, 0f, 180f, 320f, paint)
                        paint.color = Color.GREEN; canvas.drawRect(180f, 0f, 360f, 320f, paint)
                        paint.color = Color.BLUE; canvas.drawRect(0f, 320f, 180f, 640f, paint)
                        paint.color = Color.YELLOW; canvas.drawRect(180f, 320f, 360f, 640f, paint)
                    } finally { surface.unlockCanvasAndPost(canvas) }
                    firstProducedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                    produced.incrementAndGet()
                } }
                delay(16)
            }
        }
        try {
            val shownAt = SystemClock.elapsedRealtime()
            controller.show(93, 360, 640, nativeOutput = { surface ->
                if (surface != null) boundAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                output.set(surface); true
            }) {
                AndroidControlPreviewFrame(Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.MAGENTA)
                })
            }
            withTimeout(5000) { while (!controller.state.value.nativeStreaming || controller.state.value.renderedFrames < 1 || firstProducedAt.get() == 0L) delay(10) }
            val presentedAt = SystemClock.elapsedRealtime()
            assertTrue("A native frame must be produced after an authorized output binding", boundAt.get() > 0 && firstProducedAt.get() >= boundAt.get())
            println("PREVIEW_FIRST_NATIVE_FRAME=PASS; show_to_present_ms=${presentedAt-shownAt}; bound_to_present_ms=${presentedAt-boundAt.get()}; produced_to_present_ms=${presentedAt-firstProducedAt.get()}; controlled_synthetic_source=true")
            withTimeout(3000) { while (controller.state.value.renderedFrames < 2) delay(25) }
            val compact = awaitStableExpandedBounds(controller)
            assertTrue("A new portrait preview must leave room to use the main screen",
                compact.height() <= (safeArea(service).height() * .51).roundToInt())
            assertRenderedNativePicture(controller, producedSize, "initial")
            repeat(3) { cycle ->
                val beforeGrow = awaitStableExpandedBounds(controller)
                dragControl("调整预览大小", 0, dp(context, 72), hold = true)
                val grown = awaitStableExpandedBounds(controller)
                assertTrue("Resize must actually enlarge the on-screen picture", grown.width() > beforeGrow.width())
                assertRenderedNativePicture(controller, producedSize, "grow-$cycle")
                dragControl("调整预览大小", 0, beforeGrow.height() - grown.height(), hold = true)
                val shrunk = awaitStableExpandedBounds(controller)
                assertTrue("Resize must actually shrink the on-screen picture", shrunk.width() < grown.width())
                assertRenderedNativePicture(controller, producedSize, "shrink-$cycle")
            }

            // The user's minimize gesture is shrinking the bottom handle to its limit.
            // Releasing there must collapse; it is not only a menu action.
            val beforeHandleMinimize = awaitStableExpandedBounds(controller)
            dragControl("调整预览大小", 0, -beforeHandleMinimize.height(), hold = true)
            assertMinimizedTopRight(controller, service)
            withTimeout(3000) { while (output.get() != null || controller.state.value.nativeStreaming) delay(25) }
            assertEquals("Shrinking to minimum keeps the task alive", 0, stopped.get())
            clickOwn("展开操作预览")
            withTimeout(3000) { while (!controller.state.value.expanded ||
                controller.state.value.windowBounds != beforeHandleMinimize) delay(25) }
            assertRenderedNativePicture(controller, producedSize, "handle-minimize-restore")
            assertEquals(0, stopped.get())

            // A cancelled touch must not unexpectedly minimize a running task.
            val beforeCancelledShrink = awaitStableExpandedBounds(controller)
            dragControl("调整预览大小", 0, -beforeCancelledShrink.height(), hold = true, cancel = true)
            val cancelledShrink = awaitStableExpandedBounds(controller)
            assertTrue("ACTION_CANCEL at minimum must remain expanded", controller.state.value.expanded)
            assertEquals(0, stopped.get())
            assertRenderedNativePicture(controller, producedSize, "handle-minimum-cancel")
            dragControl("调整预览大小", 0, beforeCancelledShrink.height() - cancelledShrink.height(), hold = true)
            val restoredCancelledShrink = awaitStableExpandedBounds(controller)
            assertTrue("Cancelled shrinking must still allow normal enlargement",
                abs(restoredCancelledShrink.width()-beforeCancelledShrink.width()) <= 1 &&
                    abs(restoredCancelledShrink.height()-beforeCancelledShrink.height()) <= 1)
            println("PREVIEW_HANDLE_MINIMIZE=PASS; real_touch_release_at_minimum=true; safe_top_right=true; restores_pre_gesture_bounds=true; native_output_paused=true; cancel_keeps_expanded=true; task_kept_running=true")

            val floating = awaitStableExpandedBounds(controller)
            clickOwn("窗口菜单")
            ownNode("全屏"); ownNode("最小化"); ownNode("关闭")
            assertNull("The preview menu must not add a split-screen action", findOwnNow("分屏"))
            clickOwn("全屏")
            withTimeout(3000) { while (controller.state.value.windowBounds == floating) delay(25) }
            assertRenderedNativePicture(controller, producedSize, "fullscreen")
            clickOwn("窗口菜单"); clickOwn("退出全屏")
            withTimeout(3000) { while (controller.state.value.windowBounds != floating) delay(25) }
            assertRenderedNativePicture(controller, producedSize, "restore")
            // Minimize again after resize/full-screen round trips. The system window,
            // not merely the requested coordinates, must be in the safe top-right corner.
            repeat(2) { cycle ->
                val beforeMinimize = awaitStableExpandedBounds(controller)
                minimizePreview()
                assertMinimizedTopRight(controller, service)
                clickOwn("展开操作预览")
                withTimeout(3000) { while (!controller.state.value.expanded ||
                    controller.state.value.windowBounds != beforeMinimize) delay(25) }
                assertRenderedNativePicture(controller, producedSize, "minimize-restore-$cycle")
            }
            assertEquals("Preview actions must keep the main application focused", beforeFocus, mainFocus())
            assertTrue(produced.get() >= 20)
            assertNull("A poster must not cover a streaming native frame", controller.state.value.bitmap)
            println("PREVIEW_RENDER_RESIZE=PASS; synthetic_pixels_all_quadrants=true; native_child_fills_window=true; source_buffer=360x640; grow_shrink_cycles=3; fullscreen_restore=true")
            println("PREVIEW_SCREEN_CAPTURE=PASS; own_synthetic_native_pixels_visible=true; single_renderer=true; repeated_minimize_top_right=true; compact_default=true")
        } finally {
            controller.hide()
            withTimeoutOrNull(3000) { while (controller.state.value.displayId != null) delay(25) }
            producer.cancelAndJoin()
            val restore = prefs.edit().clear()
            savedPreferences.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            } }
            assertTrue(restore.commit())
        }
    }

    private suspend fun assertRenderedNativePicture(
        controller: AndroidControlPreviewController,
        producedSize: AtomicReference<Pair<Int, Int>?>,
        phase: String,
    ) {
        val expected = expectedPictureBounds(awaitStableExpandedBounds(controller))
        var details = "No native view sampled"
        val passed = withTimeoutOrNull(3000) {
            while (true) {
                var matches = false
                instrumentation.runOnMainSync {
                    val hostField = AndroidControlPreviewController::class.java.getDeclaredField("host").apply { isAccessible = true }
                    val host = requireNotNull(hostField.get(controller))
                    val rootField = host.javaClass.getDeclaredField("panel").apply { isAccessible = true }
                    val root = rootField.get(host) as ViewGroup
                    fun findTexture(view: View): TextureView? = when (view) {
                        is TextureView -> view
                        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findTexture(view.getChildAt(it)) }
                        else -> null
                    }
                    val texture = requireNotNull(findTexture(root))
                    val actual = Rect().also { texture.getGlobalVisibleRect(it) }
                    val bitmap = texture.getBitmap(expected.width(), expected.height())
                    try {
                        val samples = bitmap?.let { image ->
                            listOf(.2f to .2f, .8f to .2f, .2f to .8f, .8f to .8f).map { (x,y) ->
                                image.getPixel((image.width*x).toInt().coerceIn(0,image.width-1),
                                    (image.height*y).toInt().coerceIn(0,image.height-1))
                            }
                        }
                        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                        fun close(actualColor: Int, expectedColor: Int) = Color.alpha(actualColor) >= 250 &&
                            abs(Color.red(actualColor)-Color.red(expectedColor)) <= 8 &&
                            abs(Color.green(actualColor)-Color.green(expectedColor)) <= 8 &&
                            abs(Color.blue(actualColor)-Color.blue(expectedColor)) <= 8
                        val pixelMatch = samples?.zip(colors)?.all { (actualColor, expectedColor) -> close(actualColor, expectedColor) } == true
                        val imageField = host.javaClass.getDeclaredField("image").apply { isAccessible = true }
                        val placeholderField = host.javaClass.getDeclaredField("placeholder").apply { isAccessible = true }
                        val image = imageField.get(host) as ImageView
                        val placeholder = placeholderField.get(host) as TextView
                        val pictureField = host.javaClass.getDeclaredField("picture").apply { isAccessible = true }
                        val picture = pictureField.get(host) as ViewGroup
                        val onlyNativeLayer = texture.visibility == View.VISIBLE && image.visibility == View.GONE &&
                            image.drawable == null && placeholder.visibility == View.GONE &&
                            (0 until picture.childCount).count { picture.getChildAt(it) is TextureView } == 1
                        matches = actual == expected && producedSize.get() == (360 to 640) && pixelMatch && onlyNativeLayer &&
                            bitmap != null && bitmap.width == expected.width() && bitmap.height == expected.height()
                        details = "phase=$phase expected=$expected texture=$actual bitmap=${bitmap?.width}x${bitmap?.height} producer=${producedSize.get()} samples=${samples?.map(Integer::toHexString)} singleNativeLayer=$onlyNativeLayer"
                    } finally { bitmap?.recycle() }
                }
                if (matches) return@withTimeoutOrNull true
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        android.util.Log.i("PreviewRenderTest", details)
        assertTrue("Native picture must fill its actual resized view with source pixels: $details", passed == true)
        assertOwnPreviewVisibleInScreenCapture(expected, phase)
    }

    /** Captures only the fixture screen and this test's synthetic overlay, never another app. */
    private suspend fun assertOwnPreviewVisibleInScreenCapture(bounds: Rect, phase: String) {
        var details = "No screen capture"
        val passed = withTimeoutOrNull(3000) {
            while (true) {
                val screenshot = ui.takeScreenshot()
                val matches = try {
                    if (screenshot == null || bounds.left < 0 || bounds.top < 0 ||
                        bounds.right > screenshot.width || bounds.bottom > screenshot.height) false
                    else {
                        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                        val samples = listOf(.2f to .2f, .8f to .2f, .2f to .8f, .8f to .8f).map { (x, y) ->
                            screenshot.getPixel(bounds.left + (bounds.width() * x).toInt(),
                                bounds.top + (bounds.height() * y).toInt())
                        }
                        details = "phase=$phase screen=${screenshot.width}x${screenshot.height} window=$bounds samples=${samples.map(Integer::toHexString)}"
                        samples.zip(colors).all { (actual, expected) ->
                            Color.alpha(actual) >= 250 && abs(Color.red(actual)-Color.red(expected)) <= 12 &&
                                abs(Color.green(actual)-Color.green(expected)) <= 12 &&
                                abs(Color.blue(actual)-Color.blue(expected)) <= 12
                        }
                    }
                } finally { screenshot?.recycle() }
                if (matches) return@withTimeoutOrNull true
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        android.util.Log.i("PreviewScreenTest", details)
        assertTrue("Own ordinary preview must remain visible in screenshots and recording composition: $details", passed == true)
    }

    private suspend fun assertMinimizedTopRight(controller: AndroidControlPreviewController, context: Context) {
        val safe = safeArea(context)
        val settled = withTimeoutOrNull(3000) {
            while (true) {
                val current = controller.state.value
                val actual = observedWindowBounds()
                if (!current.expanded && actual != null && actual == current.windowBounds &&
                    actual.top == safe.top && actual.right == safe.right) return@withTimeoutOrNull true
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        assertTrue("Actual minimized window must be at the usable top-right: safe=$safe state=${controller.state.value.windowBounds} actual=${observedWindowBounds()}", settled == true)
    }

    @Test fun nativeWindowEdgesCollapseToAnAppIconIdleHalfOutsideAndRestoreTheWholeWindow() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "StatsUsageTest")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        val service = awaitAuthorizedLabService()
        val context = instrumentation.targetContext
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val beforeFocus = awaitMainFocus()
        val prefs = context.getSharedPreferences("android_control_preview_layout", Context.MODE_PRIVATE)
        val savedPreferences = prefs.all.toMap()
        assertTrue(prefs.edit().clear().commit())
        val captures = AtomicInteger()
        val stopped = CompletableDeferred<Unit>()
        val controller = AndroidControlPreviewController(context) { stopped.complete(Unit) }
        var phase = "open portrait"
        fun edgeDiagnostic(): String {
            var requested = "no host"
            instrumentation.runOnMainSync {
                val host = AndroidControlPreviewController::class.java.getDeclaredField("host")
                    .apply { isAccessible = true }.get(controller)
                if (host != null) {
                    val params = host.javaClass.getDeclaredField("params").apply { isAccessible = true }
                        .get(host) as WindowManager.LayoutParams
                    val event = host.javaClass.getDeclaredField("lastDragEvent").apply { isAccessible = true }.get(host)
                    requested = "x=${params.x},y=${params.y},width=${params.width},height=${params.height},flags=${params.flags},last=$event"
                }
            }
            return "phase=$phase state=${controller.state.value} requested=[$requested] observer=${observedWindowBounds()}"
        }
        try {
            controller.show(92, 1080, 1920, appPackage = MAIN) {
                captures.incrementAndGet()
                AndroidControlPreviewFrame(Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.GREEN)
                })
            }
            val original = awaitStableExpandedBounds(controller)
            val safe = safeArea(service)
            assertFrameless(controller, original, 1080, 1920)
            assertTrue(abs(original.width() - safe.width() * .59) <= 1 ||
                abs(original.height() - safe.height() * .5) <= 2)

            suspend fun collapseAtEdge(left: Boolean) {
                phase = if (left) "left edge drag" else "right edge drag"
                val start = awaitStableExpandedBounds(controller)
                val targetX = if (left) safe.left - (start.width() * .51).roundToInt()
                    else safe.right - start.width() + (start.width() * .51).roundToInt()
                println("PREVIEW_NATIVE_EDGE_START; phase=$phase start=$start safe=$safe header=${controlBounds("窗口菜单")} requestedLeft=$targetX")
                // Grab away from the center so the finger itself remains on the
                // physical screen while the requested window crosses half outside.
                dragControl("窗口菜单", targetX - start.left, 0, startFractionX = if (left) .75f else .25f)
                println("PREVIEW_NATIVE_EDGE_RELEASE; ${edgeDiagnostic()}")
                withTimeout(3000) {
                    while (controller.state.value.expanded || controller.state.value.windowBounds?.let {
                        it.width() == dp(context, 56) && it.height() == dp(context, 56)
                    } != true) delay(25)
                }
                val icon = requireNotNull(controller.state.value.windowBounds)
                assertEquals(dp(context, 56), icon.width())
                assertEquals(icon.width(), icon.height())
                assertNull(controller.state.value.contentBounds)
                instrumentation.runOnMainSync {
                    val host = AndroidControlPreviewController::class.java.getDeclaredField("host")
                        .apply { isAccessible = true }.get(controller)!!
                    val image = host.javaClass.getDeclaredField("handle").apply { isAccessible = true }.get(host)
                    assertTrue("The collapsed control must display a drawable app icon", image is ImageView)
                    assertNotNull((image as ImageView).drawable)
                }
                val pausedAt = captures.get()
                phase = if (left) "left icon idle half-hide" else "right icon idle half-hide"
                withTimeout(4500) {
                    while (controller.state.value.windowBounds?.let {
                        if (left) it.left == safe.left - it.width() / 2
                        else it.right == safe.right + it.width() / 2
                    } != true) delay(25)
                }
                assertEquals("An icon must pause screenshot capture", pausedAt, captures.get())
                assertFalse(stopped.isCompleted)
                phase = if (left) "left visible icon tap restore" else "right visible icon tap restore"
                touchOwnVisible("展开操作预览", service, controller)
                assertEquals("Tapping the reachable icon restores the exact pre-edge window", start,
                    awaitStableExpandedBounds(controller))
                assertEquals(beforeFocus, mainFocus())
            }
            for (left in listOf(true, false)) {
                for (outside in listOf(.25f, .49f)) {
                    phase = "${if (left) "left" else "right"} park ${outside * 100}% outside"
                    val start = awaitStableExpandedBounds(controller)
                    val targetX = if (left) safe.left - (start.width() * outside).roundToInt()
                        else safe.right - start.width() + (start.width() * outside).roundToInt()
                    dragControl("窗口菜单", targetX - start.left, 0,
                        startFractionX = if (left) .75f else .25f)
                    val parked = awaitStableExpandedBounds(controller)
                    assertEquals("A partial outside release must retain its requested x", targetX, parked.left)
                    assertEquals(start.width(), parked.width())
                    assertTrue("Below half must stay an expanded live window", controller.state.value.expanded)
                    delay(450)
                    assertEquals("A partial outside release must not spring back after layout", parked,
                        awaitStableExpandedBounds(controller))
                    if (outside == .25f) {
                        collapseAtEdge(left)
                        assertEquals("Icon restore must preserve the partial outside placement", parked,
                            awaitStableExpandedBounds(controller))
                    } else {
                        // Reopening uses persisted placement rather than the current host.
                        controller.hide()
                        withTimeout(3000) { while (controller.state.value.displayId != null) delay(25) }
                        controller.show(92, 1080, 1920, appPackage = MAIN) {
                            captures.incrementAndGet()
                            AndroidControlPreviewFrame(Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) })
                        }
                        assertEquals("Reopening must retain a 49-percent outside placement", parked,
                            awaitStableExpandedBounds(controller))
                    }
                    val current = awaitStableExpandedBounds(controller)
                    dragControl("窗口菜单", original.left - current.left, original.top - current.top,
                        startFractionX = if (left) .75f else .25f)
                    assertEquals(original, awaitStableExpandedBounds(controller))
                }
            }
            assertEquals(original, awaitStableExpandedBounds(controller))

            // The native portrait sample also keeps one scale under the actual bottom-bar gesture.
            phase = "portrait bottom resize grow"
            dragControl("调整预览大小", 0, dp(context, 24), hold = true)
            val portraitGrown = awaitStableExpandedBounds(controller)
            assertTrue(portraitGrown.width() > original.width())
            assertFrameless(controller, portraitGrown, 1080, 1920)
            phase = "portrait bottom resize shrink"
            dragControl("调整预览大小", 0, original.height() - portraitGrown.height(), hold = true)
            val portraitShrunk = awaitStableExpandedBounds(controller)
            assertTrue(portraitShrunk.width() < portraitGrown.width())
            assertFrameless(controller, portraitShrunk, 1080, 1920)

            // A system-cancelled edge drag keeps an expanded window with a reachable half.
            phase = "cancelled left edge drag"
            dragControl("窗口菜单", safe.left - portraitShrunk.centerX(), 0, cancel = true)
            assertTrue(controller.state.value.expanded)
            val cancelled = awaitStableExpandedBounds(controller)
            val reachable = Rect(cancelled)
            assertTrue(reachable.intersect(safe))
            assertTrue(reachable.width() >= cancelled.width() / 2)

            controller.hide()
            phase = "retire portrait"
            withTimeout(3000) { while (controller.state.value.displayId != null) delay(25) }
            assertTrue(prefs.edit().clear().commit())
            controller.show(91, 1920, 1080, appPackage = MAIN) {
                AndroidControlPreviewFrame(Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.CYAN)
                })
            }
            phase = "open landscape"
            val landscape = awaitStableExpandedBounds(controller)
            assertFrameless(controller, landscape, 1920, 1080)
            assertTrue(abs(landscape.width() - safe.width() * .81) <= 1 ||
                abs(landscape.height() - safe.height() * .5) <= 2)
            phase = "landscape bottom resize grow"
            dragControl("调整预览大小", 0, dp(context, 24), hold = true)
            val landscapeGrown = awaitStableExpandedBounds(controller)
            assertTrue(landscapeGrown.width() > landscape.width())
            assertFrameless(controller, landscapeGrown, 1920, 1080)

            phase = "landscape menu minimize"
            minimizePreview()
            withTimeout(3000) {
                while (controller.state.value.expanded || controller.state.value.windowBounds?.width() != dp(context, 56)) delay(25)
            }
            phase = "icon long press STOP"
            touchOwnVisible("展开操作预览", service, controller, hold = true)
            clickOwn("停止手机操作")
            withTimeout(3000) { stopped.await(); while (controller.state.value.displayId != null) delay(25) }
            assertEquals(beforeFocus, mainFocus())
            println("PREVIEW_NATIVE_ICON=PASS; portrait_9x16_landscape_16x9=true; proportional_bottom_handle_resize=true; quarter_and_49_percent_outside_stay=true; left_right_51_percent_edge_drag=true; app_icon=true; idle_half_hidden=true; real_touch_restore_partial_bounds=true; reopen_partial_position=true; cancel_keeps_window=true; long_press_stop=true")
        } catch (failure: Throwable) {
            throw AssertionError("Native mini-window interaction failed: ${edgeDiagnostic()}", failure)
        } finally {
            controller.hide()
            val restore = prefs.edit().clear()
            savedPreferences.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue(restore.commit())
        }
    }

    @Test fun framelessPreviewKeepsFreePlacementAndProportionalResizeAcrossItsLifecycle() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val expectedLab = InstrumentationRegistry.getArguments().getString("lab_avd_name", "StatsUsageTest")
        require(expectedLab in setOf("StatsUsageTest", "CodexReaderQA"))
        assertEquals(expectedLab, shell("getprop ro.boot.qemu.avd_name"))
        val service = awaitAuthorizedLabService()
        val context = instrumentation.targetContext
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val beforeFocus = awaitMainFocus()
        val prefs = context.getSharedPreferences("android_control_preview_layout", Context.MODE_PRIVATE)
        val savedPreferences = prefs.all.toMap()
        @Suppress("DEPRECATION")
        val originalRotation = service.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        assertTrue(prefs.edit().clear().commit())
        val stopped = CompletableDeferred<Unit>()
        val stopCount = AtomicInteger()
        val controller = AndroidControlPreviewController(context) { stopCount.incrementAndGet(); stopped.complete(Unit) }
        fun show(id: Int) = controller.show(id, 720, 1280) {
            AndroidControlPreviewFrame(Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GREEN)
            })
        }
        try {
            show(96)
            val initial = awaitExpandedBounds(controller)
            assertFrameless(controller, initial)
            val safe = safeArea(service)
            val targetLeft = safe.left + (safe.width() - initial.width()) / 2
            val targetTop = safe.top + (safe.height() - initial.height()) / 3
            dragControl("窗口菜单", targetLeft - initial.left, targetTop - initial.top)
            val centered = awaitWindowPosition(controller, targetLeft, targetTop)
            assertTrue("A middle drop must retain an interior x coordinate", centered.left > safe.left)
            assertTrue("A middle drop must not dock right", centered.right < safe.right)
            delay(400)
            assertEquals(centered, controller.state.value.windowBounds)

            // A cancelled drag may clamp at the screen bounds but must not secretly snap to an edge.
            val cancelLeft = safe.left + (safe.width() - centered.width()) / 3
            val cancelTop = safe.top + (safe.height() - centered.height()) / 2
            dragControl("窗口菜单", cancelLeft - centered.left, cancelTop - centered.top, cancel = true)
            val cancelled = awaitWindowPosition(controller, cancelLeft, cancelTop)
            assertFrameless(controller, cancelled)

            val grow = minOf(dp(context, 64), (safe.bottom - cancelled.bottom - dp(context, 8)).coerceAtLeast(24))
            dragControl("调整预览大小", 0, grow, hold = true)
            val resized = withTimeout(3000) {
                var result = awaitExpandedBounds(controller)
                while (result.height() <= cancelled.height()) { delay(25); result = awaitExpandedBounds(controller) }
                result
            }
            assertFrameless(controller, resized)
            assertTrue(safe.contains(resized))
            assertTrue("Resizing must expand both sides and bottom while preserving the top",
                resized.left < cancelled.left && resized.top == cancelled.top &&
                    resized.right > cancelled.right && resized.bottom > cancelled.bottom)
            assertTrue(abs(resized.exactCenterX() - cancelled.exactCenterX()) <= 1f)

            // Holding and dragging to the largest on-screen destination must cap one scale,
            // not independently clamp width and height into a distorted frame.
            val resizeHandle = controlBounds("调整预览大小")
            dragControl("调整预览大小", 0, safe.bottom - resizeHandle.centerY() - 2, hold = true)
            val capped = awaitExpandedBounds(controller)
            assertFrameless(controller, capped)
            assertTrue("Large resize must stay inside the usable display", safe.contains(capped))
            assertEquals(beforeFocus, mainFocus())

            // Shrink again and deliberately save an interior position; a maximum-sized frame
            // naturally touches bounds and cannot distinguish persistence from edge docking.
            dragControl("调整预览大小", 0, resized.height() - capped.height(), hold = true)
            val smaller = awaitExpandedBounds(controller)
            val floatingX = safe.left + (safe.width() - smaller.width()) / 2
            val floatingY = safe.top + (safe.height() - smaller.height()) / 3
            dragControl("窗口菜单", floatingX - smaller.left, floatingY - smaller.top)
            val floating = awaitWindowPosition(controller, floatingX, floatingY)
            assertTrue(floating.left > safe.left && floating.right < safe.right)
            assertFrameless(controller, floating)

            // Full-screen and collapse are reversible and must not overwrite a free floating position.
            clickOwn("窗口菜单")
            clickOwn("全屏")
            val fullscreen = withTimeout(3000) {
                while (controller.state.value.windowBounds == floating) delay(25)
                awaitStableExpandedBounds(controller)
            }
            assertFrameless(controller, fullscreen)
            assertTrue(safe.contains(fullscreen))
            clickOwn("窗口菜单")
            clickOwn("退出全屏")
            withTimeout(3000) { while (controller.state.value.windowBounds != floating) delay(25) }
            minimizePreview()
            withTimeout(3000) { while (controller.state.value.expanded) delay(25) }
            clickOwn("展开操作预览")
            withTimeout(3000) { while (controller.state.value.windowBounds != floating || !controller.state.value.expanded) delay(25) }
            assertFrameless(controller, floating)

            // Reopen from preferences before rotating. This catches a saved x fraction rounded to 0/1.
            controller.hide()
            withTimeout(3000) { while (controller.state.value.displayId != null) delay(25) }
            show(95)
            val reopened = awaitExpandedBounds(controller)
            assertEquals("Reopening must retain the free position and size", floating, reopened)
            assertFrameless(controller, reopened)

            val alternateRotation = if (originalRotation == Surface.ROTATION_0 || originalRotation == Surface.ROTATION_180)
                Surface.ROTATION_90 else Surface.ROTATION_0
            assertTrue(ui.setRotation(frozenRotation(alternateRotation)))
            withTimeout(5000) {
                while ((safeArea(service).width() > safeArea(service).height()) == (safe.width() > safe.height())) delay(30)
            }
            // Android can publish an intermediate resized frame using the previous
            // display width before the overlay's configuration callback repositions it.
            // Merely being inside the new screen would accept that temporary frame.
            val rotated = awaitRelativeFloatingBounds(controller, service, floating, safe)
            assertFrameless(controller, rotated)
            assertTrue(ui.setRotation(frozenRotation(originalRotation)))
            val rotatedBack = awaitOriginalFloatingBounds(controller, floating)
            assertFrameless(controller, rotatedBack)

            // Full-screen must retain the floating area's coordinate system even if the
            // display rotates before the user exits full-screen.
            clickOwn("窗口菜单"); clickOwn("全屏")
            withTimeout(3000) { while (controller.state.value.windowBounds == floating) delay(25) }
            assertTrue(ui.setRotation(frozenRotation(alternateRotation)))
            withTimeout(5000) {
                while ((safeArea(service).width() > safeArea(service).height()) == (safe.width() > safe.height())) delay(30)
            }
            withTimeout(5000) {
                while (controller.state.value.windowBounds?.let { safeArea(service).contains(it) } != true) delay(30)
            }
            clickOwn("窗口菜单"); clickOwn("退出全屏")
            val restoredRotated = awaitStableExpandedBounds(controller)
            assertFrameless(controller, restoredRotated)
            val rotatedSafe = safeArea(service)
            assertTrue(rotatedSafe.contains(restoredRotated))
            val xFraction = (floating.left-safe.left).toDouble() / (safe.width()-floating.width()).coerceAtLeast(1)
            val yFraction = (floating.top-safe.top).toDouble() / (safe.height()-floating.height()).coerceAtLeast(1)
            val expectedX = rotatedSafe.left + ((rotatedSafe.width()-restoredRotated.width()) * xFraction).roundToInt()
            val expectedY = rotatedSafe.top + ((rotatedSafe.height()-restoredRotated.height()) * yFraction).roundToInt()
            assertTrue("Exiting rotated full-screen must retain relative free placement", abs(restoredRotated.left-expectedX) <= 1 && abs(restoredRotated.top-expectedY) <= 1)
            assertTrue(ui.setRotation(frozenRotation(originalRotation)))
            assertFrameless(controller, awaitOriginalFloatingBounds(controller, floating))
            stopExpandedPreview()
            withTimeout(3000) { stopped.await(); while (controller.state.value.displayId != null) delay(25) }
            assertNull(controller.state.value.windowBounds)
            assertTrue(awaitMainFocus().contains(MAIN))

            // Wide virtual displays used to make a 37px-high frame with overlapping
            // 28px header and 20px resize hit zones. Use real touches, not ACTION_CLICK.
            controller.show(94, 1440, 320) {
                AndroidControlPreviewFrame(Bitmap.createBitmap(1440, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) })
            }
            val wide = awaitExpandedBounds(controller)
            assertFrameless(controller, wide, 1440, 320)
            assertNull(findOwnNow("关闭"))
            dragControl("窗口菜单", 0, 0)
            ownNode("全屏") // A touch on the top control must really open the menu.
            repeat(5) {
                if (findOwnNow("关闭") == null) {
                    val scroll = ui.windows.asSequence().mapNotNull { it.root }
                        .mapNotNull { findScrollable(it, 0, intArrayOf(2000)) }.firstOrNull()
                    assertNotNull("STOP in a short preview must be reachable in the scrollable menu", scroll)
                    assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    delay(100)
                }
            }
            ownNode("关闭")
            dragControl("关闭", 0, 0)
            withTimeout(3000) { while (controller.state.value.displayId != null || stopCount.get() < 2) delay(25) }
            println("PREVIEW_FREE_WINDOW=PASS; bottom_grip_without_extra_strip=true; free_drop_up_cancel=true; centered_proportional_resize=true; bounds_ratio=true; reopen_rotation=true")
        } finally {
            controller.hide()
            ui.setRotation(frozenRotation(originalRotation))
            ui.setRotation(UiAutomation.ROTATION_UNFREEZE)
            val restore = prefs.edit().clear()
            savedPreferences.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> Unit
            } }
            assertTrue(restore.commit())
        }
    }

    @Test fun ownedVirtualDisplayRotationReflowsPreviewWithoutRotatingMainScreen() = runBlocking {
        // Explicit emulator-only setup. The test creates and releases its own secondary display;
        // it never rotates the main display, launches a real app, or accesses another app's pixels.
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        val service = awaitAuthorizedLabService()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(DisplayManager::class.java)
        val mainRotation = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation
        val reader = ImageReader.newInstance(320, 640, PixelFormat.RGBA_8888, 2)
        val display = manager.createVirtualDisplay("onvyra-own-rotation-test", 320, 640, 160,
            reader.surface, 1 /* PUBLIC */ or 8 /* OWN_CONTENT_ONLY */ or 128 /* ROTATES_WITH_CONTENT */)
        val id = display.display.displayId
        assertTrue(id > 0)
        val controller = AndroidControlPreviewController(context) {}
        try {
            // A synthetic authorized poster isolates overlay geometry from any game's renderer.
            // nativeOutput only exercises the TextureView transform; it does not capture pixels.
            controller.show(id, 320, 640, nativeOutput = { true }) {
                val size = Point().also { display.display.getRealSize(it) }
                AndroidControlPreviewFrame(Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.CYAN) }, "自建虚拟屏旋转测试")
            }
            for (rotation in listOf(0, 1, 2, 3, 0)) {
                shell("wm user-rotation -d $id lock $rotation")
                val expectedWidth = if (rotation % 2 == 0) 320 else 640
                val expectedHeight = if (rotation % 2 == 0) 640 else 320
                withTimeout(5000) {
                    while (display.display.rotation != rotation || controller.state.value.contentBounds?.let {
                        abs(it.height() - it.width() * expectedHeight.toDouble() / expectedWidth) <= 1
                    } != true) delay(40)
                }
                val bounds = awaitStableExpandedBounds(controller)
                assertFrameless(controller, bounds, expectedWidth, expectedHeight)
                assertEquals("Target rotation must not change the main phone screen", mainRotation,
                    manager.getDisplay(android.view.Display.DEFAULT_DISPLAY).rotation)
                instrumentation.runOnMainSync {
                    val host = AndroidControlPreviewController::class.java.getDeclaredField("host")
                        .apply { isAccessible = true }.get(controller)!!
                    val texture = host.javaClass.getDeclaredField("texture").apply { isAccessible = true }
                        .get(host) as TextureView
                    val points = floatArrayOf(0f, 0f, texture.width.toFloat(), 0f, 0f, texture.height.toFloat())
                    texture.getTransform(null).mapPoints(points)
                    val width = texture.width.toFloat()
                    val height = texture.height.toFloat()
                    val expected = when (rotation) {
                        0 -> floatArrayOf(0f, 0f, width, 0f, 0f, height)
                        1 -> floatArrayOf(0f, height, 0f, 0f, width, height)
                        2 -> floatArrayOf(width, height, 0f, height, width, 0f)
                        else -> floatArrayOf(width, 0f, width, height, 0f, 0f)
                    }
                    assertArrayEquals("Native view must compensate the target's rotation=$rotation",
                        expected, points, 1.1f)
                }
            }
            println("PREVIEW_TARGET_ROTATION=PASS; rotations=0,90,180,270,0; actual_owned_vd=true; overlay_ratio=true; native_matrix=true; main_rotation_unchanged=true")
        } finally {
            controller.hide()
            delay(200)
            display.release()
            reader.close()
        }
    }

    private suspend fun assertFrameless(controller: AndroidControlPreviewController, bounds: Rect, displayWidth: Int = 720, displayHeight: Int = 1280) {
        val check = ++geometryCheck
        val expectedContent = expectedPictureBounds(bounds)
        android.util.Log.i("PreviewWindowTest", "geometry[$check] expected=$bounds state=${controller.state.value.windowBounds} content=${controller.state.value.contentBounds} observer=${observedWindowBounds()}")
        val settled = withTimeoutOrNull(2000) {
            while (observedWindowBounds() != bounds || controller.state.value.contentBounds != expectedContent) delay(25)
            true
        }
        assertTrue("geometry[$check] expected=$bounds currentState=${controller.state.value.windowBounds} currentContent=${controller.state.value.contentBounds} currentObserver=${observedWindowBounds()}", settled == true)
        assertEquals("The source app must fill the whole window without an added bottom strip", bounds, controller.state.value.contentBounds)
        assertEquals("State geometry must agree with the independent system window observer", bounds, observedWindowBounds())
        assertTrue("Picture height must match the virtual-display aspect ratio", abs(expectedContent.height() - expectedContent.width() * displayHeight.toDouble() / displayWidth) <= 1.0)
        val resizeHandle = controlBounds("调整预览大小")
        assertEquals("Resize grip must reach the outer window's bottom edge", bounds.bottom, resizeHandle.bottom)
        assertTrue("Resize touch target must stay centered instead of covering the full bottom row",
            resizeHandle.width() <= dp(instrumentation.targetContext, 64) && abs(resizeHandle.centerX() - bounds.centerX()) <= 1)
    }
    private fun expectedPictureBounds(window: Rect): Rect = Rect(window)
    private fun observedWindowBounds(): Rect? = ui.windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.root?.packageName?.toString() == packageName
    }?.root?.let { find(it, "手机操控预览窗口", 0, intArrayOf(2000)) }
        ?.let { node -> Rect().also(node::getBoundsInScreen) }
    private suspend fun awaitExpandedBounds(controller: AndroidControlPreviewController): Rect = withTimeout(5000) {
        while (!controller.state.value.expanded || controller.state.value.windowBounds?.width()?.let { it > 0 } != true ||
            controller.state.value.contentBounds != controller.state.value.windowBounds?.let(::expectedPictureBounds)) delay(25)
        Rect(requireNotNull(controller.state.value.windowBounds))
    }
    private suspend fun awaitStableExpandedBounds(controller: AndroidControlPreviewController): Rect = withTimeout(3000) {
        instrumentation.waitForIdleSync()
        var previous: Rect? = null
        var stable = 0
        while (true) {
            val current = awaitExpandedBounds(controller)
            val observed = observedWindowBounds()
            // View.getLocationOnScreen and AccessibilityWindowInfo use different
            // rounding for a negative edge (for example -159 versus -158). Keep
            // exact View/requested placement assertions at callers, while accepting
            // only this single-pixel raster boundary in the independent observer.
            val observerAgrees = observed != null && abs(observed.left - current.left) <= 1 &&
                abs(observed.top - current.top) <= 1 && abs(observed.right - current.right) <= 1 &&
                abs(observed.bottom - current.bottom) <= 1
            if (current == previous && observerAgrees) stable++ else stable = 0
            if (stable >= 3) return@withTimeout current
            previous = current
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private suspend fun awaitWindowPosition(controller: AndroidControlPreviewController, x: Int, y: Int): Rect = withTimeout(3000) {
        while (controller.state.value.windowBounds?.let { abs(it.left-x) <= 1 && abs(it.top-y) <= 1 } != true) delay(25)
        Rect(requireNotNull(controller.state.value.windowBounds))
    }
    private suspend fun awaitOriginalFloatingBounds(controller: AndroidControlPreviewController, original: Rect): Rect = withTimeout(5000) {
        while (controller.state.value.windowBounds?.let {
            it.width() == original.width() && it.height() == original.height() &&
                abs(it.left-original.left) <= 1 && abs(it.top-original.top) <= 1
        } != true) delay(25)
        Rect(requireNotNull(controller.state.value.windowBounds))
    }
    private suspend fun awaitRelativeFloatingBounds(
        controller: AndroidControlPreviewController,
        context: Context,
        original: Rect,
        originalArea: Rect,
    ): Rect = withTimeout(5000) {
        val fractionX = (original.left-originalArea.left).toDouble() / (originalArea.width()-original.width()).coerceAtLeast(1)
        val fractionY = (original.top-originalArea.top).toDouble() / (originalArea.height()-original.height()).coerceAtLeast(1)
        var stable = 0
        var previous: Rect? = null
        while (true) {
            val bounds = controller.state.value.windowBounds
            val area = safeArea(context)
            val expectedX = area.left + ((area.width() - (bounds?.width() ?: 0)) * fractionX).roundToInt()
            val expectedY = area.top + ((area.height() - (bounds?.height() ?: 0)) * fractionY).roundToInt()
            val valid = bounds != null && area.contains(bounds) && expectedPictureBounds(bounds) == controller.state.value.contentBounds &&
                bounds == observedWindowBounds() && abs(bounds.left-expectedX) <= 1 && abs(bounds.top-expectedY) <= 1
            stable = if (valid && bounds == previous) stable + 1 else 0
            if (stable >= 3) return@withTimeout Rect(requireNotNull(bounds))
            previous = bounds?.let(::Rect)
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun frozenRotation(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> UiAutomation.ROTATION_FREEZE_90
        Surface.ROTATION_180 -> UiAutomation.ROTATION_FREEZE_180
        Surface.ROTATION_270 -> UiAutomation.ROTATION_FREEZE_270
        else -> UiAutomation.ROTATION_FREEZE_0
    }
    private fun safeArea(context: Context): Rect {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(insets.left, insets.top, metrics.bounds.width()-insets.right, metrics.bounds.height()-insets.bottom)
    }
    private fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).roundToInt()
    private suspend fun touchOwnVisible(description: String, context: Context,
                                        controller: AndroidControlPreviewController, hold: Boolean = false) {
        // State updates precede the WindowManager transaction and accessibility cache.
        // Choose a real screen touch point only after the displayed icon has settled.
        withTimeout(3000) {
            while (true) {
                val expected = controller.state.value.windowBounds
                val actual = observedWindowBounds()
                if (expected != null && actual != null &&
                    abs(expected.left - actual.left) <= 1 && abs(expected.top - actual.top) <= 1 &&
                    abs(expected.right - actual.right) <= 1 && abs(expected.bottom - actual.bottom) <= 1) break
                delay(25)
            }
        }
        val visible = controlBounds(description)
        assertTrue("The own icon must retain a reachable visible half", visible.intersect(safeArea(context)))
        println("PREVIEW_ICON_TOUCH; node=$visible safe=${safeArea(context)} observer=${observedWindowBounds()}")
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                visible.exactCenterX(), visible.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        if (hold) delay(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        inject(MotionEvent.ACTION_UP)
        delay(120)
    }
    private suspend fun dragControl(description: String, dx: Int, dy: Int, hold: Boolean = false, cancel: Boolean = false,
                                    startFractionX: Float = .5f) {
        val bounds = controlBounds(description)
        val down = SystemClock.uptimeMillis()
        val startX = bounds.left + bounds.width() * startFractionX; val startY = bounds.centerY().toFloat()
        fun inject(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Control touch must reach the own overlay", ui.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN, startX, startY)
        if (hold) delay(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        repeat(12) { step ->
            val fraction = (step+1) / 12f
            inject(MotionEvent.ACTION_MOVE, startX+dx*fraction, startY+dy*fraction)
            delay(16)
        }
        inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, startX+dx, startY+dy)
        delay(120)
    }
    private suspend fun minimizePreview() { clickOwn("窗口菜单"); clickOwn("最小化") }
    private suspend fun stopExpandedPreview() { clickOwn("窗口菜单"); clickOwn("停止手机操作") }
    private suspend fun controlBounds(description: String): Rect = Rect().also {
        val node = ownNode(description)
        node.getBoundsInScreen(it)
        var parent = node.parent
        repeat(12) { _ ->
            val ancestor = parent ?: return@repeat
            if (ancestor.isScrollable) {
                val viewport = Rect().also(ancestor::getBoundsInScreen)
                assertTrue("Control must intersect its visible scroll viewport", it.intersect(viewport))
            }
            parent = ancestor.parent
        }
        observedWindowBounds()?.let { window -> assertTrue("Control must intersect its visible window", it.intersect(window)) }
    }
    private fun findOwnNow(description: String): AccessibilityNodeInfo? = ui.windows.asSequence().mapNotNull { it.root }
        .mapNotNull { find(it, description, 0, intArrayOf(2000)) }.firstOrNull()
    private suspend fun ownNode(description: String): AccessibilityNodeInfo {
        val node=withTimeoutOrNull(3000) {
            var found:AccessibilityNodeInfo?
            do { found=findOwnNow(description);if(found==null)delay(30) }while(found==null)
            found
        } ?: error("Preview control not visible to the window observer: $description; windowTypes=${ui.windows.map {it.type}}")
        return node
    }
    private suspend fun clickOwn(description: String) {
        var node=ownNode(description)
        repeat(6) {
            if (node.isClickable) return@repeat
            node.parent?.takeIf { it.packageName?.toString() == packageName }?.let { node=it }
        }
        assertTrue("The own preview control must expose a real accessibility click: $description",node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun find(node:AccessibilityNodeInfo,description:String,depth:Int,left:IntArray):AccessibilityNodeInfo? {
        if(depth>50 || left[0]--<=0)return null
        if(node.isVisibleToUser && node.packageName?.toString()==packageName && (node.contentDescription?.toString()==description || node.text?.toString()==description))return node
        for(index in 0 until node.childCount) {
            val child=node.getChild(index)?:continue
            val found=find(child,description,depth+1,left)
            if(found!=null)return found
        }
        return null
    }
    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int, left: IntArray): AccessibilityNodeInfo? {
        if (depth > 50 || left[0]-- <= 0) return null
        if (node.packageName?.toString() == packageName && node.isVisibleToUser && node.isScrollable) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findScrollable(child, depth+1, left)?.let { return it }
        }
        return null
    }
    private suspend fun awaitAuthorizedLabService():AndroidControlAccessibilityService {
        if(AndroidControlAccessibilityService.instance==null) {
            assertEquals("true",InstrumentationRegistry.getArguments().getString("allow_lab_a11y_setup"))
            val component=ComponentName(instrumentation.targetContext,AndroidControlAccessibilityService::class.java)
            fun current()=shell("settings get secure enabled_accessibility_services").takeUnless {it=="null"}.orEmpty().split(':').filter(String::isNotBlank)
            fun own(value:String)=ComponentName.unflattenFromString(value)==component
            fun set(values:List<String>) {
                assertTrue(values.all {it.matches(Regex("[A-Za-z0-9_.$/]+"))})
                if(values.isEmpty())shell("settings delete secure enabled_accessibility_services")
                else shell("settings put secure enabled_accessibility_services "+values.joinToString(":"))
            }
            val others=current().filterNot(::own)
            set(others)
            shell("settings put secure accessibility_enabled "+if(others.isEmpty())"0" else "1")
            withTimeoutOrNull(2000) {while(AndroidControlAccessibilityService.instance!=null)delay(30)}
            delay(250)
            set(current().filterNot(::own)+component.flattenToString())
            shell("settings put secure accessibility_enabled 1")
        }
        return withTimeout(15_000) {while(AndroidControlAccessibilityService.instance==null)delay(50);requireNotNull(AndroidControlAccessibilityService.instance)}
    }
    private suspend fun awaitMainFocus(): String = withTimeout(5_000) {
        var previous = ""
        var current = mainFocus()
        while (!current.contains(MAIN) || current != previous) {
            previous = current
            delay(50)
            current = mainFocus()
        }
        // Return the matching sample, rather than doing another racy read during a focus transition.
        current
    }
    private fun mainFocus():String {
        val dump=shell("dumpsys window displays")
        val block=dump.substringAfter("Display: mDisplayId=0").substringBefore("Display: mDisplayId=1")
        return Regex("(?m)^[ \t]*mCurrentFocus=.*$").find(block)?.value?.trim().orEmpty()
    }
    private fun shell(command:String):String = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    companion object {private const val MAIN="me.rerere.androidcontrol.mainfixture"}
}
