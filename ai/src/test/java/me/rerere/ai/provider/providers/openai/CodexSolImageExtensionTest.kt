package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class CodexSolImageExtensionTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jzTYAAAAASUVORK5CYII="
    private val rejected = """{"error":{"type":"image_generation_user_error","code":"invalid_value","param":"complexity","message":"Complexity 'medium' is not supported for this image model."}}"""
    private val image = """{"data":[{"b64_json":"$png"}],"usage":{"input_tokens":20,"output_tokens":343,"total_tokens":363}}"""
    private val hosted = """{"model":"gpt-6.1-sol","output":[{"type":"image_generation_call","result":"$png"}]}"""
    private val setting = ProviderSetting.OpenAI(baseUrl = "https://owner-relay.invalid/v1", apiKey = "fixture-a,fixture-b")
    private val sol = Model(modelId = "gpt-6.1-sol")
    private val calls = mutableListOf<Pair<Request, JsonObject>>()
    private fun provider(reply: (Int) -> Pair<Int, String>): OpenAIProvider = OpenAIProvider(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            calls += request to body
            val (status, response) = reply(calls.size)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build())

    @Test fun solHostedRejectionUsesOneSameKeyClientExtensionAndReportsActualEngineAndUsage() = runBlocking {
        val poison = listOf(CustomBody("model", JsonPrimitive("gpt-6.1-sol")),
            CustomBody("reasoning", buildJsonObject { put("effort", "max") }),
            CustomBody("tools", buildJsonArray { add(buildJsonObject { put("type", "image_generation") }) }),
            CustomBody("extra_body", buildJsonObject { put("model", "gpt-image-2.5-flare"); put("reasoning_effort", "max") }),
            CustomBody("quality", JsonPrimitive("low")), CustomBody("n", JsonPrimitive(8)))
        val api = provider { if (it == 1) 400 to rejected else 200 to image }
        val result = api.generateText(setting, listOf(UIMessage.user("draw a cat")),
            TextGenerationParams(sol, customBody = poison, forceImageGeneration = true))
        assertEquals(2, calls.size)
        assertEquals(listOf("/v1/responses", "/v1/images/generations"), calls.map { it.first.url.encodedPath })
        assertEquals(calls.first().first.header("Authorization"), calls.last().first.header("Authorization"))
        assertTrue(calls.all { it.first.url.host == "owner-relay.invalid" })
        val body = calls.last().second
        assertEquals("gpt-image-2", body["model"]!!.jsonPrimitive.content)
        assertEquals(1, body["n"]!!.jsonPrimitive.int)
        assertEquals("draw a cat", body["prompt"]!!.jsonPrimitive.content)
        assertEquals("low", body["quality"]!!.jsonPrimitive.content)
        for (key in listOf("reasoning", "reasoning_effort", "tools", "extra_body", "tool_choice", "input", "messages", "complexity"))
            assertFalse("Unexpected image parameter: $key", body.containsKey(key))
        assertEquals("gpt-image-2", result.model)
        assertEquals(TokenUsage(20, 343, 0, 363), result.usage)
        val source = result.message.parts.filterIsInstance<UIMessagePart.Image>().single().metadata!!["onvyra_image_source"]!!.jsonObject
        assertEquals("codex_images_extension", source["route"]!!.jsonPrimitive.content)
        assertEquals("gpt-6.1-sol", source["requested_chat_model"]!!.jsonPrimitive.content)
        assertEquals("gpt-image-2", source["actual_image_model"]!!.jsonPrimitive.content)
    }

    @Test fun imageEntryPreservesActualExtensionMetadataAndOnlyAcceptsExplicitCompatibilityErrors() = runBlocking {
        for (status in listOf(400, 422, 502)) {
            calls.clear()
            val result = provider { if (it == 1) status to rejected else 200 to image }
                .generateImage(setting, ImageGenerationParams(sol, "cat")).toList().single()
            assertEquals(2, calls.size)
            assertEquals(TokenUsage(20, 343, 0, 363), result.usage)
            assertEquals("gpt-image-2", result.metadata!!["onvyra_image_source"]!!.jsonObject["actual_image_model"]!!.jsonPrimitive.content)
        }
    }

    @Test fun authQuotaGenericFailuresAndTimeoutNeverStartAnAdditionalImageRequest() = runBlocking {
        for ((status, body) in listOf(401 to rejected, 403 to rejected, 429 to rejected, 500 to rejected,
            400 to """{"error":{"type":"invalid_request_error","message":"unrelated input error"}}""",
            502 to """{"error":{"type":"server_error","message":"upstream failed"}}""")) {
            calls.clear()
            try {
                provider { status to body }.generateImage(setting, ImageGenerationParams(sol, "cat")).toList()
                fail("Failure must propagate")
            } catch (_: Exception) { }
            assertEquals("status=$status", 1, calls.size)
            assertEquals("/v1/responses", calls.single().first.url.encodedPath)
        }
        calls.clear()
        try {
            provider { throw SocketTimeoutException("fixture timeout") }
                .generateImage(setting, ImageGenerationParams(sol, "cat")).toList()
            fail("The network timeout must propagate")
        } catch (actual: SocketTimeoutException) {
            // Coroutine stack recovery may copy the exception; type/message and
            // the absence of an Images request verify the observable contract.
            assertEquals("fixture timeout", actual.message)
        }
        assertEquals(1, calls.size)
        assertEquals("/v1/responses", calls.single().first.url.encodedPath)
        // A cancellation carrying even an otherwise matching provider payload
        // must never be interpreted as permission to start another generation.
        assertFalse(isHostedImageCompatibilityRejection(
            CancellationException("Failed to get response: 400 $rejected")))
    }

    @Test fun successfulHostedSolLunaAndOrdinaryChatKeepTheirExistingRequests() = runBlocking {
        for (id in listOf("gpt-6.1-sol", "gpt-6-luna")) {
            calls.clear()
            provider { 200 to hosted }.generateImage(setting, ImageGenerationParams(Model(modelId = id), "cat")).toList()
            assertEquals(1, calls.size)
            assertEquals("/v1/responses", calls.single().first.url.encodedPath)
        }
        calls.clear()
        provider { 200 to """{"model":"gpt-6.1-sol","output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}""" }
            .generateText(setting, listOf(UIMessage.user("Hello")), TextGenerationParams(sol, reasoningLevel = ReasoningLevel.HIGH))
        assertEquals(1, calls.size)
        assertEquals("high", calls.single().second["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        calls.clear()
        try { provider { 400 to rejected }.generateImage(setting, ImageGenerationParams(Model(modelId = "gpt-6-luna"), "cat")).toList(); fail() }
        catch (_: Exception) { }
        assertEquals(1, calls.size)
    }

    @Test fun extensionFailureIsPropagatedWithoutAThirdGenerationRequest() = runBlocking {
        try {
            provider { if (it == 1) 400 to rejected else 401 to """{"error":{"message":"fixture access denied"}}""" }
                .generateImage(setting, ImageGenerationParams(sol, "cat")).toList()
            fail("The caller must receive extension failure for its configured fallback")
        } catch (error: Exception) { assertTrue(error.message.orEmpty().startsWith("Failed to generate image: 401")) }
        assertEquals(2, calls.size)
    }
}
