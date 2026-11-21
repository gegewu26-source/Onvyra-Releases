package com.constx.onvyra.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask

/** One calling chat owns generation and polling, with no studio/repository dependency. */
internal suspend fun runChatImageTask(
    create: suspend () -> MediaGenerationTask,
    query: suspend (String) -> MediaGenerationTask,
    save: suspend (MediaGenerationOutput) -> UIMessagePart.Image,
    pollMillis: Long = 3_000L,
): List<UIMessagePart> = withTimeout(10 * 60_000L) {
    var task = create()
    val originalId = task.id
    while (!task.isTerminal) {
        delay(pollMillis)
        task = query(originalId)
        check(task.id == originalId) { "生图任务返回了不一致的标识。" }
    }
    check(task.status == MediaGenerationStatus.SUCCEEDED && task.outputs.isNotEmpty()) {
        "生图任务未成功，请检查所选模型及其接口能力。"
    }
    val usageMetadata = task.usage?.takeIf {
        it.totalTokens != null || it.inputTokens != null || it.outputTokens != null
    }?.let { usage ->
        buildJsonObject {
            put("request_id", originalId)
            usage.inputTokens?.let { put("prompt_tokens", it) }
            usage.outputTokens?.let { put("completion_tokens", it) }
            usage.cachedTokens?.let { put("cached_tokens", it) }
            put("total_tokens", maxOf(usage.totalTokens ?: 0,
                (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)))
        }
    }
    val imageSource = task.metadata["onvyra_image_source"] as? JsonObject
    val images = task.outputs.mapIndexed { index, output ->
        check(output.mimeType.startsWith("image/")) { "生图接口未返回图片。" }
        save(output).let { image ->
            // One HTTP/task usage belongs to the request, not to every returned image.
            if (imageSource == null && (usageMetadata == null || index != 0)) image else image.copy(
                metadata = JsonObject((image.metadata ?: JsonObject(emptyMap())).toMutableMap().apply {
                    if (usageMetadata != null && index == 0) put("onvyra_token_usage", usageMetadata)
                    if (imageSource != null) put("onvyra_image_source", imageSource)
                })
            )
        }
    }
    listOf(UIMessagePart.Text(buildJsonObject {
        put("status", "succeeded")
        put("output_count", images.size)
        put("displayed_in_chat", true)
        images.firstOrNull()?.metadata?.get("onvyra_animation")?.let { put("animation", it) }
        if (imageSource != null) put("image_source", imageSource)
    }.toString())) + images
}
