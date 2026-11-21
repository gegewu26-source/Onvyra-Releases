package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.MediaCreationParams
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.isDedicatedImageModel
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.ImageGenerationDataException
import com.constx.onvyra.data.files.GifAnimationException
import me.rerere.mediagen.model.*
import me.rerere.mediagen.provider.MediaGenerationProviderSetting

internal const val GENERATE_NATIVE_IMAGE_TOOL = "generate_native_image"

internal fun createNativeChatImageTool(settings: Settings, currentModel: Model,
    requestRevision: () -> Long = { 0L },
    generate: suspend (MediaGenerationProviderSetting, MediaGenerationModel, String, MediaCreationParams) -> List<UIMessagePart>,
): Tool? {
    if (!settings.currentModelUsesNativeImageOutput(currentModel) && !currentModel.isDedicatedImageModel()) return null
    val own = settings.existingProviderMediaSelection(currentModel, MediaKind.IMAGE) ?: return null
    val attempted = PerInstructionAttemptGate()
    return Tool(name = GENERATE_NATIVE_IMAGE_TOOL,
        description = "Generate an image using this chat model's own image-generation capability. " +
            "This uses the current model, not the configured fallback image model. For an animated GIF use output_format=gif: documented GIF image endpoints return the animation natively; other image models generate one same-model motion storyboard assembled into a multi-frame GIF locally. A screenshot is not image generation.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("prompt", buildJsonObject { put("type", "string") })
            put("output_format", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("gif")))) })
            animationProperties()
        }, required = listOf("prompt")) },
        systemPrompt = { _, _ -> "For an explicit image creation request use generate_native_image first. " +
            "If it fails or produces no image, use generate_media kind=image once if available. " +
            "For GIF or 动图, send output_format=gif explicitly and default frame_count=4. Report whether the result is native GIF or locally assembled; do not describe a static image as an animation. " +
            "If a GIF attempt fails, do not automatically charge a second image tool; report the failure and wait for a new explicit instruction. " +
            "These image tools are available in this session even if older messages claimed otherwise." },
        execute = { args ->
            val prompt = (args as? JsonObject)?.get("prompt")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(prompt.isNotEmpty() && prompt.length <= 32_000) { "请提供有效的生图描述。" }
            val formatValue = args.jsonObject["output_format"]?.takeUnless { it is JsonNull }
            require(formatValue == null || formatValue is JsonPrimitive && formatValue.isString && formatValue.content == "gif") { "Invalid output_format" }
            val params = args.jsonObject.animationParams((formatValue as? JsonPrimitive)?.contentOrNull)
            if (!attempted.claim(requestRevision())) listOf(UIMessagePart.Text(
                """{"status":"failed","error_code":"native_image_already_attempted"}"""))
            else try {
                generate(own.mediaProvider, own.mediaModel, prompt, params).also { parts ->
                    check(parts.any { it is UIMessagePart.Image && it.url.isNotBlank() }) { "原生生图没有返回图片。" }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "failed")
                put("error_code", nativeImageFailureCode(error))
                put("message", if (error is GifAnimationException) error.message else nativeImageFailureMessage(nativeImageFailureCode(error)))
                nativeImageHttpStatus(error)?.let { put("http_status", it) }
                put("endpoint", if (currentModel.isDedicatedImageModel()) "images/generations" else "responses/image_generation")
            }.toString())) }
        })
}

/** Report a bounded category, never a provider response that might contain account data. */
private fun nativeImageFailureCode(error: Exception): String {
    val message = error.message.orEmpty().lowercase()
    return when {
        error is GifAnimationException -> "gif_animation_invalid_result"
        error is ImageGenerationDataException -> "native_image_invalid_result"
        error is java.net.SocketTimeoutException -> "native_image_timeout"
        "stream" in message && ("must" in message || "required" in message || "only" in message) -> "native_image_stream_required"
        error is kotlinx.serialization.SerializationException -> "native_image_response_protocol"
        "complexity" in message || "reasoning" in message -> "native_image_parameter_rejected"
        "tool_choice" in message -> "native_image_tool_choice_rejected"
        "image_generation" in message && ("not support" in message || "unsupported" in message) -> "native_image_tool_unavailable"
        "401" in message || "403" in message -> "native_image_access_denied"
        "429" in message -> "native_image_rate_limited"
        "未返回图片" in message || "没有返回图片" in message -> "native_image_missing_result"
        else -> "native_image_failed"
    }
}

internal fun nativeImageHttpStatus(error: Exception): Int? =
    Regex("^Failed to (?:get response|generate image): ([1-5][0-9]{2})(?: |$)")
        .find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

private fun nativeImageFailureMessage(code: String): String = when (code) {
    "native_image_tool_unavailable" -> "服务端不支持当前模型的图片工具。"
    "native_image_tool_choice_rejected" -> "服务端拒绝了图片工具选择参数。"
    "native_image_stream_required" -> "服务端要求使用流式图片响应。"
    "native_image_response_protocol", "native_image_invalid_result" -> "原生接口返回的图片响应格式无法解析。"
    "native_image_parameter_rejected" -> "服务端拒绝了原生图片请求参数。"
    "native_image_access_denied" -> "服务端拒绝访问原生图片接口。"
    "native_image_rate_limited" -> "原生图片接口额度或速率受限。"
    "native_image_timeout" -> "原生图片接口超时。"
    "native_image_missing_result" -> "原生接口没有返回图片。"
    else -> "原生图片请求失败，可在调用结果中查看错误分类和 HTTP 状态。"
}

