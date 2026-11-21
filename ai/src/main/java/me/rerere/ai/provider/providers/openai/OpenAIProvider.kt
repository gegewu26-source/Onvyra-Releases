package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.isDedicatedImageModel
import me.rerere.ai.provider.supportsOpenAIImageTool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.common.http.forImageEndpoint
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .headers(providerSetting.mergeCustomHeaders())
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                    reasoningEffortLevels = advertisedReasoningEfforts(modelObj),
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .headers(providerSetting.mergeCustomHeaders())
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (params.model.isDedicatedImageModel()) {
        flow {
            check(params.allowHostedImageGeneration) { "Image generation requires an approved client image tool" }
            var usage: TokenUsage? = null
            dedicatedImageItems(providerSetting, messages, params).collect { item ->
                item.usage?.let { usage = usage.merge(it) }
                val id = kotlin.uuid.Uuid.random().toString()
                emit(StreamChunk.ImageStart(id, item.mimeType))
                emit(StreamChunk.ImageDelta(id, item.data.substringAfter("base64,")))
                emit(StreamChunk.ImageEnd(id))
            }
            usage?.let { emit(StreamChunk.Usage(it, replace = true)) }
            emit(StreamChunk.Finish("stop"))
        }
    } else if (usesResponsesForRequest(providerSetting, params)) {
        flow {
            var outputSeen = false
            try {
                responseAPI.streamText(providerSetting, messages, params).collect {
                    outputSeen = true
                    emit(it)
                }
            } catch (error: Exception) {
                if (outputSeen || !retryImageWithoutReasoning(params, error)) throw error
                responseAPI.streamText(providerSetting, messages, params.copy(includeReasoning = false)).collect { emit(it) }
            }
        }
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = if (params.model.isDedicatedImageModel()) {
        check(params.allowHostedImageGeneration) { "Image generation requires an approved client image tool" }
        val parts = mutableListOf<UIMessagePart>()
        var usage: TokenUsage? = null
        dedicatedImageItems(providerSetting, messages, params).collect { image ->
            image.usage?.let { usage = usage.merge(it) }
            parts += UIMessagePart.Image("data:${image.mimeType};base64,${image.data.substringAfter("base64,")}")
        }
        check(parts.isNotEmpty()) { "Image endpoint returned no images" }
        TextGenerationResult(kotlin.uuid.Uuid.random().toString(), params.model.modelId,
            UIMessage(role = MessageRole.ASSISTANT, parts = parts), finishReason = "stop", usage = usage)
    } else if (params.forceImageGeneration && params.model.usesCodexSolImageExtension()) {
        generateSolImageWithExtension(providerSetting, messages, params)
    } else if (usesResponsesForRequest(providerSetting, params)) {
        try {
            responseAPI.generateText(providerSetting, messages, params)
        } catch (error: Exception) {
            if (!retryImageWithoutReasoning(params, error)) throw error
            responseAPI.generateText(providerSetting, messages, params.copy(includeReasoning = false))
        }
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    /** A rejected hosted tool did no image work. Try Codex's client image extension once,
     * with the same provider/key; the configured fallback model belongs to the caller. */
    private suspend fun generateSolImageWithExtension(
        setting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val pinned = setting.copy(apiKey = keyRoulette.next(setting.apiKey, setting.id.toString()))
        try {
            return@withContext responseAPI.generateText(pinned, messages, params.copy(includeReasoning = false))
        } catch (error: Exception) {
            if (!isHostedImageCompatibilityRejection(error)) throw error
        }
        val user = messages.lastOrNull { it.role == MessageRole.USER }
        require(user?.parts?.any { it is UIMessagePart.Image || it is UIMessagePart.Document } != true) {
            "此图片扩展仅接受新的生图请求，请使用图片编辑功能处理参考图。"
        }
        val prompt = user?.toText()?.trim().orEmpty()
        require(prompt.isNotBlank()) { "Please provide image generation instructions" }
        val imageOptions = buildJsonObject {}.mergeCustomBody(params.customBody).forImageEndpoint()
        // These values are selected by the client extension, never inherited from chat defaults.
        val body = JsonObject(imageOptions.filterKeys {
            it in setOf("size", "quality", "output_format", "output_compression", "background", "moderation")
        }.toMutableMap().apply {
            put("model", JsonPrimitive("gpt-image-2"))
            put("prompt", JsonPrimitive(prompt))
            put("n", JsonPrimitive(1))
        })
        validateGifImageParameters(body)
        val request = Request.Builder()
            .url("${pinned.baseUrl}/images/generations")
            .headers(pinned.mergeCustomHeaders(params.customHeaders))
            .header("Authorization", "Bearer ${pinned.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(pinned.baseUrl)
            .build()
        val images = client.newCall(request).await().use { response ->
            val raw = response.body.string()
            check(response.isSuccessful) { "Failed to generate image: ${response.code} $raw" }
            parseImageResponse(raw, body["output_format"]?.jsonPrimitive?.contentOrNull)
        }
        check(images.isNotEmpty()) { "Image endpoint returned no images" }
        val source = buildJsonObject {
            put("route", "codex_images_extension")
            put("requested_chat_model", params.model.modelId)
            put("actual_image_model", "gpt-image-2")
            put("endpoint", "images/generations")
        }
        TextGenerationResult(kotlin.uuid.Uuid.random().toString(), "gpt-image-2",
            UIMessage(role = MessageRole.ASSISTANT, parts = images.map { image ->
                UIMessagePart.Image("data:${image.mimeType};base64,${image.data.substringAfter("base64,")}",
                    metadata = buildJsonObject { put("onvyra_image_source", source) })
            }), finishReason = "stop", usage = images.first().usage)
    }

    private fun retryImageWithoutReasoning(params: TextGenerationParams, error: Exception): Boolean {
        if (error is kotlinx.coroutines.CancellationException || !params.includeReasoning) return false
        val message = error.message.orEmpty().lowercase()
        // Retry once only on an explicit parameter rejection before any output. No retry on
        // timeouts, auth, billing or arbitrary 400s: those can duplicate work or hide failures.
        return params.model.supportsOpenAIImageTool() &&
            "complexity" in message && "not supported" in message && "image model" in message
    }

    private suspend fun dedicatedImageItems(setting: ProviderSetting.OpenAI, messages: List<UIMessage>,
        params: TextGenerationParams): Flow<ImageGenerationItem> {
        val message = messages.lastOrNull { it.role == MessageRole.USER }
        val prompt = message?.toText()?.trim().orEmpty()
        require(prompt.isNotBlank()) { "Please provide image generation instructions" }
        val images = message?.parts?.filterIsInstance<UIMessagePart.Image>().orEmpty().map { image ->
            val file = if (image.url.startsWith("file:")) File(java.net.URI(image.url)) else File(image.url)
            require(file.isFile) { "图片编辑需要本地参考图，请重新附加图片。" }
            file.absolutePath
        }
        if (images.isNotEmpty()) return editImage(setting, ImageEditParams(
            model = params.model, prompt = prompt, images = images,
            customHeaders = params.customHeaders, customBody = params.customBody))
        return generateImage(setting, ImageGenerationParams(model = params.model, prompt = prompt,
            customHeaders = params.customHeaders, customBody = params.customBody))
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        if (params.model.supportsOpenAIImageTool()) {
            require(params.numOfImages == 1) { "此聊天生图接口每次生成一张图片。" }
            val result = generateText(providerSetting, listOf(UIMessage.user(params.prompt)),
                TextGenerationParams(params.model, customHeaders = params.customHeaders,
                    customBody = params.customBody, includeReasoning = false, forceImageGeneration = true))
            val images = result.message.parts.filterIsInstance<UIMessagePart.Image>()
            check(images.isNotEmpty()) { "所选模型未返回图片。" }
            images.forEach { image ->
                emit(if (image.url.startsWith("data:image/")) {
                    ImageGenerationItem(image.url.substringAfter("base64,"),
                        image.url.substringAfter("data:").substringBefore(';'), usage = result.usage, metadata = image.metadata)
                } else downloadImageAsBase64(image.url, result.usage).copy(metadata = image.metadata))
            }
            return@flow
        }
        validateGifImageParameters(buildJsonObject {}.mergeCustomBody(params.customBody).forImageEndpoint())

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                
                // 只匹配 x.ai 本身及其子域名，避免 "xxx-max.ai" 之类的中转域名被误判
                val host = providerSetting.baseUrl.toHttpUrlOrNull()?.host?.lowercase()
                val isGrok = host == "x.ai" || host?.endsWith(".x.ai") == true ||
                    params.model.modelId.contains("grok", ignoreCase = true)

                if (params.size.isNotBlank() && !params.size.equals("auto", ignoreCase = true) && !isGrok) {
                    put("size", params.size)
                }
            }
                .mergeCustomBody(params.customBody).forImageEndpoint()
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string(), params.customBody.lastOrNull { it.key == "output_format" }
                ?.value?.let { (it as? JsonPrimitive)?.contentOrNull })
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }
        validateGifImageParameters(buildJsonObject {}.mergeCustomBody(params.customBody).forImageEndpoint())

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // OpenRouter 没有 /images/edits，参考图通过图像生成接口的 input_references 传入
        if (providerSetting.baseUrl.toHttpUrlOrNull()?.host?.lowercase() == "openrouter.ai") {
            editImageWithInputReferences(providerSetting, params, key).forEach { emit(it) }
            return@flow
        }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        if (params.size.isNotBlank()) {
            bodyBuilder.addFormDataPart("size", params.size)
        }

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        buildJsonObject {}.mergeCustomBody(params.customBody).forImageEndpoint().forEach { (key, element) ->
            val value = when (element) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string(), params.customBody.lastOrNull { it.key == "output_format" }
                ?.value?.let { (it as? JsonPrimitive)?.contentOrNull })
        }

        items.forEach { emit(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun editImageWithInputReferences(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageEditParams,
        key: String,
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                if (params.size.isNotBlank() && !params.size.equals("auto", ignoreCase = true)) {
                    put("size", params.size)
                }
                putJsonArray("input_references") {
                    params.images.forEach { path ->
                        val imageFile = File(path)
                        require(imageFile.exists()) {
                            "Image file does not exist: $path"
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put(
                                    "url",
                                    "data:${imageFile.imageMediaType()};base64,${Base64.encode(imageFile.readBytes())}"
                                )
                            }
                        }
                    }
                }
            }
                .mergeCustomBody(params.customBody).forImageEndpoint()
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to edit image: ${response.code} ${response.body?.string()}")
        }
        parseImageResponse(response.body.string(), params.customBody.lastOrNull { it.key == "output_format" }
            ?.value?.let { (it as? JsonPrimitive)?.contentOrNull })
    }

    private suspend fun parseImageResponse(bodyStr: String, requestedFormat: String? = null): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val usage = parseOpenAITokenUsage(body["usage"] as? JsonObject)
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: requestedFormat
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json.substringAfter("base64,"),
                    mimeType = generatedImageMimeType(b64Json, outputFormat),
                    usage = usage,
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url, usage)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String, usage: TokenUsage? = null): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val bytes = body.bytes()
        val base64 = Base64.encode(bytes)
        val mimeType = if (base64.startsWith("R0lGOD")) "image/gif" else body.contentType()?.toString() ?: "image/png"

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType,
            usage = usage,
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

