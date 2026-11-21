package me.rerere.androidcontrol.shell

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import me.rerere.androidcontrol.adb.AdbConnection
import me.rerere.androidcontrol.adb.AdbPairing
import me.rerere.androidcontrol.adb.AndroidAdb
import me.rerere.androidcontrol.adb.AdbIoSession
import me.rerere.androidcontrol.adb.AdbIoCancelledException
import me.rerere.androidcontrol.adb.literalAdbHost
import me.rerere.androidcontrol.adb.AdbServiceTypes
import me.rerere.androidcontrol.adb.NsdAdbServiceFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

data class ShellBridgeStatus(
    val connected: Boolean,
    val paired: Boolean,
    val uid: Int? = null,
    val pid: Int? = null,
    val lastError: String? = null,
    val activeMode: AndroidShellMode? = null,
)

/** Starts the same authenticated UID-2000 helper through USB, local ADB, or explicit su authorization. */
class AndroidShellBridge(context: Context) {
    private val app = context.applicationContext
    private val hub = AndroidShellHub.of(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    private val prefs = app.getSharedPreferences("android_control_adb", Context.MODE_PRIVATE)
    private val controls = app.getSharedPreferences("android_control", Context.MODE_PRIVATE)
    @Volatile private var lastError: String? = null
    @Volatile private var reconnectJob: Job? = null
    private val connectionEpoch = AtomicLong()
    private val launchLock = Any()
    private var pendingLaunch: String? = null
    private var pendingLaunchOwner: ShellConnectionOwner? = null
    private val pendingIo = ShellConnectionIoSlot(launchLock)
    @Volatile private var identityResetPending = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" && !controls.getBoolean("enabled", false)) cancelPendingConnection()
    }
    val shell: StateFlow<IAndroidShell?> get() = hub.shell

    init {
        controls.registerOnSharedPreferenceChangeListener(preferenceListener)
        // Reuse only the already-authorized local wireless endpoint after an APK
        // replacement. Never request root or start an Agent task on app startup.
        if (shell.value == null && controls.getBoolean("enabled", false) &&
            prefs.getBoolean("allow_reconnect", false)) scheduleReconnect()
        scope.launch {
            var wasConnected = false
            var connectedGeneration = hub.generation
            shell.collect { service ->
                if (service != null) {
                    wasConnected = true
                    connectedGeneration = hub.generation
                    reconnectJob?.cancel()
                    lastError = null
                    runCatching { writeUsbScript(connected = true) }
                } else if (wasConnected) {
                    wasConnected = false
                    if (hub.generation == connectedGeneration) scheduleReconnect()
                }
            }
        }
    }

    fun status(): ShellBridgeStatus {
        val service = shell.value
        val ids = service?.let { runCatching { it.identity() }.getOrNull() }
        return ShellBridgeStatus(HelperHandoff.currentImplementation(ids), prefs.getBoolean("paired", false),
            ids?.getOrNull(0), ids?.getOrNull(1), lastError,
            activeShellMode(ids, prefs.getInt("started_pid", 0), prefs.getString("started_mode", null)))
    }

