package com.constx.onvyra.data.netdisk.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.constx.onvyra.R
import com.constx.onvyra.RouteActivity
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample

/** User-started, bounded dataSync service. Android timeout pauses downloads instead of losing checkpoints. */
class NetDiskDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: NetDiskDownloadManager
    private var lease = 0L
    private var lastStartId = 0
    private var stopping = false
    private var observing = false
    private var normalIdleStop = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeAcquiredAt = 0L

    override fun onCreate() {
        super.onCreate()
        manager = NetDiskDownloadManager.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "网盘下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "网盘文件下载进度与暂停控制"
            })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        stopping = false
        normalIdleStop = false
        lease = manager.serviceStarting()
        try {
            val notification = notification(manager.tasks.value)
            if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            manager.serviceStopped(lease, "系统未允许保持后台下载，请回到应用后继续。")
            stopSelf()
            return START_NOT_STICKY
        }
        manager.serviceStarted(lease)
        keepAwake()
        if (!observing) {
            observing = true
            scope.launch {
                combine(manager.tasks, manager.working) { tasks, working -> tasks to working }.sample(750).collect { (tasks, working) ->
                    if (!working && manager.retireIdleService(lease)) {
                        if (stopSelfResult(lastStartId)) {
                            stopping = true
                            normalIdleStop = true
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            releaseWakeLock()
                        }
                    } else {
                        keepAwake()
                        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(tasks)) }
                    }
                }
            }
        }
        if (intent?.action == ACTION_PAUSE_ALL) manager.pauseAll()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        manager.serviceStopped(lease, "后台下载已达到系统运行时限，请回到下载页面继续。")
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (normalIdleStop) manager.serviceDetached(lease)
        else manager.serviceStopped(lease, if (stopping) null else "后台下载已暂停，回到下载页面可继续。")
        releaseWakeLock()
        super.onDestroy()
    }

    private fun keepAwake() {
        if (SystemClock.elapsedRealtime() - wakeAcquiredAt < 9 * 60_000L && wakeLock?.isHeld == true) return
        val value = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Onvyra:NetDiskDownload").apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        runCatching { value.acquire(10 * 60_000L); wakeAcquiredAt = SystemClock.elapsedRealtime() }
    }

    private fun releaseWakeLock() { wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } } }

    private fun notification(tasks: List<NetDiskDownloadTask>): Notification {
        val active = tasks.filter { it.status in NetDiskDownloadManager.ACTIVE }
        val speed = active.sumOf { it.speedBytesPerSecond }.coerceAtLeast(0)
        val title = if (active.isEmpty()) "网盘下载正在收尾" else "网盘高速下载 · ${active.size} 个任务"
        val text = active.firstOrNull()?.let { "${it.fileName} · ${rate(speed)}" } ?: "正在保存任务记录"
        val open = Intent(this, RouteActivity::class.java).putExtra("open_netdisk_downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(this, 5050, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 5051,
            Intent(this, NetDiskDownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val known = active.isNotEmpty() && active.all { it.totalBytes > 0 }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0) }
        val done = active.sumOf { it.downloadedBytes.coerceAtLeast(0) }
        val progress = if (known && total > 0) ((done.toDouble() / total) * 10_000).toInt().coerceIn(0, 10_000) else 0
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_onvyra)
            .setContentTitle(title).setContentText(text).setContentIntent(content)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(10_000, progress, !known).addAction(0, "全部暂停", pause).build()
    }

    private fun rate(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MiB/s", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KiB/s", bytes / 1024.0)
        else -> "$bytes B/s"
    }

    companion object {
        private const val CHANNEL = "netdisk_downloads"
        private const val NOTIFICATION_ID = 5050
        private const val ACTION_PAUSE_ALL = "com.constx.onvyra.action.NETDISK_PAUSE_ALL"
        internal fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, NetDiskDownloadService::class.java))
            true
        } catch (_: Exception) { false }
    }
}
