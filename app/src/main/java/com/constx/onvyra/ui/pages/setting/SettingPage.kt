package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import com.constx.onvyra.R
import com.constx.onvyra.Screen
import com.constx.onvyra.data.datastore.isNotConfigured
import com.constx.onvyra.data.files.FilesManager
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.hooks.rememberColorMode
import com.constx.onvyra.ui.theme.ColorMode
import com.constx.onvyra.ui.pages.netdisk.NetDiskIcon
import com.constx.onvyra.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val androidControlConnected by produceState(false, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = try {
                    withContext(Dispatchers.IO) {
                        me.rerere.androidcontrol.AndroidControl.get(context.applicationContext).bridge.status().connected
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                delay(1000)
            }
        }
    }
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val pageColor = MaterialTheme.colorScheme.background

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(48.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                shape = CircleShape,
                            ),
                    ) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = pageColor,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = pageColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("settings-page-list"),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (settings.isNotConfigured()) {
                item("providerWarning") {
                    ProviderConfigWarningCard(
                        onClick = { navController.navigate(Screen.SettingProvider) }
                    )
                }
            }

            item("generalSettings") {
                var colorMode by rememberColorMode()
                var showColorModes by remember { mutableStateOf(false) }
                SettingOutlineGroup(title = stringResource(R.string.setting_page_group_general)) {
                    Box {
                        SettingOutlineRow(
                            icon = colorMode.icon(),
                            title = stringResource(R.string.setting_page_color_mode),
                            summary = colorMode.label(),
                            trailingIcon = HugeIcons.ArrowDown01,
                            onClick = { showColorModes = true },
                        )
                        // Use the value/arrow end of the row as the popup anchor. A full-row
                        // anchor makes DropdownMenu align to the far left of the settings card.
                        Box(Modifier.align(Alignment.BottomEnd).size(1.dp).testTag("settings-color-anchor")) {
                        DropdownMenu(
                            expanded = showColorModes,
                            onDismissRequest = { showColorModes = false },
                            modifier = Modifier.testTag("settings-color-menu"),
                        ) {
                            ColorMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label()) },
                                    leadingIcon = { Icon(mode.icon(), contentDescription = null) },
                                    onClick = {
                                        showColorModes = false
                                        if (colorMode != mode) {
                                            colorMode = mode
                                            navController.navigate(Screen.Setting) {
                                                popUpTo(Screen.Setting) { inclusive = true }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        }
                    }
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.Settings03,
                        title = stringResource(R.string.setting_page_preferences),
                        onClick = { navController.navigate(Screen.SettingPreferences) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.SmartPhone01,
                        title = stringResource(R.string.setting_page_phone_control),
                        summary = stringResource(if (androidControlConnected) R.string.setting_page_phone_control_connected else R.string.setting_page_phone_control_disconnected),
                        onClick = { navController.navigate(Screen.SettingAndroidControl) },
                    )
                }
            }

            item("extensions") {
                SettingOutlineGroup(title = stringResource(R.string.setting_page_extensions)) {
                    SettingOutlineRow(
                        icon = HugeIcons.Package,
                        title = stringResource(R.string.setting_page_extensions),
                        onClick = { navController.navigate(Screen.Extensions) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.Package,
                        title = "网盘解析",
                        onClick = { navController.navigate(Screen.NetDisk) },
                        leadingContent = { NetDiskIcon(Modifier.size(24.dp)) },
                    )
                }
            }

            item("modelServices") {
                SettingOutlineGroup(title = stringResource(R.string.setting_page_model_and_services)) {
                    SettingOutlineRow(
                        icon = HugeIcons.AiMagic,
                        title = stringResource(R.string.setting_page_default_model),
                        onClick = { navController.navigate(Screen.SettingModels) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.Brain02,
                        title = stringResource(R.string.setting_page_providers),
                        onClick = { navController.navigate(Screen.SettingProvider) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.GlobalSearch,
                        title = stringResource(R.string.setting_page_search_service),
                        onClick = { navController.navigate(Screen.SettingSearch) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.Megaphone01,
                        title = stringResource(R.string.setting_page_tts_service),
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.McpServer,
                        title = stringResource(R.string.setting_page_mcp),
                        onClick = { navController.navigate(Screen.SettingMcp) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.ServerStack01,
                        title = stringResource(R.string.setting_page_web_server),
                        onClick = { navController.navigate(Screen.SettingWeb) },
                    )
                }
            }

            item("dataSettings") {
                val storageState by produceState(-1 to 0L) {
                    value = filesManager.countChatFiles()
                }
                SettingOutlineGroup(title = stringResource(R.string.setting_page_group_data)) {
                    SettingOutlineRow(
                        icon = HugeIcons.Database02,
                        title = stringResource(R.string.setting_page_data_backup),
                        onClick = { navController.navigate(Screen.Backup) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.ImageUpload,
                        title = stringResource(R.string.setting_page_chat_storage),
                        summary = if (storageState.first == -1) {
                            stringResource(R.string.calculating)
                        } else {
                            stringResource(
                                R.string.setting_page_chat_storage_desc,
                                storageState.first,
                                storageState.second / 1024 / 1024.0,
                            )
                        },
                        onClick = { navController.navigate(Screen.SettingFiles) },
                    )
                }
            }

            item("aboutSettings") {
                SettingOutlineGroup(title = stringResource(R.string.setting_page_about)) {
                    SettingOutlineRow(
                        icon = HugeIcons.Clapping01,
                        title = stringResource(R.string.setting_page_about_desc),
                        onClick = { navController.navigate(Screen.SettingAbout) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.Clapping01,
                        title = stringResource(R.string.setting_page_donate),
                        onClick = { navController.navigate(Screen.SettingDonate) },
                    )
                    SettingOutlineDivider()
                    SettingOutlineRow(
                        icon = HugeIcons.Bookshelf01,
                        title = stringResource(R.string.setting_page_request_logs),
                        onClick = { navController.navigate(Screen.Log) },
                    )
                }
            }
        }
    }
}

// These compact outlined groups belong only to the settings landing page.
@Composable
private fun SettingOutlineGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingOutlineRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    trailingIcon: ImageVector = HugeIcons.ArrowRight01,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingContent != null) leadingContent() else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = title,
            modifier = if (summary == null) Modifier.weight(1f) else Modifier,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary != null) {
            Text(
                text = summary,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingOutlineDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

private fun ColorMode.icon(): ImageVector = when (this) {
    ColorMode.SYSTEM -> HugeIcons.SmartPhone01
    ColorMode.LIGHT -> HugeIcons.Sun01
    ColorMode.DARK -> HugeIcons.Moon02
}

@Composable
private fun ColorMode.label(): String = stringResource(
    when (this) {
        ColorMode.SYSTEM -> R.string.setting_page_color_mode_system
        ColorMode.LIGHT -> R.string.setting_page_color_mode_light
        ColorMode.DARK -> R.string.setting_page_color_mode_dark
    }
)

@Composable
private fun ProviderConfigWarningCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.Alert01, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_page_config_api_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.setting_page_config_api_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}
