package me.rerere.androidcontrol.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.androidcontrol.AndroidControl
import me.rerere.androidcontrol.adb.AdbServiceTypes
import me.rerere.androidcontrol.adb.NsdAdbServiceFinder
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import me.rerere.androidcontrol.shell.ShellConnectionOwner
import java.util.UUID

/** A user-started setup session that keeps Settings' one-time pairing dialog in the foreground. */
class AndroidControlPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val control by lazy { AndroidControl.get(applicationContext) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var work: Job? = null
    private var connectionOwner = ShellConnectionOwner()
    private class Submission(val code: String, val observedPort: Int? = null) {
        override fun toString(): String = "PairingSubmission(redacted)"
    }
    private var submissions = Channel<Submission>(Channel.RENDEZVOUS)
    private var currentPairingPorts: Set<Int> = emptySet()
    private var pairingPortsObservedAt = 0L
    private val automaticAttempts = WirelessPairingAutoAttempts()
    private var generation = 0L
    private var pairedThisSession = false
    private var completed = false
    private var replyToken = ""
    private var replyAction: PendingIntent? = null
    private var cancelAction: PendingIntent? = null
    private val preferences by lazy { getSharedPreferences("android_control",MODE_PRIVATE) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if (key == "enabled" && !control.enabled) scope.launch { cancelSetup() }
    }

