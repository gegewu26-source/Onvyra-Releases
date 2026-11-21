package com.constx.onvyra.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.Folder
import com.constx.onvyra.data.repository.FolderRepository
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Projects reuse the app's existing assistant-specific conversation folders. */
@Composable
internal fun ConversationProjectSheet(
    conversation: Conversation,
    onMove: suspend (Uuid?) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val repository = koinInject<FolderRepository>()
    val folders by remember(conversation.assistantId) {
        repository.getFoldersOfAssistant(conversation.assistantId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun move(folder: Folder?) {
        if (busy) return
        busy = true
        scope.launch {
            try { onMove(folder?.id); onDismiss() }
            catch (_: Exception) { onError("未能移动聊天，请重试") }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("添加到项目", style = MaterialTheme.typography.headlineSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                if (conversation.folderId != null) item {
                    Surface(onClick = { move(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("移出当前项目", Modifier.padding(16.dp))
                    }
                }
                items(folders, key = { it.id.toString() }) { folder ->
                    Surface(onClick = { move(folder) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = if (folder.id == conversation.folderId) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surface) {
                        Text(folder.name, Modifier.padding(16.dp))
                    }
                }
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("新项目名称") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("new-chat-project-name"))
            Button(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val project = repository.createFolder(conversation.assistantId, name.trim().take(100))
                        onMove(project.id)
                        onDismiss()
                    } catch (_: Exception) { onError("未能创建项目，请重试") }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("创建并添加") }
        }
    }
}
