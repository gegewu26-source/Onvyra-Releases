package me.rerere.ai.provider.providers.openai

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseToolNameMappingTest {
    private val api = ResponseAPI(OkHttpClient())
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://example.com/v1", useResponseApi = true)
    private val invalidName = "mcp__旧 手机工具__jobs/status"

    @Test
    fun `invalid and oversized names have stable unique legal wire names`() {
        val names = listOf("", "中文", "a b", "a/b", "a?b", "line\nend", "x".repeat(129), "valid.Name-123_")
        val first = ResponseToolNameMapping.forNames(names)
        val reordered = ResponseToolNameMapping.forNames(names.reversed())
        val wire = names.map(first::wireName)
        assertEquals(wire.size, wire.toSet().size)
        names.forEach { original ->
            val encoded = first.wireName(original)
            assertTrue(encoded.matches(Regex("[a-zA-Z0-9_.-]+")))
            assertTrue(encoded.length <= 128)
            assertEquals(encoded, reordered.wireName(original))
            assertEquals(original, first.originalName(encoded))
        }
        assertEquals("valid.Name-123_", first.wireName("valid.Name-123_"))
        assertEquals("unknown_name", first.originalName("unknown_name"))
    }

    @Test
    fun `legal names reserve aliases without merging different tools`() {
        val generated = ResponseToolNameMapping.forNames(listOf(invalidName)).wireName(invalidName)
        val names = listOf(generated, invalidName)
        val mapping = ResponseToolNameMapping.forNames(names)
        assertEquals(generated, mapping.wireName(generated))
        assertNotEquals(generated, mapping.wireName(invalidName))
        assertEquals(invalidName, mapping.originalName(mapping.wireName(invalidName)))
        assertEquals(mapping.wireName(invalidName), ResponseToolNameMapping.forNames(names.reversed()).wireName(invalidName))
    }

    @Test
    fun `shared codec honors the shorter chat completions limit`() {
        val mapping = ResponseToolNameMapping.forNames(listOf("a".repeat(65), invalidName, "normal", "period.name"), maxNameLength = 64, allowDots = false)
        listOf("a".repeat(65), invalidName, "period.name").forEach { original ->
            assertTrue(mapping.wireName(original).length <= 64)
            assertTrue(mapping.wireName(original).matches(Regex("[a-zA-Z0-9_-]+")))
            assertEquals(original, mapping.originalName(mapping.wireName(original)))
        }
        assertEquals("normal", mapping.wireName("normal"))
        assertTrue(mapping.isEncodedNamePrefix(mapping.wireName(invalidName).take(12)))
        assertFalse(mapping.isEncodedNamePrefix("normal"))
    }

    @Test
    fun `history and currently declared tools share names and preserve pair data`() {
        val tool = executedTool(invalidName)
        val messages = listOf(UIMessage.user("fixture"), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
        for (stream in listOf(false, true)) {
            val body = api.buildRequestBody(provider, messages, params(invalidName), stream)
            val call = body.getValue("input").jsonArray.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }.jsonObject
            val output = body.getValue("input").jsonArray.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" }.jsonObject
            val declaration = body.getValue("tools").jsonArray.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function" }.jsonObject
            assertEquals(declaration.getValue("name"), call.getValue("name"))
            assertNotEquals(invalidName, call.getValue("name").jsonPrimitive.content)
            assertEquals("call_fixture", call.getValue("call_id").jsonPrimitive.content)
            assertEquals(call.getValue("call_id"), output.getValue("call_id"))
            assertEquals("{\"fixture_number\":42}", call.getValue("arguments").jsonPrimitive.content)
            assertEquals("{\"fixture_number\":42}", output.getValue("output").jsonPrimitive.content)
        }
        assertEquals(invalidName, tool.toolName)
        assertEquals(invalidName, (messages.last().parts.single() as UIMessagePart.Tool).toolName)
    }

    @Test
    fun `disabled historical MCP tools are repaired without a current declaration`() {
        val messages = listOf(UIMessage.user("fixture"), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executedTool(invalidName))))
        val standalone = api.buildMessages(messages)
        val body = api.buildRequestBody(provider, messages, params(), false)
        assertEquals(standalone, body.getValue("input"))
        val name = standalone.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" }.jsonObject.getValue("name").jsonPrimitive.content
        assertTrue(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        assertFalse((body["tools"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { it.jsonObject["type"]?.jsonPrimitive?.content == "function" })
    }

    @Test
    fun `only protocol function names are changed`() {
        val body = buildJsonObject {
            put("model", "gpt-6-luna")
            put("input", buildJsonArray {
                add(buildJsonObject { put("type", "function_call"); put("name", invalidName); put("call_id", "call_fixture"); put("arguments", "{\"name\":\"$invalidName\"}") })
                add(buildJsonObject { put("type", "function_call_output"); put("call_id", "call_fixture"); put("output", invalidName) })
                add(buildJsonObject { put("role", "user"); put("content", invalidName) })
            })
            put("tools", buildJsonArray {
                add(buildJsonObject { put("type", "function"); put("name", invalidName); put("description", invalidName); put("parameters", buildJsonObject { put("name", invalidName) }) })
                add(buildJsonObject { put("type", "web_search") })
            })
            put("tool_choice", buildJsonObject { put("type", "function"); put("name", invalidName) })
        }
        val mapping = ResponseToolNameMapping.forRequest(body)
        val fixed = mapping.rewrite(body)
        assertEquals(mapping.wireName(invalidName), fixed.getValue("tool_choice").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(body.getValue("input").jsonArray[1], fixed.getValue("input").jsonArray[1])
        assertEquals(body.getValue("input").jsonArray[2], fixed.getValue("input").jsonArray[2])
        assertEquals(body.getValue("input").jsonArray[0].jsonObject.getValue("arguments"), fixed.getValue("input").jsonArray[0].jsonObject.getValue("arguments"))
        assertEquals(body.getValue("tools").jsonArray[0].jsonObject.getValue("parameters"), fixed.getValue("tools").jsonArray[0].jsonObject.getValue("parameters"))
        assertEquals(body.getValue("tools").jsonArray[1], fixed.getValue("tools").jsonArray[1])
        assertEquals(body.getValue("model"), fixed.getValue("model"))
    }

    @Test
    fun `allowed tools selection refers to the same mapped declaration`() {
        val body = buildJsonObject {
            put("tools", buildJsonArray { add(buildJsonObject { put("type", "function"); put("name", invalidName) }) })
            put("tool_choice", buildJsonObject {
                put("type", "allowed_tools"); put("mode", "auto")
                put("tools", buildJsonArray { add(buildJsonObject { put("type", "function"); put("name", invalidName) }) })
            })
        }
        val fixed = ResponseToolNameMapping.forRequest(body).rewrite(body)
        assertEquals(fixed.getValue("tools").jsonArray.single().jsonObject.getValue("name"),
            fixed.getValue("tool_choice").jsonObject.getValue("tools").jsonArray.single().jsonObject.getValue("name"))
    }

    @Test
    fun `custom body name overrides are mapped after the final request merge`() {
        val choice = buildJsonObject { put("type", "function"); put("name", invalidName) }
        val prepared = api.prepareRequest(provider, listOf(UIMessage.user("fixture")),
            params(invalidName).copy(customBody = listOf(CustomBody("tool_choice", choice))), false)
        val wire = prepared.body.getValue("tool_choice").jsonObject.getValue("name").jsonPrimitive.content
        assertNotEquals(invalidName, wire)
        assertEquals(invalidName, prepared.toolNames.originalName(wire))
        assertEquals(prepared.body.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content, wire)
    }

    @Test(timeout = 10_000)
    fun `nonstream response returns original local execution name`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val requestBody = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val sent = json.parseToJsonElement(requestBody).jsonObject
            val wire = sent.getValue("tools").jsonArray.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function" }.jsonObject.getValue("name").jsonPrimitive.content
            assertNotEquals(invalidName, wire)
            val response = buildJsonObject {
                put("id", "resp_fixture"); put("model", "gpt-6-luna")
                put("output", buildJsonArray { add(buildJsonObject {
                    put("type", "function_call"); put("call_id", "new_call"); put("name", wire); put("arguments", "{}")
                }) })
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = ResponseAPI(client).generateText(provider, listOf(UIMessage.user("fixture")), params(invalidName))
        val returned = result.message.parts.single() as UIMessagePart.Tool
        assertEquals(invalidName, returned.toolName)
        assertEquals("new_call", returned.toolCallId)
    }

    @Test(timeout = 10_000)
    fun `stream response returns original local execution name before argument deltas`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val sent = json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            val wire = sent.getValue("tools").jsonArray.single { it.jsonObject["type"]?.jsonPrimitive?.content == "function" }.jsonObject.getValue("name").jsonPrimitive.content
            val events = listOf(
                buildJsonObject { put("type", "response.output_item.added"); put("output_index", 0); put("item", buildJsonObject {
                    put("type", "function_call"); put("id", "fc_fixture"); put("call_id", "new_call"); put("name", wire); put("arguments", "")
                }) },
                buildJsonObject { put("type", "response.function_call_arguments.delta"); put("item_id", "fc_fixture"); put("delta", "{}") },
                buildJsonObject { put("type", "response.output_item.done"); put("item", buildJsonObject {
                    put("type", "function_call"); put("id", "fc_fixture"); put("call_id", "new_call"); put("name", wire); put("arguments", "{}")
                }) },
                buildJsonObject { put("type", "response.completed"); put("response", buildJsonObject { put("id", "resp_fixture"); put("status", "completed"); put("model", "gpt-6-luna") }) },
            )
            val sse = events.joinToString("") { "data: $it\n\n" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val chunks = ResponseAPI(client).streamText(provider, listOf(UIMessage.user("fixture")), params(invalidName)).toList()
        val start = chunks.filterIsInstance<StreamChunk.ToolCallStart>().single()
        assertEquals(invalidName, start.toolName)
        assertEquals("new_call", start.id)
        assertEquals("{}", chunks.filterIsInstance<StreamChunk.ToolCallDelta>().single().inputDelta)
        assertEquals("new_call", chunks.filterIsInstance<StreamChunk.ToolCallEnd>().single().id)
    }

    @Test
    fun `long synthetic history repairs input 125 name without discarding history`() {
        val messages = buildList {
            repeat(62) { index ->
                add(UIMessage.user("Synthetic fixture question $index"))
                add(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Synthetic fixture answer $index"))))
            }
            add(UIMessage.user("Synthetic tool lookup fixture"))
            add(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executedTool(invalidName))))
            add(UIMessage.user("请只回复英文 OK，不添加其他内容，不要调用任何工具。"))
        }
        val body = api.buildRequestBody(provider, messages, params(invalidName).copy(maxTokens = 1024), false)
        val input = body.getValue("input").jsonArray
        assertEquals(128, input.size)
        assertEquals("function_call", input[125].jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(input[125].jsonObject.getValue("name").jsonPrimitive.content.matches(Regex("[a-zA-Z0-9_.-]+")))
        assertEquals("function_call_output", input[126].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(input[125].jsonObject.getValue("call_id"), input[126].jsonObject.getValue("call_id"))
        assertEquals("gpt-6-luna", body.getValue("model").jsonPrimitive.content)
        System.getenv("ONVYRA_RESPONSE_NAME_FIXTURE")?.takeIf { it.isNotBlank() }?.let { path ->
            val target = File(path)
            require(target.isAbsolute) { "Synthetic fixture output must be an absolute path" }
            target.parentFile?.mkdirs()
            target.writeText(body.toString(), Charsets.UTF_8)
        }
    }

    private fun params(vararg names: String) = TextGenerationParams(
        model = Model(modelId = "gpt-6-luna", abilities = listOf(ModelAbility.TOOL)),
        tools = names.map { name -> Tool(name = name, description = "Synthetic fixture lookup", parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) }, execute = { emptyList() }) },
        includeReasoning = false,
        allowHostedImageGeneration = false,
    )

    private fun executedTool(name: String) = UIMessagePart.Tool(
        toolCallId = "call_fixture", toolName = name,
        input = "{\"fixture_number\":42}", output = listOf(UIMessagePart.Text("{\"fixture_number\":42}")),
    )
}
