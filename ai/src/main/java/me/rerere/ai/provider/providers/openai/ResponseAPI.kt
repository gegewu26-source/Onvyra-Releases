package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.reasoningCapabilities
import me.rerere.ai.provider.mergeReasoningCustomBody
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.supportsOpenAIImageTool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.normalizeGeneratedImageResult
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ImageGenerationDataException
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.common.http.awaitResult
import me.rerere.common.http.withoutReasoningControls
import me.rerere.common.http.withImageComplexity
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ResponseAPI"

class ResponsesPayloadException internal constructor(message: String) : IllegalStateException(message)

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val prepared = prepareRequest(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val requestBody = prepared.body
        var request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.responsesPath}")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .apply { if (params.forceImageGeneration) header("X-Onvyra-Image-Generation", "1") }
            .build()

        for (attempt in 0..1) {
            // Keep Call cancellation active throughout the body read, and close a rejected
            // response before issuing the existing same-model compatibility retry.
            val result = client.newCall(request).awaitResult { response ->
                val bodyStr = response.body.string()
                if (response.isSuccessful) {
                    val payload = try {
                        json.parseToJsonElement(bodyStr) as? JsonObject
                    } catch (_: SerializationException) {
                        null
                    } ?: throw ResponsesPayloadException("服务商未返回有效的 Responses JSON。")
                    val tools = requestBody["tools"]
                    val allowChatCompletionText = !params.forceImageGeneration &&
                        (tools == null || tools is JsonArray && tools.isEmpty())
                    val parsed = parseResponseOutput(payload, allowChatCompletionText)
                    if (parsed.message.parts.isEmpty()) {
                        throw ResponsesPayloadException("服务商的 Responses 响应没有可用输出。")
                    }
                    prepared.toolNames.restore(parsed)
                } else if (attempt == 0 && params.forceImageGeneration &&
                    response.code in setOf(400, 422) && bodyStr.contains("tool_choice", ignoreCase = true)) {
                    null
                } else {
                    throw Exception("Failed to get response: ${response.code} $bodyStr")
                }
            }
            if (result != null) return@withContext result
            // Some reverse proxies accept image_generation but reject forcing that tool.
            // Reuse the exact provider/account/model/options, changing only tool_choice.
            val compatibleBody = JsonObject(requestBody.filterKeys { it != "tool_choice" })
            request = request.newBuilder()
                .post(json.encodeToString(compatibleBody).toRequestBody("application/json".toMediaType()))
                .build()
        }
        error("图片请求兼容重试未返回结果。")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = callbackFlow {
        val prepared = prepareRequest(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val requestBody = prepared.body
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.responsesPath}")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .apply { if (params.forceImageGeneration) header("X-Onvyra-Image-Generation", "1") }
            .build()

        val decoder = ResponseApiStreamDecoder()

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(prepared.toolNames.restore(chunk)).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t ?: java.io.IOException("HTTP ${response?.code ?: "error"}: Responses request failed")


                val bodyRaw = runCatching { response?.body?.string() }.getOrNull()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    exception = exception ?: e
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                try {
                    sendChunks(decoder.onClosed())
                    close()
                } catch (error: Throwable) {
                    close(error)
                }
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject = prepareRequest(providerSetting, messages, params, stream).body

    internal fun prepareRequest(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): PreparedResponseRequest {
        val raw = buildRawRequestBody(providerSetting, messages, params, stream)
        val toolNames = ResponseToolNameMapping.forRequest(raw)
        return PreparedResponseRequest(toolNames.rewrite(raw), toolNames)
    }

    private fun buildRawRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        val reasoning = reasoningCapabilities(params.model, providerSetting)
        val controlsReasoning = params.includeReasoning && params.model.type == ModelType.CHAT &&
            (ModelAbility.REASONING in params.model.abilities || reasoning.isKnown)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }

            // messages
            put("input", buildUnmappedMessages(messages, capabilities))

            // reasoning
            if (controlsReasoning) {
                val level = reasoning.effectiveLevel(params.reasoningLevel)
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            val builtInTools = params.model.tools + if (Modality.IMAGE in params.model.outputModalities ||
                params.model.supportsOpenAIImageTool()) {
                setOf(BuiltInTools.ImageGeneration)
            } else emptySet()
            if (useFunctionTools || builtInTools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                                // Response API 不传 strict 时默认按严格模式处理, 会把 schema 里的可选字段全部变成必填,
                                // 模型只能给本应省略的字段硬填默认值 (0 / "" / []), 导致工具参数校验失败
                                put("strict", false)
                            })
                        }
                    }
                    // built-in tools
                    builtInTools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                })
                            }
                        }
                    }
                }
            }
        }.let { body ->
            if (controlsReasoning) body.mergeReasoningCustomBody(params.customBody, params.reasoningLevel)
            else body.mergeCustomBody(params.customBody)
        }.let { if (params.includeReasoning) it else it.withoutReasoningControls() }
            .let { body ->
                // Client image actions go through the host's task approval first.
                // Their forceImageGeneration subrequest keeps the same model's hosted tool.
                if (params.forceImageGeneration || params.allowHostedImageGeneration &&
                    params.tools.none { it.name == "generate_native_image" }) body
                else JsonObject(body.toMutableMap().apply {
                    val tools = (get("tools") as? kotlinx.serialization.json.JsonArray).orEmpty()
                    put("tools", kotlinx.serialization.json.JsonArray(tools.filterNot {
                        (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "image_generation"
                    }))
                    if ((get("tool_choice") as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "image_generation")
                        remove("tool_choice")
                })
            }
            .let { body ->
                if (!params.forceImageGeneration) body else JsonObject(body.toMutableMap().apply {
                    val tools = (get("tools") as? kotlinx.serialization.json.JsonArray).orEmpty()
                    if (tools.none { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "image_generation" })
                        put("tools", kotlinx.serialization.json.JsonArray(tools + buildJsonObject { put("type", "image_generation") }))
                    put("tool_choice", buildJsonObject { put("type", "image_generation") })
                })
            }
            .withImageComplexity()
            .withCompatibleReasoningSampling(params.model, responseApi = true)
    }

    internal fun buildMessages(
        messages: List<UIMessage>,
        capabilities: ResponseProviderCapabilities = ResponseProviderCapabilities(),
    ): kotlinx.serialization.json.JsonArray {
        val raw = buildJsonObject { put("input", buildUnmappedMessages(messages, capabilities)) }
        return ResponseToolNameMapping.forRequest(raw).rewrite(raw).getValue("input").jsonArray
    }

    private fun buildUnmappedMessages(
        messages: List<UIMessage>,
        capabilities: ResponseProviderCapabilities,
    ) = buildJsonArray {
        messages
            .filter { message ->
                message.role != MessageRole.SYSTEM && (
                    message.isValidToUpload() || message.parts.any { part ->
                        part is UIMessagePart.Reasoning &&
                            part.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent != null
                    }
                )
            }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message, capabilities)
                } else {
                    addUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(
        message: UIMessage,
        capabilities: ResponseProviderCapabilities,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    val emittedReasoningIds = mutableSetOf<String>()
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                val reasoningId = reasoningMetadata?.reasoningId
                                // Local thoughts and other providers' signatures are not Responses items.
                                if (reasoningId == null && reasoningMetadata?.encryptedContent == null) {
                                    return@forEach
                                }
                                if (reasoningId != null && !emittedReasoningIds.add(reasoningId)) {
                                    return@forEach
                                }
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                val reasoningParts = if (reasoningId == null) {
                                    listOf(part)
                                } else {
                                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().filter {
                                        it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId == reasoningId
                                    }
                                }
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    reasoningId?.let { put("id", it) }
                                    put("summary", buildJsonArray {
                                        reasoningParts
                                            .filter { it.reasoningType == ReasoningType.SUMMARY_TEXT }
                                            .filter { it.reasoning.isNotEmpty() }
                                            .forEach {
                                                add(buildJsonObject {
                                                    put("type", "summary_text")
                                                    put("text", it.reasoning)
                                                })
                                            }
                                    })
                                    // Some GPT upstreams reject nonempty reasoning.content on replay.
                                    // Keep raw thoughts locally for display; continue native reasoning
                                    // with its summary and opaque encrypted state.
                                    val encryptedContent = reasoningParts.firstNotNullOfOrNull {
                                        it.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent
                                    }
                                    // Ark supports plaintext continuation in its own Responses dialect.
                                    if (capabilities.supportsReasoningContentReplay && encryptedContent == null) {
                                        val content = reasoningParts.filter {
                                            it.reasoningType == ReasoningType.REASONING_TEXT && it.reasoning.isNotEmpty()
                                        }
                                        if (content.isNotEmpty()) {
                                            put("content", buildJsonArray {
                                                content.forEach {
                                                    add(buildJsonObject {
                                                        put("type", "reasoning_text")
                                                        put("text", it.reasoning)
                                                    })
                                                }
                                            })
                                        }
                                    }
                                    encryptedContent?.let {
                                        put("encrypted_content", it)
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part))
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            is UIMessagePart.ServerTool -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addServerToolItem(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 同一批并发工具调用需先输出全部 function_call，再输出对应结果。
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                    }
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            if (hasImage) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64().onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.printStackTrace()
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else {
                                put(
                                    "output",
                                    tool.output.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                )
                            }
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    private fun JsonArrayBuilder.addServerToolItem(tool: UIMessagePart.ServerTool) {
        val metadata = tool.metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.OPENAI_RESPONSES) return

        val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.OPENAI_RESPONSES }
        if (rawCall != null) {
            add(rawCall)
            return
        }

        add(buildJsonObject {
            put("type", "${tool.toolName.removeSuffix("_call")}_call")
            put("id", tool.toolCallId)
            put("status", tool.status.toOpenAIStatus())
            tool.input?.let { input ->
                if (tool.toolName.removeSuffix("_call") == "web_search") put("action", input)
                else if (input is JsonObject) input.forEach { (key, value) -> put(key, value) }
                else put("input", input)
            }
            tool.output?.let { put("output", it) }
        })
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    internal fun parseResponseOutput(
        jsonObject: JsonObject,
        allowChatCompletionText: Boolean = false,
    ): TextGenerationResult {
        if (jsonObject["error"]?.let { it != JsonNull } == true) {
            throw jsonObject.parseErrorDetail()
        }
        val status = jsonObject["status"]?.jsonPrimitiveOrNull?.contentOrNull
        if (status in setOf("failed", "cancelled", "queued", "in_progress")) {
            throw HttpException("服务商的 Responses 请求未完成（$status）。")
        }
        val outputField = jsonObject["output"]
        if (outputField == null && allowChatCompletionText) {
            parseCompatibleTextResponse(jsonObject)?.let { return it }
        }
        val outputs = outputField as? JsonArray
            ?: throw ResponsesPayloadException("服务商的 Responses 响应缺少有效的 output 数组。")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val reasoningMetadata = OpenAIReasoningMetadata(
                        reasoningId = output["id"]?.jsonPrimitive?.contentOrNull,
                        encryptedContent = output["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                    val reasoningPartStart = parts.size
                    output["summary"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now(),
                                        metadata = reasoningMetadata,
                                        reasoningType = ReasoningType.SUMMARY_TEXT,
                                    )
                                )
                            }
                        }
                    }
                    output["content"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        if (part["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                            parts.add(
                                UIMessagePart.Reasoning(
                                    reasoning = part["text"]?.jsonPrimitive?.content ?: error("text not found"),
                                    createdAt = Clock.System.now(),
                                    finishedAt = Clock.System.now(),
                                    metadata = reasoningMetadata,
                                    reasoningType = ReasoningType.REASONING_TEXT,
                                )
                            )
                        }
                    }
                    if (parts.size == reasoningPartStart) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = Clock.System.now(),
                                metadata = reasoningMetadata,
                                reasoningType = ReasoningType.REASONING_TEXT,
                            )
                        )
                    }
                }

                "image_generation_call" -> {
                    val data = (output["result"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    val mime = generatedImageMimeType("", output["output_format"]?.jsonPrimitive?.contentOrNull)
                    val url = data?.let { normalizeGeneratedImageResult(it, mime) }
                        ?: throw ImageGenerationDataException("图片生成已结束，但接口未返回完整图片。")
                    parts.add(UIMessagePart.Image(url))
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }

                else -> if (isOpenAIServerToolCall(type)) {
                    parts.add(output.toOpenAIServerTool())
                }
            }
        }

        return TextGenerationResult(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
            ),
            finishReason = if (status == "incomplete") {
                val reason = jsonObject["incomplete_details"]?.jsonObjectOrNull
                    ?.get("reason")?.jsonPrimitiveOrNull?.contentOrNull
                reason?.let { "$status:$it" } ?: status
            } else status,
            usage = parseOpenAIResponseUsage(jsonObject)
        )
    }

}

