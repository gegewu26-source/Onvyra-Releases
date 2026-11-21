package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.accumulate
import me.rerere.ai.core.merge
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageUsageAggregationTest {
    @Test
    fun `snapshots from one request are merged and separate tool requests are added once`() {
        var messages = listOf(UIMessage.user("search"))
        val first = StreamChunkHandler()
        messages = first.handle(messages, StreamChunk.Usage(TokenUsage(promptTokens = 100, completionTokens = 2)))
        messages = first.handle(messages, StreamChunk.Usage(TokenUsage(promptTokens = 100, completionTokens = 10)))
        messages = first.handle(messages, StreamChunk.Finish())
        assertEquals(110, messages.last().usage?.totalTokens)

        val continuation = StreamChunkHandler()
        messages = continuation.handle(messages, StreamChunk.Usage(TokenUsage(promptTokens = 150)))
        messages = continuation.handle(messages, StreamChunk.Usage(TokenUsage(completionTokens = 5)))
        messages = continuation.handle(messages, StreamChunk.Usage(TokenUsage(promptTokens = 150, completionTokens = 20)))

        assertEquals(TokenUsage(promptTokens = 250, completionTokens = 30, totalTokens = 280), messages.last().usage)
        assertEquals(TokenUsage(promptTokens = 150, completionTokens = 20, totalTokens = 170), messages.last().lastRequestUsage)
    }

    @Test
    fun `non streaming requests preserve the original start and add usage`() {
        val startedAt = LocalDateTime(2026, 1, 1, 12, 0)
        val base = UIMessage.assistant("").copy(createdAt = startedAt)
        val first = TextGenerationResult(
            id = "request-1",
            model = "test",
            message = UIMessage.assistant("tool call"),
            usage = TokenUsage(promptTokens = 10, completionTokens = 5),
        )
        val second = first.copy(
            id = "request-2",
            message = UIMessage.assistant("answer"),
            usage = TokenUsage(promptTokens = 20, completionTokens = 8),
        )
        val messages = listOf(base).handleTextGenerationResult(first).handleTextGenerationResult(second)

        assertEquals(startedAt, messages.last().createdAt)
        assertEquals(TokenUsage(promptTokens = 30, completionTokens = 13, totalTokens = 43), messages.last().usage)
        assertEquals(second.usage, messages.last().lastRequestUsage)
    }

    @Test
    fun `missing usage stays missing rather than inventing zero usage`() {
        val result = TextGenerationResult(id = "response", model = "test", message = UIMessage.assistant("answer"))
        val message = listOf(UIMessage.assistant("")).handleTextGenerationResult(result).last()

        assertNull(message.usage)
        assertNull(message.lastRequestUsage)
    }

    @Test
    fun `provider totals are retained and large accumulated counts cannot overflow`() {
        var messages = listOf(UIMessage.user("hello"))
        messages = StreamChunkHandler().handle(messages,
            StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 18)),
        )
        assertEquals(18, messages.last().usage?.totalTokens)
        val summed = messages.last().usage.accumulate(TokenUsage(promptTokens = 20, completionTokens = 5, totalTokens = 30))
        assertEquals(48, summed?.totalTokens)

        val huge = TokenUsage(promptTokens = Int.MAX_VALUE, totalTokens = Int.MAX_VALUE)
            .accumulate(TokenUsage(promptTokens = 10, completionTokens = 5))
        assertEquals(Int.MAX_VALUE, huge?.promptTokens)
        assertEquals(Int.MAX_VALUE, huge?.totalTokens)
    }

    @Test
    fun `split provider subtotals cannot replace the combined input and output total`() {
        val combined = TokenUsage(promptTokens = 100, totalTokens = 100)
            .merge(TokenUsage(completionTokens = 20, totalTokens = 20))
        assertEquals(100, combined.promptTokens)
        assertEquals(20, combined.completionTokens)
        assertEquals(120, combined.totalTokens)
    }

    @Test
    fun `authoritative final usage can correct an earlier larger snapshot`() {
        val combined = TokenUsage(promptTokens = 17, completionTokens = 2544, totalTokens = 2561)
            .merge(TokenUsage(promptTokens = 17, completionTokens = 1352, totalTokens = 1369))
        assertEquals(1352, combined.completionTokens)
        assertEquals(1369, combined.totalTokens)
    }

    @Test
    fun `complete zero snapshots clear this request without erasing earlier requests`() {
        val previous = UIMessage.assistant("tool round").copy(usage = TokenUsage(100, 10, 60, 110))
        val handler = StreamChunkHandler()
        var messages = handler.handle(listOf(previous), StreamChunk.Usage(TokenUsage(50, 5, 20, 55), replace = true))
        messages = handler.handle(messages, StreamChunk.Usage(TokenUsage(50, 0, 0, 50), replace = true))
        assertEquals(TokenUsage(150, 10, 60, 160), messages.last().usage)
        assertEquals(TokenUsage(50, 0, 0, 50), messages.last().lastRequestUsage)
    }
}
