package com.constx.onvyra.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Share04
import com.constx.onvyra.data.model.TemporaryChatMode

enum class ChatHeaderAction { SHARE, PIN, LOCK, PROJECT, FILES, SEARCH, SHORTCUT, ARCHIVE, DELETE }

/** Only navigation controls live here; the parent owns and validates each real action. */
@Composable
fun ChatHeaderActions(
    hasMessages: Boolean,
    temporaryMode: TemporaryChatMode,
    conversationTitle: String,
    isPinned: Boolean,
    isLocked: Boolean,
    isArchived: Boolean,
    onTemporaryToggle: () -> Unit,
    onNewChat: () -> Unit,
    onAction: (ChatHeaderAction) -> Unit,
) {
    if (!hasMessages) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            IconButton(onClick = onTemporaryToggle,
                modifier = Modifier.size(44.dp).testTag("chat-temporary-toggle")) {
                Icon(if (temporaryMode == TemporaryChatMode.OFF) temporaryChatIcon else temporaryChatActiveIcon,
                    contentDescription = if (temporaryMode == TemporaryChatMode.OFF) "开启临时聊天" else "退出临时聊天",
                    modifier = Modifier.size(25.dp))
            }
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNewChat, modifier = Modifier.size(44.dp).testTag("chat-new-conversation")) {
                Icon(newConversationIcon, contentDescription = "新会话", modifier = Modifier.size(25.dp))
            }
            Box {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp).testTag("chat-more-actions")) {
                    Icon(HugeIcons.MoreVertical, contentDescription = "聊天选项", modifier = Modifier.size(25.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    shape = RoundedCornerShape(24.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.widthIn(min = 242.dp, max = 290.dp).heightIn(max = 560.dp)
                        .testTag("chat-actions-menu")) {
                    Text(conversationTitle.ifBlank { "新会话" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                    ChatHeaderAction.entries.forEach { action ->
                        // A temporary chat cannot gain durable history metadata or a launcher shortcut.
                        val available = temporaryMode == TemporaryChatMode.OFF || action in setOf(
                            ChatHeaderAction.SHARE, ChatHeaderAction.FILES, ChatHeaderAction.SEARCH, ChatHeaderAction.DELETE)
                        if (available) {
                            val destructive = action == ChatHeaderAction.DELETE
                            DropdownMenuItem(
                                text = { Text(action.label(isPinned, isLocked, isArchived)) },
                                leadingIcon = { Icon(action.icon(isPinned), contentDescription = null, modifier = Modifier.size(22.dp)) },
                                trailingIcon = if (action == ChatHeaderAction.PROJECT) {
                                    { Icon(chevronRightIcon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null,
                                colors = if (destructive) MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error)
                                else MenuDefaults.itemColors(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("chat-action-${action.name}"),
                                onClick = { expanded = false; onAction(action) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ChatHeaderAction.label(pinned: Boolean, locked: Boolean, archived: Boolean): String = when (this) {
    ChatHeaderAction.SHARE -> "分享"
    ChatHeaderAction.PIN -> if (pinned) "取消置顶" else "置顶"
    ChatHeaderAction.LOCK -> if (locked) "解除聊天锁定" else "锁定聊天"
    ChatHeaderAction.PROJECT -> "添加到项目"
    ChatHeaderAction.FILES -> "已上传文件"
    ChatHeaderAction.SEARCH -> "在聊天中查找"
    ChatHeaderAction.SHORTCUT -> "添加到主屏幕"
    ChatHeaderAction.ARCHIVE -> if (archived) "取消归档" else "归档"
    ChatHeaderAction.DELETE -> "删除"
}

private fun ChatHeaderAction.icon(pinned: Boolean): ImageVector = when (this) {
    ChatHeaderAction.SHARE -> HugeIcons.Share04
    ChatHeaderAction.PIN -> if (pinned) HugeIcons.PinOff else HugeIcons.Pin
    ChatHeaderAction.LOCK -> lockChatIcon
    ChatHeaderAction.PROJECT -> HugeIcons.FolderAdd
    ChatHeaderAction.FILES -> attachmentIcon
    ChatHeaderAction.SEARCH -> HugeIcons.Search01
    ChatHeaderAction.SHORTCUT -> homeIcon
    ChatHeaderAction.ARCHIVE -> archiveIcon
    ChatHeaderAction.DELETE -> HugeIcons.Delete01
}

@Composable
fun TemporaryChatWelcome(mode: TemporaryChatMode, onSettings: () -> Unit) {
    if (mode == TemporaryChatMode.OFF) return
    var showInfo by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(28.dp).testTag("temporary-chat-welcome"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("临时聊天", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("此聊天不会显示在历史记录中。", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showInfo = true }, modifier = Modifier.testTag("temporary-chat-learn-more")) {
                Text("了解更多", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onSettings, modifier = Modifier.testTag("temporary-chat-settings")) {
                Text(mode.personalizationLabel(), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(HugeIcons.ArrowDown01, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
    if (showInfo) {
        AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("关于临时聊天") },
            text = { Text("退出临时聊天或新建会话后，本次内容不会保留在本机聊天历史中，也不会写入记忆。个性化模式可读取已有记忆和扩展设置；不个性化模式不使用这些内容。发送给模型服务商的数据仍由该服务商处理。") },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } })
    }
}

@Composable
fun TemporaryChatSettingsSheet(
    mode: TemporaryChatMode,
    onSelect: (TemporaryChatMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember(mode) { mutableStateOf(if (mode == TemporaryChatMode.OFF) TemporaryChatMode.NON_PERSONALIZED else mode) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag("temporary-chat-settings-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("临时聊天设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TemporaryChatMode.PERSONALIZED, TemporaryChatMode.NON_PERSONALIZED).forEach { option ->
                    Row(Modifier.fillMaxWidth().selectable(selected = pending == option, role = Role.RadioButton,
                        onClick = { pending = option }).testTag("temporary-chat-option-${option.name}")
                        .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = pending == option, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(option.personalizationLabel(), style = MaterialTheme.typography.titleMedium)
                            Text(if (option == TemporaryChatMode.PERSONALIZED) "此聊天可使用记忆、插件和自定义指令"
                                else "此聊天不会使用记忆、插件和自定义指令",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(onClick = { onSelect(pending); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                shape = CircleShape, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("temporary-chat-settings-done")) {
                Text("完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun TemporaryChatMode.personalizationLabel(): String = if (this == TemporaryChatMode.PERSONALIZED) "个性化" else "不个性化"

private fun outlinedHeaderIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

private val temporaryChatIcon = outlinedHeaderIcon("TemporaryChat") { temporaryBubbleOutline() }
private val temporaryChatActiveIcon = outlinedHeaderIcon("TemporaryChatActive") {
    temporaryBubbleOutline()
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(8.1f, 11.8f); lineTo(10.8f, 14.4f); lineTo(15.9f, 8.9f)
    }
}
private fun ImageVector.Builder.temporaryBubbleOutline() {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5.5f, 5.7f); curveTo(9f, 1.8f, 15.2f, 1.9f, 18.6f, 5.7f)
        moveTo(21f, 9.2f); curveTo(22.3f, 13.8f, 19.9f, 18.7f, 15.4f, 20.4f)
        moveTo(10.4f, 21f); curveTo(8.6f, 20.8f, 7.3f, 20.1f, 6.1f, 19.3f)
        lineTo(2.5f, 20.4f); lineTo(3.6f, 16.8f)
        curveTo(1.7f, 14.1f, 1.6f, 10.6f, 3f, 8.1f)
    }
}
private val newConversationIcon = outlinedHeaderIcon("NewConversation") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(10f, 4f); lineTo(6f, 4f); curveTo(4.3f, 4f, 3f, 5.3f, 3f, 7f)
        lineTo(3f, 18f); curveTo(3f, 19.7f, 4.3f, 21f, 6f, 21f)
        lineTo(17f, 21f); curveTo(18.7f, 21f, 20f, 19.7f, 20f, 18f); lineTo(20f, 14f)
        moveTo(10f, 14f); lineTo(11f, 9.8f); lineTo(18.8f, 2.1f)
        curveTo(20.3f, .6f, 23.4f, 3.6f, 21.9f, 5.2f)
        lineTo(14.2f, 13f); close()
    }
}
private val lockChatIcon = outlinedHeaderIcon("LockChat") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(7f, 10f); lineTo(7f, 7f); curveTo(7f, .5f, 17f, .5f, 17f, 7f); lineTo(17f, 10f)
        moveTo(5f, 10f); lineTo(19f, 10f); lineTo(20f, 11f); lineTo(20f, 20f)
        lineTo(19f, 21f); lineTo(5f, 21f); lineTo(4f, 20f); lineTo(4f, 11f); close()
        moveTo(12f, 14f); lineTo(12f, 17f)
    }
}
private val attachmentIcon = outlinedHeaderIcon("ChatAttachments") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
        moveTo(7f, 8f); lineTo(7f, 16f); curveTo(7f, 23f, 19f, 23f, 19f, 16f)
        lineTo(19f, 6f); curveTo(19f, -1f, 10f, -1f, 10f, 6f)
        lineTo(10f, 16f); curveTo(10f, 19f, 15.5f, 19f, 15.5f, 16f); lineTo(15.5f, 6f)
    }
}
private val homeIcon = outlinedHeaderIcon("ChatHomeShortcut") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(2f, 10f); lineTo(12f, 2.3f); lineTo(22f, 10f)
        moveTo(5f, 8f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 14f)
        lineTo(14f, 14f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 8f)
    }
}
private val archiveIcon = outlinedHeaderIcon("ArchiveChat") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 8f); lineTo(3f, 8f); close()
        moveTo(5f, 8f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 8f)
        moveTo(9f, 12f); lineTo(15f, 12f)
    }
}
private val chevronRightIcon = outlinedHeaderIcon("ChatProjectChevron") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    }
}
