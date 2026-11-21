package me.rerere.androidcontrol

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import me.rerere.androidcontrol.accessibility.previewCaptureFailure
import me.rerere.androidcontrol.accessibility.previewCaptureMessage
import me.rerere.androidcontrol.shell.AndroidShellBridge
import me.rerere.androidcontrol.shell.IAndroidShell
import me.rerere.androidcontrol.slider.SliderSolver
import me.rerere.androidcontrol.captcha.CaptchaOcr
import me.rerere.androidcontrol.captcha.CaptchaRegion
import me.rerere.androidcontrol.ui.AndroidControlNotificationService
import me.rerere.androidcontrol.ui.AndroidControlPreviewController
import me.rerere.androidcontrol.ui.AndroidControlPreviewFrame
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal fun accessibilityReadinessError(authorized: Boolean, connected: Boolean): String? = when {
    !authorized -> "accessibility_not_authorized"
    connected -> null
    else -> "accessibility_service_disconnected"
}

/** A helper connection is only one prerequisite; use one snapshot for UI and tool readiness. */
data class AndroidControlReadiness(
    val enabled: Boolean, val supported: Boolean, val shellConnected: Boolean,
    val accessibilityAuthorized: Boolean, val accessibilityConnected: Boolean,
    val notificationsAllowed: Boolean = true,
) {
    val error: String? get() = when {
        !enabled -> "control_disabled_open_settings"
        !supported -> "isolated_background_control_requires_android_14"
        !notificationsAllowed -> "notification_permission_required_for_stop_control"
        !accessibilityAuthorized -> "accessibility_not_authorized"
        !accessibilityConnected -> "accessibility_service_disconnected"
        !shellConnected -> "shell_not_connected_open_settings"
        else -> null
    }
    val ready: Boolean get() = error == null
}

/** One explicitly enabled, display-scoped task; model calls never receive a global shell. */
class AndroidControl private constructor(private val context: Context) {
    val bridge = AndroidShellBridge(context)
    private val preferences = context.getSharedPreferences("android_control", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val epoch = AtomicLong()
    private val agentScreenshotCount = AtomicLong()
    private val localOcrCount = AtomicLong()
    private val localFrameCaptureCount = AtomicLong()
    private val ownershipLock = Any()
    @Volatile private var operationOwner: String? = null
    @Volatile private var operationUserStop: (() -> Unit)? = null
    private val entries = ArrayDeque<String>()
    private val approvalModes = LinkedHashMap<String, AndroidControlApprovalMode>()

    fun setApprovalMode(owner: String, mode: AndroidControlApprovalMode) = synchronized(approvalModes) {
        require(owner.isNotBlank() && owner.length <= 128)
        approvalModes[owner] = mode
        while (approvalModes.size > 128) approvalModes.remove(approvalModes.keys.first { it != session?.owner })
    }

    /** Initial run snapshot must not overwrite a newer, explicit choice made while a tool runs. */
    fun registerApprovalMode(owner: String, mode: AndroidControlApprovalMode) = synchronized(approvalModes) {
        if (!approvalModes.containsKey(owner)) setApprovalMode(owner, mode)
    }

    fun currentApprovalMode(owner: String): AndroidControlApprovalMode = approvalMode(owner)

    private fun approvalMode(owner: String): AndroidControlApprovalMode = synchronized(approvalModes) {
        approvalModes[owner] ?: if (confirmSensitive) AndroidControlApprovalMode.AUTONOMOUS else AndroidControlApprovalMode.FULL_ACCESS
    }
    private val logFile=File(context.noBackupFilesDir,"android-control.log")
    @Volatile private var session: Session? = null
    @Volatile private var latestTree = JsonObject(emptyMap())
    @Volatile private var pendingSensitive: String? = null
    @Volatile private var paused = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastPreviewCaptureError: String? = null
    private val preview = AndroidControlPreviewController(context) { requestStop() }
    /** Offline character-captcha recognition shared by all tasks on this process. */
    private val captchaOcr by lazy { CaptchaOcr(context) }
    val previewState get() = preview.state
    private data class Session(val owner: String, val id: String, val display: Int, val reader: ImageReader,
                               val thread: HandlerThread, val shell: IAndroidShell, var packageName: String? = null,
                               var onUserStop: (() -> Unit)? = null,
                               val cancelled: AtomicBoolean = AtomicBoolean(false),
                               val visualFrames: ControlVisualFrames = ControlVisualFrames(),
                               var visualObservation: Boolean = false,
                               var visualOcrEnabled: Boolean = true,
                               var visualOcr: JsonArray = JsonArray(emptyList()),
                               var lastVisualScreenshot: File? = null,
                               var dragId: Long = 0L,
                               var dragStartedAt: Long = 0L,
                               var dragGeometry: List<Int> = emptyList(),
                               var localOcrAt: Long = 0L)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) {
            preferences.edit().putBoolean("enabled", value).apply()
            if (!value) requestStop()
            refreshStandbyProtection()
        }
    var confirmSensitive: Boolean
        get() = preferences.getBoolean("confirm_sensitive", true)
        set(value) { preferences.edit().putBoolean("confirm_sensitive", value).apply() }
    var previewEnabled: Boolean
        get() = preferences.getBoolean("preview_enabled", true)
        set(value) {
            preferences.edit().putBoolean("preview_enabled", value).apply()
            if (value) session?.let(::showPreview) else preview.hide()
        }

