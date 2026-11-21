// Narrow subset adapted from Mika, revision 688f59d5a18d99d0231504598da4059f3feb226e. Apache-2.0.
package me.rerere.androidcontrol.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import me.rerere.androidcontrol.ui.PreviewDisplayGeometry

/** Shell owns displays and inputs. No arbitrary shell command is exported over Binder. */
internal class ShellService(private val allowedUid: Int) : IAndroidShell.Stub() {
    private val context = ShellContext.create()
    private data class OwnedDisplay(val display: VirtualDisplay, val width: Int, val height: Int,
                                    val drainSurface: Surface, var previewSurface: Surface? = null,
                                    var appliedRotation: Int? = null,
                                    @Volatile var rotationAppliedAt: Long = 0L,
                                    var orientationComponent: String? = null,
                                    var runtimeOrientation: Int? = null,
                                    var runtimeComponent: String? = null,
                                    var lastRuntimeCheck: Long = 0L,
                                    val launchedPackages: MutableSet<String> = linkedSetOf())
    private val displays = HashMap<Int, OwnedDisplay>()
    private val launchLock = Any()
    private val orientationWorker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "android-control-orientation").apply { isDaemon = true }
    }
    private var orientationWatch: ScheduledFuture<*>? = null
    private val orientationOutput = Executors.newSingleThreadExecutor { task ->
        Thread(task, "android-control-orientation-read").apply { isDaemon = true }
    }
    private val inputLock = Any()
    private data class HeldDrag(val lease: HeldDragLease, val owned: OwnedDisplay,
                                var x: Int, var y: Int, var expiry: ScheduledFuture<*>? = null)
    // Guarded by inputLock. InputManager sees one pointer device, so no other touch
    // or key transaction may interleave while this finger is held between Binder calls.
    private var heldDrag: HeldDrag? = null
    private val nextDragToken = AtomicLong()
    private val dragWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "android-control-drag-watchdog").apply { isDaemon = true }
    }
    private val cancelledDisplays = ConcurrentHashMap.newKeySet<Int>()
    private data class FallbackInput(val process: java.lang.Process, val displayId: Int, val x: Int, val y: Int,
                                     val keyCode: Int? = null, val cleaned: AtomicBoolean = AtomicBoolean())
    private val fallbackInputs = ConcurrentHashMap<Int, FallbackInput>()
    private val inputCleanup = Executors.newSingleThreadExecutor { task ->
        Thread(task, "android-control-input-cleanup").apply { isDaemon = true }
    }
    @SuppressLint("PrivateApi")
    private val displayManager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)
    private val taskManager by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }
    private val processManager by lazy {
        ActivityManager::class.java.getMethod("getService").invoke(null)!!
    }
    // Guarded by launchLock. Keep identities after a bounded wait times out so a
    // subsequent open cannot reuse a process whose asynchronous removal is pending.
    private val pendingTaskProcesses = linkedSetOf<OwnedProcessSnapshot>()
    private val taskInfo by lazy { Class.forName("android.app.TaskInfo") }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("Android control caller UID is not authorized")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid(), HelperHandoff.IMPLEMENTATION_REVISION)

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        // Earlier releases cannot guarantee independent focus without stealing main-screen focus.
        if (Build.VERSION.SDK_INT < 34 || !surface.isValid || width !in 120..4096 || height !in 120..4096 || dpi !in 72..640) return -1
        return try {
            synchronized(launchLock) {
                if (synchronized(displays) { displays.size >= 4 }) return -1
                val vd = displayManager.createVirtualDisplay("onvyra-agent", width, height, dpi, surface, DISPLAY_FLAGS)
                val id = vd.display.displayId
                if (id <= Display.DEFAULT_DISPLAY) { vd.release(); return -1 }
                synchronized(displays) { displays[id] = OwnedDisplay(vd, width, height, surface) }
                runCatching {
                    val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
                    wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, id, 0)
                }.onFailure { Log.w(TAG, "Virtual-display IME policy unavailable", it) }
                // Respect only this task display's fixed-orientation apps. OEM defaults for external
                // displays may ignore requests even with ROTATES_WITH_CONTENT. Never alter display 0.
                configureAppOrientation(id)
                watchOwnedOrientation()
                id
            }
        } catch (e: Exception) { Log.w(TAG, "Could not create independent display", e); -1 }
    }

    @SuppressLint("PrivateApi")
    private fun configureAppOrientation(displayId: Int) {
        if (!ownDisplay(displayId)) return
        val wm = runCatching { Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null) }.getOrNull() ?: return
        runCatching { wm.javaClass.getMethod("setIgnoreOrientationRequest", Int::class.java, Boolean::class.java)
            .invoke(wm, displayId, false) }
            .onFailure { Log.w(TAG, "App orientation policy unavailable for owned display", it) }
        runCatching { wm.javaClass.getMethod("setFixedToUserRotation", Int::class.java, Int::class.java)
            .invoke(wm, displayId, 1 /* FIXED_TO_USER_ROTATION_DISABLED */) }
            .onFailure { Log.w(TAG, "App rotation policy unavailable for owned display", it) }
    }

    /** Some OEMs letterbox a fixed-landscape activity without rotating an external display.
     * ROTATES_WITH_CONTENT then publishes no change for the preview to observe. Follow the
     * actual top activity's request in the helper, restricted to displays this helper owns. */
    private fun watchOwnedOrientation() {
        if (orientationWatch?.isCancelled == false && orientationWatch?.isDone == false) return
        orientationWatch = orientationWorker.scheduleWithFixedDelay({
            runCatching {
                val topTasks = tasks().groupBy(::displayOf)
                val ownedSnapshot = synchronized(displays) { displays.toMap() }
                for ((id, owned) in ownedSnapshot) {
                    val task = topTasks[id]?.firstOrNull() ?: continue
                    val component = taskInfo.getField("topActivity").get(task) as? ComponentName ?: continue
                    val info = runCatching { taskInfo.getField("topActivityInfo").get(task) as? ActivityInfo }.getOrNull()
                        ?: runCatching { context.packageManager.getActivityInfo(component, 0) }.getOrNull()
                        ?: continue
                    val name = component.flattenToShortString()
                    val taskId = taskInfo.getField("taskId").getInt(task)
                    // The manifest is available immediately. Runtime requests are read at low
                    // cadence because TaskInfo.topActivityInfo does not reflect setRequestedOrientation.
                    val now = SystemClock.uptimeMillis()
                    if (owned.runtimeComponent != name) synchronized(launchLock) {
                        if (synchronized(displays) { displays[id] === owned }) {
                            applyOwnedOrientation(id, info.screenOrientation, name)
                        }
                    }
                    if (owned.runtimeComponent != name || now - owned.lastRuntimeCheck >= 2_000) {
                        // No launch/input/display lock is held while the bounded read-only process runs.
                        val runtime = readRuntimeOrientation(id, taskId, name)
                        owned.runtimeOrientation = runtime
                        owned.runtimeComponent = name
                        owned.lastRuntimeCheck = now
                    }
                    synchronized(launchLock) {
                        if (synchronized(displays) { displays[id] !== owned } || top(id) != component) return@synchronized
                        applyOwnedOrientation(id, owned.runtimeOrientation ?: info.screenOrientation, name)
                    }
                }
            }.onFailure { Log.w(TAG, "Owned-display orientation check unavailable", it) }
        }, 100, 400, TimeUnit.MILLISECONDS)
    }

    private fun readRuntimeOrientation(displayId: Int, taskId: Int, component: String): Int? {
        if (!ownDisplay(displayId)) return null
        val process = runCatching {
            ProcessBuilder("/system/bin/dumpsys", "activity", "activities", component)
                .redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val output = orientationOutput.submit<String> {
            process.inputStream.use { String(it.readNBytes(192 * 1024), Charsets.UTF_8) }
        }
        return try {
            val text = output.get(600, TimeUnit.MILLISECONDS)
            val parsed = if (ownDisplay(displayId)) requestedOrientationFromActivityDump(text, taskId, component) else null
            parsed
        } catch (_: Exception) { null }
        finally {
            process.destroyForcibly()
            output.cancel(true)
        }
    }

    private fun stopOrientationWatchIfIdle() {
        if (synchronized(displays) { displays.isEmpty() }) {
            orientationWatch?.cancel(false)
            orientationWatch = null
        }
    }

    @SuppressLint("PrivateApi")
    private fun applyOwnedOrientation(displayId: Int, orientation: Int, component: String) {
        val owned = synchronized(displays) { displays[displayId] } ?: return
        val geometry = logicalGeometry(owned) ?: return
        val rotation = requestedDisplayRotation(orientation, owned.width, owned.height, geometry.rotation)
        val wm = runCatching { Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null) }.getOrNull() ?: return
        if (rotation == null) {
            // Do not keep the previous fixed activity's lock when entering another activity
            // that delegates rotation to the platform (or follows a runtime sensor request).
            if (owned.appliedRotation != null) {
                val thawed = runCatching {
                    val method = wm.javaClass.methods.first { it.name == "thawDisplayRotation" }
                    if (method.parameterTypes.size == 2) method.invoke(wm, displayId, "Onvyra owned display")
                    else method.invoke(wm, displayId)
                }.isSuccess
                if (thawed) owned.appliedRotation = null
            }
            owned.orientationComponent = component
            return
        }
        owned.orientationComponent = component
        if (owned.appliedRotation == rotation && geometry.rotation == rotation) return
        // This is the production fallback for OEM external-display letterboxing, not a
        // preview crop. Android changes app resources, window bounds and input axes together.
        val changed = runCatching {
            val method = wm.javaClass.methods.first { it.name == "freezeDisplayRotation" }
            if (method.parameterTypes.size == 3) method.invoke(wm, displayId, rotation, "Onvyra owned display")
            else method.invoke(wm, displayId, rotation)
        }.isSuccess || exec("wm", "user-rotation", "-d", displayId.toString(), "lock", rotation.toString())
        if (changed) {
            owned.appliedRotation = rotation
            owned.rotationAppliedAt = SystemClock.uptimeMillis()
        }
    }

    // Query the service directly: Display objects created before rotation may
    // retain caller-adjusted/cached metrics in an app_process helper.
    private val rawDisplayManager by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "display") as android.os.IBinder
        Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
    }
    private fun logicalGeometry(owned: OwnedDisplay): PreviewDisplayGeometry? = runCatching {
        val id = owned.display.display.displayId
        val info = rawDisplayManager.javaClass.getMethod("getDisplayInfo", Int::class.java)
            .invoke(rawDisplayManager, id) ?: return null
        PreviewDisplayGeometry(info.javaClass.getField("logicalWidth").getInt(info),
            info.javaClass.getField("logicalHeight").getInt(info), info.javaClass.getField("rotation").getInt(info))
    }.getOrNull()

    private fun ownDisplay(displayId: Int) = displayId > 0 && synchronized(displays) { displayId in displays }

    /** Rebind the owned display's output, never its input/focus or the user's main screen.
     * The display is deliberately NONSECURE: SurfaceFlinger redacts protected source layers. */
    override fun setPreviewSurface(displayId: Int, surface: Surface?): Boolean = synchronized(launchLock) {
        val owned = if (displayId > Display.DEFAULT_DISPLAY) synchronized(displays) { displays[displayId] } else null
        if (owned == null || surface?.isValid == false) {
            // Incoming Surface objects are this helper's Binder-parcel wrappers, not the
            // application's producer wrapper or SurfaceTexture consumer.
            surface?.release()
            return false
        }
        val target = surface ?: owned.drainSurface
        if (!target.isValid) { surface?.release(); return false }
        try {
            owned.display.surface = target
            val previous = owned.previewSurface
            owned.previewSurface = surface
            if (previous != null && previous !== surface) previous.release()
            true
        } catch (_: Exception) {
            if (surface !== owned.previewSurface) surface?.release()
            false
        }
    }

    private fun releaseOwned(owned: OwnedDisplay) {
        try { owned.display.release() }
        finally { owned.previewSurface?.release(); owned.drainSurface.release() }
    }

    override fun releaseDisplay(displayId: Int) = synchronized(launchLock) {
        val owned = synchronized(displays) { displays[displayId] } ?: return@synchronized
        cancelDisplayInput(displayId)
        // Let ActivityTaskManager finish the owned tasks while their Display still
        // exists. Releasing first can strand cached native engines on a removed
        // display. removeTask delegates process cleanup to Android, which protects
        // packages that still have tasks/services elsewhere; never force-stop a package.
        finishOwnedTasks(displayId, owned)
        thawOwnedDisplay(displayId, owned)
        try {
            synchronized(inputLock) {
                val removed = synchronized(displays) {
                    if (displays[displayId] === owned) displays.remove(displayId) else null
                }
                removed?.let(::releaseOwned)
            }
        } finally {
            cancelledDisplays.remove(displayId)
            stopOrientationWatchIfIdle()
        }
        Unit
    }

    private fun finishOwnedTasks(displayId: Int, owned: OwnedDisplay) {
        if (owned.launchedPackages.isEmpty()) return
        runCatching {
            val launched = owned.launchedPackages.toSet()
            val candidates = tasks().map(::taskSnapshot).filter { it.displayId == displayId &&
                it.packages.any { name -> name in launched } }
            for (expected in candidates) {
                val currentTasks = tasks().map(::taskSnapshot)
                val current = currentTasks.firstOrNull { it.id == expected.id }
                if (!ownedTaskCanBeRemoved(displayId, launched, expected, current)) continue
                runCatching {
                    val protectedPackages = currentTasks.filter { it.id != expected.id }
                        .flatMap { it.packages }.toSet()
                    val protectedPids = serviceProcessIds()
                    val oldProcesses = processSnapshots(expected.packages).filter { process ->
                        process.packages.none { it in protectedPackages } && process.pid !in protectedPids
                    }
                    val removed = taskManager.javaClass.getMethod("removeTask", Int::class.java)
                        .invoke(taskManager, expected.id) as? Boolean ?: false
                    if (removed) pendingTaskProcesses += oldProcesses.map { it.copy(removedTaskId = expected.id) }
                    else Log.w(TAG, "Owned task did not acknowledge removal before display teardown")
                }.onFailure { Log.w(TAG, "Owned task cleanup unavailable before display teardown", it) }
            }
            if (!awaitRemovedTaskProcesses()) Log.w(TAG, "Owned process cleanup is pending; reopening waits for its old identity to exit")
        }.onFailure { Log.w(TAG, "Could not verify owned tasks for cleanup; no other task was touched", it) }
    }

    private fun processSnapshots(wantedPackages: Set<String>): List<OwnedProcessSnapshot> {
        val rows = processManager.javaClass.getMethod("getRunningAppProcesses").invoke(processManager)
            as? List<*> ?: error("Cannot inspect running processes")
        return rows.filterIsInstance<ActivityManager.RunningAppProcessInfo>().filter { info ->
            info.pid > 0 && info.uid / 100_000 == allowedUid / 100_000 &&
                info.pkgList.orEmpty().any { it in wantedPackages }
        }.mapNotNull { info ->
            val start = try { processStartTime(File("/proc/${info.pid}/stat").readText()) }
                catch (missing: java.io.FileNotFoundException) {
                    if (!File("/proc/${info.pid}").exists()) return@mapNotNull null
                    throw missing
                }
            checkNotNull(start) { "Cannot verify process start time" }
            OwnedProcessSnapshot(info.pid, info.uid, info.processName, start, info.pkgList.orEmpty().toSet())
        }
    }

    private fun serviceProcessIds(): Set<Int> {
        val rows = processManager.javaClass.getMethod("getServices", Int::class.java, Int::class.java)
            .invoke(processManager, 1000, 0) as? List<*> ?: return emptySet()
        // AOSP also defers remove-task kills for ordinary started services. Never
        // wait for, kill, or block reopening a process intentionally kept by a service.
        return rows.filterIsInstance<ActivityManager.RunningServiceInfo>()
            .filter { it.foreground || it.started }.mapTo(hashSetOf()) { it.pid }
    }

    private fun currentRemovedProcess(expected: OwnedProcessSnapshot): OwnedProcessSnapshot? {
        // AMS can drop ProcessRecord before the old Linux process has exited.
        // Confirm the previously captured kernel identity directly; do not treat
        // absence from getRunningAppProcesses as proof that its PID is gone.
        val directory = File("/proc/${expected.pid}")
        val start = try { processStartTime(File(directory, "stat").readText()) }
            catch (missing: java.io.FileNotFoundException) {
                if (!directory.exists()) return null
                throw missing
            }
        checkNotNull(start) { "Cannot verify removed process start time" }
        // UID/name were matched to this exact PID/startTime before removal. A
        // different startTime releases the gate immediately for a reused PID.
        return expected.copy(startTime = start)
    }

    private fun awaitRemovedTaskProcesses(packageName: String? = null): Boolean {
        fun relevant() = pendingTaskProcesses.any { packageName == null || packageName in it.packages }
        if (!relevant()) return true
        val deadline = SystemClock.uptimeMillis() + 1_500L
        return try {
            while (true) {
                val currentTasks = tasks().map(::taskSnapshot)
                val protectedPids = serviceProcessIds()
                pendingTaskProcesses.removeAll { old ->
                    val protectedPackages = currentTasks.filter { it.id != old.removedTaskId }
                        .flatMap { it.packages }.toSet()
                    !removedProcessStillPending(old, currentRemovedProcess(old), protectedPackages, protectedPids)
                }
                if (!relevant()) return true
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) return false
                // This runs only in the shell helper's Binder/IO path under the
                // launch gate, never the application's main thread. Exit on death.
                SystemClock.sleep(minOf(25L, left))
            }
            @Suppress("UNREACHABLE_CODE") false
        } catch (error: Exception) {
            Log.w(TAG, "Cannot confirm removed process cleanup; deferred launch stays blocked", error)
            false
        }
    }

    private fun taskSnapshot(task: Any) = OwnedTaskSnapshot(taskInfo.getField("taskId").getInt(task),
        displayOf(task), packagesOf(task).toSet())

    private fun thawOwnedDisplay(displayId: Int, owned: OwnedDisplay) {
        if (owned.appliedRotation == null) return
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            val method = wm.javaClass.methods.first { it.name == "thawDisplayRotation" }
            if (method.parameterTypes.size == 2) method.invoke(wm, displayId, "Onvyra owned display cleanup")
            else method.invoke(wm, displayId)
        }.onFailure { Log.w(TAG, "Owned-display rotation cleanup unavailable", it) }
    }

    override fun releaseAll() = synchronized(launchLock) {
        // Reuse exactly the same input/task/surface cleanup for explicit revoke and
        // helper shutdown; do not bypass task cleanup by clearing ownership first.
        synchronized(displays) { displays.keys.toList() }.forEach { displayId ->
            runCatching { releaseDisplay(displayId) }
                .onFailure { Log.w(TAG, "Owned display teardown failed", it) }
        }
        stopOrientationWatchIfIdle()
        Unit
    }

    override fun destroy() { releaseAll(); exitProcess(0) }

    override fun cancelDisplayInput(displayId: Int) {
        if (!ownDisplay(displayId)) return
        cancelledDisplays.add(displayId)
        fallbackInputs[displayId]?.let { active ->
            active.process.destroyForcibly()
            inputCleanup.execute { runCatching { cleanupFallback(active) } }
        }
        synchronized(inputLock) {
            heldDrag?.takeIf { it.lease.displayId == displayId }?.let { cancelHeldDragLocked(it) }
        }
    }

    override fun startApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId) || !PACKAGE.matches(packageName)) return START_ERROR
        synchronized(inputLock) {
            heldDrag?.takeIf { it.lease.displayId == displayId }?.let { cancelHeldDragLocked(it) }
        }
        try {
            if (!awaitRemovedTaskProcesses(packageName)) return START_ERROR
            val all = tasks()
            // Fail closed if visibility cannot be inspected. Never pull a user's current task away.
            val main = all.filter { displayOf(it) == Display.DEFAULT_DISPLAY }
            val visible = runCatching { taskInfo.getField("isVisible") }.getOrNull()
            if (main.withIndex().any { (i, task) -> packageName in packagesOf(task) && (visible?.getBoolean(task) ?: (i == 0)) }) {
                return START_IN_USE_ON_MAIN
            }
            val ours = synchronized(displays) { displays.keys.toSet() } - displayId
            if (all.any { displayOf(it) in ours && packageName in packagesOf(it) }) return START_IN_USE_BY_OTHER
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.resolveActivityInfo(context.packageManager, 0)?.let { launcher ->
                applyOwnedOrientation(displayId, launcher.screenOrientation,
                    intent.component?.flattenToShortString() ?: packageName)
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
            val ok = runCatching {
                val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
                (start.invoke(taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                    0, 0, null, options, allowedUid / 100_000) as Int) >= 0
            }.getOrElse {
                val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
                exec("am", "start", "--user", (allowedUid / 100_000).toString(), "--display", displayId.toString(), "-n", component)
            }
            if (ok) {
                synchronized(inputLock) {
                    if (!ownDisplay(displayId)) return START_ERROR
                    cancelledDisplays.remove(displayId)
                    synchronized(displays) { displays[displayId]?.launchedPackages?.add(packageName) }
                }
                START_OK
            } else START_ERROR
        } catch (e: Exception) { Log.w(TAG, "Refused launch because task state could not be verified", e); START_ERROR }
    }

    override fun topActivity(displayId: Int): String? = top(displayId)?.className
    override fun topPackage(displayId: Int): String? = top(displayId)?.packageName

    private fun top(displayId: Int): ComponentName? {
        if (!ownDisplay(displayId)) return null
        return runCatching { tasks().firstOrNull { displayOf(it) == displayId }?.let {
            taskInfo.getField("topActivity").get(it) as? ComponentName
        } }.getOrNull()
    }

    override fun returnToMainDisplay(displayId: Int): Boolean = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return false
        synchronized(inputLock) {
            heldDrag?.takeIf { it.lease.displayId == displayId }?.let { cancelHeldDragLocked(it) }
        }
        runCatching {
            val task = tasks().firstOrNull { displayOf(it) == displayId } ?: return false
            val id = taskInfo.getField("taskId").getInt(task)
            taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
                .invoke(taskManager, id, Display.DEFAULT_DISPLAY)
            true
        }.getOrDefault(false)
    }

    private fun displayOf(task: Any) = taskInfo.getField("displayId").getInt(task)
    private fun packagesOf(task: Any) = listOf("topActivity", "baseActivity").mapNotNull {
        (taskInfo.getField(it).get(task) as? ComponentName)?.packageName
    }
    private fun tasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val method = methods.firstOrNull { it.parameterTypes.size == 4 }
        val rows = if (method != null) method.invoke(taskManager, 200, false, false, Display.INVALID_DISPLAY)
            else methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, 200, false, false)
        return (rows as List<*>).filterNotNull()
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean = synchronized(inputLock) {
        if (heldDrag != null || !ownDisplay(displayId) || displayId in cancelledDisplays || keyCode !in ALLOWED_KEYS) return false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        if (!inject(down, displayId)) return fallbackInput(displayId,
            listOf("keyboard", "-d", displayId.toString(), "keyevent", keyCode.toString()), keyCode = keyCode)
        inject(up, displayId) || inject(up, displayId)
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        injectStroke(displayId, intArrayOf(x, x), intArrayOf(y, y), longArrayOf(0, 50))

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        injectStrokeInternal(displayId, xs, ys, times, allowRotated = false)

    override fun injectStrokeRotated(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        injectStrokeInternal(displayId, xs, ys, times, allowRotated = true)

    private fun injectStrokeInternal(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray, allowRotated: Boolean): Boolean = synchronized(inputLock) {
        if (heldDrag != null) return false
        val owned = synchronized(displays) { displays[displayId] } ?: return false
        val bounds = logicalGeometry(owned) ?: return false
        if (displayId in cancelledDisplays) return false
        val n = times.size
        if (n !in 2..1024 || xs.size != n || ys.size != n || times[0] != 0L || times.last() !in 1..10_000 ||
            (1 until n).any { times[it] < times[it - 1] } ||
            (0 until n).any { !bounds.contains(xs[it], ys[it]) }) return false
        // The platform input command normalizes coordinates against the current
        // rotated display projection. Some releases acknowledge a direct Binder
        // motion injection yet project it outside the app after rotation. Choose
        // one complete display-scoped stroke path before sending any DOWN event.
        // Display metrics can change before WM publishes its new input-window
        // projection. Only the first stroke after our rotation waits for that
        // transaction to settle; normal input has no per-call delay.
        val settleUntil = owned.rotationAppliedAt + 650L
        while (SystemClock.uptimeMillis() < settleUntil) {
            if (displayId in cancelledDisplays || !ownDisplay(displayId) || logicalGeometry(owned) != bounds) return false
            SystemClock.sleep(20)
        }
        if (bounds.rotation != 0 && !allowRotated) {
            val arguments = shellStrokeFallbackArguments(displayId, xs, ys, times) ?: return false
            return fallbackInput(displayId, arguments, xs[0], ys[0], durationMs = times.last(), expectedGeometry = bounds)
        }
        val start = SystemClock.uptimeMillis()
        var sentDown = false
        var last = 0
        fun send(i: Int, action: Int): Boolean {
            if (action != MotionEvent.ACTION_CANCEL) {
                while (true) {
                    // A rotation invalidates the whole stroke's coordinate snapshot. Cancel its
                    // down event rather than continuing across a different logical viewport.
                    if (displayId in cancelledDisplays || !ownDisplay(displayId) || logicalGeometry(owned) != bounds) return false
                    val wait = start + times[i] - SystemClock.uptimeMillis()
                    if (wait <= 0) break
                    SystemClock.sleep(minOf(wait, 20L))
                }
            }
            return injectPointer(displayId, start, action, xs[i], ys[i])
        }
        try {
            sentDown = send(0, MotionEvent.ACTION_DOWN)
            if (!sentDown) {
                if (logicalGeometry(owned) != bounds) return false
                val arguments = shellStrokeFallbackArguments(displayId, xs, ys, times) ?: return false
                return fallbackInput(displayId, arguments, xs[0], ys[0], durationMs = times.last(), expectedGeometry = bounds)
            }
            var ok = true
            for (i in 1 until n - 1) {
                if (!send(i, MotionEvent.ACTION_MOVE)) { ok = false; break }
                last = i
            }
            if (!ok || displayId in cancelledDisplays) return false
            val up = send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
            if (up) last = n - 1
            sentDown = !up
            ok && up
        } finally {
            if (sentDown) runCatching { send(last, MotionEvent.ACTION_CANCEL) }
        }
    }

    override fun beginDrag(displayId: Int, x: Int, y: Int): Long = synchronized(inputLock) {
        if (heldDrag != null || displayId <= Display.DEFAULT_DISPLAY || displayId in cancelledDisplays) return 0L
        val owned = synchronized(displays) { displays[displayId] } ?: return 0L
        val geometry = logicalGeometry(owned)?.takeIf { it.contains(x, y) } ?: return 0L
        val settleUntil = owned.rotationAppliedAt + 650L
        while (SystemClock.uptimeMillis() < settleUntil) {
            if (displayId in cancelledDisplays || !ownDisplay(displayId) || logicalGeometry(owned) != geometry) return 0L
            SystemClock.sleep(20)
        }
        val active = HeldDrag(HeldDragLease(nextDragToken.incrementAndGet(), displayId, geometry,
            SystemClock.uptimeMillis()), owned, x, y)
        heldDrag = active
        if (!heldDragIsCurrent(active, x, y) || !injectPointer(displayId, active.lease.downTime, MotionEvent.ACTION_DOWN, x, y)) {
            cancelHeldDragLocked(active)
            return 0L
        }
        active.expiry = runCatching {
            dragWatchdog.scheduleWithFixedDelay({
                synchronized(inputLock) {
                    if (heldDrag === active && !heldDragIsCurrent(active, active.x, active.y)) cancelHeldDragLocked(active)
                }
            }, 100, 100, TimeUnit.MILLISECONDS)
        }.getOrElse {
            cancelHeldDragLocked(active)
            return 0L
        }
        active.lease.token
    }

    override fun moveDrag(displayId: Int, dragId: Long, x: Int, y: Int, durationMs: Int): Boolean = synchronized(inputLock) {
        val active = heldDrag ?: return false
        if (!active.lease.matches(displayId, dragId)) return false
        if (durationMs !in 0..MAX_HELD_DRAG_MOVE_MS || !heldDragIsCurrent(active, x, y)) {
            cancelHeldDragLocked(active)
            return false
        }
        val startX = active.x
        val startY = active.y
        val startedAt = SystemClock.uptimeMillis()
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val fraction = if (durationMs == 0) 1.0 else (elapsed.toDouble() / durationMs).coerceAtMost(1.0)
            val nextX = (startX + (x.toDouble() - startX) * fraction).roundToInt()
            val nextY = (startY + (y.toDouble() - startY) * fraction).roundToInt()
            if (!heldDragIsCurrent(active, nextX, nextY) ||
                !injectPointer(displayId, active.lease.downTime, MotionEvent.ACTION_MOVE, nextX, nextY)) {
                cancelHeldDragLocked(active)
                return false
            }
            active.x = nextX
            active.y = nextY
            if (fraction >= 1.0) return true
            SystemClock.sleep(minOf(16L, (durationMs - elapsed).coerceAtLeast(1L)))
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    override fun endDrag(displayId: Int, dragId: Long, cancel: Boolean): Boolean = synchronized(inputLock) {
        val active = heldDrag ?: return false
        if (!active.lease.matches(displayId, dragId)) return false
        if (cancel) return cancelHeldDragLocked(active)
        if (!heldDragIsCurrent(active, active.x, active.y)) {
            cancelHeldDragLocked(active)
            return false
        }
        val released = injectPointer(displayId, active.lease.downTime, MotionEvent.ACTION_UP, active.x, active.y)
        if (!released) {
            cancelHeldDragLocked(active)
            return false
        }
        active.expiry?.cancel(false)
        heldDrag = null
        true
    }

    private fun heldDragIsCurrent(active: HeldDrag, x: Int, y: Int): Boolean =
        heldDrag === active && active.lease.displayId !in cancelledDisplays &&
            synchronized(displays) { displays[active.lease.displayId] === active.owned } &&
            active.lease.mayContinue(logicalGeometry(active.owned), SystemClock.uptimeMillis(), x, y)

    /** Called under inputLock, before ownership is removed on stop/release. */
    private fun cancelHeldDragLocked(active: HeldDrag): Boolean {
        if (heldDrag !== active) return false
        active.expiry?.cancel(false)
        heldDrag = null
        val cancelled = injectPointer(active.lease.displayId, active.lease.downTime,
            MotionEvent.ACTION_CANCEL, active.x, active.y)
        if (!cancelled) cancelledDisplays.add(active.lease.displayId)
        return cancelled
    }

    /** The same logical coordinates and finger metadata are used for strokes and held drags. */
    private fun injectPointer(displayId: Int, downTime: Long, action: Int, x: Int, y: Int): Boolean = runCatching {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x.toFloat(); this.y = y.toFloat(); size = 1f
            pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
        })
        val source = InputDevice.SOURCE_TOUCHSCREEN
        val deviceId = InputDevice.getDeviceIds().firstOrNull { InputDevice.getDevice(it)?.supportsSource(source) == true } ?: 0
        val event = runCatching {
            MotionEvent::class.java.getMethod("obtain", java.lang.Long.TYPE, java.lang.Long.TYPE,
                Integer.TYPE, Integer.TYPE, props.javaClass, coords.javaClass, Integer.TYPE, Integer.TYPE,
                java.lang.Float.TYPE, java.lang.Float.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE,
                Integer.TYPE, Integer.TYPE).invoke(null, downTime, SystemClock.uptimeMillis(), action, 1,
                    props, coords, 0, 0, 1f, 1f, deviceId, 0, source, displayId, 0) as MotionEvent
        }.getOrElse {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords,
                0, 0, 1f, 1f, deviceId, 0, source, 0)
        }
        try { inject(event, displayId) } finally { event.recycle() }
    }.getOrDefault(false)

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = runCatching {
        if (!ownDisplay(displayId)) return false
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, 2 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */) as Boolean
    }.getOrDefault(false)

    /** Fixed argv only. No shell interpolation, arbitrary command, main-display or partial replay path. */
    private fun fallbackInput(displayId: Int, arguments: List<String>, x: Int = 0, y: Int = 0,
                              durationMs: Long = 0, keyCode: Int? = null,
                              expectedGeometry: PreviewDisplayGeometry? = null): Boolean {
        if (Process.myUid() != 2000 || !ownDisplay(displayId) || displayId in cancelledDisplays) return false
        val process = runCatching { ProcessBuilder(listOf("/system/bin/input") + arguments)
            .redirectErrorStream(true).redirectOutput(java.io.File("/dev/null")).start() }.getOrNull() ?: return false
        val active = FallbackInput(process, displayId, x, y, keyCode)
        fallbackInputs[displayId] = active
        val deadline = SystemClock.uptimeMillis() + durationMs + 3_000L
        var completed = false
        try {
            while (true) {
                if (displayId in cancelledDisplays || !ownDisplay(displayId) || SystemClock.uptimeMillis() >= deadline) return false
                if (expectedGeometry != null) {
                    val owned = synchronized(displays) { displays[displayId] } ?: return false
                    if (logicalGeometry(owned) != expectedGeometry) return false
                }
                if (process.waitFor(20, TimeUnit.MILLISECONDS)) {
                    completed = process.exitValue() == 0 && displayId !in cancelledDisplays
                    return completed
                }
            }
        } finally {
            fallbackInputs.remove(displayId, active)
            if (!completed) {
                cancelledDisplays.add(displayId)
                process.destroyForcibly()
                inputCleanup.execute { runCatching { cleanupFallback(active) } }
            }
        }
    }

    private fun cleanupFallback(active: FallbackInput) {
        if (!active.cleaned.compareAndSet(false, true)) return
        runCatching { active.process.waitFor(100, TimeUnit.MILLISECONDS) }
        synchronized(inputLock) {
            // A successful explicit reopen clears this marker. Never let delayed cleanup cancel
            // the next task's input after the user has resumed or switched its app.
            if (!ownDisplay(active.displayId) || active.displayId !in cancelledDisplays) return
            if (active.keyCode != null) {
                // A failed/terminated key command must never be replayed as another complete key press.
                val now = SystemClock.uptimeMillis()
                inject(KeyEvent(now, now, KeyEvent.ACTION_UP, active.keyCode, 0, 0, -1, 0, 0,
                    InputDevice.SOURCE_KEYBOARD), active.displayId)
            } else {
                // Android input help and AOSP InputShellCommand both explicitly support CANCEL.
                val command = ProcessBuilder("/system/bin/input", "touchscreen", "-d", active.displayId.toString(),
                    "motionevent", "CANCEL", active.x.toString(), active.y.toString())
                    .redirectErrorStream(true).redirectOutput(java.io.File("/dev/null")).start()
                if (!command.waitFor(1_000, TimeUnit.MILLISECONDS)) command.destroyForcibly()
            }
        }
    }

    private fun exec(vararg command: String): Boolean {
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(java.io.File("/dev/null")).start()
        val ended = process.waitFor(10, TimeUnit.SECONDS)
        if (!ended) process.destroyForcibly()
        return ended && process.exitValue() == 0
    }

    companion object {
        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
        const val START_IN_USE_BY_OTHER = 7
        private const val TAG = "AndroidControlShell"
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val ALLOWED_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
        // PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL|
        // TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED|TOUCH_FEEDBACK_DISABLED|OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED.
        private const val DISPLAY_FLAGS = (1 shl 0) or (1 shl 3) or (1 shl 6) or (1 shl 7) or
            (1 shl 8) or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14) or (1 shl 16)
    }
}
