package me.rerere.ai.provider.providers.claude

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeUsageSnapshotsTest {
    private fun start(usage: String) = SseEvent(event = "message_start", data = """{"message":{"id":"msg","usage":$usage}}""")
    private fun delta(usage: String, stop: String? = null) = SseEvent(event = "message_delta", data =
        """{"usage":$usage,"delta":${if (stop == null) "{}" else """{"stop_reason":"$stop"}"""}}""")
    private fun stop() = SseEvent(event = "message_stop", data = "{}")
    private fun decode(vararg events: SseEvent) = ClaudeStreamDecoder().let { decoder -> events.flatMap { decoder.accept(it).chunks } }
    private fun handled(chunks: List<StreamChunk>, initial: List<UIMessage> = listOf(UIMessage.user("hello"))) =
        StreamChunkHandler().let { handler -> chunks.fold(initial) { messages, chunk -> handler.handle(messages, chunk) }.last() }

    @Test fun `native partial input updates retain cache partitions from message start`() {
        val chunks = decode(
            start("""{"input_tokens":100,"cache_read_input_tokens":200,"cache_creation_input_tokens":50,"output_tokens":0}"""),
            delta("""{"input_tokens":150,"output_tokens":20}"""),
        )
        assertTrue(chunks.filterIsInstance<StreamChunk.Usage>().all { it.replace })
        assertEquals(TokenUsage(promptTokens = 400, completionTokens = 20, cachedTokens = 200, totalTokens = 420), handled(chunks).usage)
    }

    @Test fun `cache reads and output can explicitly become zero without losing untouched cache writes`() {
        val chunks = decode(
            start("""{"input_tokens":100,"cache_read_input_tokens":200,"cache_creation_input_tokens":50,"output_tokens":10}"""),
            delta("""{"cache_read_input_tokens":0,"output_tokens":0}"""),
        )
        assertEquals(TokenUsage(promptTokens = 150, completionTokens = 0, cachedTokens = 0, totalTokens = 150), handled(chunks).usage)
    }

    @Test fun `missing and null partitions retain previous values independently`() {
        val chunks = decode(
            start("""{"input_tokens":100,"cache_read_input_tokens":20,"cache_creation_input_tokens":10,"output_tokens":5}"""),
            delta("""{"input_tokens":null,"cache_read_input_tokens":null,"output_tokens":8}"""),
            delta("""{"cache_creation_input_tokens":30}"""),
        )
        assertEquals(TokenUsage(promptTokens = 150, completionTokens = 8, cachedTokens = 20, totalTokens = 158), handled(chunks).usage)
    }

    @Test fun `a complete explicit zero snapshot clears all earlier estimates in this request`() {
        val chunks = decode(
            start("""{"input_tokens":100,"cache_read_input_tokens":20,"cache_creation_input_tokens":10,"output_tokens":5}"""),
            delta("""{"input_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":0}"""),
        )
        assertEquals(TokenUsage(), handled(chunks).usage)
    }

    @Test fun `separate tool loop requests reset raw counters and keep prior billed usage once`() {
        val first = handled(decode(
            start("""{"input_tokens":100,"cache_read_input_tokens":200,"cache_creation_input_tokens":50,"output_tokens":10}"""), stop(),
        ))
        val second = handled(decode(start("""{"input_tokens":80,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":0}"""), stop()), listOf(first))
        assertEquals(TokenUsage(promptTokens = 430, completionTokens = 10, cachedTokens = 200, totalTokens = 440), second.usage)
        assertEquals(TokenUsage(promptTokens = 80, completionTokens = 0, cachedTokens = 0, totalTokens = 80), second.lastRequestUsage)
    }

    @Test fun `pause turn emits full snapshots and permits final cache and output corrections to zero`() = runBlocking {
        val passes = listOf(
            decode(
                start("""{"input_tokens":100,"cache_read_input_tokens":200,"cache_creation_input_tokens":50,"output_tokens":10}"""),
                delta("""{"input_tokens":150,"output_tokens":20}""", "pause_turn"), stop(),
            ),
            decode(
                start("""{"input_tokens":80,"cache_read_input_tokens":40,"cache_creation_input_tokens":0,"output_tokens":5}"""),
                delta("""{"cache_read_input_tokens":0,"output_tokens":0}""", "end_turn"), stop(),
            ),
        )
        var request = 0
        val chunks = streamClaudeWithPauseTurn(listOf(UIMessage.user("hello")), Model(modelId = "claude-test")) {
            flowOf(*passes[request++].toTypedArray())
        }.toList()
        val usage = chunks.filterIsInstance<StreamChunk.Usage>()
        assertEquals(2, request)
        assertTrue(usage.all { it.replace })
        assertEquals(TokenUsage(promptTokens = 480, completionTokens = 20, cachedTokens = 200, totalTokens = 500), handled(chunks).usage)
        assertEquals(TokenUsage(promptTokens = 80, completionTokens = 0, cachedTokens = 0, totalTokens = 80), usage.last().lastRequestUsage)
    }

    @Test fun `counter sums saturate without wrapping negative`() {
        val chunks = decode(start("""{"input_tokens":2147483647,"cache_read_input_tokens":100,"cache_creation_input_tokens":100,"output_tokens":100}"""))
        val usage = handled(chunks).usage!!
        assertEquals(Int.MAX_VALUE, usage.promptTokens)
        assertEquals(Int.MAX_VALUE, usage.totalTokens)
        assertEquals(100, usage.cachedTokens)
    }
}
