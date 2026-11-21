package me.rerere.ai.provider.providers.openai

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsToolNameCompatibilityTest {
    private val setting = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", apiKey = "fixture-key")
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val validName = Regex("[a-zA-Z0-9_-]{1,64}")
    private fun tool(name: String) = Tool(name = name, description = "fixture", execute = { emptyList() })
    private fun params(names: List<String> = emptyList()) = TextGenerationParams(
        model = Model(modelId = "gpt-6-luna", abilities = listOf(ModelAbility.TOOL)),
        tools = names.map(::tool), maxTokens = 256, reasoningLevel = ReasoningLevel.OFF, includeReasoning = false,
    )
    private fun executed(id: String, name: String) = UIMessagePart.Tool(
        toolCallId = id, toolName = name, input = """{"fixture":42}""",
        output = listOf(UIMessagePart.Text("""{"answer":42}""")),
    )
    private fun history(names: List<String>) = listOf(
        UIMessage.user("fixture history"),
        UIMessage(role = MessageRole.ASSISTANT, parts = names.mapIndexed { i, name -> executed("call_$i", name) }),
        UIMessage.user("请只回复英文 OK，不调用任何工具。"),
    )
    private fun declared(body: JsonObject) = body["tools"]!!.jsonArray.map {
        it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
    }
    private fun calls(body: JsonObject) = body["messages"]!!.jsonArray.flatMap {
        (it.jsonObject["tool_calls"] as? JsonArray).orEmpty().map { call -> call.jsonObject }
    }

    @Test fun definitionsAndHistoricalCallsShareLegalNamesWithoutLosingCallsOrResults() {
        val originals = listOf("mcp__PhoneTools__job/status", "mcp__测试__天气 查询", "dot.name", "", "x".repeat(150), "already_valid-name")
        val messages = history(originals)
        val request = api.prepareChatCompletionRequest(messages, params(originals), setting)
        val declarationNames = declared(request.body)
        val historicalCalls = calls(request.body)
        assertEquals(originals.size, declarationNames.toSet().size)
        assertTrue(declarationNames.all(validName::matches))
        assertEquals("already_valid-name", declarationNames.last())
        historicalCalls.forEachIndexed { i, call ->
            assertEquals("call_$i", call["id"]!!.jsonPrimitive.content)
            assertEquals(declarationNames[i], call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals("""{"fixture":42}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        }
        val results = request.body["messages"]!!.jsonArray.map { it.jsonObject }.filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(originals.size, results.size)
        results.forEachIndexed { i, result ->
            assertEquals("call_$i", result["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("""{"answer":42}""", result["content"]!!.jsonPrimitive.content)
        }
        assertEquals(originals, messages[1].parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName })
        assertEquals("gpt-6-luna", request.body["model"]!!.jsonPrimitive.content)
    }

    @Test fun oldDisabledToolsRemainReplayableWithoutAnyCurrentDeclarations() {
        val original = "mcp__Old Tools__job/status"
        val request = api.prepareChatCompletionRequest(history(listOf(original)), params(), setting)
        assertFalse(request.body.containsKey("tools"))
        val name = calls(request.body).single()["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        assertTrue(validName.matches(name))
        assertEquals(original, request.toolNames.originalName(name))
    }

    @Test fun collidingLegalNameIsReservedAndForcedToolChoiceUsesTheSameAlias() {
        val original = "job/status"
        val collision = ResponseToolNameMapping.forNames(listOf(original), 64, allowDots = false).wireName(original)
        val choice = buildJsonObject { put("type", "function"); put("function", buildJsonObject { put("name", original) }) }
        val request = api.prepareChatCompletionRequest(history(listOf(original)), params(listOf(original, collision)).copy(
            customBody = listOf(CustomBody("tool_choice", choice)),
        ), setting)
        val names = declared(request.body)
        assertEquals(collision, names[1])
        assertNotEquals(collision, names[0])
        assertEquals(names[0], request.body["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(names[0], calls(request.body).single()["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun nonstreamResponseRestoresOriginalNameBeforeLocalExecution() = runBlocking {
        val original = "mcp__PhoneTools__job/status"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val wire = declared(body).single()
            assertTrue(validName.matches(wire))
            val reply = """{"id":"fixture-response","model":"gpt-6-luna","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[{"id":"new_call","type":"function","function":{"name":"$wire","arguments":"{}"}}]}}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = ChatCompletionsAPI(http, KeyRoulette.default()).generateText(setting, history(listOf(original)), params(listOf(original)))
        val call = result.message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(original, call.toolName)
        assertEquals("new_call", call.toolCallId)
        assertEquals("{}", call.input)
    }

    @Test fun actualSsePathRestoresFunctionNameSplitAcrossSeveralEvents() = runBlocking {
        val original = "mcp__PhoneTools__job/status"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val alias = declared(body).single()
            val events = listOf(
                event(0, "new_call", alias.take(5), "{"),
                event(0, null, alias.substring(5, 19), "\"value\":"),
                event(0, null, alias.substring(19), "42}"),
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ).joinToString("") { "data: $it\n\n" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(events.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val chunks = withTimeout(10_000) {
            ChatCompletionsAPI(http, KeyRoulette.default()).streamText(setting, history(listOf(original)), params(listOf(original))).toList()
        }
        val result = merge(chunks).single()
        assertEquals(original, result.toolName)
        assertEquals("""{"value":42}""", result.input)
        assertEquals("new_call", result.toolCallId)
    }

    @Test fun interleavedParallelNameFragmentsAreRestoredPerCallAndArgumentsKeepTheirOrder() {
        val originals = listOf("first/job", "second job")
        val prepared = api.prepareChatCompletionRequest(history(originals), params(originals), setting)
        val aliases = declared(prepared.body)
        val decoder = ChatCompletionsStreamDecoder()
        val restorer = ChatCompletionToolNameRestorer(prepared.aliases)
        val chunks = listOf(
            event(0, "call_a", aliases[0].take(9), "{"),
            event(1, "call_b", aliases[1].take(10), "["),
            event(0, null, aliases[0].drop(9), "\"a\":1}"),
            event(1, null, aliases[1].drop(10), "2]"),
            "[DONE]",
        ).flatMap { restorer.restore(decoder.accept(SseEvent(data = it)).chunks) }
        val tools = merge(chunks).associateBy { it.toolCallId }
        assertEquals(originals[0], tools.getValue("call_a").toolName)
        assertEquals(originals[1], tools.getValue("call_b").toolName)
        assertEquals("""{"a":1}""", tools.getValue("call_a").input)
        assertEquals("[2]", tools.getValue("call_b").input)
    }

    @Test fun ordinaryLegalNamesKeepExistingImmediateStreamingBehavior() {
        val prepared = api.prepareChatCompletionRequest(history(listOf("bad/name")), params(listOf("bad/name", "normal_tool")), setting)
        val restorer = ChatCompletionToolNameRestorer(prepared.aliases)
        val start = StreamChunk.ToolCallStart("c", "normal_")
        val delta = StreamChunk.ToolCallDelta("c", toolNameDelta = "tool", inputDelta = "{}")
        assertEquals(listOf(start), restorer.restore(listOf(start)))
        assertEquals(listOf(delta), restorer.restore(listOf(delta)))
    }

    @Test fun legitimateNameThatOnlySharesAnAliasPrefixIsNotLostAtEnd() {
        val restorer = ChatCompletionToolNameRestorer(mapOf("onvyra_encoded_fixture" to "bad/name"))
        val chunks = restorer.restore(listOf(StreamChunk.ToolCallStart("c", "onvyra_"), StreamChunk.ToolCallEnd("c")))
        assertEquals("onvyra_", merge(chunks).single().toolName)
    }

    @Test fun fullAliasOnStartIsRestoredImmediatelyAndEmptyStartCanReceiveALegalNameLater() {
        val prepared = api.prepareChatCompletionRequest(history(listOf("bad/name")), params(listOf("bad/name")), setting)
        val alias = declared(prepared.body).single()
        val restorer = ChatCompletionToolNameRestorer(prepared.aliases)
        assertEquals(listOf(StreamChunk.ToolCallStart("a", "bad/name")), restorer.restore(listOf(StreamChunk.ToolCallStart("a", alias))))
        assertEquals(listOf(StreamChunk.ToolCallStart("b", "")), restorer.restore(listOf(StreamChunk.ToolCallStart("b"))))
        val lateName = StreamChunk.ToolCallDelta("b", toolNameDelta = "normal_tool", inputDelta = "{}")
        assertEquals(listOf(lateName), restorer.restore(listOf(lateName)))
    }

    @Test fun longHistoricalFixtureUsesProductionRequestPreparationAndCanBeExportedForLiveAcceptance() {
        val original = "mcp__PhoneTools__job/status"
        val messages = buildList {
            repeat(62) { add(UIMessage.user("Synthetic turn $it")); add(UIMessage.assistant("Synthetic answer $it")) }
            add(UIMessage.user("Read the synthetic status."))
            add(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executed("call_fixture_status", original))))
            add(UIMessage.user("请只回复英文 OK，不调用任何工具。"))
        }
        val request = api.prepareChatCompletionRequest(messages, params(listOf(original)), setting)
        assertEquals(128, request.body["messages"]!!.jsonArray.size)
        val call = request.body["messages"]!!.jsonArray[125].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject
        assertTrue(validName.matches(call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content))
        assertEquals(declared(request.body).single(), call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("call_fixture_status", call["id"]!!.jsonPrimitive.content)
        assertEquals("call_fixture_status", request.body["messages"]!!.jsonArray[126].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        System.getenv("ONVYRA_CC_NAME_FIXTURE")?.takeIf { it.isNotBlank() }?.let { destination ->
            File(destination).apply { parentFile?.mkdirs() }.writeText(request.body.toString())
        }
    }

    private fun event(index: Int, id: String?, name: String, arguments: String) = buildJsonObject {
        put("id", "fixture-response")
        put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject {
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", index); id?.let { put("id", it) }; put("type", "function")
                put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
            }) })
        }) }) })
    }.toString()

    private fun merge(chunks: List<StreamChunk>): List<UIMessagePart.Tool> {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("fixture"))
        chunks.forEach { messages = handler.handle(messages, it) }
        return messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
    }
}
