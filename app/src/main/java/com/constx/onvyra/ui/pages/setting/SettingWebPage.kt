package com.constx.onvyra.ui.pages.setting

import android.content.Intent
import android.os.Build

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Play
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.SecurityCheck
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.InformationCircle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.stroke.Stop
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.service.WebServerService
import com.constx.onvyra.ui.components.ui.permission.PermissionLocalNetwork
import com.constx.onvyra.ui.components.ui.permission.PermissionManager
import com.constx.onvyra.ui.components.ui.permission.PermissionNotification
import com.constx.onvyra.ui.components.ui.permission.rememberPermissionState
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.utils.plus
import com.constx.onvyra.web.WebServerManager
import org.koin.compose.koinInject

@Composable
fun SettingWebPage() {
    val webServerManager: WebServerManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val serverState by webServerManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)
    var portText by remember(settings.webServerPort) {
        mutableStateOf(settings.webServerPort.toString())
    }
    var accessPasswordText by remember(settings.webServerAccessPassword) {
        mutableStateOf(settings.webServerAccessPassword)
    }
    var passwordVisible by remember {
        mutableStateOf(false)
    }

    val permissionState = rememberPermissionState(
        permissions = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionNotification)
            }
            if (Build.VERSION.SDK_INT >= 37 && !settings.webServerLocalhostOnly) {
                add(PermissionLocalNetwork)
            }
        },
    )
    PermissionManager(permissionState = permissionState)

    var pendingStart by remember { mutableStateOf(false) }

    fun startWebServer() {
        val intent = Intent(context, WebServerService::class.java).apply {
            action = WebServerService.ACTION_START
            putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
            putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
        }
        context.startForegroundService(intent)
        scope.launch {
            settingsStore.update { it.copy(webServerEnabled = true) }
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (pendingStart && permissionState.allPermissionsGranted) {
            pendingStart = false
            startWebServer()
        }
    }

    fun copyUrl(url: String) {
        clipboardManager.setText(AnnotatedString(url))
        toaster.show(copiedText)
    }

    Scaffold(
        topBar = { SettingsCompactTopBar(stringResource(R.string.setting_page_web_server)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (serverState.isLoading) return@ExtendedFloatingActionButton
                    if (!serverState.isRunning) {
                        if (permissionState.allPermissionsGranted) startWebServer()
                        else { pendingStart = true; permissionState.requestPermissions() }
                    } else {
                        context.startService(Intent(context, WebServerService::class.java).apply {
                            action = WebServerService.ACTION_STOP
                        })
                        scope.launch { settingsStore.update { it.copy(webServerEnabled = false) } }
                    }
                },
                icon = {
                    if (serverState.isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (serverState.isRunning) HugeIcons.Stop else HugeIcons.Play, null)
                },
                text = { Text(stringResource(if (serverState.isRunning) R.string.setting_page_web_server_stop
                    else R.string.setting_page_web_server_start)) },
                shape = CircleShape,
                containerColor = if (serverState.isRunning) MaterialTheme.colorScheme.error else Color(0xFF0963E7),
                contentColor = Color.White,
            )
        },
        containerColor = SettingsCompactPageColor(),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SettingsCompactSectionTitle("连接设置") }
            item {
                SettingsCompactGroup {
                    WebCompactRow(HugeIcons.ServerStack01, stringResource(R.string.setting_page_web_server_port)) {
                        WebCompactInput(
                            value = portText,
                            onValueChange = { value ->
                                portText = value.filter { it.isDigit() }
                                val port = portText.toIntOrNull()
                                if (port != null && port in 1024..65535) scope.launch {
                                    settingsStore.update { it.copy(webServerPort = port) }
                                }
                            },
                            modifier = Modifier.width(76.dp),
                            enabled = !serverState.isRunning,
                            isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    SettingsCompactDivider()
                    WebCompactRow(HugeIcons.ComputerTerminal01, stringResource(R.string.setting_page_web_server_localhost_only)) {
                        Switch(
                            checked = settings.webServerLocalhostOnly,
                            onCheckedChange = { checked -> scope.launch {
                                settingsStore.update { it.copy(webServerLocalhostOnly = checked) }
                            } },
                            enabled = !serverState.isRunning,
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF0963E7), checkedThumbColor = Color.White),
                        )
                    }
                    SettingsCompactDivider()
                    WebCompactRow(HugeIcons.SecurityCheck, stringResource(R.string.setting_page_web_server_jwt_enable)) {
                        Switch(
                            checked = settings.webServerJwtEnabled,
                            onCheckedChange = { checked -> scope.launch {
                                settingsStore.update { it.copy(webServerJwtEnabled = checked) }
                            } },
                            enabled = settings.webServerJwtEnabled || accessPasswordText.isNotBlank(),
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF0963E7), checkedThumbColor = Color.White),
                        )
                    }
                    SettingsCompactDivider()
                    WebCompactRow(HugeIcons.Key01, stringResource(R.string.setting_page_web_server_password)) {
                        WebCompactInput(
                            value = accessPasswordText,
                            onValueChange = { value ->
                                accessPasswordText = value
                                scope.launch { settingsStore.update { it.copy(
                                    webServerAccessPassword = value,
                                    webServerJwtEnabled = it.webServerJwtEnabled && value.isNotBlank(),
                                ) } }
                            },
                            modifier = Modifier.width(148.dp),
                            isError = settings.webServerJwtEnabled && accessPasswordText.isBlank(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingContent = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }, modifier = Modifier.size(32.dp)) {
                                    Icon(if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View, null, Modifier.size(20.dp))
                                }
                            },
                        )
                    }
                }
            }
            item { SettingsCompactSectionTitle("地址说明", Modifier.padding(top = 4.dp)) }
            item {
                SettingsCompactGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(HugeIcons.InformationCircle, null, Modifier.size(24.dp))
                            Text("访问方式", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(R.string.settings_compact_web_access),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (serverState.isRunning) {
                        val port = serverState.port
                        if (!serverState.localhostOnly) {
                            val lanUrl = "http://${serverState.address ?: "localhost"}:$port"
                            SettingsCompactDivider()
                            WebAddressRow(stringResource(R.string.setting_page_web_server_lan_address), lanUrl) { copyUrl(lanUrl) }
                            serverState.hostname?.let { hostname ->
                                val mdnsUrl = "http://$hostname:$port"
                                SettingsCompactDivider()
                                WebAddressRow(stringResource(R.string.setting_page_web_server_mdns_address), mdnsUrl) { copyUrl(mdnsUrl) }
                            }
                        }
                        val localUrl = "http://localhost:$port"
                        SettingsCompactDivider()
                        WebAddressRow(stringResource(R.string.setting_page_web_server_local_address), localUrl) { copyUrl(localUrl) }
                    }
                }
            }
            serverState.error?.let { error -> item {
                SettingsCompactGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.setting_page_web_server_error), color = MaterialTheme.colorScheme.error)
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } }
        }
    }
}

@Composable
private fun WebCompactRow(icon: ImageVector, title: String, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

@Composable
private fun WebCompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Surface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = SettingsCompactSoftColor(),
        border = if (isError) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null) {
        Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp, end = if (trailingContent == null) 12.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onValueChange, modifier = Modifier.weight(1f), enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                keyboardOptions = keyboardOptions, singleLine = true, visualTransformation = visualTransformation)
            trailingContent?.invoke()
        }
    }
}

@Composable
private fun WebAddressRow(title: String, url: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
