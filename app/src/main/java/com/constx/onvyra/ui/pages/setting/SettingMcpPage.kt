package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Refresh01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.McpServer
import com.constx.onvyra.R
import com.constx.onvyra.Screen
import com.constx.onvyra.data.ai.mcp.McpCommonOptions
import com.constx.onvyra.data.ai.mcp.McpManager
import com.constx.onvyra.data.ai.mcp.McpServerConfig
import com.constx.onvyra.data.ai.mcp.McpStatus
import com.constx.onvyra.ui.components.ui.ItemAction
import com.constx.onvyra.ui.components.ui.ItemActionMenu
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.theme.extendColors
import com.constx.onvyra.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val navController = LocalNavController.current
    var showImportDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SettingsCompactTopBar(
                title = stringResource(R.string.setting_mcp_page_title),
                actions = {
                    IconButton(
                        onClick = {
                            showImportDialog = true
                        }
                    ) {
                        Icon(HugeIcons.FileImport, null)
                    }
                    IconButton(
                        onClick = {
                            // 名称为空的配置不会连接，先加进列表，具体配置到详情页里填
                            val config = McpServerConfig.StreamableHTTPServer()
                            vm.updateSettings { latest ->
                                latest.copy(mcpServers = latest.mcpServers + config)
                            }
                            navController.navigate(Screen.SettingMcpDetail(serverId = config.id.toString()))
                        }
                    ) {
                        Icon(HugeIcons.Add01, null)
                    }
                },
            )
        },
        containerColor = SettingsCompactPageColor()
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        val layoutDirection = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                )
            ) {
                item { SettingsCompactSectionTitle("已添加的服务器") }
                if (mcpConfigs.isNotEmpty()) item {
                    SettingsCompactGroup {
                        mcpConfigs.forEachIndexed { index, mcpConfig ->
                            if (index > 0) SettingsCompactDivider()
                            McpServerItem(
                            item = mcpConfig,
                            onEdit = {
                                navController.navigate(Screen.SettingMcpDetail(serverId = mcpConfig.id.toString()))
                            },
                            onDelete = {
                                vm.updateSettings { latest ->
                                    latest.copy(
                                        mcpServers = latest.mcpServers.filter { it.id != mcpConfig.id }
                                    )
                                }
                            },
                            onToggleEnabled = {
                                vm.updateSettings { latest -> latest.copy(mcpServers = latest.mcpServers.map { config ->
                                    if (config.id == mcpConfig.id) config.clone(commonOptions = config.commonOptions.copy(enable = !config.commonOptions.enable)) else config
                                }) }
                            },
                            onMove = { direction ->
                                vm.updateSettings { latest ->
                                    val list = latest.mcpServers.toMutableList()
                                    val current = list.indexOfFirst { it.id == mcpConfig.id }
                                    val destination = current + direction
                                    if (current >= 0 && destination in list.indices) {
                                        val moved = list.removeAt(current)
                                        list.add(destination, moved)
                                    }
                                    latest.copy(mcpServers = list)
                                }
                            },
                            canMoveUp = index > 0,
                            canMoveDown = index < mcpConfigs.lastIndex,
                        )
                        }
                    }
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            onImport = { newConfigs ->
                val existingIds = mcpConfigs.map { it.commonOptions.name }.toSet()
                val toAdd = newConfigs.filter { it.commonOptions.name.isNotBlank() && it.commonOptions.name !in existingIds }
                vm.updateSettings { it.copy(mcpServers = it.mcpServers + toAdd) }
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun McpServerItem(
    item: McpServerConfig,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onToggleEnabled: () -> Unit,
    onMove: (Int) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    val mcpManager = koinInject<McpManager>()
    val scope = rememberCoroutineScope()
    val status by mcpManager.getStatus(item).collectAsStateWithLifecycle(McpStatus.Idle)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf<McpStatus.Error?>(null) }

    errorDetail?.let { error ->
        val context = LocalContext.current
        val fullText = error.detail ?: error.message
        AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { Text(item.commonOptions.name.ifBlank { "MCP" }) },
            text = {
                SelectionContainer {
                    Text(
                        text = fullText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.writeClipboardText(fullText)
                        errorDetail = null
                    }
                ) {
                    Text(stringResource(R.string.copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { errorDetail = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    Box(modifier = modifier.clickable { onEdit(item) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (status == McpStatus.Connecting || status is McpStatus.Reconnecting || status == McpStatus.Authorizing)
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(HugeIcons.McpServer, null, Modifier.size(26.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item.commonOptions.name.ifBlank { "MCP" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val dotColor = when {
                        !item.commonOptions.enable -> MaterialTheme.colorScheme.outline
                        status == McpStatus.Connected -> MaterialTheme.extendColors.green6
                        status == McpStatus.Connecting || status is McpStatus.Reconnecting || status == McpStatus.Authorizing -> Color(0xFFD69420)
                        else -> MaterialTheme.extendColors.red6
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .drawWithContent {
                                drawCircle(
                                    color = dotColor
                                )
                            }
                    )
                }

                Text(
                    text = when (item) {
                        is McpServerConfig.SseTransportServer -> "SSE"
                        is McpServerConfig.StreamableHTTPServer -> "Streamable HTTP"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status is McpStatus.Error) {
                    val error = status as McpStatus.Error
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { errorDetail = error },
                    )
                }
                if (status == McpStatus.NeedsAuthorization) {
                    val context = LocalContext.current
                    Text(
                        text = "需要 OAuth 授权",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { mcpManager.startAuthorization(item, context) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text("OAuth 授权")
                    }
                }
                if (status == McpStatus.Authorizing) {
                    Text(
                        text = "正在授权，请在浏览器中完成…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(
                        onClick = { mcpManager.cancelAuthorization(item) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text("取消授权")
                    }
                }
            }

            ItemActionMenu(
                actions = listOf(
                    ItemAction(text = stringResource(R.string.edit), icon = HugeIcons.McpServer, onClick = { onEdit(item) }),
                    ItemAction(text = if (item.commonOptions.enable) "停用" else "启用",
                        icon = if (item.commonOptions.enable) HugeIcons.ViewOff else HugeIcons.View, onClick = onToggleEnabled),
                    ItemAction(text = "重新连接", icon = HugeIcons.Refresh01, enabled = item.commonOptions.enable,
                        onClick = { scope.launch { mcpManager.addClient(item) } }),
                    ItemAction(text = "上移", icon = HugeIcons.ArrowUp01, enabled = canMoveUp, onClick = { onMove(-1) }),
                    ItemAction(text = "下移", icon = HugeIcons.ArrowDown01, enabled = canMoveDown, onClick = { onMove(1) }),
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            )
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, item.commonOptions.name))
    }
}

private fun parseMcpServersFromJson(json: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(json).jsonObject
    val mcpServers = root["mcpServers"]?.jsonObject ?: return emptyList()
    return mcpServers.entries.mapNotNull { (name, element) ->
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "streamable_http"
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val headers = obj["headers"]?.jsonObject?.entries?.map { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyList()
        val commonOptions = McpCommonOptions(name = name, headers = headers)
        when (type) {
            "sse" -> McpServerConfig.SseTransportServer(commonOptions = commonOptions, url = url)
            else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
        }
    }
}

@Composable
private fun McpImportModal(
    onDismiss: () -> Unit,
    onImport: (List<McpServerConfig>) -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val noValidConfigMsg = stringResource(R.string.setting_mcp_page_import_no_valid_config)
    val parseErrorMsg = stringResource(R.string.setting_mcp_page_import_parse_error)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.setting_mcp_page_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setting_mcp_page_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("{ \"mcpServers\": { ... } }") },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        try {
                            val configs = parseMcpServersFromJson(jsonText.trim())
                            if (configs.isEmpty()) {
                                errorMessage = noValidConfigMsg
                            } else {
                                onImport(configs)
                            }
                        } catch (e: Exception) {
                            errorMessage = parseErrorMsg.format(e.message ?: "")
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_mcp_page_import_confirm))
                }
            }
        }
    }
}
