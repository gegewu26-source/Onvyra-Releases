package com.constx.onvyra.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constx.onvyra.data.model.MediaCreationStatus
import com.constx.onvyra.Screen
import com.constx.onvyra.data.repository.MediaCreationRepository
import com.constx.onvyra.ui.components.richtext.ZoomableAsyncImage
import com.constx.onvyra.ui.components.ui.VideoPlayerDialog
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

/** Observe the persisted task instead of exposing generated binary media to a text-only chat model. */
@Composable
internal fun GeneratedMediaPreview(payload: String) {
    val recordId = remember(payload) {
        runCatching {
            val value = JsonInstant.parseToJsonElement(payload) as? JsonObject ?: return@runCatching null
            if ((value["type"] as? JsonPrimitive)?.contentOrNull != "generated_media") return@runCatching null
            (value["record_id"] as? JsonPrimitive)?.contentOrNull?.let(Uuid::parse)
        }.getOrNull()
    } ?: return
    val repository: MediaCreationRepository = koinInject()
    val navController = LocalNavController.current
    val recordFlow = remember(repository, recordId) { repository.observeRecord(recordId) }
    val record by recordFlow.collectAsStateWithLifecycle(initialValue = null)
    var playing by remember(recordId) { mutableStateOf<File?>(null) }
    val current = record ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${current.providerName} · ${current.modelId}", style = MaterialTheme.typography.labelMedium)
            when (current.status) {
                MediaCreationStatus.SUCCEEDED -> current.outputs.forEach { output ->
                    val file = repository.resolve(output.path)
                    if (output.isVideo) {
                        TextButton(onClick = { playing = file }) { Text("播放生成的视频") }
                    } else {
                        ZoomableAsyncImage(file.toUri().toString(), "生成的图片",
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp))
                    }
                }
                MediaCreationStatus.FAILED -> Text(current.error ?: "生成失败", color = MaterialTheme.colorScheme.error)
                MediaCreationStatus.CANCELLED -> Text("生成已取消")
                else -> {
                    Text(if (current.status == MediaCreationStatus.DOWNLOADING) "正在保存生成结果…" else "正在生成…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (current.kind == me.rerere.mediagen.model.MediaKind.VIDEO) {
                TextButton(onClick = { navController.navigate(Screen.MediaCreation(current.sessionId.toString())) }) {
                    Text("查看创作记录")
                }
            }
        }
    }
    playing?.let { file -> VideoPlayerDialog(file = file, onDismissRequest = { playing = null }) }
}
