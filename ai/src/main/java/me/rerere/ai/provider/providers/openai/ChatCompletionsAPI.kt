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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
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
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.reasoningCapabilities
import me.rerere.ai.provider.chatReasoningProtocolHost
import me.rerere.ai.provider.mergeReasoningCustomBody
import me.rerere.ai.provider.glmThinkingRequest
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
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
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.common.http.awaitResult
import me.rerere.common.http.withoutReasoningControls
import me.rerere.common.http.jsonArrayOrNull
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

private const val TAG = "ChatCompletionsAPI"

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val prepared =
            prepareChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )
        val requestBody = prepared.body

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val bodyStr = client.newCall(request).awaitResult { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("Failed to get response: ${response.code} $body")
            body
        }
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        prepared.toolNames.restore(TextGenerationResult(
            id = id,
            model = model,
            message = parseMessage(message),
            finishReason = finishReason,
            usage = usage
        ))
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val prepared = prepareChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )
        val requestBody = prepared.body

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())

        val decoder = ChatCompletionsStreamDecoder()
        val toolNameRestorer = ChatCompletionToolNameRestorer(prepared.aliases)

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            toolNameRestorer.restore(chunks).forEach { chunk ->
                trySend(chunk).onFailure { e ->
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
                Log.d(TAG, "onEvent: $data")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        println(bodyElement)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                    exception = e
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                try {
                    sendChunks(decoder.onClosed())
                    close()
                } catch (e: Throwable) {
                    close(e)
                }
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    internal fun prepareChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): PreparedChatCompletionRequest {
        // Collect from the final merged body so saved custom tool declarations and
        // historical calls use the same aliases. No stored message is rewritten.
        val body = buildChatCompletionRequest(messages, params, providerSetting, stream)
        val names = chatCompletionFunctionNames(body)
        val mapping = ResponseToolNameMapping.forNames(names, maxNameLength = 64, allowDots = false)
        return PreparedChatCompletionRequest(
            rewriteChatCompletionFunctionNames(body, mapping),
            mapping,
            names.distinct().associate { mapping.wireName(it) to it }.filter { (wire, original) -> wire != original },
        )
    }

    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val isOpenRouter = host == "openrouter.ai"
        val reasoning = reasoningCapabilities(params.model, providerSetting)
        // Reasoning controls belong to text chat requests. An IMAGE/EMBEDDING model
        // must never receive depth fields on the text endpoint; its media route owns
        // the request shape instead.
        val controlsReasoning = params.includeReasoning && params.model.type == ModelType.CHAT &&
            (ModelAbility.REASONING in params.model.abilities || reasoning.isKnown)
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    messages = messages,
                    includeHistoryReasoning = providerSetting.includeHistoryReasoning,
                    includeOpenRouterReasoningDetails = isOpenRouter,
                    supportInputModalities = params.model.inputModalities,
                )
            )

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // OpenRouter's session field is specific to that provider.
            if (isOpenRouter) {
                params.sessionId?.let { put("session_id", it) }
            }
            // Image output is explicitly configured on the model. Compatible gateways
            // also need this field, regardless of their domain name.
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("modalities", buildJsonArray {
                    add("image")
                    add("text")
                })
            }

            if (controlsReasoning && params.reasoningLevel != ReasoningLevel.AUTO) {
                val level = reasoning.effectiveLevel(params.reasoningLevel)
                when (chatReasoningProtocolHost(params.model, host)) {
                    "__glm_native__" -> glmThinkingRequest(params.model, level)?.forEach { (key, value) -> put(key, value) }
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
                            "Pro/moonshotai/Kimi-K2.5",
                            "Pro/zai-org/GLM-5",
                            "Pro/zai-org/GLM-5.1",
                            "Pro/zai-org/GLM-4.7",
                            "deepseek-ai/DeepSeek-V3.2",
                            "Pro/deepseek-ai/DeepSeek-V3.2",
                            "Qwen/Qwen3.5-397B-A17B",
                            "Qwen/Qwen3.5-122B-A10B",
                            "Qwen/Qwen3.5-35B-A3B",
                            "Qwen/Qwen3.5-27B",
                            "Qwen/Qwen3.5-9B",
                            "Qwen/Qwen3.5-4B",
                            "zai-org/GLM-4.6",
                            "Qwen/Qwen3-8B",
                            "Qwen/Qwen3-14B",
                            "Qwen/Qwen3-32B",
                            "Qwen/Qwen3-30B-A3B",
                            "tencent/Hunyuan-A13B-Instruct",
                            "zai-org/GLM-4.5V",
                            "deepseek-ai/DeepSeek-V3.1-Terminus",
                            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                            "deepseek-ai/DeepSeek-V4-Flash",
                            "Pro/deepseek-ai/DeepSeek-V4-Flash",
                            "deepseek-ai/DeepSeek-V4-Pro",
                            "Pro/deepseek-ai/DeepSeek-V4-Pro",
                        )
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    "aiping.cn" -> {
                        put("enable_thinking", level.isEnabled)
                    }

                    "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> {
                        // 小米 MiMo
                        // https://mimo.mi.com/docs/zh-CN/api/chat/openai-api
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                            // K2.6 的 thinking.keep 默认为 null（忽略历史思考），思考开启时
                            // 需显式传 "all" 才是保留式思考；文档推荐与 enabled 搭配（#1586）
                            if (level.isEnabled && ModelRegistry.KIMI_K2_6.match(params.model.modelId)) {
                                put("keep", "all")
                            }
                        })
                    }

                    "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            val effort = when (level) {
                                ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
                                ReasoningLevel.MAX -> "max"
                                else -> level.effort
                            }
                            put("reasoning_effort", effort)
                        }
                    }

                    "integrate.api.nvidia.com" -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = when (level) {
                                    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                            }
                        }
                    }

                    "opencode.ai" -> {
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    else -> {
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                            ?: InputSchema.Obj(properties = JsonObject(emptyMap()))
                                    )
                                )
                            })
                        })
                    }
                }
            }
        }.let { body ->
            if (controlsReasoning) body.mergeReasoningCustomBody(params.customBody, params.reasoningLevel)
            else body.mergeCustomBody(params.customBody)
        }.let { if (params.includeReasoning) it else it.withoutReasoningControls() }
            .let { body ->
                if (params.allowHostedImageGeneration || "modalities" !in body) body
                else JsonObject(body + ("modalities" to buildJsonArray { add("text") }))
            }
            .withCompatibleReasoningSampling(params.model, responseApi = false)
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        val isMoonshotRestricted = ModelRegistry.KIMI_K2_5.match(model.modelId) ||
                ModelRegistry.KIMI_K2_6.match(model.modelId) ||
                ModelRegistry.KIMI_K3.match(model.modelId) ||
                ModelRegistry.KIMI_K3_ALIAS.match(model.modelId)
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) &&
               !ModelRegistry.GPT_5.match(model.modelId) &&
               !isMoonshotRestricted
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        includeOpenRouterReasoningDetails: Boolean = false,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeReasoning = includeHistoryReasoning,
                    includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
                    supportInputModalities = supportInputModalities,
                )
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeReasoning: Boolean,
        includeOpenRouterReasoningDetails: Boolean,
        supportInputModalities: List<Modality>,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart,
                        includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tool.toolCallId)
                            put("content", tool.toToolResultContent(supportInputModalities))
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart,
                includeOpenRouterReasoningDetails = includeOpenRouterReasoningDetails,
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?,
        includeOpenRouterReasoningDetails: Boolean,
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val reasoningDetails = if (includeOpenRouterReasoningDetails) {
            reasoningPart?.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails
        } else {
            null
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank() || !reasoningDetails.isNullOrEmpty()
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content
            if (hasReasoning) {
                if (!reasoningDetails.isNullOrEmpty()) {
                    put("reasoning_details", reasoningDetails)
                } else {
                    put("reasoning_content", reasoningPart?.reasoning.orEmpty())
                }
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                                put("arguments", tool.inputAsJson().toString())
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
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

    private fun UIMessagePart.Tool.toToolResultContent(supportInputModalities: List<Modality>): JsonElement {
        // 只考虑文字和图片;只有模型支持图片输入时,图片才作为多模态内容回传,否则以文本占位,避免发给不支持的模型报错
        val supportsImageInput = Modality.IMAGE in supportInputModalities
        val hasImageToSend = output.any { it is UIMessagePart.Image && supportsImageInput }
        return if (!hasImageToSend) {
            JsonPrimitive(output.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    is UIMessagePart.Image -> "[Image output omitted: current model does not support image input]"
                    else -> null
                }
            }.joinToString("\n"))
        } else {
            buildJsonArray {
                output.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                }.onFailure {
                                    Log.w(TAG, "encode tool result image failed: ${part.url}", it)
                                    put("type", "text")
                                    put("text", "Error: Failed to encode image to base64")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        val content = jsonObject.chatCompletionText()
        val reasoning = jsonObject.chatCompletionReasoning()
        val reasoningMetadata = jsonObject["reasoning_details"]?.jsonArrayOrNull?.let {
            OpenRouterReasoningMetadata(reasoningDetails = it).toMetadata()
        }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning.orEmpty(),
                            createdAt = Clock.System.now(),
                            finishedAt = null,
                            metadata = reasoningMetadata,
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList()
                        )
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            // 各 provider 汇报缓存命中的字段形状不统一，按方言兜底解析（#1576）：
            // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}

internal data class PreparedChatCompletionRequest(
    val body: JsonObject,
    val toolNames: ResponseToolNameMapping,
    val aliases: Map<String, String>,
)

private fun chatCompletionFunctionNames(body: JsonObject): List<String> = buildList {
    fun addFunction(value: JsonElement?) {
        val function = (value as? JsonObject)?.get("function") as? JsonObject ?: return
        (function["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::add)
    }
    (body["messages"] as? JsonArray).orEmpty().forEach { value ->
        val message = value as? JsonObject ?: return@forEach
        (message["tool_calls"] as? JsonArray).orEmpty().forEach(::addFunction)
    }
    (body["tools"] as? JsonArray).orEmpty().forEach(::addFunction)
    val choice = body["tool_choice"] as? JsonObject
    addFunction(choice)
    ((choice?.get("allowed_tools") as? JsonObject)?.get("tools") as? JsonArray).orEmpty().forEach(::addFunction)
}

private fun rewriteChatCompletionFunctionNames(
    body: JsonObject,
    names: ResponseToolNameMapping,
): JsonObject {
    fun rewriteFunction(value: JsonElement): JsonElement {
        val item = value as? JsonObject ?: return value
        val function = item["function"] as? JsonObject ?: return value
        val original = (function["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return value
        val wire = names.wireName(original)
        if (wire == original) return value
        return JsonObject(item + ("function" to JsonObject(function + ("name" to JsonPrimitive(wire)))))
    }
    return JsonObject(body.toMutableMap().apply {
        (body["messages"] as? JsonArray)?.let { messages ->
            put("messages", JsonArray(messages.map { value ->
                val message = value as? JsonObject ?: return@map value
                val calls = message["tool_calls"] as? JsonArray ?: return@map value
                JsonObject(message + ("tool_calls" to JsonArray(calls.map(::rewriteFunction))))
            }))
        }
        (body["tools"] as? JsonArray)?.let { tools ->
            put("tools", JsonArray(tools.map(::rewriteFunction)))
        }
        (body["tool_choice"] as? JsonObject)?.let { choice ->
            var rewritten = rewriteFunction(choice) as JsonObject
            (choice["allowed_tools"] as? JsonObject)?.let { allowed ->
                (allowed["tools"] as? JsonArray)?.let { tools ->
                    rewritten = JsonObject(rewritten + ("allowed_tools" to JsonObject(
                        allowed + ("tools" to JsonArray(tools.map(::rewriteFunction))),
                    )))
                }
            }
            put("tool_choice", rewritten)
        }
    })
}

/** CC may split function names across deltas; decode an alias only once it is complete. */
internal class ChatCompletionToolNameRestorer(private val aliases: Map<String, String>) {
    private val pendingNames = mutableMapOf<String, String>()

    fun restore(chunks: Iterable<StreamChunk>): List<StreamChunk> = buildList {
        chunks.forEach { chunk ->
            when (chunk) {
                is StreamChunk.ToolCallStart -> {
                    val restored = aliases[chunk.toolName]
                    if (restored != null) {
                        add(chunk.copy(toolName = restored))
                    } else if (aliases.keys.any { it.startsWith(chunk.toolName) }) {
                        pendingNames[chunk.id] = chunk.toolName
                        add(chunk.copy(toolName = ""))
                    } else add(chunk)
                }
                is StreamChunk.ToolCallDelta -> {
                    val pending = pendingNames[chunk.id]
                    if (pending == null) {
                        add(chunk)
                    } else {
                        val complete = pending + chunk.toolNameDelta
                        val restored = aliases[complete]
                        when {
                            restored != null -> {
                                pendingNames.remove(chunk.id)
                                add(chunk.copy(toolNameDelta = restored))
                            }
                            aliases.keys.none { it.startsWith(complete) } -> {
                                pendingNames.remove(chunk.id)
                                add(chunk.copy(toolNameDelta = complete))
                            }
                            else -> {
                                pendingNames[chunk.id] = complete
                                add(chunk.copy(toolNameDelta = ""))
                            }
                        }
                    }
                }
                is StreamChunk.ToolCallEnd -> {
                    pendingNames.remove(chunk.id)?.takeIf { it.isNotEmpty() }?.let { pending ->
                        add(StreamChunk.ToolCallDelta(chunk.id, toolNameDelta = pending))
                    }
                    add(chunk)
                }
                is StreamChunk.Finish -> {
                    pendingNames.forEach { (id, pending) ->
                        if (pending.isNotEmpty()) add(StreamChunk.ToolCallDelta(id, toolNameDelta = pending))
                    }
                    pendingNames.clear()
                    add(chunk)
                }
                else -> add(chunk)
            }
        }
    }
}
