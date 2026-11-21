package com.constx.onvyra.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Icon1stBracket
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Refresh01
import kotlinx.coroutines.launch
import com.constx.onvyra.R
import com.constx.onvyra.data.ai.mcp.McpManager
import com.constx.onvyra.data.ai.mcp.McpServerConfig
import com.constx.onvyra.data.ai.mcp.McpStatus
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.ui.components.ui.Tag
import com.constx.onvyra.ui.components.ui.TagType
import me.rerere.ui.components.ToggleSurface
import org.koin.compose.koinInject

@Composable
fun McpPickerButton(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    mcpManager: McpManager,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit
) {
    var showMcpPicker by remember { mutableStateOf(false) }
    val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val loading = status.values.any { it == McpStatus.Connecting }
    val enabledServers = servers.fastFilter {
        it.commonOptions.enable && assistant.mcpServers.contains(it.id)
    }
    ToggleSurface(
        modifier = modifier,
        checked = assistant.mcpServers.isNotEmpty(),
        onClick = {
            showMcpPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    BadgedBox(
                        badge = {
                            if (enabledServers.isNotEmpty()) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(text = enabledServers.size.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.McpServer,
                            contentDescription = stringResource(R.string.mcp_picker_title),
                        )
                    }

                }
            }
        }
    }
    if (showMcpPicker) {
        ModalBottomSheet(
            onDismissRequest = { showMcpPicker = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            Column(
                modifier = Modifier.Companion
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.mcp_picker_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    McpRefreshButton(servers, mcpManager)
                }
                AnimatedVisibility(loading) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        LinearWavyProgressIndicator()
                        Text(
                            text = stringResource(id = R.string.mcp_picker_syncing),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                McpPicker(
                    assistant = assistant,
                    servers = servers,
                    onUpdateAssistant = {
                        onUpdateAssistant(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
fun McpPickerListItem(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    mcpManager: McpManager,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val loading = status.values.any { it == McpStatus.Connecting }
    val enabledServers = servers.fastFilter {
        it.commonOptions.enable && assistant.mcpServers.contains(it.id)
    }

    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        modifier = modifier,
        leadingContent = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(
                    imageVector = HugeIcons.McpServer,
                    contentDescription = null,
                )
            }
        },
        trailingContent = {
            if (enabledServers.isNotEmpty()) {
                CountBadge(count = enabledServers.size)
            }
        },
    ) {
        Text(stringResource(R.string.mcp_picker_title))
    }
}

// MCP 选择页，作为子页面嵌在加号 sheet 里
@Composable
internal fun McpPickerPage(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    mcpManager: McpManager,
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
    onBack: () -> Unit,
) {
    val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val loading = status.values.any { it == McpStatus.Connecting }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.mcp_picker_title),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
            actions = { McpRefreshButton(servers, mcpManager) },
        )
        AnimatedVisibility(
            visible = loading,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                LinearWavyProgressIndicator()
                Text(
                    text = stringResource(id = R.string.mcp_picker_syncing),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        McpPickerContent(
            assistant = assistant,
            servers = servers,
            onUpdateAssistant = onUpdateAssistant,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        )
    }
}

@Composable
private fun McpRefreshButton(servers: List<McpServerConfig>, mcpManager: McpManager) {
    val scope = rememberCoroutineScope()
    val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    // A server waiting for a retry must remain manually refreshable.
    val busy = refreshing || status.values.any { it == McpStatus.Connecting }
    IconButton(
        enabled = !busy && servers.any { it.commonOptions.enable },
        onClick = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try {
                        mcpManager.syncAll()
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(HugeIcons.Refresh01, contentDescription = "刷新 MCP 服务器和工具")
        }
    }
}

@Composable
fun McpPicker(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onUpdateAssistant: (Assistant) -> Unit
) {
    McpPickerContent(
        assistant = assistant,
        servers = servers,
        modifier = modifier,
        contentPadding = contentPadding,
        colors = colors,
        onUpdateAssistant = { update -> onUpdateAssistant(update(assistant)) },
    )
}

@Composable
private fun McpPickerContent(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    onUpdateAssistant: ((Assistant) -> Assistant) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val enabledServers = servers.fastFilter { it.commonOptions.enable }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(enabledServers, key = { _, server -> server.id }) { index, server ->
            val status by mcpManager.getStatus(server).collectAsStateWithLifecycle(McpStatus.Idle)
            val checked = server.id in assistant.mcpServers
            SegmentedListItem(
                checked = checked,
                onCheckedChange = { checked ->
                    onUpdateAssistant { latest ->
                        val selected = if (checked) latest.mcpServers + server.id else latest.mcpServers - server.id
                        latest.copy(mcpServers = selected.filterTo(mutableSetOf()) { id -> servers.any { it.id == id } })
                    }
                },
                shapes = ListItemDefaults.segmentedShapes(index = index, count = enabledServers.size),
                colors = colors,
                leadingContent = {
                    when (status) {
                        McpStatus.Idle -> Icon(HugeIcons.Icon1stBracket, null)
                        McpStatus.Connected -> Icon(HugeIcons.McpServer, null)
                        McpStatus.Connecting,
                        is McpStatus.Reconnecting,
                        McpStatus.Authorizing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

                        is McpStatus.Error,
                        McpStatus.NeedsAuthorization -> Icon(
                            imageVector = HugeIcons.Alert01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                supportingContent = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = when (val s = status) {
                                is McpStatus.Idle -> "Idle"
                                is McpStatus.Connecting -> "Connecting"
                                is McpStatus.Connected -> "Connected"
                                is McpStatus.Reconnecting -> "Reconnecting (${s.attempt}/${s.maxAttempts})"
                                is McpStatus.Error -> "Error: ${s.message}"
                                is McpStatus.NeedsAuthorization -> "Needs authorization"
                                is McpStatus.Authorizing -> "Authorizing"
                            },
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (status == McpStatus.Connected) {
                            val tools = server.commonOptions.tools
                            val enabledTools = tools.fastFilter { it.enable }
                            Tag(
                                type = TagType.INFO
                            ) {
                                Text("${enabledTools.size}/${tools.size} tools")
                            }
                        }
                    }
                },
                trailingContent = {
                    // 整行都能点击切换，开关只用来显示状态
                    Switch(checked = checked, onCheckedChange = null)
                },
            ) {
                Text(server.commonOptions.name)
            }
        }
    }
}
