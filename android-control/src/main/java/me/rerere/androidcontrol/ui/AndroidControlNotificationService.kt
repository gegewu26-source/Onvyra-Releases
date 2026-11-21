package me.rerere.androidcontrol.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import me.rerere.androidcontrol.AndroidControl
import me.rerere.androidcontrol.ControlProtectionMode
import me.rerere.androidcontrol.controlProtectionMode

/** User-visible protection for explicitly authorized control, including idle accessibility standby. */
class AndroidControlNotificationService : Service() {
    private var foreground = false
    private var notificationState: Pair<Boolean,Boolean>? = null
    private val notificationChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            update(AndroidControl.get(applicationContext))
        }
    }
    private val grantsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val control = AndroidControl.get(applicationContext)
            if (!control.accessibilityAuthorizationGranted()) control.requestStop()
            update(control)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "手机操作", NotificationManager.IMPORTANCE_LOW).apply {
                description = "手机操控待机保护、运行状态和停止按钮"
                setShowBadge(false)
            },
        )
        // Fulfil queued foreground starts before evaluating STOP/disable. Promotion
        // can be denied by Android/OEM policy; never crash accessibility's Main.
        promote(AndroidControl.get(applicationContext))
        runCatching { contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, grantsObserver,
        ) }
        runCatching { contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, grantsObserver,
        ) }
        val filter = IntentFilter(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED).apply {
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(notificationChanges, filter, RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(notificationChanges, filter)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val control = AndroidControl.get(applicationContext)
        val promoted = promote(control)
        when (intent?.action) {
            ACTION_STOP -> control.requestStop()
            ACTION_DISABLE -> control.enabled = false
        }
        if (!promoted) return START_NOT_STICKY
        return if (update(control)) START_STICKY else START_NOT_STICKY
    }

    private fun update(control: AndroidControl): Boolean {
        if (mode(control) == ControlProtectionMode.OFF) {
            if (control.hasActiveSession()) control.requestStop()
            removeProtection()
            stopSelf()
            return false
        }
        return promote(control)
    }

    private fun mode(control: AndroidControl) = controlProtectionMode(
        control.enabled, control.accessibilityAuthorizationGranted(), notificationsAllowed(this), control.hasActiveSession(),
    )

    private fun promote(control: AndroidControl): Boolean = runCatching {
        val active = control.hasActiveSession()
        val connected = control.accessibilityConnected()
        // Reconcile is invoked both before and after lifecycle commands. Reposting
        // identical notifications can hit Android's per-package enqueue limit and
        // cause the later, important active -> standby update to be dropped.
        if (foreground && notificationState == (active to connected)) return@runCatching true
        val open = PendingIntent.getActivity(this, 801, controlSettingsIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 802, Intent(this, AndroidControlNotificationService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disable = PendingIntent.getService(this, 803, Intent(this, AndroidControlNotificationService::class.java)
            .setAction(ACTION_DISABLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(if (active) "手机操作正在运行" else "手机操控待机保护")
            .setContentText(when {
                !connected -> "无障碍未连接；点击查看修复步骤，任务不会自动执行"
                active -> "点击查看状态或手动接管；停止任务后保留待机"
                else -> "无障碍已连接，暂无任务；关闭操控可结束待机保护"
            })
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply { if (active) addAction(Notification.Action.Builder(null, "停止任务", stop).build()) }
            .addAction(Notification.Action.Builder(null, "关闭操控", disable).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
        notificationState = active to connected
        running = true
        lastStartError = null
        true
    }.getOrElse {
        lastStartError = "foreground_protection_unavailable"
        removeProtection()
        stopSelf()
        control.onProtectionUnavailable()
        false
    }

    private fun removeProtection() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        notificationState = null
        running = false
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(grantsObserver) }
        runCatching { unregisterReceiver(notificationChanges) }
        removeProtection()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the UI task is not the user's "关闭操控" action. Keep the
        // already authorized, visible standby service; never toggle the system
        // accessibility grant or replay any unfinished screen operation here.
        update(AndroidControl.get(applicationContext))
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "me.rerere.androidcontrol.action.STOP"
        const val ACTION_DISABLE = "me.rerere.androidcontrol.action.DISABLE"
        private const val CHANNEL_ID = "android_control_session"
        internal const val NOTIFICATION_ID = 6801
        @Volatile var running = false
            private set
        @Volatile var lastStartError: String? = null
            private set

        fun notificationsAllowed(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        /** Called only for lifecycle/user events. No timer, wake lock or restart loop. */
        internal fun reconcile(context: Context, control: AndroidControl, allowStart: Boolean): Boolean {
            val desired = controlProtectionMode(control.enabled, control.accessibilityAuthorizationGranted(),
                notificationsAllowed(context), control.hasActiveSession())
            if (desired == ControlProtectionMode.OFF) {
                context.stopService(Intent(context, AndroidControlNotificationService::class.java))
                lastStartError = null
                return false
            }
            if (!allowStart && !running) return false
            return runCatching {
                context.startForegroundService(Intent(context, AndroidControlNotificationService::class.java))
                true
            }.getOrElse {
                lastStartError = "foreground_protection_start_restricted"
                false
            }
        }
    }
}
