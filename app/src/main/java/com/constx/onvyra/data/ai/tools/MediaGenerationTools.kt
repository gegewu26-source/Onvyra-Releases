package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.MediaGenerationParameter
import me.rerere.mediagen.provider.capabilities
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.MediaCreationParams
import com.constx.onvyra.data.model.MediaCreationRecord
import com.constx.onvyra.data.model.mediaCreationRequiredDefaults
import com.constx.onvyra.data.model.withRequired
import com.constx.onvyra.data.files.GifAnimationException
import kotlin.uuid.Uuid

internal const val GENERATE_MEDIA_TOOL = "generate_media"
internal const val MEDIA_STATUS_TOOL = "media_generation_status"

private class ImageFallbackAlreadyAttempted : IllegalStateException()

internal fun Settings.selectedMediaModel(kind: MediaKind): Pair<MediaGenerationProviderSetting, MediaGenerationModel>? {
    val selectedId = if (kind == MediaKind.IMAGE) chatImageModelId else chatVideoModelId
    if (selectedId == null) return null
    if (providers.any { provider -> provider.models.any { it.id == selectedId } }) {
        return existingProviderMediaSelection(selectedId, kind)?.let { it.mediaProvider to it.mediaModel }
    }
    return mediaGenerationProviders.firstNotNullOfOrNull { provider ->
        provider.models.find { it.id == selectedId && it.kind == kind && kind in provider.supportedKinds }
            ?.let { provider to it }
    }
}

