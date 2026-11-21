package com.constx.onvyra.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.repository.ConversationRepository
import com.constx.onvyra.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun ArchivedConversationsSheet(
    assistantId: Uuid,
    onOpen: (Conversation) -> Unit,
    onRestore: suspend (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val repo = koinInject<ConversationRepository>()
    val archived by remember(assistantId) { repo.getArchivedConversations(assistantId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("已归档聊天", style = MaterialTheme.typography.headlineSmall)
            if (archived.isEmpty()) Text("暂无已归档聊天", Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("archived-conversations")) {
                items(archived, key = { it.id.toString() }) { chat ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { onDismiss(); onOpen(chat) }, modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (chat.isLocked) "已锁定聊天" else chat.title.ifBlank { "新聊天" }, maxLines = 2)
                                if (chat.isLocked) Text("打开时验证身份", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(onClick = { scope.launch {
                            try { onRestore(chat.id) }
                            catch (_: Exception) { toaster.show("恢复失败，请重试", type = ToastType.Error) }
                        } }) { Text("恢复") }
                    }
                }
            }
        }
    }
}
