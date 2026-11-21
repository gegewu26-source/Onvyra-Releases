package com.constx.onvyra.service

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.constx.onvyra.R
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.datastore.SettingsStore
import org.koin.android.ext.android.inject

/** User-enabled foreground lifetime for the persistent, visible floating chat shortcut. */
class ChatKeepAliveService : Service() {
    companion object {
        private const val TAG = "ChatKeepAliveService"
        private const val CHANNEL_ID = "chat_keep_alive"
        private const val NOTIFICATION_ID = 2010
        private const val ACTION_STOP = "com.constx.onvyra.action.STOP_CHAT_KEEP_ALIVE"
        private val runningState = MutableStateFlow(false)
        val running = runningState.asStateFlow()

        /** Call from a visible activity; Android restricts new foreground services in the background. */
        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ChatKeepAliveService::class.java))
                true
            }.onFailure { Log.e(TAG, "Unable to start floating shortcut", it) }.getOrDefault(false)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatKeepAliveService::class.java))
        }
    }

    private val settingsStore: SettingsStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var observer: Job? = null
    private var ball: ChatFloatingBall? = null
    private var isForeground = false
    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val overlayPermissionObserver = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName) {
            handler.post {
                if (!Settings.canDrawOverlays(this)) stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "悬浮球与后台保活", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示悬浮球保活状态，可随时停止"
                setShowBadge(false)
                enableVibration(false)
            }
        )
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionObserver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Persist the notification action so the next activity resume does not turn it back on.
            ball?.dismiss()
            ball = null
            runningState.value = false
            observer?.cancel()
            scope.launch {
                try {
                    settingsStore.update { it.copy(enableChatKeepAlive = false) }
                } finally {
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                settingsStore.settingsFlow.filter { !it.init }.map { it.enableChatKeepAlive }
                    .distinctUntilChanged().collect { enabled ->
                        if (!enabled || !Settings.canDrawOverlays(this@ChatKeepAliveService)) {
                            stopSelf()
                        } else if (ball == null) {
                            runCatching {
                                ChatFloatingBall(
                                    context = this@ChatKeepAliveService,
                                    onClick = ::openApp,
                                    onWindowFailure = { stopSelf() },
                                ).also {
                                    ball = it
                                    it.show()
                                    runningState.value = true
                                }
                            }.onFailure {
                                Log.e(TAG, "Unable to show floating shortcut", it)
                                stopSelf()
                            }
                        }
                    }
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ball?.onConfigurationChanged()
    }

    override fun onDestroy() {
        runningState.value = false
        handler.removeCallbacksAndMessages(null)
        appOps.stopWatchingMode(overlayPermissionObserver)
        ball?.dismiss()
        ball = null
        scope.cancel()
        if (isForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = runCatching {
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, appIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            Intent(this, ChatKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_onvyra)
            .setContentTitle("${getString(R.string.app_name)} 保活已开启")
            .setContentText("静置 3 秒缩入边缘；点击露出的边展开，再点返回应用")
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "关闭保活", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
        true
    }.onFailure { Log.e(TAG, "Unable to enter foreground", it) }.getOrDefault(false)

    private fun appIntent() = Intent(this, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    private fun openApp() {
        runCatching { startActivity(appIntent()) }.onFailure { Log.e(TAG, "Unable to reopen app", it) }
    }
}