    /** A saved system grant is not proof that Android has bound the service. */
    fun accessibilityAuthorizationGranted(): Boolean {
        if (runCatching { Settings.Secure.getInt(context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) }.getOrDefault(0) != 1) return false
        val component = ComponentName(context.packageName,
            "me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService")
        val configured = runCatching { Settings.Secure.getString(context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull().orEmpty()
        return configured.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == component }
    }

    fun accessibilityConnected(): Boolean = accessibilityAuthorizationGranted() && AndroidControlAccessibilityService.instance != null

    fun readiness(): AndroidControlReadiness {
        val authorized = accessibilityAuthorizationGranted()
        return AndroidControlReadiness(enabled, Build.VERSION.SDK_INT >= 34, bridge.status().connected,
            authorized, authorized && AndroidControlAccessibilityService.instance != null,
            AndroidControlNotificationService.notificationsAllowed(context))
    }

    /** Explicit UI retry: wait for an already authorized system rebind, never toggle Secure settings. */
    suspend fun awaitReadiness(): AndroidControlReadiness {
        if (enabled && accessibilityAuthorizationGranted()) {
            refreshStandbyProtection()
            AndroidControlAccessibilityService.awaitConnected()
        }
        return readiness()
    }

    /** Reconcile only on user/lifecycle events; a status read never starts or restarts services. */
    fun refreshStandbyProtection(allowStart: Boolean = true): Boolean =
        AndroidControlNotificationService.reconcile(context, this, allowStart)

    internal fun onProtectionUnavailable() {
        lastError = "foreground_protection_unavailable"
        requestStop()
    }

    init {
        scope.launch {
            val restored=runCatching {if(logFile.isFile)logFile.readLines().takeLast(200) else emptyList()}.getOrDefault(emptyList())
            synchronized(entries) {if(entries.isEmpty())entries.addAll(restored)}
        }
        scope.launch {
            var previous: android.os.IBinder? = null
            bridge.shell.collect { shell ->
                val binder = shell?.asBinder()
                if (previous != null && previous !== binder) { epoch.incrementAndGet(); operations.withLock { releaseSession() }; log("助手断开，任务已停止") }
                previous = binder
            }
        }
    }

    fun state(): JsonObject = buildJsonObject {
        val readiness = readiness()
        put("enabled", readiness.enabled); put("confirm_sensitive", confirmSensitive)
        put("shell_connected", readiness.shellConnected); put("bridge", bridge.status().toString())
        put("accessibility_connected", readiness.accessibilityConnected)
        put("accessibility_authorized", readiness.accessibilityAuthorized)
        put("control_ready", readiness.ready)
        readiness.error?.let { put("readiness_error", it) }
        put("standby_protected", AndroidControlNotificationService.running)
        put("control_notifications_allowed", readiness.notificationsAllowed)
        AndroidControlNotificationService.lastStartError?.let { put("standby_protection_error", it) }
        put("android_supported", Build.VERSION.SDK_INT >= 34); put("paused", paused)
        put("agent_screenshot_count", agentScreenshotCount.get())
        put("local_ocr_count", localOcrCount.get())
        put("local_frame_capture_count", localFrameCaptureCount.get())
        put("preview_enabled", previewEnabled)
        put("preview_visible", previewState.value.visible)
        put("preview_message", previewState.value.message)
        lastPreviewCaptureError?.let { put("preview_capture_error", it) }
        put("state",if(paused)"paused" else if(session!=null)"running" else "idle")
        session?.let { put("task_id", it.id); put("display_id", it.display); put("package_name", it.packageName) }
        lastError?.let { put("error", it) }
    }
    fun logs(): List<String> = synchronized(entries) { entries.toList() }
    internal fun hasActiveSession(): Boolean = session != null
    private fun log(message: String) {
        val entry = "${SystemClock.elapsedRealtime()} $message"
        synchronized(entries) {
            entries.addLast(entry); while (entries.size > 200) entries.removeFirst()
            runCatching {if(logFile.length()>32768)logFile.writeText(entries.joinToString("\n")+"\n") else logFile.appendText(entry+"\n")}
        }
        // Never log arguments, input text, screen text, pairing codes or screenshots.
    }

    /** Natural completion releases this exact display without cancelling its final chat reply. */
    suspend fun completeTask(owner: String, shouldRetain: () -> Boolean = { false }): Boolean {
        val expected = synchronized(ownershipLock) {
            session?.takeIf { it.owner == owner }
        } ?: return false
        return withContext(Dispatchers.IO) {
            operations.withLock {
                currentCoroutineContext().ensureActive()
                // Match the operation -> ownership lock order used by normal phone tools.
                // Recheck after waiting: an update, takeover or a newer display wins.
                synchronized(ownershipLock) {
                    if (session !== expected || expected.owner != owner || expected.cancelled.get() ||
                        paused || shouldRetain()) {
                        false
                    } else {
                        expected.onUserStop = null
                        expected.cancelled.set(true)
                        epoch.incrementAndGet()
                        log("task_stop:task_completed")
                        // No suspension between the final checks and retiring this Session.
                        // releaseDisplay also cancels its input and finishes only owned tasks.
                        releaseSession()
                        true
                    }
                }
            }
        }
    }

    fun requestStop(revoke: Boolean = false) {
        log("task_stop:user_stop")
        // Revoking the helper credentials also ends this app's explicit control opt-in.
        // Never change the user's system accessibility switch programmatically.
        if (revoke) {
            preferences.edit().putBoolean("enabled", false).apply()
            refreshStandbyProtection(allowStart = false)
        }
        var stoppedSession: Session? = null
        val stopOwner = synchronized(ownershipLock) {
            stoppedSession = session
            val callback = session?.onUserStop ?: operationUserStop
            session?.onUserStop = null
            operationUserStop = null
            session?.cancelled?.set(true)
            epoch.incrementAndGet()
            callback
        }
        // The host owns model/provider cancellation. Deliver synchronously so a model reply
        // cannot enqueue a fresh open_app between closing this window and releasing its display.
        runCatching { stopOwner?.invoke() }
        preview.hide()
        bridge.cancelPendingConnection()
        context.stopService(Intent(context, me.rerere.androidcontrol.ui.AndroidControlPairingService::class.java))
        val stopped = stoppedSession
        scope.launch {
            stopped?.let { runCatching { it.shell.cancelDisplayInput(it.display) } }
            operations.withLock {
                // The cancelled job may have released its screen already. Do not retire a
                // later user's screen while this asynchronous cleanup waited for the mutex.
                if (session === stopped || revoke) releaseSession()
                if (revoke) bridge.revoke()
            }
        }
    }
    suspend fun stopAll(revoke: Boolean = false) = withContext(Dispatchers.IO) {
        if (revoke) {
            preferences.edit().putBoolean("enabled", false).apply()
            refreshStandbyProtection(allowStart = false)
        }
        synchronized(ownershipLock) {
            session?.cancelled?.set(true)
            epoch.incrementAndGet()
        }
        preview.hide()
        bridge.cancelPendingConnection()
        context.stopService(Intent(context, me.rerere.androidcontrol.ui.AndroidControlPairingService::class.java))
        session?.let {runCatching {it.shell.cancelDisplayInput(it.display)}}
        operations.withLock { releaseSession(); if (revoke) bridge.revoke() }
    }
    private fun releaseSession() {
        preview.hide()
        val old = session
        session = null; latestTree = JsonObject(emptyMap()); pendingSensitive = null; paused = false
        lastPreviewCaptureError = null
        if (old != null) {
            val released = runCatching { old.shell.releaseDisplay(old.display) }.isSuccess
            runCatching { old.reader.close() }; old.thread.quitSafely()
            log(if (released) "任务停止，虚拟屏已释放" else "任务已停止；助手未确认旧虚拟屏清理")
        }
        // Release the virtual display completely, but keep already-running, explicitly
        // authorized accessibility standby. Cleanup must never start a new service.
        refreshStandbyProtection(allowStart = false)
    }

    /** The existing Agent approval dialog gates this exact observed action, not a model-supplied flag. */
    fun approvalFingerprint(name: String, args: JsonObject, owner: String): String? {
        if (name !in MUTATIONS) return null
        val mode = approvalMode(owner)
        val selected = targetNodes(latestTree, args).joinToString(" ") { node -> nodes(node).joinToString(" ") { labels(it) } }
        val coordinate = name=="tap" && listOf("node_id", "target_text", "resource_id").none { args.string(it) != null }
        val enterMaySubmit = name=="press_key" && args.string("key")?.uppercase() in setOf("ENTER","CONFIRM") &&
            nodes(latestTree).any { ControlPolicy.sensitive(labels(it)) }
        val risk = coordinate || enterMaySubmit || ControlPolicy.sensitive(selected) || args["action_kind"]?.jsonPrimitive?.contentOrNull in setOf("send", "delete", "payment")
        if (!requiresControlApproval(mode, mutation = true, risk = risk)) return null
        return digest("$owner|${session?.id}|$mode|$name|$args|${latestTree}")
    }

    suspend fun execute(name: String, args: JsonObject, owner: String = "manual", approvedFingerprint: String? = null,
                        shouldReplan: () -> Boolean = { false }, onUserStop: (() -> Unit)? = null): JsonObject {
        val startedAt = SystemClock.elapsedRealtime()
        val ticket = epoch.get()
        if (name == "stop_task") {
            val active = synchronized(ownershipLock) {
                val currentOwner = session?.owner ?: operationOwner
                if (currentOwner != null && currentOwner != owner) return failure("task_owned_by_another_conversation")
                if (shouldReplan()) return controlReplanResult(latestTree)
                log("task_stop:model_stop_task")
                preview.hide()
                // Invalidate queued calls and a createDisplay that has not returned a Session yet.
                val current = session?.also { it.cancelled.set(true) }
                epoch.incrementAndGet()
                current
            }
            if(active!=null)withContext(Dispatchers.IO) {runCatching {active.shell.cancelDisplayInput(active.display)}}
            operations.withLock {
                // A different conversation may have started after STOP; never release its task.
                if (session?.owner == owner) releaseSession()
            }
            return buildJsonObject { put("ok", true); put("stopped", true) }
        }
        return withContext(Dispatchers.IO) {
            operations.withLock {
                try {
                    synchronized(ownershipLock) {
                        checkRunning(ticket)
                        check(session == null || session?.owner == owner) { "task_owned_by_another_conversation" }
                        operationOwner = owner
                        operationUserStop = onUserStop
                        session?.takeIf { it.owner == owner }?.onUserStop = onUserStop
                    }
                    check(enabled) { "control_disabled_open_settings" }
                    check(Build.VERSION.SDK_INT >= 34) { "isolated_background_control_requires_android_14" }
                    check(bridge.shell.value != null) { "shell_not_connected_open_settings" }
                    val authorized = accessibilityAuthorizationGranted()
                    val accessibility = AndroidControlAccessibilityService.instance ?: if (authorized) {
                        refreshStandbyProtection()
                        preview.updateAction("等待无障碍服务恢复…")
                        AndroidControlAccessibilityService.awaitConnected()
                    } else null
                    // STOP/revocation wins even if Android completes its bind meanwhile.
                    checkRunning(ticket)
                    accessibilityReadinessError(accessibilityAuthorizationGranted(),
                        accessibility != null)?.let { error(it) }
                    val active = session
                    check(active == null || active.owner == owner) { "task_owned_by_another_conversation" }
                    active?.let(::reconcileHeldDrag)
                    check(!paused) { "user_takeover_active" }
                    preview.updateAction(actionLabel(name))
                    currentCoroutineContext().ensureActive()
                    if (shouldReplan()) {
                        active?.let(::cancelHeldDrag)
                        return@withLock controlReplanResult(latestTree)
                    }
                    val output = if (name == "run_steps") runSteps(args, owner, ticket, shouldReplan)
                    else action(name, args, owner, ticket, approvedFingerprint, shouldReplan = shouldReplan)
                    val current = session?.takeIf { it.owner == owner }
                    current?.let(::reconcileHeldDrag)
                    if (!output.ok()) current?.let(::cancelHeldDrag)
                    if (current != null && (current.dragId > 0L || name == "drag")) JsonObject(output +
                        ("drag_active" to JsonPrimitive(current.dragId > 0L))) else output
                } catch (cancelled: CancellationException) {
                    log("task_stop:operation_cancelled")
                    epoch.incrementAndGet(); releaseSession(); throw cancelled
                } catch (error: Exception) {
                    val active = session
                    active?.takeIf { it.owner == owner }?.let(::cancelHeldDrag)
                    // A manual handoff interrupts the operation, but keeps its display available
                    // for an explicit resume. STOP/revocation/Binder death always wins instead.
                    val takeoverInterrupted = paused && active?.owner == owner &&
                        !active.cancelled.get() && enabled && bridge.shell.value?.asBinder()?.isBinderAlive == true
                    val code = if (takeoverInterrupted && error.message in setOf("task_stopped", "user_takeover_active")) "user_takeover_active"
                        else if(epoch.get()!=ticket || active?.cancelled?.get()==true) "task_stopped"
                        else error.message?.takeIf {it.matches(Regex("[a-z][a-z0-9_]{0,100}"))} ?: "operation_failed_${error.javaClass.simpleName}"
                    if (code == "task_stopped" && session?.owner == owner) releaseSession()
                    lastError = code; log("$name 失败: ${code.substringBefore(':')}")
                    preview.updateAction("操作暂停：${actionLabel(name)}失败")
                    failure(code)
                } finally {
                    synchronized(ownershipLock) { operationOwner = null; operationUserStop = null }
                }
            }
        }.let { JsonObject(it + ("elapsed_ms" to JsonPrimitive(SystemClock.elapsedRealtime() - startedAt))) }
    }

    private suspend fun awaitAccessibility(): AndroidControlAccessibilityService {
        AndroidControlAccessibilityService.instance?.let { return it }
        if (!accessibilityAuthorizationGranted()) error("accessibility_not_authorized")
        return AndroidControlAccessibilityService.awaitConnected() ?: error("accessibility_service_disconnected")
    }

    private fun checkRunning(ticket: Long) {
        check(epoch.get() == ticket && enabled) { "task_stopped" }
        check(session?.cancelled?.get() != true) { "task_stopped" }
        check(bridge.shell.value != null) { "shell_disconnected" }
        session?.let { check(it.shell.asBinder() === bridge.shell.value?.asBinder()) { "shell_disconnected" } }
    }
    private fun cancelHeldDrag(s: Session) {
        val token = s.dragId
        s.dragId = 0L
        if (token > 0L) runCatching { s.shell.endDrag(s.display, token, true) }
    }
    private fun heldDragGeometry(s: Session): List<Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(s.display) ?: return emptyList()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return listOf(metrics.widthPixels, metrics.heightPixels, display.rotation)
    }
    private fun reconcileHeldDrag(s: Session) {
        if (s.dragId > 0L && (SystemClock.elapsedRealtime() - s.dragStartedAt >= 60_000L ||
                heldDragGeometry(s) != s.dragGeometry)) cancelHeldDrag(s)
    }
    private suspend fun newSession(owner: String, args: JsonObject, ticket: Long): Session {
        check(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED) { "notification_permission_required_for_stop_control" }
        check(AndroidControlNotificationService.notificationsAllowed(context)) { "notification_permission_required_for_stop_control" }
        val geometryKeys = setOf("width", "height", "dpi")
        val geometry = if (args.keys.any { it in geometryKeys }) {
            require(args.keys.containsAll(geometryKeys)) { "explicit_display_geometry_requires_width_height_and_dpi" }
            fun dimension(key: String) = (args[key] as? JsonPrimitive)?.intOrNull
                ?: error("invalid_display_geometry")
            explicitDisplayGeometry(dimension("width"), dimension("height"), dimension("dpi"))
        } else {
            // Match the reference native small-window ratio while preserving short-edge
            // resolution and density; do not stretch the physical 22:10 screen into it.
            val metrics = DisplayMetrics()
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: error("phone_display_unavailable")
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            floatingDisplayGeometry(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
        val (width, height, dpi) = geometry
        val thread = HandlerThread("android-control-frames").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        // Drain frames to keep BufferQueue live. Screen reads use the authorized Accessibility API.
        reader.setOnImageAvailableListener({ value -> runCatching { value.acquireLatestImage()?.close() } }, Handler(thread.looper))
        try {
            val creator = bridge.shell.value ?: error("shell_disconnected")
            val display = creator.createDisplay(reader.surface, width, height, dpi)
            check(display > 0) { "virtual_display_creation_failed" }
            val created = Session(owner, UUID.randomUUID().toString(), display, reader, thread, creator,
                onUserStop = operationUserStop)
            session = created
            // STOP can arrive while the Binder creates the display. Do not enqueue an FGS for
            // that cancelled task; execute() will release the newly returned display below.
            checkRunning(ticket)
            try { context.startForegroundService(Intent(context, AndroidControlNotificationService::class.java)) }
            catch (error: Exception) { releaseSession(); throw IllegalStateException("foreground_notification_unavailable", error) }
            log("创建虚拟屏 $display")
            return created
        } catch (error: Exception) { reader.close(); thread.quitSafely(); throw error }
    }

    private suspend fun action(name: String, requestedArgs: JsonObject, owner: String, ticket: Long, approval: String?, batch: Boolean=false, visualAttempt: Int=0,
                               shouldReplan: () -> Boolean = { false }): JsonObject {
        val actionBegan = SystemClock.elapsedRealtime()
        var args = requestedArgs
        var executionMethod = "native_observation"
        var observedAfterLaunch: JsonObject? = null
        var solvedGap: SliderSolver.Result? = null
        var solvedCaptureMs = 0L
        checkRunning(ticket)
        if (shouldReplan()) return controlReplanResult(latestTree)
        var resolvedPackage: String? = null
        if (name == "open_app") {
            val openApproval = approvalFingerprint(name, args, owner)
            if (openApproval != null && openApproval != approval) return failure("confirmation_required_retry_single_action")
            val requestedPackage = args.string("package_name")?.trim().orEmpty()
            check(requestedPackage != context.packageName) { "controller_permissions_require_user_settings" }
            val requestedName = args.string("app_name")?.trim().orEmpty()
            require(requestedPackage.isNotEmpty() || requestedName.isNotEmpty()) { "app_name_or_package_name_required" }
            val query = requestedName.ifEmpty { requestedPackage }
            val installed = installedControlApps(context)
            val exact = installed.firstOrNull { it.packageName == requestedPackage }
            val matches = exact?.let(::listOf) ?: matchControlApps(query, installed)
            if (matches.size != 1) {
                val code = if (matches.isEmpty()) "app_launcher_not_found" else "app_name_ambiguous_choose_package"
                lastError = code
                return buildJsonObject {
                    put("ok", false); put("error", code)
                    put("message", if (matches.isEmpty()) "未找到对应的已安装应用，请核对手机上的应用名称。" else "有多个匹配应用，请根据候选包名选择。")
                    put("app_candidates", JsonArray(matches.map { candidate -> buildJsonObject {
                        put("package_name", candidate.packageName); put("app_name", candidate.label)
                    } }))
                }
            }
            resolvedPackage = matches.single().packageName
        }
        val s = session ?: if (name == "open_app") newSession(owner, args, ticket) else error("no_active_task_call_open_app")
        checkRunning(ticket)
        check(s.owner == owner) { "task_owned_by_another_conversation" }
        args["display_id"]?.jsonPrimitive?.intOrNull?.let { check(it == s.display) { "display_not_owned" } }
        val service = awaitAccessibility()
        checkRunning(ticket)
        val shell = bridge.shell.value ?: error("shell_disconnected")
        // Releasing a held pointer must remain available even if the target page
        // changed into a dialog/password field while the model was observing it.
        if (name == "drag" && args.string("phase") in setOf("end", "cancel")) {
            val token = s.dragId
            check(token > 0 && shell.endDrag(s.display, token, args.string("phase") == "cancel")) { "drag_not_active_or_expired" }
            s.dragId = 0L
            return result(s, readTree(s)) {
                put("execution_method", "display_scoped_held_drag")
                put("drag_active", false); put("input_accepted", true); put("effect_verified", false)
            }
        }
        check(s.dragId == 0L || name in setOf("drag", "screenshot", "get_ui_tree", "wait_for")) {
            "drag_active_finish_or_cancel_first"
        }
        if (name == "wait_for") return waitFor(s, args, ticket, shouldReplan)
        // Launching another app does not act on the old app's controls. Its fresh
        // tree is read after launch below; scanning/OCR of the old page only delays it.
        val before = if (name == "open_app") JsonObject(emptyMap()) else readTree(s,
            includeOcr = name == "get_ui_tree" && args["visual_fallback"]?.jsonPrimitive?.booleanOrNull == true)
        args = resolveControlTarget(before, args, name)
        val expected = args.string("expect_app")
        if (name != "open_app" && expected != null) check(s.packageName == expected && before.string("packageName") == expected) { "unexpected_app" }
        if (name in MUTATIONS && name != "open_app") {
            if (s.visualFrames.blocked && nativeActionableNodeCount(before) == 0) return visualNoProgress(before)
            check(before.string("packageName") == s.packageName) { "unexpected_app_or_dialog" }
            if(batch)check(applicationWindows(before)<=1) { "unexpected_dialog_requires_review" }
            val selectedId=args.string("node_id")
            if(applicationWindows(before)>1 && selectedId!=null) {
                val topWindow=(before["windows"] as? JsonArray)?.firstOrNull {it.jsonObject["type"]?.jsonPrimitive?.intOrNull==1}
                check(topWindow!=null && nodes(topWindow).any {it.string("id")==selectedId}) { "node_covered_by_dialog_refresh_required" }
            }
            val all = nodes(before).filter { it["visible"]?.jsonPrimitive?.booleanOrNull != false }.toList()
            if (all.any { it["password"]?.jsonPrimitive?.booleanOrNull == true || ControlPolicy.takeover(labels(it)) }) {
                return failure("user_takeover_required", before)
            }
            val risk = approvalFingerprint(name, requestedArgs, owner)
            if (risk != null && risk != approval) {
                pendingSensitive = risk
                return failure("confirmation_required_retry_single_action", before)
            }
        }
        checkRunning(ticket)
        val revision = service.revision(s.display)
        if (shouldReplan()) return controlReplanResult(latestTree)
        when (name) {
            "open_app" -> {
                val packageName = resolvedPackage ?: error("app_name_or_package_name_required")
                require(PACKAGE.matches(packageName)) { "invalid_package_name" }
                check(packageName!=context.packageName) { "controller_permissions_require_user_settings" }
                when (shell.startApp(s.display, packageName)) {
                    0 -> s.packageName = packageName
                    1 -> error("app_in_use_on_main_screen")
                    2 -> error("app_launcher_not_found")
                    else -> error("app_launch_failed")
                }
                // App playback may start before its accessibility tree/OCR is ready.
                // Create the local preview now; its own protected-window gate still
                // decides when pixels may be shown, independently of the Agent read.
                showPreview(s)
                val result = waitFor(s, buildJsonObject { put("expect_app", packageName); put("timeout_ms", 10_000) }, ticket, shouldReplan)
                if (!result.ok() || result.isTaskUpdatePending()) return if (result.isTaskUpdatePending()) {
                    JsonObject(result + ("action_result" to buildJsonObject {
                        put("app_launch_dispatched", true); put("package_name", packageName)
                    }))
                } else result
                observedAfterLaunch = result["screen"] as? JsonObject
                executionMethod = "display_scoped_launch"
            }
            "get_ui_tree" -> Unit
            "screenshot" -> {
                val reason = args.string("fallback_reason")
                val includeOcr = args["include_ocr"]?.jsonPrimitive?.booleanOrNull != false
                require(reason == null || reason in setOf("visual_content", "native_controls_unavailable", "user_requested")) { "invalid_screenshot_fallback_reason" }
                if (screenshotNeedsNativeRedirect(before, reason)) return buildJsonObject {
                    put("ok", false); put("error", "native_controls_available_use_nodes"); put("screen", before)
                    put("execution_method", "native_observation")
                }
                val top = topApplicationWindow(before)
                val topId = top?.get("id")?.jsonPrimitive?.intOrNull
                val topBounds = top?.get("bounds")?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList()
                val cached = if (s.dragId == 0L && reason != "user_requested" &&
                    (!includeOcr || !screenshotNeedsLocalOcr(before,reason) || s.visualOcr.isNotEmpty())) s.visualFrames.cached(topId, topBounds,
                    before["revision"]?.jsonPrimitive?.longOrNull ?: -1, SystemClock.elapsedRealtime()) else null
                if (cached != null) {
                    // A framework tree refresh may replace node bookkeeping while
                    // the bound visual window/revision remain unchanged. Preserve
                    // the OCR belonging to this cached frame, not an empty tree.
                    latestTree = controlObservation(JsonObject(before + if (s.visualOcr.isNotEmpty()) mapOf(
                        "ocr" to s.visualOcr, "read_method" to JsonPrimitive("accessibility_then_local_ocr")) else emptyMap()),
                        s.visualOcr.isNotEmpty())
                    return visualFrameResult(s, latestTree, cached, unchanged = true, cached = true)
                }
                // Debounced accessibility events can arrive after launch/scroll
                // returns. Wait for a short quiet interval before binding pixels
                // to a revision, without taking and throwing away extra images.
                val settleUntil = SystemClock.elapsedRealtime() + 650
                while (SystemClock.elapsedRealtime() < settleUntil) {
                    checkRunning(ticket)
                    val current = service.revision(s.display)
                    if (!service.awaitChange(s.display,current,120)) break
                }
                agentScreenshotCount.incrementAndGet()
                val captureBegan = SystemClock.elapsedRealtime()
                val capture = service.screenshotWithWindow(s.display) ?: return failure(
                    screenshotFailureCode(service.lastScreenshotError(s.display)),before)
                val captureMs = SystemClock.elapsedRealtime() - captureBegan
                val bitmap = capture.bitmap
                val bounds = capture.windowBounds.let { listOf(it.left,it.top,it.right,it.bottom) }
                var observed = service.tree(s.display)
                val observation: ControlVisualFrames.Observation
                val encoded: ByteArray
                val uploadSize = controlUploadSize(bitmap.width, bitmap.height)
                var encodeMs = 0L
                var ocrMs = 0L
                var proofMs = 0L
                val needsOcr = includeOcr && screenshotNeedsLocalOcr(before,reason)
                try {
                    if (!ocrWindowMatches(observed, capture.windowId, bounds, observed["revision"]?.jsonPrimitive?.longOrNull ?: -1)) {
                        if (visualAttempt == 0) return action(name,requestedArgs,owner,ticket,approval,batch,visualAttempt=1,shouldReplan=shouldReplan)
                        return failure("visual_frame_changed_retry", readTree(s))
                    }
                    val encodeBegan = SystemClock.elapsedRealtime()
                    val upload = if (bitmap.width == uploadSize.first && bitmap.height == uploadSize.second) bitmap
                        else Bitmap.createScaledBitmap(bitmap, uploadSize.first, uploadSize.second, true)
                    encoded = try { java.io.ByteArrayOutputStream().use { bytes ->
                        check(upload.compress(Bitmap.CompressFormat.JPEG, 82, bytes)); bytes.toByteArray()
                    } } finally { if (upload !== bitmap) upload.recycle() }
                    encodeMs = SystemClock.elapsedRealtime() - encodeBegan
                    // JPEG is deliberately lossy. Reuse/OCR authorization must
                    // compare the original pixels, not a coincidentally identical
                    // downscaled upload (a small label may change between frames).
                    val fingerprint = visualPixelFingerprint(bitmap)
                    // Include events during capture in the later pixel proof,
                    // rather than discarding a usable frame before OCR starts.
                    var boundRevision = if (needsOcr) capture.revisionBefore else observed["revision"]!!.jsonPrimitive.long
                    val previous = s.visualFrames.frame
                    val samePixels = previous?.fingerprint == fingerprint && previous.windowId == capture.windowId && previous.bounds == bounds
                    // Canvas text is read from this exact frame, without a second
                    // capture or a cloud vision/model round trip. Unchanged pixels
                    // reuse their OCR; native controls never enter this path.
                    if (!samePixels || needsOcr && s.visualOcr.isEmpty()) {
                        s.visualOcr = if (needsOcr) {
                            localOcrCount.incrementAndGet()
                            val recognized = localUiOcrResult(bitmap, s.display, bounds[0], bounds[1]) {
                                epoch.get() != ticket || s.cancelled.get()
                            }
                            ocrMs = recognized.elapsedMs
                            if (recognized.status !in setOf("recognized","empty")) return buildJsonObject {
                                failure("local_ocr_${recognized.status}",observed).forEach { (key,value) -> put(key,value) }
                                put("local_ocr_status",recognized.status)
                                put("local_ocr_elapsed_ms",recognized.elapsedMs)
                            }
                            recognized.labels
                        } else JsonArray(emptyList())
                    }
                    observed = service.tree(s.display)
                    if (needsOcr && observed["revision"]?.jsonPrimitive?.longOrNull != boundRevision) {
                        // A delayed window event can arrive while the offline OCR
                        // model starts. Rebind only after a second protected capture
                        // proves these exact pixels are still current; never guess
                        // whether the event was harmless or rerun cloud/model vision.
                        agentScreenshotCount.incrementAndGet()
                        val proofBegan = SystemClock.elapsedRealtime()
                        val proof = service.screenshotWithWindow(s.display)
                        val matches = if (proof == null) false else try {
                            observed = service.tree(s.display)
                            boundRevision = proof.revisionAfter
                            proof.revisionBefore == proof.revisionAfter && proof.windowId == capture.windowId &&
                                proof.windowBounds == capture.windowBounds && proof.bitmap.sameAs(bitmap)
                        } finally { proof.bitmap.recycle() }
                        proofMs = SystemClock.elapsedRealtime() - proofBegan
                        if (!matches) {
                            s.visualOcr = JsonArray(emptyList())
                            if (visualAttempt == 0) return action(name,requestedArgs,owner,ticket,approval,batch,visualAttempt=1,shouldReplan=shouldReplan)
                            return failure("visual_frame_changed_retry",readTree(s))
                        }
                    }
                    if (!ocrWindowMatches(observed, capture.windowId, bounds,
                            if (needsOcr) boundRevision else observed["revision"]!!.jsonPrimitive.long)) {
                        s.visualOcr = JsonArray(emptyList())
                        return failure("visual_frame_changed_retry", readTree(s))
                    }
                    observation = s.visualFrames.observe(fingerprint,capture.windowId,bounds,
                        observed["revision"]!!.jsonPrimitive.long,SystemClock.elapsedRealtime(),
                        uploadSize.first,uploadSize.second)
                } finally { bitmap.recycle() }
                checkRunning(ticket)
                latestTree = controlObservation(buildJsonObject {
                    observed.forEach { (key,value) -> put(key,value) }
                    bridge.shell.value?.topActivity(s.display)?.let { put("activity",it) }
                    if (s.visualOcr.isNotEmpty()) { put("ocr",s.visualOcr); put("read_method","accessibility_then_local_ocr") }
                }, s.visualOcr.isNotEmpty())
                val file = if (!observation.unchanged || reason == "user_requested") {
                    val directory = File(context.cacheDir, "android-control").apply { mkdirs() }
                    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }
                    File(directory, UUID.randomUUID().toString()+".jpg").apply { writeBytes(encoded) }
                        .also { s.lastVisualScreenshot = it }
                } else null
                s.visualObservation = true
                s.visualOcrEnabled = needsOcr
                return JsonObject(visualFrameResult(s, latestTree, observation.frame, observation.unchanged,
                    actionUnchanged = observation.actionUnchanged, path = file?.absolutePath) + mapOf(
                    "image_resent" to JsonPrimitive(observation.unchanged && reason == "user_requested"),
                    "local_timings_ms" to buildJsonObject {
                        put("capture",captureMs); put("encode",encodeMs); put("ocr",ocrMs); put("proof_capture",proofMs)
                        put("tool_total",SystemClock.elapsedRealtime()-actionBegan)
                    }, "image_bytes" to JsonPrimitive(encoded.size), "ocr_enabled" to JsonPrimitive(needsOcr)))
            }
            "tap" -> {
                executionMethod = "accessibility_node"
                val id = args.string("node_id")
                val duration = args.int("duration_ms", 50).coerceIn(30, 5000)
                val node = if (id != null) nodes(before).firstOrNull { it.string("id") == id } ?: error("stale_or_unknown_node_id") else null
                val bounds = node?.get("bounds") as? JsonArray
                if (node != null) check(node["enabled"]?.jsonPrimitive?.booleanOrNull == true && node["visible"]?.jsonPrimitive?.booleanOrNull != false) { "node_not_actionable" }
                if (id == null || duration > 150 || !service.perform(s.display, id, "click")) {
                    if (id != null) check(node?.string("source") == "local_ocr" || service.validateNode(s.display,id)) { "stale_or_unknown_node_id" }
                    if(node?.string("source")=="local_ocr")check(service.revision(s.display)==before["revision"]?.jsonPrimitive?.longOrNull) { "ocr_screen_changed_refresh_required" }
                    val point = if (bounds != null) (bounds[0].jsonPrimitive.int + bounds[2].jsonPrimitive.int) / 2 to
                        (bounds[1].jsonPrimitive.int + bounds[3].jsonPrimitive.int) / 2
                        else controlPoint(s, before, args, args.intRequired("x"), args.intRequired("y"))
                    val (x,y) = point
                    checkRunning(ticket)
                    executionMethod = "display_scoped_gesture"
                    check(if (duration > 150) shell.injectStroke(s.display, intArrayOf(x,x), intArrayOf(y,y), longArrayOf(0,duration.toLong())) else shell.injectTap(s.display,x,y)) { "touch_injection_failed" }
                }
            }
            "input_text" -> {
                executionMethod = "accessibility_set_text"
                val text = args.string("text") ?: error("text_required")
                require(text.length <= 32768) { "input_too_long" }
                val id = args.string("node_id") ?: nodes(before).firstOrNull {
                    it["editable"]?.jsonPrimitive?.booleanOrNull == true && it["focused"]?.jsonPrimitive?.booleanOrNull == true
                }?.string("id") ?: error("editable_node_required")
                check(service.perform(s.display,id,"set_text",text)) { "set_text_failed_use_user_takeover" }
                if (!service.textMatches(s.display,id,text)) service.awaitChange(s.display, revision, 250)
                check(service.textMatches(s.display,id,text)) { "input_readback_mismatch" }
                // Refreshing the edited node verifies the actual app value, but
                // its cached parent/children can still contain the previous text.
                // Discard this service's framework node cache before walking the
                // returned tree; never patch the observation with requested text.
                if (Build.VERSION.SDK_INT >= 33) service.clearCache()
            }
            "swipe" -> {
                executionMethod = "accessibility_scroll"
                val id = args.string("node_id")
                val direction = args.string("direction") ?: "down"
                val scrollAction = when(direction) { "up"->"scroll_backward"; "down"->"scroll_forward"; "left"->"scroll_left"; "right"->"scroll_right"; else->error("invalid_scroll_direction") }
                if (id == null || !service.perform(s.display,id,scrollAction)) {
                    if(id!=null)check(service.validateNode(s.display,id)) { "stale_or_unknown_node_id" }
                    val duration = args.int("duration_ms",450).coerceIn(80,5000)
                    val bounds = id?.let { target -> nodes(before).firstOrNull { it.string("id") == target } }
                        ?.get("bounds")?.jsonArray?.map { it.jsonPrimitive.int }
                    val derived = bounds?.let { swipeWithinBounds(it, direction) }
                    val start = if (derived != null) derived.first[0] to derived.second[0]
                        else controlPoint(s,before,args,args.intRequired("x1"),args.intRequired("y1"))
                    val end = if (derived != null) derived.first[1] to derived.second[1]
                        else controlPoint(s,before,args,args.intRequired("x2"),args.intRequired("y2"))
                    val (x1,y1) = start; val (x2,y2) = end
                    val count=20
                    val xs=IntArray(count+1) { x1+((x2-x1).toLong()*it/count).toInt() }
                    val ys=IntArray(count+1) { y1+((y2-y1).toLong()*it/count).toInt() }
                    val times=LongArray(count+1) { duration.toLong()*it/count }
                    executionMethod = "display_scoped_gesture"
                    check(shell.injectStroke(s.display,xs,ys,times)) { "stroke_injection_failed" }
                }
            }
            "solve_slider" -> {
                executionMethod = "slider_solver"
                val window = topApplicationWindow(before) ?: error("app_window_unavailable")
                val bounds = window["bounds"]!!.jsonArray.map { it.jsonPrimitive.int }
                val imageSpace = args.string("coordinate_space") == "image"
                require(args.string("coordinate_space") in setOf(null, "display", "image")) { "invalid_coordinate_space" }
                val frame = if (imageSpace) {
                    // Validate the supplied frame before interpreting any distance/region.
                    controlPoint(s, before, args, 0, 0)
                    checkNotNull(s.visualFrames.frame)
                } else null
                fun distance(value: Int) = if (frame == null) value else
                    sliderScaledDistance(value, frame.imageWidth, bounds[2] - bounds[0])
                val distanceHint = if (args.containsKey("distance_px")) args.intRequired("distance_px").let {
                    require(it != 0) { "invalid_slider_distance" }; distance(it)
                } else null
                val id = args.string("node_id")
                val handleHint = if (id != null) {
                    val node = nodes(before).firstOrNull { it.string("id") == id &&
                        (it.string("source") == "local_ocr" || nodes(window).any { native -> native.string("id") == id }) }
                        ?: error("stale_or_unknown_node_id")
                    check(node["enabled"]?.jsonPrimitive?.booleanOrNull == true &&
                        node["visible"]?.jsonPrimitive?.booleanOrNull != false) { "node_not_actionable" }
                    check(node.string("source") == "local_ocr" || service.validateNode(s.display, id)) { "stale_or_unknown_node_id" }
                    val b = node["bounds"]!!.jsonArray.map { it.jsonPrimitive.int }
                    require(b[0] >= bounds[0] && b[1] >= bounds[1] && b[2] <= bounds[2] && b[3] <= bounds[3]) { "node_not_actionable" }
                    (b[0] + (b[2] - b[0]) / 2) to (b[1] + (b[3] - b[1]) / 2)
                } else if (args.containsKey("handle_x") || args.containsKey("handle_y")) {
                    controlPoint(s, before, args, args.intRequired("handle_x"), args.intRequired("handle_y"))
                } else null
                val metrics = DisplayMetrics()
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(s.display)
                    ?: error("owned_display_unavailable")
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                val displayBounds = intArrayOf(0, 0, metrics.widthPixels, metrics.heightPixels)
                // An observed handle and explicit distance need input, not a new screenshot.
                // FLAG_SECURE prevents pixels; it does not prohibit a known-coordinate drag.
                val solved = if (handleHint != null && distanceHint != null) {
                    SliderSolver.solveExplicit(handleHint.first, handleHint.second, distanceHint, displayBounds)
                } else {
                    val captureBegan = SystemClock.elapsedRealtime()
                    val capture = service.screenshotWithWindow(s.display)
                        ?: return failure(screenshotFailureCode(service.lastScreenshotError(s.display)), before)
                    solvedCaptureMs = SystemClock.elapsedRealtime() - captureBegan
                    val bitmap = capture.bitmap
                    try {
                        val capturedBounds = capture.windowBounds.let { listOf(it.left, it.top, it.right, it.bottom) }
                        check(capturedBounds == bounds && capture.windowId == window["id"]?.jsonPrimitive?.intOrNull) {
                            "visual_frame_changed_retry"
                        }
                        if (frame != null) check(visualPixelFingerprint(bitmap) == frame.fingerprint) {
                            "stale_screenshot_frame_refresh_required"
                        }
                        fun region(key: String): IntArray? = args[key]?.let { value ->
                            require(value is JsonArray) { "invalid_slider_region" }
                            val values = value
                            val rect = values.map { it.jsonPrimitive.int }
                            if (frame != null) sliderImageRectangle(rect, frame.imageWidth, frame.imageHeight,
                                bitmap.width, bitmap.height)
                            else sliderImageRectangle(rect.mapIndexed { index, value ->
                                value - if (index % 2 == 0) bounds[0] else bounds[1]
                            }, bitmap.width, bitmap.height, bitmap.width, bitmap.height)
                        }
                        SliderSolver.solve(bitmap, bounds[0], bounds[1], displayBounds,
                            region("roi"), region("track"), handleHint, distanceHint,
                            pieceWidthHint = if (args.containsKey("piece_width_px")) args.intRequired("piece_width_px").let {
                                require(it > 0) { "invalid_piece_width" }; distance(it)
                            } else null, pieceStartXHint = if (args.containsKey("piece_start_x")) args.intRequired("piece_start_x").let {
                                if (frame == null) it - bounds[0] else {
                                    require(it in 0 until frame.imageWidth) { "image_coordinates_out_of_bounds" }
                                    (it.toLong() * bitmap.width / frame.imageWidth).toInt()
                                }
                            } else null)
                    } finally { bitmap.recycle() }
                }
                if (!solved.ok || solved.stroke == null) {
                    return failure(solved.error ?: "slider_solve_failed", before)
                }
                checkRunning(ticket)
                val stroke = solved.stroke
                val injected = shell.injectStrokeRotated(s.display, stroke.xs, stroke.ys, stroke.times)
                if (!injected) {
                    return failure("slider_stroke_injection_failed", before)
                }
                solvedGap = solved
                // Reuse the standard mutation post-observation flow below.
            }
            "read_captcha" -> {
                executionMethod = "captcha_ocr"
                val window = topApplicationWindow(before) ?: error("app_window_unavailable")
                val bounds = window["bounds"]!!.jsonArray.map { it.jsonPrimitive.int }
                val windowWidth = bounds[2] - bounds[0]
                val windowHeight = bounds[3] - bounds[1]
                val imageSpace = args.string("coordinate_space") == "image"
                require(args.string("coordinate_space") in setOf(null, "display", "image")) { "invalid_coordinate_space" }
                // A supplied region is mandatory: a whole-screen crop would read
                // every label on the page instead of the code.
                val requested = (args["region"] as? JsonArray)?.map { value ->
                    require(value is JsonPrimitive && value.isString == false) { "invalid_captcha_region" }
                    value.int
                } ?: error("captcha_region_required")
                val frame = if (imageSpace) {
                    controlPoint(s, before, args, 0, 0)
                    checkNotNull(s.visualFrames.frame)
                } else null
                val capture = service.screenshotWithWindow(s.display)
                    ?: return failure(screenshotFailureCode(service.lastScreenshotError(s.display)), before)
                val bitmap = capture.bitmap
                val captchaRead = try {
                    val capturedBounds = capture.windowBounds.let { listOf(it.left, it.top, it.right, it.bottom) }
                    check(capturedBounds == bounds && capture.windowId == window["id"]?.jsonPrimitive?.intOrNull) {
                        "visual_frame_changed_retry"
                    }
                    if (frame != null) check(visualPixelFingerprint(bitmap) == frame.fingerprint) {
                        "stale_screenshot_frame_refresh_required"
                    }
                    val rect = when {
                        frame != null -> CaptchaRegion.imageToWindow(requested, frame.imageWidth, frame.imageHeight,
                            bitmap.width, bitmap.height)
                        args.string("coordinate_space") == "display" ->
                            CaptchaRegion.displayToWindow(requested, bounds[0], bounds[1], bitmap.width, bitmap.height)
                        else -> CaptchaRegion.windowRect(requested, bitmap.width, bitmap.height)
                    }
                    require(CaptchaRegion.looksLikeCaptchaField(rect[2] - rect[0], rect[3] - rect[1])) {
                        "captcha_region_not_field_shaped"
                    }
                    val crop = Bitmap.createBitmap(bitmap, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
                    val read = try {
                        captchaOcr.read(crop, me.rerere.androidcontrol.captcha.CaptchaCharset.whitelist(args.string("charset")))
                    } finally { crop.recycle() }
                    read to rect
                } catch (error: IllegalStateException) {
                    return failure(error.message ?: "captcha_read_failed", before)
                } finally {
                    bitmap.recycle()
                }
                val (read, rect) = captchaRead
                return result(s, before) {
                    put("execution_method", "captcha_ocr")
                    put("captcha_text", read.text)
                    put("captcha_status", read.status)
                    put("captcha_confidence", read.meanConfidence)
                    put("captcha_steps", read.steps)
                    put("local_ocr_elapsed_ms", read.elapsedMs)
                    // Region reported back in the caller's own coordinate space so
                    // a follow-up tap/input_text needs no second conversion.
                    val space = args.string("coordinate_space") ?: "window"
                    val echoed = if (space == "display") rect.mapIndexed { index, value ->
                        value + if (index % 2 == 0) bounds[0] else bounds[1]
                    } else rect.toList()
                    put("captcha_region", buildJsonObject {
                        put("coordinate_space", space)
                        put("bounds", JsonArray(echoed.map(::JsonPrimitive)))
                    })
                    put("input_accepted", false)
                    put("effect_verified", false)
                    put("message", when {
                        read.status == "recognized" ->
                            "已离线识别验证码文字，请用 input_text 填入普通输入框，或按需 tap。识别不保证验证通过。"
                        read.status == "empty" -> "该区域没有识别出字符；请核对裁剪区域是否为验证码图片。"
                        else -> "验证码识别未完成（${read.status}）；请重新读取或改用截图自行判断。"
                    })
                }
            }
            "drag" -> {
                executionMethod = "display_scoped_held_drag"
                val phase = args.string("phase") ?: error("drag_phase_required")
                require(phase in setOf("begin", "move")) { "invalid_drag_phase" }
                val (x, y) = controlPoint(s, before, args, args.intRequired("x"), args.intRequired("y"))
                if (phase == "begin") {
                    check(s.dragId == 0L) { "drag_already_active" }
                    s.dragStartedAt = SystemClock.elapsedRealtime()
                    s.dragGeometry = heldDragGeometry(s)
                    s.dragId = shell.beginDrag(s.display, x, y)
                    check(s.dragId > 0L) { "drag_begin_failed" }
                } else {
                    check(s.dragId > 0L && shell.moveDrag(s.display, s.dragId, x, y,
                        args.int("duration_ms", 160).coerceIn(0, 1000))) { "drag_not_active_or_move_failed" }
                }
            }
            "press_key" -> {
                executionMethod = "display_scoped_key"
                val key=when(args.string("key")?.uppercase()) { "BACK"->4; "ENTER","CONFIRM"->66; "SEARCH"->84; "TAB"->61; "DELETE","BACKSPACE"->67; else->error("unsupported_display_scoped_key") }
                check(shell.injectKey(s.display,key)) { "key_injection_failed" }
            }
            else -> error("unknown_android_control_tool")
        }
        checkRunning(ticket)
        if (name in MUTATIONS && name !in setOf("open_app", "input_text")) {
            // ACTION_CLICK is queued by the target app. The first event can be
            // focus rather than its resulting page; allow a short event quiet
            // interval before reporting the next native observation.
            val deadline=SystemClock.elapsedRealtime()+300
            if (service.awaitChange(s.display,revision,180)) {
                while (SystemClock.elapsedRealtime()<deadline) {
                    checkRunning(ticket)
                    val observed=service.revision(s.display)
                    val remaining=deadline-SystemClock.elapsedRealtime()
                    if (remaining<=0 || !service.awaitChange(s.display,observed,minOf(remaining,40))) break
                }
            }
        }
        // A read-only request already has a fresh tree. Walking it again repeats every
        // accessibility/Binder read and, for canvas apps, the same screenshot/OCR work.
        // Canvas games can redraw without an accessibility event. An accepted
        // action invalidates the old OCR even when the native tree is identical.
        if (name in MUTATIONS) latestTree = JsonObject(latestTree.filterKeys { it != "ocr" })
        val after = observedAfterLaunch ?: if (name == "get_ui_tree") before else readTree(s)
        checkRunning(ticket)
        if (name == "get_ui_tree" && before.string("local_ocr_status") in setOf("timed_out","busy","failed","invalid_frame"))
            return failure("local_ocr_${before.string("local_ocr_status")}",before)
        if (name in MUTATIONS && after.string("packageName") != s.packageName) return failure("unexpected_app_or_dialog",after)
        if (name in MUTATIONS && applicationWindows(after)>1) return failure("unexpected_dialog_requires_review",after)
        log("$name 完成（屏 ${s.display}）")
        lastError = null
        preview.updateAction("${actionLabel(name)}完成")
        if (name in MUTATIONS && name != "open_app" && !(name == "drag" && args.string("phase") == "begin")) s.visualFrames.markAction()
        val actionMetadata = buildJsonObject {
            if (name == "drag") { put("drag_active", s.dragId > 0L); put("drag_timeout_ms", 60_000) }
            solvedGap?.let { solved ->
                put("slider", buildJsonObject {
                    put("gap_x", solved.gapX); put("handle_x", solved.handleX); put("handle_y", solved.handleY)
                    put("distance_px", solved.distancePx); put("method", solved.method); put("confidence", solved.confidence)
                    put("trajectory_points", solved.stroke?.size); put("trajectory_duration_ms", solved.stroke?.times?.lastOrNull())
                    put("capture_ms", solvedCaptureMs)
                })
            }
        }
        if (name == "drag" && args["observe_after"]?.jsonPrimitive?.booleanOrNull != false ||
            shouldObserveVisualAction(s.visualObservation,batch,name,nativeActionableNodeCount(after),
                args["observe_after"]?.jsonPrimitive?.booleanOrNull)) {
            val inputMs = SystemClock.elapsedRealtime()-actionBegan
            val observation = action("screenshot",buildJsonObject {
                put("fallback_reason","visual_content");put("include_ocr",s.visualOcrEnabled)
            },owner,ticket,null)
            return JsonObject(observation + actionMetadata + mapOf(
                "input_accepted" to JsonPrimitive(true), "effect_verified" to JsonPrimitive(false),
                "action_execution_method" to JsonPrimitive(executionMethod),
                "observation_execution_method" to (observation["execution_method"] ?: JsonPrimitive("visual_observation")),
                "execution_method" to JsonPrimitive(executionMethod),
                "action_observation" to JsonPrimitive(true), "action_elapsed_ms" to JsonPrimitive(inputMs),
                "action_effect" to JsonPrimitive(if (observation["image_unchanged"]?.jsonPrimitive?.booleanOrNull == true)
                    "no_visible_change" else if (observation.ok()) "new_visual_observation" else "observation_failed")))
        }
        return result(s,after) {
            put("execution_method", executionMethod)
            actionMetadata.forEach { (key, value) -> put(key, value) }
            if (name in MUTATIONS) {
                put("input_accepted",true)
                // Input dispatch alone is never proof that a canvas popup changed.
                put("effect_verified",name == "input_text" || name == "open_app")
                if (nativeActionableNodeCount(after) == 0 && name != "open_app") put("action_effect","pending_visual_check")
            }
        }
    }

    private fun topApplicationWindow(tree: JsonObject): JsonObject? = (tree["windows"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }?.firstOrNull { it["type"]?.jsonPrimitive?.intOrNull == 1 }

    private fun visualPixelFingerprint(bitmap: Bitmap): String {
        val digest=MessageDigest.getInstance("SHA-256")
        val row=IntArray(bitmap.width)
        val bytes=java.nio.ByteBuffer.allocate(bitmap.width*4)
        // A single reusable row avoids allocating another full-resolution frame.
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            bytes.asIntBuffer().put(row)
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }

    private fun controlPoint(s: Session, tree: JsonObject, args: JsonObject, x: Int, y: Int): Pair<Int,Int> {
        val window = topApplicationWindow(tree) ?: error("app_window_unavailable")
        val bounds = window["bounds"]!!.jsonArray.map { it.jsonPrimitive.int }
        return when (args.string("coordinate_space") ?: "display") {
            "image" -> s.visualFrames.imagePoint(args.string("frame_id"),x,y,window["id"]?.jsonPrimitive?.intOrNull,
                bounds,tree["revision"]?.jsonPrimitive?.longOrNull ?: -1)
            "display" -> {
                // A rotating app can publish node bounds before its window bounds.
                // Validate explicit display pixels against the live owned display;
                // the shell independently validates the same geometry at dispatch.
                val metrics = DisplayMetrics()
                val display = context.getSystemService(DisplayManager::class.java).getDisplay(s.display)
                    ?: error("owned_display_unavailable")
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                require(x in 0 until metrics.widthPixels && y in 0 until metrics.heightPixels) { "display_coordinates_out_of_bounds" }
                x to y
            }
            else -> error("invalid_coordinate_space")
        }
    }

    /** Internal local matching uses the same protected frame and never widens screen ownership. */
    suspend fun captureMediaPickerFrame(owner: String, shouldReplan: () -> Boolean = { false },
        onUserStop: (() -> Unit)? = null): JsonObject {
        val captured = execute("screenshot", buildJsonObject {
            put("fallback_reason", "visual_content"); put("include_ocr", false)
        }, owner, shouldReplan = shouldReplan, onUserStop = onUserStop)
        if (captured["ok"]?.jsonPrimitive?.booleanOrNull != true) return captured
        return synchronized(ownershipLock) {
            val active = session?.takeIf { it.owner == owner && !it.cancelled.get() && enabled && !paused }
                ?: return@synchronized failure("owned_picker_frame_unavailable")
            val frame = active.visualFrames.frame
                ?: return@synchronized failure("owned_picker_frame_unavailable")
            val revision = captured["screen"]?.jsonObject?.get("revision")?.jsonPrimitive?.longOrNull
            if (active.visualFrames.blocked || frame.id != captured["frame_id"]?.jsonPrimitive?.contentOrNull ||
                frame.revision != revision || latestTree["revision"]?.jsonPrimitive?.longOrNull != revision ||
                AndroidControlAccessibilityService.instance?.revision(active.display) != revision) {
                return@synchronized failure("stale_picker_frame_refresh_required")
            }
            val file = active.lastVisualScreenshot?.takeIf { it.isFile && it.length() > 0 }
                ?: return@synchronized failure("owned_picker_frame_unavailable")
            JsonObject(captured + ("screenshot_path" to JsonPrimitive(file.absolutePath)))
        }
    }

    private fun visualNoProgress(tree: JsonObject) = buildJsonObject {
        put("ok",false); put("error","visual_no_progress_use_user_takeover")
        put("manual_takeover_required",true); put("action_effect","no_visible_change"); put("effect_verified",false)
        put("message","两次操作后画面均未改变，已停止重复猜坐标。请停止本次操控后手动处理，或在页面变化后重新读取。")
        put("screen",tree)
    }

    private fun visualFrameResult(s: Session, tree: JsonObject, frame: ControlVisualFrames.Frame,
        unchanged: Boolean, cached: Boolean = false, actionUnchanged: Boolean = false, path: String? = null): JsonObject = buildJsonObject {
        // Lack of progress pauses repeated blind input, never a read-only screenshot.
        result(s,tree).forEach { (key,value) -> put(key,value) }
        if (s.visualFrames.blocked) put("visual_actions_paused", true)
        put("execution_method",if (cached) "visual_frame_cache" else if (s.visualOcr.isNotEmpty()) "visual_screenshot_with_local_ocr" else "visual_screenshot")
        put("frame_id",frame.id); put("image_unchanged",unchanged); put("capture_reused",cached)
        put("image_width",frame.imageWidth); put("image_height",frame.imageHeight)
        put("display_image_width",frame.bounds[2]-frame.bounds[0]); put("display_image_height",frame.bounds[3]-frame.bounds[1])
        put("image_origin",JsonArray(frame.bounds.take(2).map(::JsonPrimitive)))
        put("coordinate_rule","For uploaded-image pixel x/y use coordinate_space=image and this frame_id; the phone maps image_width/image_height to the full window and adds image_origin. Prefer exact OCR targets when present. Default display uses logical display pixels. Never rescale or add origin yourself, and never use preview-thumbnail or normalized 0..1000 coordinates.")
        if (actionUnchanged) { put("action_effect","no_visible_change"); put("effect_verified",false) }
        if (unchanged) put("message", if (path != null) "画面未变，已按请求重新提供截图。" else
            "画面未变，不重复发送图片。使用同帧OCR文字控件；不要重复同一点击或滑动。")
        if (path != null) { put("screenshot_path",path); put("mime_type","image/jpeg") }
    }

    private suspend fun readTree(s: Session, includeOcr: Boolean = false): JsonObject {
        val service = awaitAccessibility()
        var recognitionResult: LocalUiOcrResult? = null
        val previous = latestTree
        var tree = service.tree(s.display)
        val nativeNodes = nodes(tree).toList()
        // Explicit fallback also covers a canvas with one unrelated native Back
        // button; that button does not expose the painted start/match controls.
        val needsOcr = includeOcr &&
            nativeNodes.none { it["password"]?.jsonPrimitive?.booleanOrNull == true }
        val previousOcr = if ((!includeOcr || SystemClock.elapsedRealtime()-s.localOcrAt in 0..1500) &&
            previous["revision"] == tree["revision"] && previous["displayId"] == tree["displayId"] &&
            previous["packageName"] == tree["packageName"] && previous["windows"] == tree["windows"])
            previous["ocr"] as? JsonArray ?: JsonArray(emptyList()) else JsonArray(emptyList())
        val ocr = if (previousOcr.isNotEmpty()) previousOcr else if (needsOcr && tree.string("packageName") != null) {
            val ocrEpoch=epoch.get()
            var recognized = JsonArray(emptyList())
            // A launch event may arrive while the bundled OCR model starts. A
            // bounded pixel proof permits the same on-device labels to survive
            // that event, without exporting an image or trusting stale targets.
            val firstFrameDeadline = SystemClock.elapsedRealtime() + 2_500
            for (attempt in 0..3) {
                if (attempt > 0) {
                    if (SystemClock.elapsedRealtime() >= firstFrameDeadline) break
                    delay(minOf(500, firstFrameDeadline-SystemClock.elapsedRealtime()).coerceAtLeast(0))
                }
                val settleUntil = SystemClock.elapsedRealtime() + 650
                while (SystemClock.elapsedRealtime() < settleUntil && epoch.get()==ocrEpoch && !s.cancelled.get()) {
                    val revision = service.revision(s.display)
                    if (!service.awaitChange(s.display,revision,120)) break
                }
                if (epoch.get()!=ocrEpoch || s.cancelled.get()) break
                localFrameCaptureCount.incrementAndGet()
                val capturedAt = SystemClock.elapsedRealtime()
                val capture = service.screenshotWithWindow(s.display)
                if (capture == null) {
                    if (attempt<3 && service.lastScreenshotError(s.display)==
                        android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) continue
                    break
                }
                val bounds = capture.windowBounds.let { listOf(it.left,it.top,it.right,it.bottom) }
                try {
                    tree = service.tree(s.display)
                    if (!ocrWindowMatches(tree,capture.windowId,bounds,tree["revision"]?.jsonPrimitive?.longOrNull ?: -1)) continue
                    localOcrCount.incrementAndGet()
                    val recognition = localUiOcrResult(capture.bitmap,s.display,capture.windowBounds.left,capture.windowBounds.top) {
                        epoch.get()!=ocrEpoch || s.cancelled.get()
                    }
                    recognitionResult = recognition
                    val labels = recognition.labels
                    tree = service.tree(s.display)
                    if (epoch.get()!=ocrEpoch || s.cancelled.get()) break
                    if (recognition.status in setOf("timed_out","busy","failed","invalid_frame","stopped")) break
                    if (labels.isEmpty() && attempt<3) continue
                    var revision = capture.revisionBefore
                    if (tree["revision"]?.jsonPrimitive?.longOrNull != revision) {
                        delay((400-(SystemClock.elapsedRealtime()-capturedAt)).coerceAtLeast(0))
                        if (epoch.get()!=ocrEpoch || s.cancelled.get()) break
                        localFrameCaptureCount.incrementAndGet()
                        val proof = service.screenshotWithWindow(s.display)
                        val matches = if (proof==null) false else try {
                            tree = service.tree(s.display)
                            revision = proof.revisionAfter
                            proof.revisionBefore==proof.revisionAfter && proof.windowId==capture.windowId &&
                                proof.windowBounds==capture.windowBounds && proof.bitmap.sameAs(capture.bitmap)
                        } finally { proof.bitmap.recycle() }
                        if (!matches) continue
                    }
                    if (ocrWindowMatches(tree,capture.windowId,bounds,revision)) {
                        recognized=labels; s.localOcrAt=SystemClock.elapsedRealtime(); break
                    }
                } finally { capture.bitmap.recycle() }
            }
            recognized
        } else JsonArray(emptyList())
        latestTree = controlObservation(buildJsonObject {
            tree.forEach { (key,value)->put(key,value) }
            if (ocr.isNotEmpty()) { put("ocr",ocr); put("read_method","accessibility_then_local_ocr") }
            recognitionResult?.let { recognition ->
                put("local_ocr_status",recognition.status)
                put("local_ocr_elapsed_ms",recognition.elapsedMs)
                if (recognition.status in setOf("timed_out","busy"))
                    put("local_ocr_retry","wait_for_local_recognition_before_retrying; no repeated screenshot loop")
            }
            bridge.shell.value?.topActivity(s.display)?.let { put("activity",it) }
        }, ocr.isNotEmpty())
        return latestTree
    }
    private suspend fun waitFor(s: Session, args: JsonObject, ticket: Long, shouldReplan: () -> Boolean = { false }): JsonObject {
        val deadline=SystemClock.elapsedRealtime()+args.int("timeout_ms",5000).coerceIn(1,60000)
        val expect=args.string("expect"); val gone=args.string("expect_gone"); val app=args.string("expect_app")
        require(expect!=null || gone!=null || app!=null) { "wait_condition_required" }
        val service=awaitAccessibility()
        while (true) {
            checkRunning(ticket)
            if (shouldReplan()) return controlReplanResult(latestTree)
            // Waiting reads native nodes by default too. OCR is only an explicit
            // fallback, so a slow/unreadable screen cannot trigger it every step.
            val tree=readTree(s, includeOcr = args["visual_fallback"]?.jsonPrimitive?.booleanOrNull == true)
            val labels=nodes(tree).joinToString(" ") { labels(it) }
            val currentApp=tree.string("packageName")
            if (currentApp!=null && (expect==null || labels.contains(expect,ignoreCase=true)) && (gone==null || !labels.contains(gone,ignoreCase=true)) &&
                (app==null || currentApp==app)) return result(s,tree) { put("condition_met",true) }
            if (currentApp!=null && currentApp!=s.packageName && currentApp!=app) return failure("unexpected_app_or_dialog",tree)
            val left=deadline-SystemClock.elapsedRealtime()
            if(left<=0) return failure("expectation_timeout",tree)
            // Event-driven, short bounded wake-ups also observe STOP when no events arrive.
            service.awaitChange(s.display,tree["revision"]?.jsonPrimitive?.longOrNull ?: 0,minOf(left,250))
        }
    }
    private suspend fun runSteps(args: JsonObject, owner: String, ticket: Long, shouldReplan: () -> Boolean): JsonObject {
        val steps=args["steps"] as? JsonArray ?: error("steps_required")
        require(steps.size in 1..20) { "batch_size_must_be_1_to_20" }
        val output=ArrayList<JsonObject>()
        for ((index, raw) in steps.withIndex()) {
            checkRunning(ticket)
            if (shouldReplan()) return controlReplanResult(latestTree, output, index)
            val step=raw.jsonObject; val tool=step.string("tool") ?: error("step_tool_required")
            require(tool in TOOLS && tool !in setOf("run_steps","stop_task","screenshot","solve_slider","read_captcha","drag")) { "invalid_batch_tool" }
            val values=(step["args"] as? JsonObject) ?: JsonObject(emptyMap())
            var entry=try {
                action(tool,values,owner,ticket,null,batch=true,shouldReplan=shouldReplan)
            } catch (cancelled:CancellationException) { throw cancelled
            } catch (error:Exception) {
                // Return completed steps and the just-observed native screen. A
                // stale selector must stop this batch, not erase its progress and
                // encourage the next caller to guess new screenshot coordinates.
                val code=if(epoch.get()!=ticket || session?.cancelled?.get()==true) "task_stopped"
                    else error.message?.takeIf {it.matches(Regex("[a-z][a-z0-9_]{0,100}"))}
                        ?: "operation_failed_${error.javaClass.simpleName}"
                failure(code,latestTree)
            }
            // A real STOP or permission revocation still wins over an ordinary new instruction.
            checkRunning(ticket)
            if (shouldReplan() || entry.isTaskUpdatePending()) {
                output.add(buildJsonObject { put("index",index);put("tool",tool);put("result",JsonObject(entry.filterKeys {it!="screen"})) })
                return controlReplanResult(latestTree, output,
                    if (entry.isTaskUpdatePending()) index else index + 1)
            }
            if(entry.ok() && listOf("expect","expect_gone","expect_app").any { step[it]!=null }) {
                val execution=entry["execution_method"]
                val observed=waitFor(session ?: error("no_active_task"),buildJsonObject {
                    listOf("expect","expect_gone","expect_app","timeout_ms").forEach { key->step[key]?.let {put(key,it)} }
                },ticket,shouldReplan)
                entry=if(observed.isTaskUpdatePending()) {
                    // The click/text entry already happened even if its follow-up expectation
                    // wait was interrupted. Keep that evidence so replanning cannot repeat it.
                    JsonObject(observed + ("action_result" to JsonObject(entry.filterKeys {it!="screen"})))
                } else if(execution!=null) JsonObject(observed + ("execution_method" to execution)) else observed
            }
            checkRunning(ticket)
            output.add(buildJsonObject { put("index",index);put("tool",tool);put("result",JsonObject(entry.filterKeys {it!="screen"})) })
            if (shouldReplan() || entry.isTaskUpdatePending()) {
                return controlReplanResult(latestTree, output, index + 1)
            }
            if(!entry.ok()) return buildJsonObject { put("ok",false);put("stopped_at",index);put("steps",JsonArray(output));put("error",entry["error"] ?: JsonPrimitive("batch_failed"));put("screen",latestTree);put("execution_method","native_batch") }
        }
        val current=session
        checkRunning(ticket)
        if (shouldReplan()) return controlReplanResult(latestTree, output, steps.size)
        if (current != null && shouldObserveVisualAction(current.visualObservation,false,"tap",
                nativeActionableNodeCount(latestTree),args["observe_after"]?.jsonPrimitive?.booleanOrNull)) {
            val observed=action("screenshot",buildJsonObject {
                put("fallback_reason","visual_content");put("include_ocr",current.visualOcrEnabled)
            },owner,ticket,null)
            return JsonObject(observed + mapOf("steps" to JsonArray(output), "action_observation" to JsonPrimitive(true),
                "execution_method" to JsonPrimitive("local_batch_then_visual_observation")))
        }
        return buildJsonObject { put("ok",true);put("steps",JsonArray(output));put("screen",latestTree);put("execution_method","native_batch") }
    }
    suspend fun takeover(): JsonObject = withContext(Dispatchers.IO) {
        val handoff = synchronized(ownershipLock) {
            if (!enabled) return@withContext failure("control_disabled_open_settings")
            val current = session ?: return@withContext failure("no_active_task")
            if (current.cancelled.get()) return@withContext failure("task_stopped")
            // Publish pause before invalidating an in-flight wait so it cannot release the
            // display as if STOP was pressed while this handoff waits for the operation lock.
            paused = true
            current to epoch.incrementAndGet()
        }
        val (s, ticket) = handoff
        preview.hide()
        runCatching { bridge.shell.value?.cancelDisplayInput(s.display) }
        operations.withLock {
            if (session !== s || epoch.get() != ticket || !enabled || s.cancelled.get())
                return@withLock failure("task_stopped")
            val returned = runCatching { bridge.shell.value?.returnToMainDisplay(s.display) == true }.getOrDefault(false)
            if (session !== s || epoch.get() != ticket || !enabled || s.cancelled.get())
                return@withLock failure("task_stopped")
            if (returned) buildJsonObject {put("ok",true);put("paused",true)}
            // Stay paused on a failed handoff; the user can retry or STOP without queued
            // model actions resuming unexpectedly on a partially moved application.
            else failure("takeover_failed")
        }
    }
    suspend fun resumeAfterTakeover(): JsonObject = withContext(Dispatchers.IO) {
        val ticket=epoch.get()
        operations.withLock {
            if (epoch.get() != ticket || !enabled || session?.cancelled?.get() == true)
                return@withLock failure("task_stopped")
            if (bridge.shell.value == null) return@withLock failure("shell_disconnected")
            val s=session ?: return@withLock failure("no_active_task")
            val app=s.packageName ?: return@withLock failure("no_target_app")
            val launched = runCatching { bridge.shell.value?.startApp(s.display,app) == 0 }.getOrDefault(false)
            if (launched && epoch.get() == ticket && session === s && !s.cancelled.get()) {
                paused=false; showPreview(s); buildJsonObject {put("ok",true);put("resumed",true)}
            } else if (epoch.get() != ticket || s.cancelled.get()) failure("task_stopped")
            else failure("leave_target_app_on_main_then_retry")
        }
    }
    private fun result(s:Session, tree:JsonObject, extra:JsonObjectBuilder.()->Unit={})=buildJsonObject {
        showPreview(s)
        put("ok",true);put("task_id",s.id);put("display_id",s.display);put("screen",tree);extra()
    }
    private fun showPreview(s: Session) {
        if (!previewEnabled || !enabled || paused || session !== s || s.cancelled.get()) return
        val capturedShell = bridge.shell.value
        val capturedBinder = capturedShell?.asBinder()
        preview.show(s.display, s.reader.width, s.reader.height, appPackage = s.packageName, nativeOutput = { surface ->
            if (surface != null) {
                if (session !== s || !previewEnabled || !enabled || paused || s.cancelled.get() ||
                    bridge.shell.value?.asBinder() !== capturedBinder) false
                else runCatching { capturedShell?.setPreviewSurface(s.display, surface) == true }.getOrDefault(false)
            } else if (capturedBinder?.isBinderAlive != true) {
                true // Its producer died; no display can still be using this consumer.
            } else if (session !== s && session?.display == s.display) {
                true // Never touch a different task even if Android eventually reuses an ID.
            } else if (runCatching { capturedShell?.setPreviewSurface(s.display, null) == true }.getOrDefault(false)) {
                true
            } else {
                // A failed restore must not abandon a live display's buffer queue. Release only
                // this original helper's owned display, then retire the consumer after its ACK.
                runCatching {
                    checkNotNull(capturedShell).releaseDisplay(s.display)
                    if (session === s) requestStop()
                    true
                }.getOrDefault(false)
            }
        }) {
            if (session !== s || !previewEnabled || !enabled || paused || s.cancelled.get()) {
                return@show AndroidControlPreviewFrame(message = "操作已停止")
            }
            // This bitmap is an on-device preview only: it is never a model input,
            // never written to disk, and never authorizes an Agent action. Show a
            // normal login window to its user while existing credential/verification
            // action guards still require manual takeover. The protected window API
            // below continues to reject FLAG_SECURE content explicitly.
            val service = AndroidControlAccessibilityService.instance
                ?: return@show AndroidControlPreviewFrame(message = "无障碍服务已断开")
            val capture = service.screenshotPreviewWithWindow(s.display)
            if (session !== s || !previewEnabled || !enabled || paused || s.cancelled.get()) {
                capture.capture?.bitmap?.recycle()
                return@show AndroidControlPreviewFrame(message = "操作已停止")
            }
            if (capture.skipped) AndroidControlPreviewFrame(skipped = true)
            else if (capture.capture != null) {
                lastPreviewCaptureError = null
                AndroidControlPreviewFrame(bitmap = capture.capture.bitmap, message = "后台应用画面")
            } else {
                val failure = capture.failure ?: previewCaptureFailure(capture.error)
                lastPreviewCaptureError = failure
                AndroidControlPreviewFrame(message = previewCaptureMessage(failure), captureFailure = failure)
            }
        }
    }
    private fun actionLabel(name: String) = when (name) {
        "open_app" -> "打开应用"; "get_ui_tree" -> "读取界面"; "screenshot" -> "查看画面"
        "tap" -> "点击"; "input_text" -> "输入文字"; "swipe" -> "滑动"; "solve_slider" -> "拖动滑块"; "drag" -> "持续拖动"
        "read_captcha" -> "识别验证码"
        "press_key" -> "按键"; "wait_for" -> "等待页面"; "run_steps" -> "批量操作"
        else -> "正在操作"
    }
    private fun failure(code:String,tree:JsonObject?=null)=buildJsonObject {
        put("ok",false);put("error",code);tree?.let {put("screen",it)}
        if (code == "screenshot_protected_window") {
            put("message", "Android 系统未提供受 FLAG_SECURE 保护的画面；截图请求没有停止任务。演示程序须关闭该窗口的截图保护后才能获取图像。")
            put("retryable", true)
        }
    }
    private fun screenshotFailureCode(error: Int?): String = when (error) {
        android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "screenshot_protected_window"
        android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshot_rate_limited_retry_later"
        else -> "screenshot_unavailable"
    }
    companion object {
        @Volatile private var singleton:AndroidControl?=null
        fun get(context:Context):AndroidControl=singleton ?: synchronized(this) { singleton ?: AndroidControl(context.applicationContext).also {singleton=it} }
        val TOOLS=setOf("open_app","get_ui_tree","screenshot","tap","input_text","swipe","solve_slider","read_captcha","drag","press_key","wait_for","run_steps","stop_task")
        private val MUTATIONS=setOf("open_app","tap","input_text","swipe","solve_slider","drag","press_key")
        private val PACKAGE=Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        fun nodes(element:JsonElement):Sequence<JsonObject> = sequence {
            when(element) {
                is JsonObject -> { if(element["id"] is JsonPrimitive && element["type"] is JsonPrimitive && element["bounds"]!=null) yield(element); element.values.forEach {yieldAll(nodes(it))} }
                is JsonArray -> element.forEach {yieldAll(nodes(it))}
                else -> Unit
            }
        }
        private fun labels(node:JsonObject)=listOf("text","description","hint").mapNotNull {node.string(it)}.joinToString(" ")
        private fun applicationWindows(tree:JsonObject)=(tree["windows"] as? JsonArray)?.count { (it as? JsonObject)?.get("type")?.jsonPrimitive?.intOrNull==1 } ?: 0
        private fun targetNodes(tree:JsonObject,args:JsonObject):List<JsonObject> {
            if (listOf("node_id", "target_text", "resource_id").any { args.string(it) != null })
                return matchingControlTargets(tree, args)
            val x=args["x"]?.jsonPrimitive?.intOrNull;val y=args["y"]?.jsonPrimitive?.intOrNull
            if(x==null || y==null) return emptyList()
            return nodes(tree).filter { node-> val b=node["bounds"] as? JsonArray; b!=null && b.size==4 && x>=b[0].jsonPrimitive.int && x<b[2].jsonPrimitive.int && y>=b[1].jsonPrimitive.int && y<b[3].jsonPrimitive.int }.toList()
        }
        private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {"%02x".format(it)}
        private fun JsonObject.string(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key:String,default:Int)=(this[key] as? JsonPrimitive)?.intOrNull ?: default
        private fun JsonObject.intRequired(key:String)=(this[key] as? JsonPrimitive)?.intOrNull ?: error("${key}_required")
        private fun JsonObject.ok()=this["ok"]?.jsonPrimitive?.booleanOrNull==true
    }
}