    suspend fun pair(host: String, pairPort: Int, code: String, owner: ShellConnectionOwner? = null): Result<Unit> {
        owner?.checkActive()
        val ioTicket = connectionEpoch.get()
        return operation.withLock {
        capture {
            owner?.checkActive()
            if (connectionEpoch.get() != ioTicket) throw AdbIoCancelledException()
            resetIdentityIfPending()
            val generation = hub.generation
            require(Build.VERSION.SDK_INT >= 34) { "Independent Android control requires Android 14 or later" }
            val localHost = withContext(Dispatchers.IO) { localAddress(host) }
            require(pairPort in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit pairing code and pairing port" }
            withNetworkIo(ioTicket,owner) { io ->
                AdbPairing(AndroidAdb.tls(AndroidAdb.identity(app), io::register)).pair(localHost, pairPort, code)
                io.ifActive { synchronized(launchLock) { synchronized(hub) {
                    owner?.checkActive()
                    if (connectionEpoch.get() != ioTicket) throw AdbIoCancelledException()
                    check(hub.generation == generation) { "Android shell authorization was revoked" }
                    check(prefs.edit().putBoolean("paired", true).commit())
                } } }
            }
        }
        }
    }

    suspend fun connect(host: String, port: Int, owner: ShellConnectionOwner? = null): Result<Unit> {
        owner?.checkActive()
        reconnectJob?.cancel()
        return connectEndpoint(host, port, hub.generation, owner = owner, retireOtherMode = true)
    }

    /** Explicit row click: keep pairing credentials and rediscover a changed adbd port. */
    suspend fun connectSavedWireless(): Result<Unit> {
        if (!prefs.getBoolean("paired", false)) return Result.failure(IllegalStateException("无线调试尚未配对"))
        if (!controls.getBoolean("enabled", false)) return Result.failure(IllegalStateException("Android control is disabled"))
        val current = status()
        if (current.connected && current.activeMode == AndroidShellMode.WIRELESS) return Result.success(Unit)
        if (shell.value != null) stopPreservingPairing()
        val ticket = connectionEpoch.get()
        val host = prefs.getString("last_host", null)
        val port = prefs.getInt("last_port", 0)
        val triedPorts = linkedSetOf<Int>()
        var lastFailure: Result<Unit>? = null
        if (host != null && port in 1..65535) {
            triedPorts += port
            val saved = connect(host, port)
            if (saved.isSuccess) return saved
            lastFailure = saved
        }
        if (connectionEpoch.get() != ticket) return Result.failure(AdbIoCancelledException())
        val finder = NsdAdbServiceFinder(app)
        val fresh = finder.findFirstPort(AdbServiceTypes.CONNECT, 3_000L)
            ?: return Result.failure(IllegalStateException("未发现本机无线调试连接，请开启系统无线调试"))
        if (connectionEpoch.get() != ticket) return Result.failure(AdbIoCancelledException())
        if (triedPorts.add(fresh)) {
            val first = connect("127.0.0.1", fresh)
            if (first.isSuccess) return first
            lastFailure = first
        }
        if (connectionEpoch.get() != ticket) return Result.failure(AdbIoCancelledException())
        // Android's mDNS cache can keep a previous local adbd endpoint. Keep the
        // successful first-endpoint path fast, but collect the rest after a failure.
        for (candidate in finder.findPorts(AdbServiceTypes.CONNECT, 3_000L)) {
            if (connectionEpoch.get() != ticket) return Result.failure(AdbIoCancelledException())
            if (!triedPorts.add(candidate)) continue
            val result = connect("127.0.0.1", candidate)
            if (result.isSuccess) return result
            lastFailure = result
        }
        return lastFailure ?: Result.failure(IllegalStateException("本机无线调试连接未成功，请回系统无线调试重试"))
    }

    /** Explicit user action only. su grants startup; the operational helper remains UID2000. */
    suspend fun startWithRoot(): Result<Unit> {
        val requestTicket = connectionEpoch.get()
        reconnectJob?.cancel()
        return operation.withLock {
            capture {
                if (connectionEpoch.get() != requestTicket) throw AdbIoCancelledException()
                require(Build.VERSION.SDK_INT >= 34) { "Independent Android control requires Android 14 or later" }
                check(controls.getBoolean("enabled", false)) { "Android control is disabled" }
                val current = status()
                if (shell.value != null && (!current.connected || current.activeMode != AndroidShellMode.ROOT)) stopPreservingPairing()
                val ioTicket = connectionEpoch.get()
                val generation = hub.generation
                var shellAuthorized = false
                try {
                    withTimeout(60_000L) {
                        withNetworkIo(ioTicket) { io ->
                            io.ifActive { synchronized(hub) {
                                check(hub.generation == generation) { "Android shell authorization was revoked" }
                                // Root startup must not inherit an old wireless reconnect policy.
                                check(prefs.edit().putBoolean("allow_reconnect", false).commit())
                            } }
                            shell.value?.takeIf { current.connected && current.activeMode == AndroidShellMode.ROOT && it.asBinder().isBinderAlive }?.let { service ->
                                if (service.identity().getOrNull(0) != HelperHandoff.SHELL_UID) {
                                    throw RootShellStartException(RootShellStartException.Reason.WRONG_UID)
                                }
                                return@withNetworkIo
                            }
                            val command = RootShellBootstrap.arguments(app.applicationInfo.sourceDir, app.packageName,
                                Process.myUid(), ownCertificatePin())
                            val process = try { ProcessBuilder(command).redirectErrorStream(true).start() }
                            catch (failure: IOException) { throw RootShellStartException(RootShellStartException.Reason.UNAVAILABLE, failure) }
                            io.register(Closeable { RootShellBootstrap.closeProcess(process) })
                            // The command prints no nonce, key or package data. Ignore su banners.
                            if (!RootShellBootstrap.awaitReady(process.inputStream)) {
                                val wrongUid = process.waitFor(200, TimeUnit.MILLISECONDS) && process.exitValue() == RootShellBootstrap.WRONG_UID_EXIT
                                throw RootShellStartException(if (wrongUid) RootShellStartException.Reason.WRONG_UID else RootShellStartException.Reason.DENIED)
                            }
                            shellAuthorized = true
                            val nonce = synchronized(launchLock) {
                                if (connectionEpoch.get() != ioTicket) throw AdbIoCancelledException()
                                io.checkActive()
                                hub.issueLaunch(generation).also { pendingLaunch = it; pendingLaunchOwner = null }
                            }
                            try {
                                process.outputStream.write(HelperHandoff.stdinLine(nonce))
                                process.outputStream.flush()
                                val service = withTimeoutOrNull(20_000L) { shell.filterNotNull().first() }
                                    ?: throw RootShellStartException(RootShellStartException.Reason.HANDOFF_TIMEOUT)
                                if (service.identity().getOrNull(0) != HelperHandoff.SHELL_UID) {
                                    throw RootShellStartException(RootShellStartException.Reason.WRONG_UID)
                                }
                                io.ifActive {
                                    check(hub.generation == generation) { "Android shell authorization was revoked" }
                                    rememberStartedMode(AndroidShellMode.ROOT, service.identity())
                                }
                                runCatching { writeUsbScript(connected = true) }
                            } finally {
                                synchronized(launchLock) {
                                    hub.cancelLaunch(nonce)
                                    if (pendingLaunch == nonce) { pendingLaunch = null; pendingLaunchOwner = null }
                                }
                            }
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    throw RootShellStartException(if (shellAuthorized) RootShellStartException.Reason.HANDOFF_TIMEOUT
                        else RootShellStartException.Reason.AUTHORIZATION_TIMEOUT, timeout)
                }
            }
        }
    }

    private suspend fun connectEndpoint(host: String, port: Int, expectedGeneration: Long,
                                        requestTicket: Long = connectionEpoch.get(), owner: ShellConnectionOwner? = null,
                                        retireOtherMode: Boolean = false): Result<Unit> = operation.withLock {
        capture {
            owner?.checkActive()
            if (connectionEpoch.get() != requestTicket) throw AdbIoCancelledException()
            var ioTicket = requestTicket
            var generation = expectedGeneration
            // Serialize retirement with Root and other connection startups. An old
            // service cannot retire a newer mode while waiting for this mutex.
            if (retireOtherMode) {
                val current = status()
                if (shell.value != null && (!current.connected || current.activeMode != AndroidShellMode.WIRELESS)) {
                    owner?.checkActive()
                    stopPreservingPairing()
                    ioTicket = connectionEpoch.get()
                    generation = hub.generation
                }
            }
            owner?.checkActive()
            resetIdentityIfPending()
            require(Build.VERSION.SDK_INT >= 34) { "Independent Android control requires Android 14 or later" }
            val localHost = withContext(Dispatchers.IO) { localAddress(host) }
            require(port in 1..65535) { "Enter the wireless-debugging connection port" }
            withNetworkIo(ioTicket,owner) { io ->
                if (shell.value?.asBinder()?.isBinderAlive == true) return@withNetworkIo
                val pin = ownCertificatePin()
                val nonce = synchronized(launchLock) {
                    owner?.checkActive()
                    io.checkActive()
                    if (connectionEpoch.get() != ioTicket) throw AdbIoCancelledException()
                    hub.issueLaunch(generation).also { pendingLaunch = it; pendingLaunchOwner = owner }
                }
                try {
                AdbConnection.open(localHost, port, AndroidAdb.tls(AndroidAdb.identity(app), io::register)).use { connection ->
                    io.register(connection)
                        currentCoroutineContext().ensureActive()
                        check(hub.generation == generation) { "Android shell authorization was revoked" }
                        connection.exec(HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), pin)).use { stream ->
                            stream.write(HelperHandoff.stdinLine(nonce))
                            withTimeoutOrNull(20_000) { shell.filterNotNull().first() }
                                ?: error("Helper handoff timed out")
                        }
                }
                check(hub.generation == generation) { "Android shell authorization was revoked" }
                io.ifActive { synchronized(launchLock) { synchronized(hub) {
                    owner?.checkActive()
                    if (connectionEpoch.get() != ioTicket) throw AdbIoCancelledException()
                    check(hub.generation == generation) { "Android shell authorization was revoked" }
                    check(prefs.edit().putBoolean("paired", true).putBoolean("allow_reconnect", true)
                        .putString("last_host", localHost).putInt("last_port", port).commit())
                    rememberStartedMode(AndroidShellMode.WIRELESS, shell.value?.identity())
                } } }
                writeUsbScript(connected = true)
                Unit
                } finally {
                    synchronized(launchLock) {
                        hub.cancelLaunch(nonce)
                        if (pendingLaunch == nonce) { pendingLaunch = null; pendingLaunchOwner = null }
                    }
                }
            }
        }
    }

    /** Returns the exact computer command; no secret appears in it or in status(). */
    fun startUsbScript(): String {
        val generation = hub.generation
        check(Build.VERSION.SDK_INT >= 34) { "Independent Android control requires Android 14 or later" }
        return synchronized(this) {
            HelperHandoff.usbCommand(writeUsbScript(shell.value != null, generation).absolutePath)
        }
    }

    /** Revoke local capability/keys immediately. Remove the paired entry in Android settings too. */
    fun revoke() {
        cancelPendingConnection()
        val previous = synchronized(hub) {
            val old = hub.revoke()
            identityResetPending = true
            prefs.edit().putBoolean("paired", false).putBoolean("allow_reconnect", false)
                .remove("last_host").remove("last_port").remove("started_mode").remove("started_pid").apply()
            old
        }
        lastError = null
        scope.launch {
            previous?.let { runCatching { it.destroy() } }
            synchronized(this@AndroidShellBridge) { runCatching { scriptFile().delete() } }
            operation.withLock {
                resetIdentityIfPending()
            }
        }
    }

    /** Stop only the helper/connection; pairing keys and the saved endpoint remain available. */
    fun stopPreservingPairing() {
        cancelPendingConnection()
        val (previous, stoppedGeneration) = synchronized(hub) {
            // Bump the handoff generation before publishing a disconnected state.
            // A late pairing/launch completion may not re-enable a stopped mode.
            prefs.edit().putBoolean("allow_reconnect", false)
                .remove("started_mode").remove("started_pid").apply()
            hub.revoke() to hub.generation
        }
        lastError = null
        scope.launch {
            previous?.let { runCatching { it.destroy() } }
            synchronized(this@AndroidShellBridge) {
                if (hub.generation == stoppedGeneration && shell.value == null) runCatching { scriptFile().delete() }
            }
        }
    }

    private fun rememberStartedMode(mode: AndroidShellMode, identity: IntArray?) {
        check(HelperHandoff.currentImplementation(identity)) { "Android shell helper is not connected" }
        check(prefs.edit().putString("started_mode", mode.name).putInt("started_pid", identity!![1]).commit())
    }

    /** Cancels blocking I/O immediately, preserving the existing pairing key and authorization. */
    fun cancelPendingConnection() {
        val cancelled = synchronized(launchLock) {
            connectionEpoch.incrementAndGet()
            pendingLaunch?.let { hub.cancelLaunch(it) }
            pendingLaunch = null
            pendingLaunchOwner = null
            reconnectJob?.cancel()
            pendingIo.detachAll()
        }
        // Close exactly the detached request. A new registration after unlocking
        // is independent and must survive this older cancellation.
        cancelled?.close()
    }

    /** Service cleanup cannot invalidate a Root/new-service request's epoch or socket. */
    fun cancelPendingConnection(owner: ShellConnectionOwner) {
        val cancelled = synchronized(launchLock) {
            val io = pendingIo.detachOwned(owner)
            if (pendingLaunchOwner === owner) {
                pendingLaunch?.let { hub.cancelLaunch(it) }
                pendingLaunch = null
                pendingLaunchOwner = null
            }
            io
        }
        cancelled?.close()
    }

    private fun resetIdentityIfPending() = synchronized(hub) {
        if (identityResetPending) { AndroidAdb.forget(app); identityResetPending = false }
    }

    private suspend fun <T> withNetworkIo(ticket: Long, owner: ShellConnectionOwner? = null, block: suspend (AdbIoSession) -> T): T {
        val io = AdbIoSession()
        try { pendingIo.register(io, owner) { connectionEpoch.get() == ticket } }
        catch (failure: Exception) { io.close(); throw failure }
        val worker = scope.async(Dispatchers.IO) { io.checkActive(); block(io) }
        try { return worker.await() }
        catch (failure: Exception) {
            if (failure !is CancellationException && io.cancelled) throw AdbIoCancelledException()
            throw failure
        } finally {
            io.close()
            // Keep the operation mutex until the closed-socket worker exits; do not race key writes.
            withContext(NonCancellable) { worker.cancelAndJoin() }
            pendingIo.finished(io)
        }
    }

    /** Only reuse the exact successful local endpoint. A new task must always be explicitly started. */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val generation = hub.generation
        val host = prefs.getString("last_host", null)
        val port = prefs.getInt("last_port", 0)
        if (!controls.getBoolean("enabled", false)) return
        if (!prefs.getBoolean("allow_reconnect", false) || host == null || port !in 1..65535) {
            lastError = "助手已断开；请在设置中连接，或重新运行 USB 启动命令"
            return
        }
        reconnectJob = scope.launch {
            for ((index, pause) in listOf(1_000L, 3_000L, 7_000L).withIndex()) {
                delay(pause)
                if (!mayReconnect(generation) || shell.value != null) return@launch
                lastError = "助手已断开，正在重连（${index + 1}/3）；原任务已停止"
                if (connectEndpoint(host, port, generation).isSuccess) return@launch
            }
            if (mayReconnect(generation) && shell.value == null) {
                lastError = "自动重连失败；请检查无线调试并重新连接，原任务不会自动恢复"
            }
        }
    }