/** Native chat output is preferred; the configured image endpoint remains available as a fallback. */
internal fun createMediaGenerationTools(
    settings: Settings,
    submit: suspend (MediaGenerationProviderSetting, MediaGenerationModel, String, MediaCreationParams) -> MediaCreationRecord,
    getRecord: suspend (Uuid) -> MediaCreationRecord?,
    currentModel: Model? = null,
    generateChatImage: (suspend (MediaGenerationProviderSetting, MediaGenerationModel, String, MediaCreationParams) -> List<UIMessagePart>)? = null,
    requestRevision: () -> Long = { 0L },
): List<Tool> {
    val nativeChatImages = settings.currentModelUsesNativeImageOutput(currentModel)
    val available = buildMap {
        settings.chatImageMediaSelection(currentModel)?.let { put(MediaKind.IMAGE, it) }
        settings.selectedMediaModel(MediaKind.VIDEO)?.let { put(MediaKind.VIDEO, it) }
    }
    if (available.isEmpty()) return emptyList()
    val imageFallbackAvailable = nativeChatImages && MediaKind.IMAGE in available
    val imageFallbackAttempted = PerInstructionAttemptGate()
    val selectedImage = available[MediaKind.IMAGE]
    val nativeDelegate = selectedImage?.second?.id?.let { id ->
        settings.existingProviderMediaSelection(id, MediaKind.IMAGE)?.let { it.model.supportsNativeChatImageOutput(it.provider) }
    } == true
    val imageFormats = if (selectedImage == null) emptySet() else
        (if (nativeDelegate) emptySet() else selectedImage.first.capabilities(MediaKind.IMAGE)?.imageOutputFormats.orEmpty()) + "gif"
    val supportsDuration = available[MediaKind.VIDEO]?.first?.capabilities(MediaKind.VIDEO)
        ?.parameters?.contains(MediaGenerationParameter.DURATION) == true
    return listOf(
        Tool(
            name = GENERATE_MEDIA_TOOL,
            description = (if (imageFallbackAvailable) "Generate an image with the configured fallback image model, or generate a video. "
                else if (nativeChatImages) "Generate a video for an explicit creation request. "
                else "Generate a new image, animated GIF, or video for an explicit creation request. ") +
                "Images are returned directly in this conversation without creating a media-creation session or record. " +
                "Video generation submits a real task; the chat displays its progress and output. " +
                (if (imageFallbackAvailable) "Prefer this chat model's own image generation first. If its image tool is unavailable, fails, returns no usable image, or cannot produce the requested format, call this tool with kind=image once for this request. "
                    else if (nativeChatImages) "Prefer this chat model's own image generation; no fallback image model is configured. " else "") +
                "A screenshot or phone-control tool cannot create a new requested image. " +
                "Available kinds: ${available.keys.joinToString { it.name.lowercase() }}. " +
                (if ("gif" in imageFormats) "GIF uses output_format=gif. Native GIF endpoints receive GIF parameters; other image models generate one motion storyboard assembled into a real multi-frame GIF locally with the same provider and model. " else "Animated GIF output is unavailable with the selected endpoint. ") +
                "A submitted video task is not a completed result. Do not submit the same task again while it is running.",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("kind", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(available.keys.map { JsonPrimitive(it.name.lowercase()) }))
                })
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 32_000)
                    put("description", "Detailed generation instructions including requested motion for a GIF or video")
                })
                if (imageFormats.isNotEmpty()) {
                    put("output_format", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(imageFormats.map(::JsonPrimitive)))
                        put("description", "Image output format only; omit for video and to use the image endpoint default")
                    })
                    animationProperties()
                }
                if (supportsDuration) {
                    put("duration_seconds", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 600) })
                }
            }, required = listOf("kind", "prompt")) },
            systemPrompt = { _, _ ->
                (if (imageFallbackAvailable) "For explicit image creation, first use generate_native_image when that tool is available, or try this chat model's own image generation. If its image tool is unavailable, fails, or returns no usable image, call generate_media with kind=image once to use the user's selected fallback model. Keep the fallback available even if native image capability was advertised. "
                    else if (nativeChatImages) "For image creation, try this chat model's own image output; no fallback image endpoint is configured. "
                    else if (MediaKind.IMAGE in available) "For explicit image creation requests, call generate_media with kind=image and return its real image output in this conversation. "
                    else "No delegated image endpoint is configured. Do not claim this tool can generate images. ") +
                    "For video creation, call generate_media with kind=video only when that kind is available. " +
                    "Tool-generated images stay in this chat and do not create a media-creation session or record. " +
                    "Do not use screenshot, screen capture, or phone-control tools to create a new requested image. " +
                    (if ("gif" in imageFormats) "For GIF or 动图 requests use kind=image and output_format=gif explicitly, with frame_count=4 by default or 8 if requested. animation_mode=auto preserves documented native GIF endpoints, otherwise it creates one same-model storyboard and assembles locally; report the actual mode from the returned metadata. Do not claim locally assembled GIFs were returned natively. If the native endpoint rejects GIF, do not automatically issue a second chargeable image request; offer storyboard mode for a new explicit request. "
                    else "The selected media endpoint does not support GIF output; explain that a GIF-capable model and endpoint must be configured. ") +
                    "Never substitute a static image or video for a requested GIF. The client displays real task progress and results. " +
                    "Report submitted/running/failed/succeeded accurately; do not invent media URLs. " +
                    "Use media_generation_status for an existing video task instead of submitting it again."
            },
            execute = { arguments ->
                try {
                    val args = arguments as? JsonObject ?: error("Invalid arguments")
                    val kind = when ((args["kind"] as? JsonPrimitive)?.contentOrNull) {
                        "image" -> MediaKind.IMAGE
                        "video" -> MediaKind.VIDEO
                        else -> error("kind must be image or video")
                    }
                    val (provider, mediaModel) = available[kind] ?: error("请先在模型设置中选择对应的图片或视频模型")
                    val prompt = (args["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                    require(prompt.isNotEmpty() && prompt.length <= 32_000) { "prompt must contain 1–32000 characters" }
                    val formatValue = args["output_format"]?.takeUnless { it is JsonNull }
                    require(formatValue == null || formatValue is JsonPrimitive && formatValue.isString) { "Invalid output_format" }
                    val format = (formatValue as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
                    val durationValue = args["duration_seconds"]?.takeUnless { it is JsonNull }
                    val duration = (durationValue as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    val capabilities = provider.capabilities(kind) ?: error("Selected provider does not support this media kind")
                    require(format == null || kind == MediaKind.IMAGE && (format == "gif" || format in capabilities.imageOutputFormats)) {
                        "所选模型接口不支持该输出格式，请检查媒体模型设置"
                    }
                    require(durationValue == null || duration != null && duration in 1..600) { "Invalid duration_seconds" }
                    require(duration == null || kind == MediaKind.VIDEO && MediaGenerationParameter.DURATION in capabilities.parameters) {
                        "Selected media endpoint does not support a video duration parameter"
                    }
                    val params = args.animationParams(format).copy(durationSeconds = duration)
                        .withRequired(capabilities, provider.mediaCreationRequiredDefaults(kind))
                    if (kind == MediaKind.IMAGE) {
                        val generate = generateChatImage ?: error("聊天图片生成暂不可用，请稍后重试")
                        if (!imageFallbackAttempted.claim(requestRevision()))
                            throw ImageFallbackAlreadyAttempted()
                        generate(provider, mediaModel, prompt, params).also {
                            require(it.any { part -> part is UIMessagePart.Image && part.url.isNotBlank() }) {
                                "图片生成没有返回可用图片，请检查所选生图模型接口"
                            }
                        }
                    } else {
                        val record = submit(provider, mediaModel, prompt, params)
                        listOf(UIMessagePart.Text(mediaTaskPayload(record).toString()))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val diagnostic = error.message.orEmpty().lowercase()
                    val reasoningConflict = listOf("reasoning", "thinking", "effort", "complexity").any { it in diagnostic }
                    val endpointMismatch = listOf("not supported", "unsupported", "endpoint", "chat/completions").any { it in diagnostic }
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("status", "failed")
                        put("error_code", when {
                            error is GifAnimationException -> "gif_animation_invalid_result"
                            error is ImageFallbackAlreadyAttempted -> "image_fallback_already_attempted"
                            reasoningConflict -> "reasoning_parameter_rejected"
                            endpointMismatch -> "model_endpoint_mismatch"
                            else -> "image_generation_failed"
                        })
                        put("message", if (error is GifAnimationException) error.message
                            else if (error is ImageFallbackAlreadyAttempted) "本条指令已经提交过图片生成，请使用已有结果；需要新图片时请发送一条新的要求。"
                            else if (reasoningConflict) "服务商拒绝了思考相关参数，图片请求不应携带聊天思考设置。请检查模型的自定义请求参数。"
                            else if (endpointMismatch) "服务商不支持所选模型与当前图片接口的组合。请核对服务商支持的接口；本次没有更换模型。"
                            else if (error is IllegalArgumentException) "生成参数无效，或所选接口不支持该输出格式。"
                            else "生成未成功。请确认所选模型具有生图能力，并且服务商支持对应的聊天生图或图片专用接口；不会自动改用其他模型。")
                    }.toString()))
                }
            },
        ),
        Tool(
            name = MEDIA_STATUS_TOOL,
            description = "Check the actual state of a previously submitted media task. Never resubmit a running task.",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("record_id", buildJsonObject { put("type", "string") })
            }, required = listOf("record_id")) },
            execute = { arguments ->
                val id = ((arguments as? JsonObject)?.get("record_id") as? JsonPrimitive)?.contentOrNull
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                val record = id?.let { getRecord(it) }
                listOf(UIMessagePart.Text(record?.let(::mediaTaskPayload)?.toString() ?: "{\"status\":\"not_found\"}"))
            },
        ),
    )
}

private fun mediaTaskPayload(record: MediaCreationRecord): JsonElement = buildJsonObject {
    put("type", "generated_media")
    put("record_id", record.id.toString())
    put("session_id", record.sessionId.toString())
    put("status", record.status.name.lowercase())
    put("kind", record.kind.name.lowercase())
    put("model", record.modelId)
    put("output_count", record.outputs.size)
    record.error?.let { put("message", it) }
}
