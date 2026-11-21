package com.constx.onvyra.ui.pages.setting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Notification01
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import me.rerere.androidcontrol.AndroidControl
import me.rerere.androidcontrol.shell.AndroidShellMode
import me.rerere.androidcontrol.ui.AndroidControlNotificationService
import me.rerere.androidcontrol.ui.AndroidControlPairingService
import me.rerere.androidcontrol.ui.PairingSetupPhase
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Settings02
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.utils.plus

private enum class AndroidControlSetupMode(val label: String) {
    WIRELESS("无线调试模式"),
    ROOT("Root 模式"),
}

private val activePairingPhases = setOf(PairingSetupPhase.DISCOVERING, PairingSetupPhase.AWAITING_CODE,
    PairingSetupPhase.PAIRING, PairingSetupPhase.CONNECTING)

@Composable
fun SettingAndroidControlPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val control = remember { AndroidControl.get(context.applicationContext) }
    val pairingStatus by AndroidControlPairingService.status.collectAsState()

    var enabled by remember { mutableStateOf(control.enabled) }
    var confirmSensitive by remember { mutableStateOf(control.confirmSensitive) }
    var previewEnabled by remember { mutableStateOf(control.previewEnabled) }

    var connected by remember { mutableStateOf(false) }
    var activeMode by remember { mutableStateOf<AndroidShellMode?>(null) }
    var a11yConnected by remember { mutableStateOf(false) }
    var a11yAuthorized by remember { mutableStateOf(false) }
    var standbyProtected by remember { mutableStateOf(false) }
    var statusGeneration by remember { mutableStateOf(0) }
    val statusRead = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationsAllowed by remember {
        mutableStateOf(AndroidControlNotificationService.notificationsAllowed(context))
    }
    var feedback by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }
    var startingMode by remember { mutableStateOf<AndroidControlSetupMode?>(null) }
    var modeJob by remember { mutableStateOf<Job?>(null) }
    var modeGeneration by remember { mutableStateOf(0) }
    var permissionResumeName by rememberSaveable { mutableStateOf<String?>(null) }
    val pairingActive = pairingStatus.phase in activePairingPhases
    val a11yStatus = when {
        a11yConnected -> "已连接"
        a11yAuthorized -> "授权仍在，服务未连接"
        else -> "未开启"
    }
    val controlReady = enabled && connected && a11yAuthorized && a11yConnected && notificationGranted &&
        notificationsAllowed && Build.VERSION.SDK_INT >= 34

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
        notificationsAllowed = AndroidControlNotificationService.notificationsAllowed(context)
        if (!granted) permissionResumeName = null
        control.refreshStandbyProtection()
    }

    fun checkStatus() {
        if (statusRead[0]?.isActive == true) return
        val generation = ++statusGeneration
        statusRead[0] = scope.launch {
            // Service and system health probes cross Binder. A slow service must
            // not freeze this settings page's Main thread or its recovery controls.
            val (snapshot, bridge) = withContext(Dispatchers.IO) { control.state() to control.bridge.status() }
            if (generation != statusGeneration) return@launch
            enabled = control.enabled
            confirmSensitive = control.confirmSensitive
            previewEnabled = control.previewEnabled
            connected = (snapshot["shell_connected"] as? JsonPrimitive)?.booleanOrNull == true
            activeMode = bridge.activeMode.takeIf { connected }
            a11yAuthorized = (snapshot["accessibility_authorized"] as? JsonPrimitive)?.booleanOrNull == true
            a11yConnected = (snapshot["accessibility_connected"] as? JsonPrimitive)?.booleanOrNull == true
            standbyProtected = AndroidControlNotificationService.running
            notificationGranted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            notificationsAllowed = AndroidControlNotificationService.notificationsAllowed(context)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            checkStatus()
            delay(800)
        }
    }

    DisposableEffect(lifecycleOwner, control) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                control.refreshStandbyProtection()
                checkStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            control.refreshStandbyProtection()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSystemSettings(action: String, fallback: String? = null) {
        val intent = Intent(action).apply {
            if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else if (fallback != null) {
            context.startActivity(Intent(fallback))
        } else {
            toaster.show("系统未提供此设置入口，请在手机设置中打开")
        }
    }

    fun runAction(progress: String, successMsg: String, block: suspend () -> Any?) {
        if (isOperating) {
            feedback = "上一步正在处理中…"
            return
        }
        isOperating = true
        feedback = progress
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { block() }
                val obj = res as? JsonObject
                val err = (obj?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val msg = (obj?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                feedback = err?.take(200) ?: msg?.take(200) ?: successMsg
                toaster.show(feedback)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                feedback = e.message?.take(200)?.ifBlank { null } ?: "操作未完成，请检查无线调试连接"
                toaster.show(feedback)
            } finally {
                isOperating = false
                checkStatus()
            }
        }
    }

    fun stopMode() {
        modeGeneration++
        modeJob?.cancel()
        modeJob = null
        permissionResumeName = null
        startingMode = null
        // Turn off reconnect before detaching the helper. Keep the pairing identity
        // so starting wireless again does not require a new six-digit code.
        enabled = false
        control.enabled = false
        control.requestStop()
        control.bridge.stopPreservingPairing()
        context.stopService(Intent(context, AndroidControlPairingService::class.java))
        connected = false
        activeMode = null
        statusGeneration++
        statusRead[0]?.cancel()
        feedback = "已停止手机助手"
        toaster.show(feedback)
        checkStatus()
    }

    fun startMode(mode: AndroidControlSetupMode) {
        if (Build.VERSION.SDK_INT < 34) {
            toaster.show("手机操控需要 Android 14 或以上")
            return
        }
        if (!notificationGranted && Build.VERSION.SDK_INT >= 33) {
            permissionResumeName = mode.name
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!AndroidControlNotificationService.notificationsAllowed(context)) {
            notificationsAllowed = false
            permissionResumeName = mode.name
            toaster.show("请先允许手机操作通知")
            openSystemSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            return
        }
        val ticket = ++modeGeneration
        modeJob?.cancel()
        // Cancel in-flight startup immediately; a late Root/TLS handoff cannot
        // publish a connection after the user has changed modes.
        control.bridge.cancelPendingConnection()
        startingMode = mode
        modeJob = scope.launch {
            try {
                val pairingWasActive = AndroidControlPairingService.status.value.phase in activePairingPhases
                if (pairingWasActive) {
                    context.startService(Intent(context, AndroidControlPairingService::class.java)
                        .setAction(AndroidControlPairingService.ACTION_CANCEL))
                    check(withTimeoutOrNull(2_000) {
                        AndroidControlPairingService.status.first { it.phase !in activePairingPhases }
                    } != null) { "上一次配对仍在结束，请稍后重试" }
                }
                if (ticket != modeGeneration) return@launch
                enabled = true
                control.enabled = true
                if (mode == AndroidControlSetupMode.ROOT) {
                    withContext(Dispatchers.IO) { control.bridge.startWithRoot().getOrThrow() }
                    if (ticket != modeGeneration) return@launch
                    val actual = withContext(Dispatchers.IO) { control.bridge.status() }
                    check(actual.connected && actual.activeMode == AndroidShellMode.ROOT) { "Root 手机助手尚未连接" }
                    toaster.show("Root 模式授权成功")
                } else {
                    val actual = withContext(Dispatchers.IO) { control.bridge.status() }
                    val reconnected = actual.paired && withContext(Dispatchers.IO) {
                        control.bridge.connectSavedWireless().isSuccess
                    }
                    if (ticket != modeGeneration) return@launch
                    if (reconnected) {
                        toaster.show("无线调试模式启动成功")
                    } else {
                        // Only this explicit tap starts pairing or opens Settings.
                        // Settings owns the one-time pairing dialog; we do not
                        // synthesize taps on the user's primary display.
                        withContext(Dispatchers.IO) { control.bridge.stopPreservingPairing() }
                        AndroidControlPairingService.start(context)
                        openSystemSettings("android.settings.WIRELESS_DEBUGGING_SETTINGS",
                            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        toaster.show("请打开「使用配对码配对设备」的六位码弹窗")
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == modeGeneration) {
                    feedback = error.message?.take(200) ?: "启动未完成，请检查系统授权"
                    toaster.show(feedback)
                }
            } finally {
                if (ticket == modeGeneration) {
                    startingMode = null
                    checkStatus()
                }
            }
        }
    }

    fun toggleMode(mode: AndroidControlSetupMode) {
        val source = if (mode == AndroidControlSetupMode.ROOT) AndroidShellMode.ROOT else AndroidShellMode.WIRELESS
        if (startingMode == mode || enabled && connected && activeMode == source ||
            mode == AndroidControlSetupMode.WIRELESS && pairingActive) stopMode()
        else startMode(mode)
    }

    LaunchedEffect(notificationGranted, notificationsAllowed, permissionResumeName) {
        val resume = AndroidControlSetupMode.entries.firstOrNull { it.name == permissionResumeName }
        if (notificationGranted && notificationsAllowed && resume != null) {
            permissionResumeName = null
            startMode(resume)
        }
    }

    LaunchedEffect(pairingStatus.phase) {
        if (pairingStatus.phase == PairingSetupPhase.CONNECTED) checkStatus()
        if (pairingStatus.phase in setOf(PairingSetupPhase.FAILED, PairingSetupPhase.PAIRED_CONNECTION_FAILED)) {
            if (pairingStatus.message.isNotBlank()) toaster.show(pairingStatus.message)
            checkStatus()
        }
    }

    val modeStatus: @Composable (AndroidControlSetupMode) -> Unit = { mode ->
        val source = if (mode == AndroidControlSetupMode.ROOT) AndroidShellMode.ROOT else AndroidShellMode.WIRELESS
        val started = enabled && connected && activeMode == source
        val pending = startingMode == mode || mode == AndroidControlSetupMode.WIRELESS && pairingActive
        if (started || pending) {
            Text(if (started) "已启动" else if (pairingActive && mode == AndroidControlSetupMode.WIRELESS) "配对中…" else "启动中…",
                style = MaterialTheme.typography.bodyMedium,
                color = if (started) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("phone-control-mode-status-${mode.name.lowercase()}"))
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { SettingsHomeTopBar("手机操控") },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("phone-control-list"),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                    item("choose_mode") {
                        SettingsHomeGroup("选择操控模式") {
                            PhoneControlOutlineRow(HugeIcons.Settings02, AndroidControlSetupMode.WIRELESS.label,
                                Modifier.testTag("phone-control-mode-wireless"),
                                onClick = { toggleMode(AndroidControlSetupMode.WIRELESS) },
                                trailing = { modeStatus(AndroidControlSetupMode.WIRELESS) })
                            SettingsHomeDivider()
                            PhoneControlOutlineRow(HugeIcons.SecurityCheck, AndroidControlSetupMode.ROOT.label,
                                Modifier.testTag("phone-control-mode-root"),
                                onClick = { toggleMode(AndroidControlSetupMode.ROOT) },
                                trailing = { modeStatus(AndroidControlSetupMode.ROOT) })
                        }
                    }
                    item("permissions") {
                        SettingsHomeGroup("系统权限设置", Modifier.testTag("phone-control-permissions")) {
                            PhoneControlOutlineRow(HugeIcons.SmartPhone01, "打开系统无障碍设置", arrow = true,
                                onClick = { openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
                            SettingsHomeDivider()
                            PhoneControlOutlineRow(HugeIcons.Settings02, "打开后台与电池设置", arrow = true,
                                onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null))) }
                                        .onFailure { toaster.show("请在系统应用信息中打开 Onvyra 的电池与后台设置") }
                                })
                            SettingsHomeDivider()
                            PhoneControlOutlineRow(HugeIcons.Notification01, "手机操作通知与待机保护", arrow = true,
                                onClick = {
                                    if (!notificationGranted && Build.VERSION.SDK_INT >= 33) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                                            .onFailure { toaster.show("请在系统设置中允许 Onvyra 的手机操作通知") }
                                    }
                                })
                        }
                    }
                    item("status_header") {
                        SettingsHomeGroup("操控状态", Modifier.testTag("phone-control-status")) {
                            PhoneControlOutlineRow(if (controlReady) HugeIcons.CheckmarkCircle02 else HugeIcons.Alert02,
                                if (controlReady) "手机操控：可以操作" else "手机操控：尚未就绪",
                                onClick = { runAction("正在检查无障碍系统绑定…", "连接检查完成。") { control.awaitReadiness() } })
                            SettingsHomeDivider()
                            PhoneControlOutlineRow(if (connected) HugeIcons.CheckmarkCircle02 else HugeIcons.Alert02,
                                if (connected) "手机助手：已连接" else "手机助手：未连接")
                            SettingsHomeDivider()
                            PhoneControlOutlineRow(if (a11yConnected) HugeIcons.CheckmarkCircle02 else HugeIcons.Alert02,
                                "无障碍：$a11yStatus")
                            SettingsHomeDivider()
                            PhoneControlOutlineRow(HugeIcons.SecurityCheck,
                                if (standbyProtected) "待机保护：运行中" else "待机保护：未运行",
                                onClick = {
                                    control.refreshStandbyProtection(); checkStatus()
                                    toaster.show(if (standbyProtected) "待机保护已启动" else "已尝试启动，请检查通知、无障碍与后台设置")
                                })
                            SettingsHomeDivider()
                            OutlinedButton(onClick = { runAction("正在停止…", "已释放后台虚拟屏幕") { control.stopAll(revoke = false) } },
                                enabled = !isOperating,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                                    .testTag("phone-control-release"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                Text("释放操作")
                            }
                        }
                    }
                    item("switches") {
                        SettingsHomeGroup("授权设置") {
                            PhoneControlOutlineSwitch(HugeIcons.SmartPhone01, "允许 AI 操作这台手机", enabled,
                                Modifier.testTag("phone-control-switch-enabled")) { check ->
                                if (!check) stopMode() else { enabled = true; control.enabled = true }
                                if (check && !notificationGranted && Build.VERSION.SDK_INT >= 33)
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                checkStatus()
                            }
                            SettingsHomeDivider()
                            PhoneControlOutlineSwitch(HugeIcons.SecurityCheck, "敏感操作前二次确认", confirmSensitive,
                                Modifier.testTag("phone-control-switch-confirm")) { check ->
                                confirmSensitive = check; control.confirmSensitive = check
                            }
                            SettingsHomeDivider()
                            PhoneControlOutlineSwitch(HugeIcons.SmartPhone01, "显示操控预览小窗", previewEnabled,
                                Modifier.testTag("phone-control-switch-preview")) { check ->
                                previewEnabled = check; control.previewEnabled = check
                            }
                        }
                    }

            }
    }
}

@Composable
private fun PhoneControlOutlineRow(icon: ImageVector, title: String, modifier: Modifier = Modifier,
    arrow: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
        else if (arrow) Icon(HugeIcons.ArrowRight01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhoneControlOutlineSwitch(icon: ImageVector, title: String, checked: Boolean,
    modifier: Modifier = Modifier, onCheckedChange: (Boolean) -> Unit) {
    PhoneControlOutlineRow(icon, title, modifier, trailing = {
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = SettingsCompactAccentColor()))
    })
}
