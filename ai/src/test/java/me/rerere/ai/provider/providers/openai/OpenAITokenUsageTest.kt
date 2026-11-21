package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class OpenAITokenUsageTest {
    private val client = OkHttpClient()
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun nonstream(api: ChatCompletionsAPI, usage: JsonObject?): TokenUsage? {
        val method = api.javaClass.getDeclaredMethod("parseTokenUsage", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(api, usage) as TokenUsage?
    }

    private fun response(usage: JsonObject?) = buildJsonObject {
        put("output", buildJsonArray {})
        usage?.let { put("usage", it) }
    }

    private fun responseStream(usage: JsonObject?) = ResponseApiStreamDecoder().accept(SseEvent(
        data = buildJsonObject {
            put("type", "response.completed")
            put("response", response(usage))
        }.toString(),
    )).chunks.filterIsInstance<StreamChunk.Usage>()

    @Test fun `OpenAI DeepSeek and top level dialects agree in stream and nonstream usage`() {
        for (fields in listOf(
            """"prompt_tokens_details":{"cached_tokens":40}""",
            """"prompt_cache_hit_tokens":40,"prompt_cache_miss_tokens":60""",
            """"cached_tokens":40""",
        )) {
            val usage = obj("""{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,$fields}""")
            val expected = TokenUsage(100, 20, 40, 120)
            assertEquals(expected, nonstream(ChatCompletionsAPI(client, KeyRoulette.default()), usage))
            val streamed = ChatCompletionsStreamDecoder().accept(SseEvent(data = """{"choices":[],"usage":$usage}"""))
                .chunks.filterIsInstance<StreamChunk.Usage>().single().usage
            assertEquals(expected, streamed)
        }
    }

    @Test fun `reasoning details are already included and a primary explicit zero beats fallbacks`() {
        val usage = obj("""{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":0},"cached_tokens":40,"completion_tokens_details":{"reasoning_tokens":10}}""")
        assertEquals(TokenUsage(100, 20, 0, 120), nonstream(ChatCompletionsAPI(client, KeyRoulette.default()), usage))
        val streamed = ChatCompletionsStreamDecoder().accept(SseEvent(data = """{"choices":[],"usage":$usage}"""))
            .chunks.filterIsInstance<StreamChunk.Usage>().single().usage
        assertEquals(TokenUsage(100, 20, 0, 120), streamed)
    }

    @Test fun `Responses input includes cache and output already includes reasoning`() {
        val usage = obj("""{"input_tokens":100,"input_tokens_details":{"cached_tokens":80},"output_tokens":30,"output_tokens_details":{"reasoning_tokens":20},"total_tokens":130}""")
        val expected = TokenUsage(100, 30, 80, 130)
        assertEquals(expected, ResponseAPI(client).parseResponseOutput(response(usage)).usage)
        assertEquals(expected, responseStream(usage).single().usage)
    }

    @Test fun `absent usage is not turned into a zero usage record`() {
        assertNull(nonstream(ChatCompletionsAPI(client, KeyRoulette.default()), null))
        assertNull(ResponseAPI(client).parseResponseOutput(response(null)).usage)
        assertTrue(responseStream(null).isEmpty())
        assertTrue(ChatCompletionsStreamDecoder().accept(SseEvent(data="""{"choices":[]}"""))
            .chunks.none { it is StreamChunk.Usage })
    }
}
