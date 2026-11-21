package me.rerere.mediagen.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.common.http.forImageEndpoint
import me.rerere.mediagen.model.*
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** The same mainline-model image tool used by chat, for studio's stored media models. */
internal class OpenAIChatImageGeneration(private val client: OkHttpClient) {
    fun body(model: MediaGenerationModel, request: MediaGenerationRequest): JsonObject {
        require(!request.prompt.isNullOrBlank())
        require(request.count == null || request.count == 1) { "此聊天生图接口每次生成一张图片。" }
        require(request.durationSeconds == null && request.generateAudio == null)
        val options = request.extraParameters.forImageEndpoint()
        return buildJsonObject {
            put("model", model.modelId)
            put("store", false)
            put("stream", false)
            put("input", buildJsonArray { add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "input_text"); put("text", request.prompt) })
                    request.inputs.forEach { input ->
                        require(input is MediaGenerationInput.Image)
                        val url = if (input.url.startsWith("https://") || input.url.startsWith("http://")) input.url else {
                            val file = File(input.url.removePrefix("file://"))
                            require(file.isFile)
                            val mime = imageMimeType(file.extension, "image/png")
                            "data:$mime;base64,${Base64.encode(file.readBytes())}"
                        }
                        add(buildJsonObject { put("type", "input_image"); put("image_url", url) })
                    }
                })
            }) })
            put("tools", buildJsonArray { add(buildJsonObject {
                put("type", "image_generation")
                listOf("quality", "output_format", "output_compression", "background", "complexity").forEach { key ->
                    options[key]?.let { put(key, it) }
                }
                request.resolution?.takeUnless { it == "auto" }?.let { put("size", it) }
            }) })
            put("tool_choice", buildJsonObject { put("type", "image_generation") })
        }
    }

    suspend fun create(setting: MediaGenerationProviderSetting.OpenAI, model: MediaGenerationModel,
        request: MediaGenerationRequest): MediaGenerationTask {
        val http = Request.Builder().url("${setting.baseUrl.trimEnd('/')}/responses")
            .addHeader("Authorization", "Bearer ${setting.apiKey}").postJson(body(model, request)).build()
        val root = client.executeJson(http, "openai")
        val usage = (root["usage"] as? JsonObject)?.let { value ->
            MediaGenerationUsage(
                totalTokens = value["total_tokens"]?.jsonPrimitive?.longOrNull,
                inputTokens = value.long("input_tokens"), outputTokens = value.long("output_tokens"),
                cachedTokens = value.obj("input_tokens_details")?.long("cached_tokens"),
            )
        }
        val outputs = (root["output"] as? JsonArray).orEmpty().mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            if (item.string("type") != "image_generation_call") return@mapNotNull null
            val data = item.string("result")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bytes = Base64.decode(data.substringAfter("base64,"))
            MediaGenerationOutput(data = bytes, mimeType = if (bytes.isGifImage()) "image/gif"
                else imageMimeType(item.string("output_format"), "image/png"))
        }
        check(outputs.isNotEmpty()) { "所选模型未返回图片。" }
        return MediaGenerationTask(id = Uuid.random().toString(), provider = "openai", model = model.modelId,
            status = MediaGenerationStatus.SUCCEEDED, outputs = outputs, usage = usage)
    }
}
