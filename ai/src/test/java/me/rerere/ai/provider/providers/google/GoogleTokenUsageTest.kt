package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleTokenUsageTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private val full = obj("""{"promptTokenCount":100,"toolUsePromptTokenCount":30,"cachedContentTokenCount":80,"candidatesTokenCount":20,"thoughtsTokenCount":10,"totalTokenCount":160}""")

    @Test fun `tool use prompts are input while cached content remains a subset`() {
        assertEquals(TokenUsage(promptTokens=130, completionTokens=30, cachedTokens=80, totalTokens=160), parseGoogleUsageMetadata(full))
    }

    @Test fun `ordinary prompts do not count cache twice and missing metadata stays absent`() {
        assertNull(parseGoogleUsageMetadata(null))
        assertEquals(TokenUsage(promptTokens=100, completionTokens=20, cachedTokens=80, totalTokens=120),
            parseGoogleUsageMetadata(obj("""{"promptTokenCount":100,"cachedContentTokenCount":80,"candidatesTokenCount":20,"totalTokenCount":120}""")))
    }

    @Test fun `streaming and nonstreaming parse the same counters`() {
        val method = GoogleProvider::class.java.getDeclaredMethod("parseUsageMeta", JsonObject::class.java)
        method.isAccessible = true
        val nonstream = method.invoke(GoogleProvider(OkHttpClient()), full)
        val streamed = GoogleStreamDecoder("request", "gemini").accept(SseEvent(data="""{"usageMetadata":$full}"""))
            .chunks.filterIsInstance<StreamChunk.Usage>().single().usage
        assertEquals(parseGoogleUsageMetadata(full), nonstream)
        assertEquals(nonstream, streamed)
    }

    @Test fun `cumulative stream snapshots do not add the same tool prompt repeatedly`() {
        val decoder = GoogleStreamDecoder("request", "gemini")
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("question"))
        for (usage in listOf(obj("""{"promptTokenCount":100,"toolUsePromptTokenCount":30,"cachedContentTokenCount":80,"candidatesTokenCount":2,"totalTokenCount":132}"""), full)) {
            for (chunk in decoder.accept(SseEvent(data="""{"usageMetadata":$usage}""")).chunks) messages = handler.handle(messages, chunk)
        }
        assertEquals(TokenUsage(promptTokens=130, completionTokens=30, cachedTokens=80, totalTokens=160), messages.last().usage)
    }

    @Test fun `reported provider totals are preserved and absent totals are calculated`() {
        assertEquals(777, parseGoogleUsageMetadata(obj("""{"promptTokenCount":100,"toolUsePromptTokenCount":30,"totalTokenCount":777}"""))?.totalTokens)
        assertEquals(160, parseGoogleUsageMetadata(obj("""{"promptTokenCount":100,"toolUsePromptTokenCount":30,"candidatesTokenCount":20,"thoughtsTokenCount":10}"""))?.totalTokens)
    }

    @Test fun `large counters saturate without wrapping input output or totals negative`() {
        val usage = parseGoogleUsageMetadata(obj("""{"promptTokenCount":2147483647,"toolUsePromptTokenCount":100,"candidatesTokenCount":2147483647,"thoughtsTokenCount":100,"totalTokenCount":4294967294,"cachedContentTokenCount":2147483647}"""))!!
        assertEquals(Int.MAX_VALUE, usage.promptTokens)
        assertEquals(Int.MAX_VALUE, usage.completionTokens)
        assertEquals(Int.MAX_VALUE, usage.totalTokens)
        assertEquals(Int.MAX_VALUE, usage.cachedTokens)
    }

    @Test fun `negative and malformed counters cannot corrupt bucket sums`() {
        assertEquals(TokenUsage(), parseGoogleUsageMetadata(obj("""{"promptTokenCount":-1,"toolUsePromptTokenCount":{},"cachedContentTokenCount":-5,"candidatesTokenCount":null,"thoughtsTokenCount":"invalid"}""")))
    }
}
