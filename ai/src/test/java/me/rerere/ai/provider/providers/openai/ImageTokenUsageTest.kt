package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ImageTokenUsageTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jzTYAAAAASUVORK5CYII="
    private val usage = """{"input_tokens":124,"output_tokens":343,"total_tokens":467,"input_tokens_details":{"cached_tokens":20}}"""
    private val expected = TokenUsage(124, 343, 20, 467)
    private val setting = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1", apiKey = "fixture")
    private fun provider(body: String) = OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())

    @Test fun dedicatedImagesAndItsChatAdaptersKeepOneRequestUsageForMultipleImages() = runBlocking {
        val body = """{"usage":$usage,"data":[{"b64_json":"$png"},{"b64_json":"$png"}]}"""
        val provider = provider(body)
        val model = Model(modelId = "gpt-image-2.5-flare")
        val images = provider.generateImage(setting, ImageGenerationParams(model, "cat", numOfImages = 2)).toList()
        assertEquals(2, images.size)
        assertTrue(images.all { it.usage == expected })
        val result = provider.generateText(setting, listOf(UIMessage.user("cat")), TextGenerationParams(model))
        assertEquals(expected, result.usage)
        val chunks = provider.streamText(setting, listOf(UIMessage.user("cat")), TextGenerationParams(model)).toList()
        assertEquals(listOf(expected), chunks.filterIsInstance<StreamChunk.Usage>().map { it.usage })
    }

    @Test fun hostedImageUsageSurvivesNativeGenerateImageAndStreamingResponse() = runBlocking {
        val root = Json.parseToJsonElement("""{"id":"fixture","model":"gpt-6-luna","status":"completed","output":[{"type":"image_generation_call","id":"image-fixture","result":"$png"}],"usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15},"tool_usage":{"image_gen":$usage}}""").jsonObject
        val images = provider(root.toString()).generateImage(setting, ImageGenerationParams(Model(modelId = "gpt-6-luna"), "cat")).toList()
        val combined = TokenUsage(136, 346, 20, 482)
        assertEquals(combined, images.single().usage)
        val event = buildJsonObject { put("type", "response.completed"); put("response", root) }
        val chunks = ResponseApiStreamDecoder().accept(SseEvent(event = "response.completed", data = event.toString())).chunks
        assertEquals(combined, chunks.filterIsInstance<StreamChunk.Usage>().single().usage)
    }

    @Test fun nullOrEmptyUsageStaysUnknownAndMissingTotalUsesReportedComponentsOnly() {
        assertNull(parseOpenAITokenUsage(null))
        assertNull(parseOpenAITokenUsage(buildJsonObject {}))
        assertNull(parseOpenAITokenUsage(Json.parseToJsonElement("""{"input_tokens":-1,"total_tokens":"invalid"}""").jsonObject))
        assertEquals(TokenUsage(124, 343, 0, 467), parseOpenAITokenUsage(Json.parseToJsonElement("""{"input_tokens":124,"output_tokens":343}""").jsonObject))
        assertEquals(TokenUsage(totalTokens = 467), parseOpenAITokenUsage(Json.parseToJsonElement("""{"total_tokens":467}""").jsonObject))
        assertEquals(TokenUsage(), parseOpenAITokenUsage(Json.parseToJsonElement("""{"input_tokens":0,"output_tokens":0,"total_tokens":0}""").jsonObject))
    }
}
