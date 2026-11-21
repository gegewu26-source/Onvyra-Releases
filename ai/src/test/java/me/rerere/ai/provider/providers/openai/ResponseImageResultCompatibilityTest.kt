package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import me.rerere.ai.util.ImageGenerationDataException
import me.rerere.ai.util.normalizeGeneratedImageResult
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ResponseImageResultCompatibilityTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jzTYAAAAASUVORK5CYII="
    private fun image(result: String, id: String = "image-1") = buildJsonObject {
        put("type", "image_generation_call"); put("id", id); put("result", result)
    }
    private data class RecordedRequest(val url: String, val authorization: String?, val body: JsonObject,
        val imageIntent: String?)
    private val imageResponse get() = buildJsonObject {
        put("id", "fixture-response"); put("model", "gpt-6-luna")
        put("output", buildJsonArray { add(image(png)) })
    }.toString()
    private val forcedImageParams get() = TextGenerationParams(
        Model(modelId = "gpt-6-luna"), includeReasoning = false, forceImageGeneration = true,
        customBody = listOf(
            CustomBody("reasoning", buildJsonObject { put("effort", "max") }),
            CustomBody("reasoning_effort", JsonPrimitive("max")),
            CustomBody("extra_body", buildJsonObject { put("thinking", true) }),
        ),
    )
    private val fixtureProvider get() = ProviderSetting.OpenAI(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture-one fixture-two",
    )
    private suspend fun <T> withHttpResponses(
        responses: List<Pair<Int, String>>,
        block: suspend (ResponseAPI, List<RecordedRequest>) -> T,
    ): T {
        val requests = mutableListOf<RecordedRequest>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            requests += RecordedRequest(request.url.toString(), request.header("Authorization"),
                Json.parseToJsonElement(buffer.readUtf8()).jsonObject, request.header("X-Onvyra-Image-Generation"))
            val (code, body) = responses.getOrElse(requests.lastIndex) { error("Unexpected extra request") }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return try { block(ResponseAPI(client), requests) } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
    private class Replay {
        val decoder = ResponseApiStreamDecoder()
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("fixture"))
        fun event(type: String, body: JsonObject) {
            val payload = JsonObject(body + ("type" to JsonPrimitive(type)))
            decoder.accept(SseEvent(event = type, data = payload.toString())).chunks.forEach { chunk ->
                messages = handler.handle(messages, chunk)
            }
        }
        fun images() = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Image>()
    }

    @Test fun forcedNativeImageCanRetryRejectedToolChoiceWithoutChangingItsAccountModelToolsOrReasoning() = runBlocking {
        for (status in listOf(400, 422)) {
            withHttpResponses(listOf(status to """{"error":{"message":"tool_choice image_generation is not supported"}}""",
                200 to imageResponse)) { api, requests ->
                val result = api.generateText(fixtureProvider, listOf(UIMessage.user("cat")), forcedImageParams)
                assertEquals("data:image/png;base64,$png", result.message.parts.filterIsInstance<UIMessagePart.Image>().single().url)
                assertEquals(2, requests.size)
                val (first, second) = requests
                assertEquals(first.url, second.url)
                assertEquals(first.authorization, second.authorization)
                assertEquals("1", first.imageIntent)
                assertEquals(first.imageIntent, second.imageIntent)
                assertEquals("gpt-6-luna", first.body["model"]!!.jsonPrimitive.content)
                assertEquals(first.body["model"], second.body["model"])
                assertTrue(first.body.containsKey("tool_choice"))
                assertFalse(second.body.containsKey("tool_choice"))
                assertEquals(JsonObject(first.body.filterKeys { it != "tool_choice" }), second.body)
                assertTrue(second.body["tools"]!!.jsonArray.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "image_generation"
                })
                for (body in listOf(first.body, second.body)) {
                    assertFalse(body.containsKey("reasoning"))
                    assertFalse(body.containsKey("reasoning_effort"))
                    assertFalse(body["extra_body"]?.jsonObject?.containsKey("thinking") == true)
                }
            }
        }
    }

    @Test fun successfulNativeImageDoesNotIssueACompatibilityRetry() = runBlocking {
        withHttpResponses(listOf(200 to imageResponse)) { api, requests ->
            val result = api.generateText(fixtureProvider, listOf(UIMessage.user("cat")), forcedImageParams)
            assertEquals(1, result.message.parts.filterIsInstance<UIMessagePart.Image>().size)
            assertEquals(1, requests.size)
        }
    }

    @Test fun completeNativeImageIsRetainedWhenGatewayReturnsExplicitNullUsage() = runBlocking {
        val response = JsonObject(Json.parseToJsonElement(imageResponse).jsonObject + ("usage" to JsonNull)).toString()
        withHttpResponses(listOf(200 to response)) { api, requests ->
            val result = api.generateText(fixtureProvider, listOf(UIMessage.user("cat")), forcedImageParams)
            assertEquals("data:image/png;base64,$png", result.message.parts.filterIsInstance<UIMessagePart.Image>().single().url)
            assertNull(result.usage)
            assertEquals(1, requests.size)
        }
    }

    @Test fun completeNativeImageAndActualUsageAreRetainedWhenTokenDetailsAreNull() = runBlocking {
        val usage = buildJsonObject {
            put("input_tokens", 12); put("output_tokens", 7); put("total_tokens", 19)
            put("input_tokens_details", JsonNull); put("output_tokens_details", JsonNull)
        }
        val response = JsonObject(Json.parseToJsonElement(imageResponse).jsonObject + ("usage" to usage)).toString()
        withHttpResponses(listOf(200 to response)) { api, requests ->
            val result = api.generateText(fixtureProvider, listOf(UIMessage.user("cat")), forcedImageParams)
            assertEquals("data:image/png;base64,$png", result.message.parts.filterIsInstance<UIMessagePart.Image>().single().url)
            assertEquals(12, result.usage?.promptTokens)
            assertEquals(7, result.usage?.completionTokens)
            assertEquals(19, result.usage?.totalTokens)
            assertEquals(0, result.usage?.cachedTokens)
            assertEquals(1, requests.size)
        }
    }

    @Test fun nullableCachedTokenCountDoesNotDiscardACompleteNativeImage() = runBlocking {
        val usage = buildJsonObject {
            put("input_tokens", 12); put("output_tokens", 7); put("total_tokens", 19)
            put("input_tokens_details", buildJsonObject { put("cached_tokens", JsonNull) })
        }
        val response = JsonObject(Json.parseToJsonElement(imageResponse).jsonObject + ("usage" to usage)).toString()
        withHttpResponses(listOf(200 to response)) { api, requests ->
            val result = api.generateText(fixtureProvider, listOf(UIMessage.user("cat")), forcedImageParams)
            assertEquals(1, result.message.parts.filterIsInstance<UIMessagePart.Image>().size)
            assertEquals(0, result.usage?.cachedTokens)
            assertEquals(1, requests.size)
        }
    }

    @Test fun toolChoiceCompatibilityDoesNotRetryOrdinaryTextOrUnrelatedHttpFailures() = runBlocking {
        val cases = listOf(
            Triple(false, 400, "tool_choice is not supported"),
            Triple(true, 400, "reasoning is not supported"),
            Triple(true, 401, "tool_choice access denied"),
            Triple(true, 429, "tool_choice rate limited"),
            Triple(true, 500, "tool_choice server failed"),
        )
        for ((forceImage, status, message) in cases) {
            withHttpResponses(listOf(status to buildJsonObject {
                put("error", buildJsonObject { put("message", message) })
            }.toString())) { api, requests ->
                try {
                    api.generateText(fixtureProvider, listOf(UIMessage.user("fixture")),
                        forcedImageParams.copy(forceImageGeneration = forceImage))
                    fail("Rejected request must surface its error")
                } catch (expected: Exception) {
                    assertTrue(expected.message.orEmpty().contains(status.toString()))
                }
                assertEquals(1, requests.size)
                assertEquals(if (forceImage) "1" else null, requests.single().imageIntent)
            }
        }
    }

    @Test fun rejectedToolChoiceIsRetriedAtMostOnce() = runBlocking {
        val rejected = """{"error":{"message":"tool_choice is not supported"}}"""
        withHttpResponses(listOf(400 to rejected, 422 to rejected)) { api, requests ->
            try {
                api.generateText(fixtureProvider, listOf(UIMessage.user("cat")), forcedImageParams)
                fail("Repeated parameter rejection must fail")
            } catch (expected: Exception) {
                assertTrue(expected.message.orEmpty().contains("422"))
            }
            assertEquals(2, requests.size)
        }
    }

    @Test fun nativeImageTransportFailureIsNotReplayed() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            throw IOException("fixture transport failure")
        }.build()
        try {
            try {
                ResponseAPI(client).generateText(fixtureProvider, listOf(UIMessage.user("cat")), forcedImageParams)
                fail("Transport failure must surface")
            } catch (expected: IOException) {
                assertEquals("fixture transport failure", expected.message)
            }
            assertEquals(1, requests)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun prematureImageStreamClosureFailsTheFlowInsteadOfLeavingItOpen() = runBlocking {
        var requests = 0
        val declaredImage = "event: response.output_item.added\n" +
            "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"image_generation_call\",\"id\":\"missing-final\"}}\n\n"
        // An application interceptor supplies the full SSE body without DNS, a socket,
        // or a real provider. This exercises the actual EventSource -> flow close path.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(declaredImage.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            try {
                withTimeout(3_000L) {
                    ResponseAPI(client).streamText(
                        ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", apiKey = "fixture"),
                        listOf(UIMessage.user("fixture")),
                        TextGenerationParams(Model(modelId = "gpt-6-luna")),
                    ).toList()
                }
                fail("A declared image without its final result must fail the flow")
            } catch (expected: ImageGenerationDataException) {
                assertTrue(expected.message.orEmpty().contains("图片"))
            }
            assertEquals(1, requests)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun rawBase64CompleteDataUriAndHttpsResultEachProduceOneRenderableImage() {
        for (value in listOf(png, "data:image/png;base64,$png", "https://cdn.example.invalid/cat.png?signature=fixture")) {
            val replay = Replay()
            replay.event("response.output_item.added", buildJsonObject { put("item", image("")) })
            assertTrue("Declaring a native tool must not create a blank image", replay.images().isEmpty())
            replay.event("response.output_item.done", buildJsonObject { put("item", image(value)) })
            val expected = if (value.startsWith("https:")) value else "data:image/png;base64,$png"
            assertEquals(expected, replay.images().single().url)
            assertFalse(replay.images().single().url.contains("base64,data:"))
        }
    }

    @Test fun terminalOnlyOutputIsMaterializedAndDoesNotDuplicateEarlierDoneResult() {
        for (sendDone in listOf(false, true)) for (wrapped in listOf(false, true)) {
            val replay = Replay()
            replay.event("response.output_item.added", buildJsonObject { put("item", image("")) })
            if (sendDone) replay.event("response.output_item.done", buildJsonObject { put("item", image(png)) })
            val terminal = buildJsonObject { put("status", "completed"); put("output", buildJsonArray { add(image(png)) }) }
            replay.event("response.completed", if (wrapped) buildJsonObject { put("response", terminal) } else terminal)
            assertEquals("data:image/png;base64,$png", replay.images().single().url)
        }
    }

    @Test fun groupedUrlSafeAndUnpaddedBase64IsAcceptedWithoutDoubleHeader() {
        val wrapped = png.replace('/', '_').removeSuffix("=").chunked(12).joinToString("\n")
        val replay = Replay()
        replay.event("response.output_item.done", buildJsonObject { put("item", image("data:image/png;base64,$wrapped")) })
        val url = replay.images().single().url
        assertFalse(url.contains('\n'))
        val data = url.substringAfter("base64,")
        assertArrayEquals(java.util.Base64.getDecoder().decode(png), kotlin.io.encoding.Base64.decode(data))
    }

    @Test fun partialThenFinalReplacesTheImageRatherThanAppendingAnotherPrefix() {
        val replay = Replay()
        replay.event("response.output_item.added", buildJsonObject { put("item", image("")) })
        replay.event("response.image_generation_call.partial_image", buildJsonObject {
            put("item_id", "image-1"); put("partial_image_b64", "data:image/png;base64,$png")
        })
        replay.event("response.output_item.done", buildJsonObject { put("item", image("data:image/png;base64,$png")) })
        assertEquals("data:image/png;base64,$png", replay.images().single().url)
    }

    @Test fun emptyOrMalformedCompleteImagesRaiseAnExplicitFailureWithoutACompletedBlankImage() {
        for (value in listOf("", "  ", "%%not-image%%", "data:text/plain;base64,YQ==")) {
            val replay = Replay()
            replay.event("response.output_item.added", buildJsonObject { put("item", image("")) })
            try {
                replay.event("response.output_item.done", buildJsonObject { put("item", image(value)) })
                fail("Malformed image must not be completed")
            } catch (error: ImageGenerationDataException) {
                assertFalse(error.message.isNullOrBlank())
                assertNotEquals("Failed requirement.", error.message)
            }
            assertTrue(replay.images().isEmpty())
        }
        val replay = Replay()
        replay.event("response.output_item.added", buildJsonObject { put("item", image("")) })
        assertThrows(ImageGenerationDataException::class.java) { replay.decoder.onClosed() }
    }

    @Test fun nonStreamingImageResultUsesTheSameNormalizationForDataUrisAndUrls() {
        val api = ResponseAPI(OkHttpClient())
        for (value in listOf(png, "data:image/png;base64,$png", "https://cdn.example.invalid/cat.png")) {
            val result = api.parseResponseOutput(buildJsonObject { put("output", buildJsonArray { add(image(value)) }) })
            assertEquals(normalizeGeneratedImageResult(value), result.message.parts.filterIsInstance<UIMessagePart.Image>().single().url)
        }
        assertThrows(ImageGenerationDataException::class.java) {
            api.parseResponseOutput(buildJsonObject { put("output", buildJsonArray { add(image("")) }) })
        }
    }

    @Test fun endingAnEmptyGenericImageRemovesThePlaceholderAndKeepsTextIndexesCorrect() {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("fixture"))
        for (chunk in listOf(StreamChunk.ImageStart("image"), StreamChunk.TextStart("text"),
            StreamChunk.TextDelta("text", "first"), StreamChunk.ImageEnd("image"), StreamChunk.TextDelta("text", " second"))) {
            messages = handler.handle(messages, chunk)
        }
        assertTrue(messages.last().parts.filterIsInstance<UIMessagePart.Image>().isEmpty())
        assertEquals("first second", messages.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }
}