    override fun onCreate() {
        super.onCreate()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID,"无线调试配对",NotificationManager.IMPORTANCE_HIGH).apply {
            description = "在系统配对窗口上方输入配对码并连接本机助手"
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val token = intent.getStringExtra(EXTRA_REPLY_TOKEN)
            if (token != null && token != replyToken) {
                if (work?.isActive != true) stopSelf()
                return START_NOT_STICKY
            }
            cancelSetup()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 34 || !control.enabled ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !notifications.areNotificationsEnabled() || notifications.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            mutableStatus.value = PairingSetupStatus(PairingSetupPhase.FAILED,"请先开启手机操控，并允许配对通知；后台操控需要 Android 14 以上。")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> startSetup()
            ACTION_SUBMIT -> {
                if (work?.isActive != true) { stopSelf(); return START_NOT_STICKY }
                if (intent.getStringExtra(EXTRA_REPLY_TOKEN) != replyToken ||
                    mutableStatus.value.phase != PairingSetupPhase.AWAITING_CODE) return START_NOT_STICKY
                val code = pairingCode(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE))
                if (code == null) publish(PairingSetupPhase.AWAITING_CODE,"请输入系统弹窗中当前显示的 6 位数字；不要关闭弹窗。",input=true)
                else submitCode(Submission(code))
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSetup() {
        if (work?.isActive == true) return
        generation++
        val ticket = generation
        work?.cancel()
        control.bridge.cancelPendingConnection(connectionOwner)
        connectionOwner = ShellConnectionOwner()
        val owner = connectionOwner
        submissions.close()
        submissions = Channel(Channel.RENDEZVOUS)
        pairedThisSession = false
        currentPairingPorts = emptySet()
        automaticAttempts.start(ticket)
        completed = false
        invalidateActions()
        replyToken = UUID.randomUUID().toString()
        val initial = notification("正在寻找本机配对窗口","请打开系统无线调试中的「使用配对码配对设备」，保持弹窗打开。")
        startForeground(NOTIFICATION_ID,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        publish(PairingSetupPhase.DISCOVERING,"请打开系统「使用配对码配对设备」弹窗。无障碍已连接时会自动识别，也可在通知中输入。")
        work = scope.launch {
            try {
                withTimeout(5 * 60_000L) {
                    coroutineScope {
                        val automaticReader = launch { watchAutomaticCode(ticket) }
                        try { setup(ticket, owner) } finally {
                            automaticReader.cancel()
                            if (ticket == generation) { automaticAttempts.close(); currentPairingPorts = emptySet() }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException && ticket == generation) finishSetup(
                    if (pairedThisSession) PairingSetupPhase.PAIRED_CONNECTION_FAILED else PairingSetupPhase.FAILED,
                    if (pairedThisSession) "已配对，但连接已超时。请回应用重试连接。" else "配对等待已超时。请重新开始并使用新的配对码。")
                else throw cancelled
            } catch (_: Exception) {
                if (ticket == generation) finishSetup(
                    if (pairedThisSession) PairingSetupPhase.PAIRED_CONNECTION_FAILED else PairingSetupPhase.FAILED,
                    if (pairedThisSession) "已配对，但连接准备失败。请回应用重试连接。" else "配对准备失败；请检查无线调试与通知后重新开始。")
            }
        }
    }

    private suspend fun watchAutomaticCode(ticket: Long) = coroutineScope {
        val stability = WirelessPairingCodeStability()
        val finder = NsdAdbServiceFinder(this@AndroidControlPairingService)
        var refresh: Job? = null
        while (ticket == generation) {
            delay(100L)
            if (!control.enabled || mutableStatus.value.phase != PairingSetupPhase.AWAITING_CODE) {
                stability.observe(null, SystemClock.elapsedRealtime())
                continue
            }
            if (SystemClock.elapsedRealtime() - pairingPortsObservedAt >= 2_500L && refresh?.isActive != true) {
                // mDNS can be slow on OEM builds. Refresh beside the short dialog
                // reads, never suspend recognition for a 1.5–2 s discovery sweep.
                refresh = launch {
                    val discovered = finder.findPorts(AdbServiceTypes.PAIRING, 1_500L)
                    if (ticket == generation && control.enabled && mutableStatus.value.phase == PairingSetupPhase.AWAITING_CODE) {
                        currentPairingPorts = discovered.toSet()
                        pairingPortsObservedAt = SystemClock.elapsedRealtime()
                    }
                }
            }
            val ports = currentPairingPorts
            if (ports.isEmpty() || SystemClock.elapsedRealtime() - pairingPortsObservedAt !in 0..5_000L) {
                stability.observe(null, SystemClock.elapsedRealtime())
                continue
            }
            // No screenshot, general UI-tree cache, model call, or Settings text in logs.
            val reading = withContext(Dispatchers.Default) {
                runCatching { AndroidControlAccessibilityService.instance?.readWirelessPairingDialog(ports) }.getOrNull()
            }
            if (!stability.observe(reading, SystemClock.elapsedRealtime())) continue
            if (reading == null) continue
            if (ticket != generation || !control.enabled || mutableStatus.value.phase != PairingSetupPhase.AWAITING_CODE ||
                !automaticAttempts.canSubmit(ticket, reading.code)) continue
            submitCode(Submission(reading.code, reading.port))
        }
    }

    /** Both notification input and automatic reads enter the same single-consumer transition. */
    private fun submitCode(submission: Submission): Boolean {
        if (work?.isActive != true || !control.enabled || mutableStatus.value.phase != PairingSetupPhase.AWAITING_CODE) return false
        val ticket = generation
        // Transition first: a resumed receiver may advance directly to CONNECTING.
        // Publishing PAIRING after trySend would overwrite that newer status.
        publish(PairingSetupPhase.PAIRING, if (submission.observedPort != null)
            "已识别系统配对码，正在与本机配对。" else "正在与本机配对，请保持系统配对弹窗打开。")
        if (submissions.trySend(submission).isSuccess) {
            automaticAttempts.submitted(ticket, submission.code)
            return true
        }
        // A rendezvous has not started receiving yet. Do not consume this code:
        // the next short-lived observation may submit it once the receiver waits.
        publish(PairingSetupPhase.AWAITING_CODE,"请保持系统配对弹窗打开，正在等待识别；也可在通知中输入。",input=true)
        return false
    }

    private suspend fun setup(ticket: Long, owner: ShellConnectionOwner) = coroutineScope {
        val finder = NsdAdbServiceFinder(this@AndroidControlPairingService)
        // Once a pairing window is present, discover the connection endpoint in
        // parallel with the user's/code-reader wait, rather than only after TLS pairing.
        var prefetchedConnection: Deferred<Int?>? = null
        var retryMessage: String? = null
        while (ticket == generation) {
            currentCoroutineContext().ensureActive()
            check(control.enabled) { "control_disabled" }
            currentPairingPorts = emptySet()
            val ports = listOfNotNull(finder.findFirstPort(AdbServiceTypes.PAIRING,3_000))
            if (ports.isEmpty()) {
                currentPairingPorts = emptySet()
                publish(PairingSetupPhase.DISCOVERING,"尚未找到本机配对窗口。请打开「使用配对码配对设备」并保持弹窗打开。")
                delay(500)
                continue
            }
            currentPairingPorts = ports.toSet()
            pairingPortsObservedAt = SystemClock.elapsedRealtime()
            if (prefetchedConnection == null) prefetchedConnection = async {
                NsdAdbServiceFinder(this@AndroidControlPairingService).findFirstPort(AdbServiceTypes.CONNECT,3_000)
            }
            publish(PairingSetupPhase.AWAITING_CODE,retryMessage ?: "保持系统配对弹窗打开。正在等待自动识别，也可在此通知输入 6 位码。",input=true)
            val submission = submissions.receive()
            publish(PairingSetupPhase.PAIRING,"正在配对本机，请保持系统弹窗打开。")
            // The automatic reader already matched a current trusted Settings
            // window to a recent local endpoint. Re-read that exact dialog and
            // start TLS now instead of paying another full discovery delay.
            val currentReading = submission.observedPort?.let {
                withContext(Dispatchers.Default) {
                    runCatching { AndroidControlAccessibilityService.instance?.readWirelessPairingDialog(currentPairingPorts) }.getOrNull()
                }
            }
            val verifiedPort = verifiedAutomaticPairingPort(submission.code,submission.observedPort,currentReading,
                currentPairingPorts,SystemClock.elapsedRealtime()-pairingPortsObservedAt)
            // Notification-entered codes still require fresh discovery. A changed
            // automatic dialog is never paired with a port from another dialog.
            val fresh = if (verifiedPort != null) listOf(verifiedPort) else if (submission.observedPort == null) {
                finder.findPorts(AdbServiceTypes.PAIRING,2_000)
            } else emptyList()
            if (fresh.isEmpty()) {
                publish(PairingSetupPhase.DISCOVERING,"配对窗口已关闭或过期。请重新打开系统配对弹窗。")
                continue
            }
            var failure: String? = null
            var paired = false
            val selectedPorts = submission.observedPort?.let { observed -> fresh.filter { it == observed } } ?: fresh
            for (port in selectedPorts) {
                currentCoroutineContext().ensureActive()
                val result = control.bridge.pair("127.0.0.1",port,submission.code,owner)
                if (result.isSuccess) { paired = true; break }
                failure = result.exceptionOrNull()?.message
            }
            if (!paired) {
                retryMessage = pairingFailure(failure)
                // A failed attempt needs a new explicit submission, never an automatic code retry loop.
                continue
            }
            pairedThisSession = true
            publish(PairingSetupPhase.CONNECTING,"已配对，正在发现本机连接端口并连接手机助手。")
            var connectionFailure: String? = null
            val earlyPort = prefetchedConnection?.await()
            if (earlyPort != null) {
                val result = control.bridge.connect("127.0.0.1",earlyPort,owner)
                if (result.isSuccess) {
                    finishSetup(PairingSetupPhase.CONNECTED,"已配对并连接手机助手。")
                    return@coroutineScope
                }
                connectionFailure = result.exceptionOrNull()?.message
            }
            repeat(3) {
                currentCoroutineContext().ensureActive()
                // A failed fast endpoint may be an old local mDNS record. Try
                // every fresh local candidate on recovery instead of selecting
                // that same first stale record for all three retries.
                val connectionPorts = finder.findPorts(AdbServiceTypes.CONNECT,3_000)
                for (port in connectionPorts) {
                    val result = control.bridge.connect("127.0.0.1",port,owner)
                    if (result.isSuccess) {
                        finishSetup(PairingSetupPhase.CONNECTED,"已配对并连接手机助手。")
                        return@coroutineScope
                    }
                    connectionFailure = result.exceptionOrNull()?.message
                }
            }
            finishSetup(PairingSetupPhase.PAIRED_CONNECTION_FAILED,"已配对，但连接助手失败。请返回应用，点击无线调试模式重试连接。" +
                connectionFailure?.take(150)?.let { "\n$it" }.orEmpty())
            return@coroutineScope
        }
    }

    private fun pairingFailure(error: String?): String = when {
        error?.contains("WRONG_CODE") == true -> "配对码不正确或已过期。保持新配对弹窗打开，在通知中重新输入。"
        error?.contains("TLS") == true -> "配对 TLS 握手失败。请确认本机配对弹窗仍打开，并使用当前配对码重试。"
        error?.contains("CONNECT_FAILED") == true -> "无法连接当前配对端口。请重新打开系统配对弹窗后重试。"
        else -> "配对未完成，请确认配对弹窗仍打开并重新输入。" + error?.take(150)?.let { "\n$it" }.orEmpty()
    }

    private fun publish(phase: PairingSetupPhase, message: String, input: Boolean = false) {
        mutableStatus.value = PairingSetupStatus(phase,message,pairedThisSession)
        notifications.notify(NOTIFICATION_ID,notification(if (input) "在通知中输入配对码" else "无线调试配对",message,input=input))
    }

    private fun finishSetup(phase: PairingSetupPhase, message: String) {
        completed = true
        automaticAttempts.close()
        currentPairingPorts = emptySet()
        invalidateActions()
        mutableStatus.value = PairingSetupStatus(phase,message,pairedThisSession)
        stopForeground(STOP_FOREGROUND_DETACH)
        notifications.notify(NOTIFICATION_ID,notification("无线调试配对结果",message,ongoing=false))
        stopSelf()
    }

    private fun cancelSetup() {
        completed = true
        generation++
        automaticAttempts.close()
        currentPairingPorts = emptySet()
        work?.cancel()
        control.bridge.cancelPendingConnection(connectionOwner)
        invalidateActions()
        submissions.close()
        mutableStatus.value = PairingSetupStatus(PairingSetupPhase.CANCELLED,"已取消配对连接；已完成的系统配对不会被撤销。",pairedThisSession)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(title: String, message: String, input: Boolean = false, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this,OPEN_REQUEST,controlSettingsIntent(this),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title).setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true).setOngoing(ongoing).setContentIntent(open)
        if (input) {
            val submit = replyAction ?: actionPendingIntent(this,ACTION_SUBMIT,replyToken).also { replyAction = it }
            builder.addAction(Notification.Action.Builder(null,"输入 6 位配对码",submit)
                .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("系统弹窗的 6 位配对码").build())
                .setAllowGeneratedReplies(false).build())
        }
        if (ongoing) {
            val cancel = cancelAction ?: actionPendingIntent(this,ACTION_CANCEL,replyToken).also { cancelAction = it }
            builder.addAction(Notification.Action.Builder(null,"取消配对连接",cancel).build())
        }
        return builder.build()
    }

    private fun invalidateActions() {
        replyAction?.cancel()
        cancelAction?.cancel()
        replyAction = null
        cancelAction = null
    }

    override fun onDestroy() {
        generation++
        automaticAttempts.close()
        currentPairingPorts = emptySet()
        scope.cancel()
        invalidateActions()
        if (!completed) control.bridge.cancelPendingConnection(connectionOwner)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        submissions.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "me.rerere.androidcontrol.pairing.START"
        const val ACTION_SUBMIT = "me.rerere.androidcontrol.pairing.SUBMIT"
        const val ACTION_CANCEL = "me.rerere.androidcontrol.pairing.CANCEL"
        const val KEY_CODE = "pairing_code"
        private const val EXTRA_REPLY_TOKEN = "pairing_reply_token"
        const val NOTIFICATION_ID = 6802
        private const val CHANNEL_ID = "android_control_pairing"
        private const val OPEN_REQUEST = 811
        private const val SUBMIT_REQUEST = 812
        private const val CANCEL_REQUEST = 813
        private val mutableStatus = MutableStateFlow(PairingSetupStatus())
        val status = mutableStatus.asStateFlow()
        fun start(context: Context) { context.startForegroundService(Intent(context,AndroidControlPairingService::class.java).setAction(ACTION_START)) }

        internal fun actionPendingIntent(context: Context, action: String, token: String): PendingIntent {
            require(action == ACTION_SUBMIT || action == ACTION_CANCEL)
            val intent = Intent(context,AndroidControlPairingService::class.java).setAction(action)
                // Extras do not participate in PendingIntent identity; the URI isolates each setup round.
                .setData(Uri.Builder().scheme("onvyra-android-control").authority("pairing").appendPath(token).build())
                .putExtra(EXTRA_REPLY_TOKEN,token)
            return PendingIntent.getService(context,if (action == ACTION_SUBMIT) SUBMIT_REQUEST else CANCEL_REQUEST,intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (action == ACTION_SUBMIT) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))
        }
    }
}