    private fun mayReconnect(generation: Long) = hub.generation == generation &&
        controls.getBoolean("enabled", false) && prefs.getBoolean("allow_reconnect", false)

    private suspend fun capture(block: suspend () -> Unit): Result<Unit> = try {
        block()
        lastError = null
        Result.success(Unit)
    } catch (timeout: TimeoutCancellationException) {
        lastError = androidShellFailureMessage(timeout)
        Result.failure(IllegalStateException(lastError))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        // Never include secrets (pairing code, nonce, key material) in the state or UI error.
        lastError = androidShellFailureMessage(failure)
        Result.failure(IllegalStateException(lastError, failure))
    }

    @Synchronized private fun writeUsbScript(connected: Boolean, expectedGeneration: Long = hub.generation): File {
        val file = scriptFile()
        val text = HelperHandoff.usbFile(connected, app.applicationInfo.sourceDir, app.packageName,
            Process.myUid(), ::ownCertificatePin, { hub.issueUsb(expectedGeneration) }, file.absolutePath)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(text)
        check(temporary.renameTo(file)) { "Could not write USB helper script" }
        return file
    }

    private fun scriptFile(): File {
        val directory = app.getExternalFilesDir(null) ?: error("App external-files directory is unavailable")
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, HelperHandoff.USB_SCRIPT)
    }

    @Suppress("DEPRECATION") private fun ownCertificatePin(): String {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return HelperHandoff.certPin(info.signingInfo?.apkContentsSigners?.map { it.toByteArray() })
            ?: error("Could not read app signing certificate")
    }

    private fun localAddress(host: String): String {
        require(host.isNotBlank() && host.length <= 253) { "Enter this phone's local wireless-debugging address" }
        val address = InetAddress.getByName(literalAdbHost(host))
        val local = address.isLoopbackAddress || NetworkInterface.getNetworkInterfaces().asSequence()
            .any { nic -> nic.inetAddresses.asSequence().any { it == address } }
        require(local) { "Android shell bridge only connects to this phone" }
        // TLS authentication trusts Android's pairing protocol; never connect to an arbitrary LAN peer.
        return address.hostAddress ?: error("Could not resolve local Android-debugging address")
    }
}