internal fun generatedImageMimeType(base64Data: String, outputFormat: String?): String =
    if (base64Data.substringAfter("base64,").startsWith("R0lGOD")) "image/gif" else when (outputFormat?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        else -> "image/png"
    }

/** GIF image responses are synchronous; do not allow custom model fields to accidentally request an SSE response. */
internal fun validateGifImageParameters(parameters: JsonObject) {
    if ((parameters["output_format"] as? JsonPrimitive)?.contentOrNull?.lowercase() != "gif") return
    parameters["stream"]?.let {
        require((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull == false) {
            "GIF output requires stream to be false or omitted"
        }
    }
    parameters["partial_images"]?.let {
        require((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull == 0) {
            "GIF output requires partial_images to be 0 or omitted"
        }
    }
    require("mask" !in parameters && "input_image_mask" !in parameters) { "GIF output does not support image masks" }
    require("output_compression" !in parameters) { "GIF output does not support output_compression" }
    parameters["frame_count"]?.let {
        require(((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull ?: -1) in 2..16) {
            "GIF frame_count must be between 2 and 16"
        }
    }
    parameters["frame_duration_ms"]?.let {
        require(((it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull ?: -1) in 20..2000) {
            "GIF frame_duration_ms must be between 20 and 2000"
        }
    }
}

/** The catalogue schema explicitly documents these as active efforts, not thinking on/off support. */
internal fun advertisedReasoningEfforts(model: JsonObject): List<String>? {
    val effort = model["effort"] as? JsonObject ?: return null
    val levels = effort["supported_levels"] as? JsonArray ?: return null
    return levels.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filter { it in setOf("low", "medium", "high", "xhigh", "max") }
        .distinct().takeIf { it.isNotEmpty() }
}
