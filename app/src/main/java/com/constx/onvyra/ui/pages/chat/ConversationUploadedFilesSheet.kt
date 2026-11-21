package com.constx.onvyra.ui.pages.chat

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.files.FilesManager
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject
import java.io.File

private data class UploadedChatAttachment(val url: String, val name: String? = null, val mime: String? = null)

@Composable
internal fun ConversationUploadedFilesSheet(conversation: Conversation, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = koinInject<FilesManager>()
    val toaster = LocalToaster.current
    val uploads = remember(conversation.messageNodes) {
        conversation.messageNodes.flatMap { it.messages }.filter { it.role == MessageRole.USER }
            .flatMap { it.parts }.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Image -> UploadedChatAttachment(part.url)
                    is UIMessagePart.Document -> UploadedChatAttachment(part.url, part.fileName, part.mime)
                    is UIMessagePart.Audio -> UploadedChatAttachment(part.url)
                    is UIMessagePart.Video -> UploadedChatAttachment(part.url)
                    else -> null
                }
            }.filter { it.url.toUri().scheme in setOf("file", "content") }.distinctBy { it.url }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("已上传文件", style = MaterialTheme.typography.headlineSmall)
            if (uploads.isEmpty()) Text("此聊天尚未上传文件", Modifier.padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("conversation-uploaded-files")) {
                items(uploads, key = { it.url }) { upload ->
                    val url = upload.url
                    val uri = remember(url) { url.toUri() }
                    val name = remember(upload) { upload.name?.takeIf { it.isNotBlank() }
                        ?: manager.getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "附件" }
                    Surface(onClick = {
                        try {
                            val shared = if (uri.scheme == "file") FileProvider.getUriForFile(context,
                                "${context.packageName}.fileprovider", File(requireNotNull(uri.path))) else uri
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(shared,
                                upload.mime ?: manager.getFileMimeType(uri) ?: "application/octet-stream")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(intent, "打开附件"))
                        } catch (_: Exception) { toaster.show("文件不可用或没有可打开它的应用", type = ToastType.Error) }
                    }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Text(name, Modifier.padding(16.dp), maxLines = 2)
                    }
                }
            }
        }
    }
}