/** Some compatible gateways return a completed Chat Completions text reply at /responses. */
private fun parseCompatibleTextResponse(payload: JsonObject): TextGenerationResult? {
    val objectType = payload["object"]?.jsonPrimitiveOrNull?.contentOrNull
    if (objectType != null && objectType != "chat.completion") return null
    val choice = (payload["choices"] as? JsonArray)?.firstOrNull()?.jsonObjectOrNull ?: return null
    val message = choice["message"]?.jsonObjectOrNull ?: return null
    if (message["role"]?.jsonPrimitiveOrNull?.contentOrNull != "assistant") return null
    if (listOf("tool_calls", "function_call", "images", "audio").any { key ->
            message[key]?.let { it != JsonNull && !(it is JsonArray && it.isEmpty()) } == true
        }) return null
    val content = message["content"]
    if (content !is JsonArray && (content as? JsonPrimitive)?.isString != true) return null
    val text = message.chatCompletionText().takeIf { it.isNotBlank() } ?: return null
    val finishReason = choice["finish_reason"]?.jsonPrimitiveOrNull?.contentOrNull
    if (finishReason != null && finishReason !in setOf("stop", "length")) return null
    return TextGenerationResult(
        id = payload["id"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        model = payload["model"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
        message = UIMessage.assistant(text),
        finishReason = finishReason,
        usage = parseOpenAITokenUsage(payload["usage"] as? JsonObject),
    )
}

internal fun isOpenAIServerToolCall(type: String): Boolean =
    type.endsWith("_call") && type !in setOf(
        "function_call",
        "custom_tool_call",
        "computer_call",
        "local_shell_call",
        "shell_call",
        "image_generation_call",
    )

internal fun JsonObject.toOpenAIServerTool(): UIMessagePart.ServerTool {
    val type = get("type")?.jsonPrimitive?.contentOrNull ?: "server_tool_call"
    val protocolFields = setOf("type", "id", "status", "result", "output")
    val input = get("action") ?: JsonObject(filterKeys { it !in protocolFields })
        .takeUnless { it.isEmpty() }
    return UIMessagePart.ServerTool(
        toolCallId = get("id")?.jsonPrimitive?.contentOrNull ?: "",
        toolName = type.removeSuffix("_call"),
        input = input,
        output = get("output") ?: get("result"),
        status = get("status")?.jsonPrimitive?.contentOrNull.toServerToolStatus(),
        metadata = ServerToolMetadata(
            protocol = ServerToolProtocol.OPENAI_RESPONSES,
            call = this,
        ).toMetadata(),
    )
}

internal fun String?.toServerToolStatus(): ServerToolStatus = when (this) {
    "completed" -> ServerToolStatus.COMPLETED
    "failed", "incomplete", "cancelled" -> ServerToolStatus.FAILED
    else -> ServerToolStatus.IN_PROGRESS
}

private fun ServerToolStatus.toOpenAIStatus(): String = when (this) {
    ServerToolStatus.IN_PROGRESS -> "in_progress"
    ServerToolStatus.COMPLETED -> "completed"
    ServerToolStatus.FAILED -> "failed"
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true,
    val supportsReasoningContentReplay: Boolean = false,
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false,
            supportsReasoningContentReplay = true,
        )

        else -> ResponseProviderCapabilities()
    }
}
