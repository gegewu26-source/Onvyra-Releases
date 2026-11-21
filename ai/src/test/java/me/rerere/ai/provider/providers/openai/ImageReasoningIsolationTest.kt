package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ImageReasoningIsolationTest {
    private val poison = listOf(
        CustomBody("reasoning_effort", JsonPrimitive("max")),
        CustomBody("reasoning", buildJsonObject { put("effort", "high") }),
        CustomBody("thinking", buildJsonObject { put("type", "enabled") }),
        CustomBody("extra_body", buildJsonObject { put("enable_thinking", true); put("quality", "high") }),
        CustomBody("quality", JsonPrimitive("high")),
    )
    private fun client(check: (Request, JsonObject) -> Unit, response: String) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            check(request, body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
    private fun clean(body: JsonObject) {
        listOf("reasoning_effort", "reasoning", "thinking", "enable_thinking").forEach { assertFalse(body.containsKey(it)) }
        (body["extra_body"] as? JsonObject)?.let { assertFalse(it.containsKey("enable_thinking")) }
    }

    @Test fun dedicatedImageEndpointDropsInheritedReasoningAndKeepsSelectedModelAndImageOptions() = runBlocking {
        val api = OpenAIProvider(client({ request, body ->
            assertEquals("/v1/images/generations", request.url.encodedPath)
            assertEquals("gpt-image-2.5-flare", body["model"]!!.jsonPrimitive.content)
            clean(body)
            assertEquals("high", body["quality"]!!.jsonPrimitive.content)
            assertEquals("high", body["extra_body"]!!.jsonObject["quality"]!!.jsonPrimitive.content)
        }, """{"data":[{"b64_json":"AQID"}]}"""))
        assertEquals(1, api.generateImage(ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"),
            ImageGenerationParams(model = Model(modelId = "gpt-image-2.5-flare"), prompt = "fixture", customBody = poison)).toList().size)
    }

    @Test fun nativeImageSubrequestOmitsReasoningEvenWhenOldModelDefaultsContainIt() = runBlocking {
        val api = OpenAIProvider(client({ request, body ->
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
            clean(body)
            assertTrue(body["tools"]!!.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "image_generation" })
        }, """{"id":"fixture","model":"gpt-6.1-sol","output":[{"type":"image_generation_call","id":"img","result":"AQID"}]}"""))
        val result = api.generateText(ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"),
            listOf(UIMessage.user("fixture")), TextGenerationParams(
                Model(modelId = "gpt-6.1-sol", outputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
                reasoningLevel = ReasoningLevel.MAX, customBody = poison, includeReasoning = false))
        assertEquals(1, result.message.parts.filterIsInstance<UIMessagePart.Image>().size)
    }

    @Test fun imageEntryUsesLunasResponsesImageToolEvenWhenModelWasMarkedAsImage() = runBlocking {
        val api = OpenAIProvider(client({ request, body ->
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("gpt-6-luna", body["model"]!!.jsonPrimitive.content)
            clean(body)
            assertEquals("image_generation", body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(body["tools"]!!.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "image_generation" })
        }, """{"id":"fixture","output":[{"type":"image_generation_call","result":"AQID"}]}"""))
        assertEquals(1, api.generateImage(ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"),
            ImageGenerationParams(Model(modelId = "gpt-6-luna", type = ModelType.IMAGE), prompt = "cat",
                customBody = poison)).toList().size)
    }

    @Test fun invalidImageComplexityIsNotConfusedWithTheChatEffortAtAnyDepth() {
        val api = ResponseAPI(OkHttpClient())
        for (level in ReasoningLevel.entries) {
            val body = api.buildRequestBody(ProviderSetting.OpenAI(), listOf(UIMessage.user("fixture")),
                TextGenerationParams(Model(modelId = "gpt-6.1-sol"), reasoningLevel = level,
                    customBody = listOf(CustomBody("complexity", JsonPrimitive("max")))), false)
            assertFalse(body.containsKey("complexity"))
            val effort = body["reasoning"]!!.jsonObject["effort"]?.jsonPrimitive?.content
            assertTrue(effort == null || effort in setOf("low", "medium", "high", "xhigh", "max"))
        }
    }

    @Test fun streamParameterRejectionRetriesOnceAndStillDeliversImageChunks() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            if (calls > 1) clean(body)
            val data = if (calls == 1) """{"error":{"message":"Complexity 'max' is not supported for this image model."}}"""
                else listOf("event: response.output_item.done",
                    """data: {"type":"response.output_item.done","item":{"type":"image_generation_call","id":"img","result":"AQID"}}""", "",
                    "event: response.completed", """data: {"type":"response.completed","response":{"id":"fixture","status":"completed"}}""", "", "").joinToString("\n")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (calls == 1) 400 else 200).message("fixture")
                .body(data.toResponseBody((if (calls == 1) "application/json" else "text/event-stream").toMediaType())).build()
        }.build()
        val chunks = OpenAIProvider(http).streamText(ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"),
            listOf(UIMessage.user("cat")), TextGenerationParams(Model(modelId = "gpt-6.1-sol"),
                reasoningLevel = ReasoningLevel.MAX)).toList()
        assertEquals(2, calls)
        assertTrue(chunks.any { it is StreamChunk.ImageEnd })
    }

    @Test fun pureImageModelImportedAsChatNeverHitsATextEndpointInEitherMode() = runBlocking {
        var count = 0
        val api = OpenAIProvider(client({ request, body ->
            count++
            assertEquals("/v1/images/generations", request.url.encodedPath)
            assertEquals("gpt-image-2.5-flare", body["model"]!!.jsonPrimitive.content)
            clean(body)
            assertFalse(body.containsKey("tools"))
        }, """{"data":[{"b64_json":"AQID"}]}"""))
        val setting = ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1", useResponseApi = true)
        val params = TextGenerationParams(Model(modelId = "gpt-image-2.5-flare"), reasoningLevel = ReasoningLevel.MAX, customBody = poison)
        assertEquals(1, api.generateText(setting, listOf(UIMessage.user("fixture")), params)
            .message.parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(api.streamText(setting, listOf(UIMessage.user("fixture")), params).toList().any { it is StreamChunk.ImageEnd })
        assertEquals(2, count)
    }

    @Test fun reasoningWithFunctionToolsUsesResponsesAndDoesNotSilentlyLowerDepth() {
        val tool = Tool(name = "fixture", description = "fixture", execute = { emptyList() })
        val setting = ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1")
        for (id in listOf("gpt-6.1-sol", "gpt-6-luna", "gpt6luna")) {
            val params = TextGenerationParams(Model(modelId = id, abilities = listOf(ModelAbility.TOOL)),
                tools = listOf(tool), reasoningLevel = ReasoningLevel.HIGH)
            assertTrue(id, usesResponsesForRequest(setting, params))
            val body = ResponseAPI(OkHttpClient()).buildRequestBody(setting, listOf(UIMessage.user("fixture")), params, false)
            assertEquals(id, body["model"]!!.jsonPrimitive.content)
            assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
        val noReasoning = TextGenerationParams(Model(modelId = "gpt-6-luna", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(tool), reasoningLevel = ReasoningLevel.OFF)
        // Known mainline image-tool models use Responses even with depth off.
        assertTrue(usesResponsesForRequest(setting, noReasoning))
        assertFalse(usesResponsesForRequest(setting, noReasoning.copy(model = Model(modelId = "unrelated"))))
    }

    @Test fun gatewayComplexityMaxRejectionRetriesOnceWithoutChangingTheSavedDepthOrModel() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content)
            if (calls == 1) assertEquals("max", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            else clean(body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (calls == 1) 400 else 200).message("fixture")
                .body((if (calls == 1) """{"error":{"message":"Complexity 'max' is not supported for this image model."}}"""
                    else """{"id":"fixture","output":[{"type":"image_generation_call","result":"AQID"}]}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val params = TextGenerationParams(Model(modelId = "gpt-6.1-sol"), reasoningLevel = ReasoningLevel.MAX)
        val result = OpenAIProvider(http).generateText(ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"),
            listOf(UIMessage.user("generate a cat")), params)
        assertEquals(2, calls)
        assertEquals(1, result.message.parts.filterIsInstance<UIMessagePart.Image>().size)
        assertEquals(ReasoningLevel.MAX, params.reasoningLevel)
        assertTrue(params.includeReasoning)
    }
}
