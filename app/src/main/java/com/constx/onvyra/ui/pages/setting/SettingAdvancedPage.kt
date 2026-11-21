package com.constx.onvyra.ui.pages.setting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constx.onvyra.service.ChatKeepAliveService
import com.constx.onvyra.ui.pages.setting.settingsDetailSwitchItem as switchItem
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAdvancedPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val running by ChatKeepAliveService.running.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var pendingEnable by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun enable() {
        vm.updateSettings { it.copy(enableChatKeepAlive = true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (pendingEnable) {
            pendingEnable = false
            if (overlayGranted) enable() else toaster.show("未授予悬浮窗权限，保活未开启")
        }
    }

    fun requestOverlay(enableAfterGrant: Boolean) {
        pendingEnable = enableAfterGrant
        runCatching {
            overlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        }.onFailure {
            pendingEnable = false
            toaster.show("无法打开悬浮窗授权页面，请在系统应用设置中授权")
        }
    }

    LifecycleResumeEffect(Unit) {
        overlayGranted = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    LaunchedEffect(settings.enableChatKeepAlive, overlayGranted) {
        if (settings.init) return@LaunchedEffect
        if (settings.enableChatKeepAlive && overlayGranted) {
            if (!ChatKeepAliveService.start(context)) toaster.show("保活服务启动失败，请重试")
        } else {
            ChatKeepAliveService.stop(context)
        }
    }

    Scaffold(
        topBar = { SettingsHomeTopBar("悬浮窗功能") },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsDetailGroup(title = { Text("后台运行") }) {
                    switchItem(
                        checked = settings.enableChatKeepAlive,
                        enabled = !settings.init,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                pendingEnable = false
                                ChatKeepAliveService.stop(context)
                                vm.updateSettings { it.copy(enableChatKeepAlive = false) }
                            } else if (Settings.canDrawOverlays(context)) {
                                overlayGranted = true
                                enable()
                            } else {
                                requestOverlay(enableAfterGrant = true)
                            }
                        },
                        headlineContent = { Text("悬浮球保活") },
                    )
                    item(
                        onClick = {
                            if (!overlayGranted) requestOverlay(enableAfterGrant = settings.enableChatKeepAlive)
                            else if (settings.enableChatKeepAlive && !ChatKeepAliveService.start(context)) {
                                toaster.show("保活服务启动失败，请重试")
                            }
                        },
                        headlineContent = { Text("运行状态") },
                        supportingContent = {
                            Text(
                                when {
                                    !settings.enableChatKeepAlive -> "已关闭"
                                    !overlayGranted -> "悬浮窗权限已被关闭，点击重新授权"
                                    running -> "保活运行中，悬浮球已显示"
                                    else -> "服务未运行，点击重试"
                                }
                            )
                        },
                    )
                    item(
                        onClick = { requestOverlay(enableAfterGrant = false) },
                        headlineContent = { Text("悬浮窗权限") },
                        supportingContent = { Text(if (overlayGranted) "已授权，点击管理" else "需要允许显示在其他应用上层") },
                    )
                    item(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }.onFailure { toaster.show("无法打开通知设置") }
                        },
                        headlineContent = { Text("通知设置") },
                        supportingContent = { Text("通知中可直接关闭保活；建议允许通知以便随时查看和停止。") },
                    )
                }
            }
        }
    }
}
