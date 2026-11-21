package me.rerere.androidcontrol.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import me.rerere.androidcontrol.AndroidControl
import me.rerere.androidcontrol.accessibilityConnectionIsLive
import me.rerere.androidcontrol.ui.WirelessPairingDialog
import me.rerere.androidcontrol.ui.WirelessPairingReading
import me.rerere.androidcontrol.ui.WirelessPairingText

/** All reads and actions are scoped to the requested display; there is no active-root fallback. */
class AndroidControlAccessibilityService : AccessibilityService() {
    private val changes = DisplayChangeBus()
    private val snapshots = AccessibilitySnapshotStore<Int, Snapshot>(MAX_DISPLAY_SNAPSHOTS)
    private val screenshotErrors = ConcurrentHashMap<Int, Int>()
    private val screenshotMutex = Mutex()
    private val screenshotWaiters = AtomicInteger()
    private val agentScreenshotRequests = AtomicLong()
    /** Test/diagnostic counter; no image content and no preview frames included. */
    internal fun agentScreenshotRequestCount(): Long = agentScreenshotRequests.get()
    // HardwareBuffer readback and window revalidation can block. They must not run on
    // the same Main thread that lays out and composes the live preview overlay.
    private val screenshotExecutor = Dispatchers.Default.asExecutor()
    private var lastScreenshotAttemptAt = 0L
    private val nextNodeId = AtomicLong()
    @Volatile private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Framework calls this on Main. A transient system-side failure must not
        // crash the entire app and kill an otherwise valid accessibility binding.
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                eventTypes = CONTENT_EVENTS
                notificationTimeout = 100L
            }
        }
        connected = true
        changes.connect()
        connection.connected(this)
        // Accessibility and the app share a process. Keep explicit, visible standby
        // after a task ends; an OEM foreground-start rejection cannot crash Main.
        runCatching { AndroidControl.get(applicationContext).refreshStandbyProtection() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null || event.eventType and CONTENT_EVENTS == 0) return
        // Never copy event text: input events can contain private or password content.
        // Window events can race window/display removal. Keep an individual
        // framework callback failure from terminating this service's Main thread.
        runCatching {
            val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                event.displayId
            } else {
                displayForWindow(event.windowId)
            }
            if (displayId != Display.INVALID_DISPLAY) changes.changed(displayId)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    private fun disconnected() {
        connected = false
        connection.disconnected(this)
        changes.disconnect()
        // No target-app Binder call or hardware capture owns this lock. A late
        // hierarchy read cannot publish nodes after its service was unbound.
        snapshots.disconnect()
        screenshotErrors.clear()
        runCatching { AndroidControl.get(applicationContext).refreshStandbyProtection(allowStart = false) }
    }

    /** Both the service RPC and system's bound-service registry must agree. No screen read/action. */
    internal fun hasLiveConnection(): Boolean = accessibilityConnectionIsLive(connected,
        frameworkInfoAvailable = { serviceInfo != null },
        systemReportsBound = {
            val expected = ComponentName(this, AndroidControlAccessibilityService::class.java)
            // AOSP AccessibilityManagerService returns mBoundServices here. The
            // Secure enabled-service string alone also includes crashed services.
            getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                    info.resolveInfo?.serviceInfo?.let { ComponentName(it.packageName, it.name) } == expected
                }
        })

    /** Setup-only read: it neither publishes a general tree nor retains pairing text in snapshots. */
    internal fun readWirelessPairingDialog(localPairingPorts: Set<Int>): WirelessPairingReading? {
        if (!hasLiveConnection() || localPairingPorts.isEmpty()) return null
        val windows = runCatching { windowsFor(Display.DEFAULT_DISPLAY) }.getOrElse { return null }
        try {
            val readings = ArrayList<WirelessPairingReading>()
            // An overlay, notification, or OEM Settings activity can own focus
            // while the pairing dialog remains visible in another app window.
            // Package trust and visible-node filtering below define the read
            // boundary; focus must not hide an otherwise visible system dialog.
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .take(12).forEach { window ->
                    val root = runCatching { window.root }.getOrNull() ?: return@forEach
                    try {
                        if (root.packageName?.toString() !in trustedSettingsPackages) return@forEach
                        val texts = ArrayList<WirelessPairingText>()
                        var remaining = 300
                        fun visit(node: AccessibilityNodeInfo, depth: Int) {
                            if (depth > 20 || remaining-- <= 0 || node.isPassword) return
                            if (node.isVisibleToUser && !node.isEditable) {
                                // OEM Settings builds expose the one-time code as text,
                                // contentDescription, or hintText. Keep all three in this
                                // short-lived in-memory read; none is published to logs.
                                listOf(node.text, node.contentDescription, node.hintText)
                                    .mapNotNull { it?.toString()?.take(200)?.takeIf(String::isNotBlank) }
                                    .distinct().forEach {
                                        texts += WirelessPairingText(it, node.viewIdResourceName)
                                    }
                            }
                            for (index in 0 until node.childCount) {
                                if (remaining <= 0) break
                                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                                try { visit(child, depth + 1) } finally { child.disposeNode() }
                            }
                        }
                        visit(root, 0)
                        WirelessPairingDialog.read(texts, localPairingPorts)?.let(readings::add)
                    } finally { root.disposeNode() }
                }
            return readings.distinctBy { it.code to it.port }.singleOrNull()
        } catch (_: Exception) {
            return null
        } finally { windows.forEach { it.disposeWindow() } }
    }

    private val trustedSettingsPackages: Set<String> by lazy {
        val candidates = setOfNotNull("com.android.settings", runCatching {
            packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0)?.activityInfo?.packageName
        }.getOrNull())
        candidates.filter { name -> runCatching {
            @Suppress("DEPRECATION")
            val flags = packageManager.getApplicationInfo(name, 0).flags
            flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false) }.toSet()
    }

    /** Compact hierarchy with pixel bounds in the selected display's own coordinate space. */
    fun tree(displayId: Int): JsonObject {
        if (!hasLiveConnection() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayId < 0) {
            return emptyTree(displayId, if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "requires_android_11" else "accessibility_unavailable")
        }
        run {
            val snapshotRead = snapshots.begin(displayId)
            val previous = snapshotRead.previous
            val registry = LinkedHashMap<String, Locator>()
            val currentKeys = HashMap<NodeKey, String>()
            var remaining = MAX_NODES
            var truncated = false
            var currentPackage: String? = null
            // Read the revision before walking. Events during the read remain visible to awaitChange.
            val revision = changes.revision(displayId)
            val windows = windowsFor(displayId)
            try {
                fun readNode(node: AccessibilityNodeInfo, windowId: Int, path: List<Int>, depth: Int): JsonObject? {
                    if (depth > MAX_DEPTH || remaining <= 0) { truncated = true; return null }
                    remaining--
                    val signature = signature(node)
                    val key = NodeKey(windowId, path, signature)
                    val id = previous?.keys?.get(key) ?: "d${displayId}n${nextNodeId.incrementAndGet().toString(36)}"
                    currentKeys[key] = id
                    registry[id] = Locator(key)
                    val children = ArrayList<JsonObject>()
                    val childCount = if (signature.password) 0 else node.childCount
                    for (index in 0 until childCount) {
                        if (remaining <= 0) { truncated = true; break }
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        try { readNode(child, windowId, path + index, depth + 1)?.let(children::add) }
                        finally { child.disposeNode() }
                    }
                    return buildJsonObject {
                        put("id", id)
                        put("type", signature.className)
                        if (!signature.password) {
                            node.text?.toString()?.takeIf(String::isNotBlank)?.let { put("text", it.take(MAX_TEXT)) }
                            node.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let { put("description", it.take(MAX_TEXT)) }
                            node.hintText?.toString()?.takeIf(String::isNotBlank)?.let { put("hint", it.take(MAX_TEXT)) }
                        }
                        signature.resourceId?.let { put("resourceId", it) }
                        put("bounds", signature.bounds.json())
                        put("clickable", node.isClickable)
                        put("editable", node.isEditable)
                        put("scrollable", node.isScrollable)
                        put("enabled", node.isEnabled)
                        put("focused", node.isFocused)
                        if (!signature.password && node.isCheckable) {
                            put("checkable", true); put("checked", node.isChecked)
                        }
                        if (!signature.password && node.isSelected) put("selected", true)
                        put("visible", node.isVisibleToUser)
                        put("password", signature.password)
                        put("children", JsonArray(children))
                    }
                }

                val windowTrees = windows.filterNot { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .sortedByDescending { it.layer }.take(MAX_WINDOWS).map { window ->
                        val root = runCatching { window.root }.getOrNull()
                        try {
                            val packageName = root?.packageName?.toString()
                            if (currentPackage == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) currentPackage = packageName
                            val bounds = Rect().also(window::getBoundsInScreen)
                            buildJsonObject {
                                put("id", window.id)
                                put("type", window.type)
                                put("layer", window.layer)
                                put("active", window.isActive)
                                put("focused", window.isFocused)
                                put("bounds", bounds.json())
                                packageName?.let { put("packageName", it) }
                                put("root", root?.let { readNode(it, window.id, emptyList(), 0) } ?: JsonNull)
                            }
                        } finally { root?.disposeNode() }
                    }
                if (windows.size > MAX_WINDOWS) truncated = true
                if (!connected || !snapshots.publish(displayId, snapshotRead, Snapshot(currentKeys, registry)))
                    return emptyTree(displayId, "accessibility_unavailable")
                return buildJsonObject {
                    put("displayId", displayId)
                    put("revision", revision)
                    put("packageName", currentPackage?.let(::JsonPrimitive) ?: JsonNull)
                    put("windows", JsonArray(windowTrees))
                    put("truncated", truncated)
                }
            } catch (_: Exception) {
                snapshots.remove(displayId, snapshotRead)
                return emptyTree(displayId, "window_read_failed")
            } finally { windows.forEach { it.disposeWindow() } }
        }
    }

    /** A node ID is useful only while its original window, path and identifying attributes match. */
    fun perform(displayId: Int, nodeId: String, action: String, text: String? = null): Boolean {
        return inspectCurrentNode(displayId, nodeId) { target ->
            if (!target.isEnabled || !target.isVisibleToUser) return@inspectCurrentNode false
            val operation = action.lowercase(Locale.ROOT)
            val actionId = when (operation) {
                "click" -> AccessibilityNodeInfo.ACTION_CLICK
                "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
                "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "scroll_up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                "scroll_down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                "scroll_left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "scroll_right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> return@inspectCurrentNode false
            }
            if (target.actionList.none { it.id == actionId }) return@inspectCurrentNode false
            if (operation == "set_text") {
                if (!target.isEditable || target.isPassword || text == null || text.length > MAX_INPUT_TEXT) return@inspectCurrentNode false
                return@inspectCurrentNode target.performAction(actionId, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })
            }
            target.performAction(actionId)
        } ?: false
    }

    /** Allows a coordinate fallback only while the observed node still denotes the same target. */
    fun validateNode(displayId: Int, nodeId: String): Boolean =
        inspectCurrentNode(displayId, nodeId) { it.isEnabled && it.isVisibleToUser } ?: false

    /** Verifies an input longer than the compact tree's text limit without exporting its contents. */
    fun textMatches(displayId: Int, nodeId: String, expected: String): Boolean =
        inspectCurrentNode(displayId, nodeId) { target ->
            target.isEditable && !target.isPassword && target.text?.toString().orEmpty() == expected
        } ?: false

    private fun <T> inspectCurrentNode(displayId: Int, nodeId: String, inspect: (AccessibilityNodeInfo) -> T): T? {
        if (!connected || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayId < 0) return null
        val locator = snapshots.get(displayId)?.nodes?.get(nodeId) ?: return null
        val windows = windowsFor(displayId)
        var node: AccessibilityNodeInfo? = null
        try {
            val window = windows.firstOrNull { it.id == locator.key.windowId } ?: return null
            node = window.root ?: return null
            for (index in locator.key.path) {
                val parent = node ?: return null
                val child = parent.getChild(index)
                node = null
                parent.disposeNode()
                node = child ?: return null
            }
            val target = node ?: return null
            if (!target.refresh() || signature(target) != locator.key.signature) return null
            return inspect(target)
        } catch (_: Exception) {
            return null
        } finally {
            node?.disposeNode()
            windows.forEach { it.disposeWindow() }
        }
    }

    fun revision(displayId: Int): Long = changes.revision(displayId)

    suspend fun awaitChange(displayId: Int, afterRevision: Long, timeoutMs: Long): Boolean =
        changes.awaitChange(displayId, afterRevision, timeoutMs)

    data class ScreenshotCapture(
        val bitmap: Bitmap,
        val windowId: Int?,
        val windowBounds: Rect,
        val revisionBefore: Long,
        val revisionAfter: Long,
    )

    /** Compatibility wrapper; metadata users must keep the bounds from the same capture. */
    suspend fun screenshot(displayId: Int): Bitmap? = screenshotWithWindow(displayId)?.bitmap

    /** Captures the top app window on this display on Android 14+, enforcing FLAG_SECURE.
     * Agent/OCR requests reserve the next platform capture slot before optional preview work. */
    suspend fun screenshotWithWindow(displayId: Int): ScreenshotCapture? {
        agentScreenshotRequests.incrementAndGet()
        screenshotWaiters.incrementAndGet()
        try {
            return screenshotMutex.withLock {
                delay((SCREENSHOT_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastScreenshotAttemptAt)).coerceAtLeast(0))
                lastScreenshotAttemptAt = SystemClock.elapsedRealtime()
                val result = captureProtectedWindow(displayId, 5_000L)
                if (result.error == null) screenshotErrors.remove(displayId) else screenshotErrors[displayId] = result.error
                result.capture
            }
        } finally { screenshotWaiters.decrementAndGet() }
    }

    data class PreviewScreenshotResult(val capture: ScreenshotCapture? = null, val error: Int? = null,
        val skipped: Boolean = false, val failure: String? = null)

    /** Opportunistic display-scoped preview. Never blocks a queued Agent screenshot or changes its error. */
    suspend fun screenshotPreviewWithWindow(displayId: Int): PreviewScreenshotResult {
        if (screenshotWaiters.get() != 0 || !screenshotMutex.tryLock()) return PreviewScreenshotResult(skipped = true)
        try {
            if (screenshotWaiters.get() != 0 || SystemClock.elapsedRealtime() - lastScreenshotAttemptAt < SCREENSHOT_INTERVAL_MS) {
                return PreviewScreenshotResult(skipped = true)
            }
            lastScreenshotAttemptAt = SystemClock.elapsedRealtime()
            return captureProtectedWindow(displayId, 1_500L, requireExactCoordinates = false)
        } finally { screenshotMutex.unlock() }
    }

    /** Per-request errors stay local: a late preview callback must not overwrite an Agent's result. */
    private suspend fun captureProtectedWindow(displayId: Int, timeoutMs: Long,
        requireExactCoordinates: Boolean = true): PreviewScreenshotResult {
        var requestErrorCode: Int? = null
        var requestFailure: String? = null
        if (!connected || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || displayId < 0) {
            requestErrorCode = if (displayId < 0) ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY else ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
            return PreviewScreenshotResult(error = requestErrorCode)
        }
        requestErrorCode = null
        val revisionBefore = changes.revision(displayId)
        val appWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) topApplicationWindow(displayId) else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && appWindow == null) {
            requestErrorCode = ERROR_TAKE_SCREENSHOT_INVALID_WINDOW
            return PreviewScreenshotResult(error = requestErrorCode)
        }
        val result: ScreenshotCapture? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val callback = object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = runCatching {
                                screenshot.hardwareBuffer.use { buffer ->
                                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return@use null
                                    try { hardware.copy(Bitmap.Config.ARGB_8888, false) }
                                    finally { hardware.recycle() }
                                }
                            }.getOrNull()
                            val capture = when {
                                bitmap == null -> {
                                    requestErrorCode = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                                    null
                                }
                                !(0..16).any { row -> (0..16).any { col ->
                                    bitmap.getPixel(col * (bitmap.width - 1) / 16,
                                        row * (bitmap.height - 1) / 16).ushr(24) != 0
                                } } -> {
                                    // Some rendering backends return an empty transparent
                                    // buffer with a success callback. It contains no usable
                                    // observation and must never be sent as a successful shot.
                                    requestErrorCode = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                                    requestFailure = "empty_frame"
                                    bitmap.recycle()
                                    null
                                }
                                appWindow != null && !captureWindowIsCurrent(appWindow.geometry(),
                                    topApplicationWindow(displayId)?.geometry(), bitmap.width, bitmap.height,
                                    requireExactCoordinates) -> {
                                    // Never use a stale window or export uncertain screenshot coordinates.
                                    // A successful, nonsecure window capture may still authorize PREVIEW
                                    // when a vendor scales its buffer; Agent/OCR stays coordinate-exact.
                                    val current = topApplicationWindow(displayId)
                                    requestFailure = if (current != appWindow) "window_changed" else "window_geometry_mismatch"
                                    bitmap.recycle()
                                    requestErrorCode = ERROR_TAKE_SCREENSHOT_INVALID_WINDOW
                                    null
                                }
                                else -> {
                                    requestErrorCode = null
                                    ScreenshotCapture(bitmap, appWindow?.id,
                                        appWindow?.bounds ?: Rect(0, 0, bitmap.width, bitmap.height),
                                        revisionBefore, changes.revision(displayId))
                                }
                            }
                            if (continuation.isActive) continuation.resume(capture) { _, cancelled, _ -> cancelled?.bitmap?.recycle() }
                            else capture?.bitmap?.recycle()
                        }
                        override fun onFailure(errorCode: Int) {
                            requestErrorCode = errorCode
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // A whole-display capture can return a redacted bitmap for a secure app.
                        // The window API reports that protection explicitly; never retry the display.
                        takeScreenshotOfWindow(requireNotNull(appWindow).id, screenshotExecutor, callback)
                    } else {
                        takeScreenshot(displayId, screenshotExecutor, callback)
                    }
                } catch (_: Exception) {
                    requestErrorCode = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        val error = requestErrorCode ?: ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR.takeIf { result == null }
        return PreviewScreenshotResult(result, error,
            failure = if (result != null) null else previewCaptureFailure(error,
                requestFailure ?: "screenshot_timeout".takeIf { requestErrorCode == null }))
    }

    private data class WindowCaptureTarget(val id: Int, val bounds: Rect) {
        fun geometry() = CaptureWindowGeometry(id, bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun topApplicationWindow(displayId: Int): WindowCaptureTarget? {
        val windows = windowsFor(displayId)
        return try {
            runCatching {
                windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
                    ?.let { WindowCaptureTarget(it.id, Rect().also(it::getBoundsInScreen)) }
            }.getOrNull()
        } finally { windows.forEach { it.disposeWindow() } }
    }

    /** Framework error codes include secure-window, rate-limit and invalid-display failures. */
    fun lastScreenshotError(displayId: Int): Int? = screenshotErrors[displayId]

    private fun windowsFor(displayId: Int): List<AccessibilityWindowInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val all = runCatching { windowsOnAllDisplays }.getOrNull() ?: return emptyList()
        val selected = all.get(displayId).orEmpty()
        // The framework returns caller-owned copies for every display, including the unused ones.
        for (index in 0 until all.size()) {
            if (all.keyAt(index) != displayId) all.valueAt(index).forEach { it.disposeWindow() }
        }
        return selected
    }

    private fun displayForWindow(windowId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || windowId < 0) return Display.INVALID_DISPLAY
        val all = runCatching { windowsOnAllDisplays }.getOrNull() ?: return Display.INVALID_DISPLAY
        var found = Display.INVALID_DISPLAY
        for (index in 0 until all.size()) {
            val windows = all.valueAt(index)
            try { if (windows.any { it.id == windowId }) found = all.keyAt(index) }
            finally { windows.forEach { it.disposeWindow() } }
        }
        return found
    }

    private fun emptyTree(displayId: Int, reason: String) = buildJsonObject {
        put("displayId", displayId)
        put("revision", changes.revision(displayId))
        put("packageName", JsonNull)
        put("windows", JsonArray(emptyList()))
        put("truncated", false)
        put("error", reason)
    }

    private fun signature(node: AccessibilityNodeInfo): NodeSignature {
        val password = node.isPassword
        val editable = node.isEditable
        return NodeSignature(
            packageName = node.packageName?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            resourceId = node.viewIdResourceName,
            bounds = Rect().also(node::getBoundsInScreen),
            password = password,
            editable = editable,
            // Editable content can change after SET_TEXT without changing the field's identity.
            label = if (password || editable) null else node.text?.toString(),
            description = if (password) null else node.contentDescription?.toString(),
            childCount = node.childCount,
            childLabels = if (!password && node.isClickable) descendantLabels(node) else null,
        )
    }

    /** A recycled clickable row can keep its ID/class/bounds while its child label changes. */
    private fun descendantLabels(node: AccessibilityNodeInfo): String {
        val labels = StringBuilder()
        var remaining = 40
        fun visit(parent: AccessibilityNodeInfo, depth: Int) {
            if (depth >= 4 || remaining <= 0) return
            for (index in 0 until parent.childCount) {
                if (remaining-- <= 0) break
                val child = parent.getChild(index) ?: continue
                try {
                    if (!child.isPassword) {
                        if (!child.isEditable) labels.append(child.text).append('\u0000')
                        labels.append(child.contentDescription).append('\u0000')
                        visit(child, depth + 1)
                    }
                } finally { child.disposeNode() }
            }
        }
        visit(node, 0)
        return labels.toString()
    }

    private data class NodeSignature(
        val packageName: String, val className: String, val resourceId: String?, val bounds: Rect,
        val password: Boolean, val editable: Boolean, val label: String?, val description: String?,
        val childCount: Int, val childLabels: String?,
    )
    private data class NodeKey(val windowId: Int, val path: List<Int>, val signature: NodeSignature)
    private data class Locator(val key: NodeKey)
    private data class Snapshot(val keys: Map<NodeKey, String>, val nodes: Map<String, Locator>)

    private fun Rect.json() = JsonArray(listOf(left, top, right, bottom).map(::JsonPrimitive))

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.disposeNode() { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle() }

    @Suppress("DEPRECATION")
    private fun AccessibilityWindowInfo.disposeWindow() { recycle() }

    companion object {
        private val connection = AccessibilityConnection<AndroidControlAccessibilityService>()
        val instance: AndroidControlAccessibilityService? get() = connection.current?.takeIf { it.hasLiveConnection() }

        /** Wait only while a caller is using control; never enable a user-disabled service. */
        internal suspend fun awaitConnected(timeoutMs: Long = 2_000L): AndroidControlAccessibilityService? {
            instance?.let { return it }
            return connection.awaitConnected(timeoutMs) { it.hasLiveConnection() }
        }
        private const val SCREENSHOT_INTERVAL_MS = 350L
        private const val MAX_NODES = 1_500
        private const val MAX_DISPLAY_SNAPSHOTS = 8
        private const val MAX_DEPTH = 50
        private const val MAX_WINDOWS = 20
        private const val MAX_TEXT = 4_000
        private const val MAX_INPUT_TEXT = 100_000
        private const val CONTENT_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_SELECTED
    }
}
