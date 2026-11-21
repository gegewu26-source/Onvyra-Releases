package me.rerere.ai.provider.providers.openai

import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ChatCompletionsContentParsingTest {
    @Test fun `nonstream keeps all ordinary text blocks and separates thinking`() {
        val message = parseMessage("""{"role":"assistant","content":[
            {"type":"thinking","thinking":[{"type":"text","text":"check A"},{"type":"text","text":" and B"}]},
            {"type":"text","text":"first "},{"type":"output_text","text":"second"},
            {"type":"unknown","text":"not visible text"}]}
        """)
        assertEquals("first second", message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
        assertEquals("check A and B", message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
    }

    @Test fun `explicit reasoning field takes priority over embedded thinking`() {
        val message = parseMessage("""{"content":[{"type":"thinking","thinking":[{"text":"duplicate"}]},{"type":"text","text":"answer"}],"reasoning_content":"original"}""")
        assertEquals("original", message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test fun `several embedded thinking blocks are retained`() {
        val message = parseMessage("""{"content":[{"type":"thinking","thinking":[{"text":"A"}]},{"type":"thinking","thinking":"B"},{"type":"text","text":"C"}]}""")
        assertEquals("AB", message.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
    }

    @Test fun `stream keeps array text blocks without showing thinking as ordinary text`() {
        val decoder = ChatCompletionsStreamDecoder()
        val result = decoder.accept(SseEvent(data = """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"text":"private analysis"}]},{"type":"text","text":"hello "},{"type":"text","text":"world"}]}}]}"""))
        assertEquals("hello world", result.chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals("private analysis", result.chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text })
        assertEquals(1, decoder.accept(SseEvent(data = "[DONE]")).chunks.count { it is StreamChunk.Finish })
    }

    @Test fun `pretty printed multiline SSE data is one JSON event`() {
        val decoder = ChatCompletionsStreamDecoder()
        val result = decoder.accept(SseEvent(data = """{
            "id":"multi",
            "choices":[{"delta":{"content":"complete"},"finish_reason":"stop"}]
        }"""))
        assertEquals("complete", result.chunks.filterIsInstance<StreamChunk.TextDelta>().single().text)
        assertEquals("stop", decoder.onClosed().filterIsInstance<StreamChunk.Finish>().single().finishReason)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test fun `legacy newline batched JSON chunks remain supported`() {
        val decoder = ChatCompletionsStreamDecoder()
        val result = decoder.accept(SseEvent(data = """{"choices":[{"delta":{"content":"A"}}]}
{"choices":[{"delta":{"content":"B"},"finish_reason":"stop"}]}"""))
        assertEquals("AB", result.chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertEquals(1, decoder.onClosed().count { it is StreamChunk.Finish })
    }

    @Test fun `EOF without a completion marker reports a truncated response`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"choices":[{"delta":{"content":"partial"}}]}"""))
        assertThrows(IOException::class.java) { decoder.onClosed() }
    }

    @Test fun `finish reason accepts EOF even when a gateway omits DONE`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""))
        assertEquals("tool_calls", decoder.onClosed().filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test fun `DONE alone completes once and tolerates surrounding whitespace`() {
        val decoder = ChatCompletionsStreamDecoder()
        assertTrue(decoder.accept(SseEvent(data = "  \r\n ")).chunks.isEmpty())
        val result = decoder.accept(SseEvent(data = " [DONE]\r\n"))
        assertTrue(result.completed)
        assertEquals(1, result.chunks.count { it is StreamChunk.Finish })
        assertTrue(decoder.onClosed().isEmpty())
        assertTrue(decoder.accept(SseEvent(data = "[DONE]")).chunks.isEmpty())
    }

    @Test fun `usage after finish reason is retained before DONE`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(data = """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val usage = decoder.accept(SseEvent(data = """{"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}"""))
        assertEquals(5, usage.chunks.filterIsInstance<StreamChunk.Usage>().single().usage.totalTokens)
        assertEquals(1, decoder.accept(SseEvent(data = "[DONE]")).chunks.count { it is StreamChunk.Finish })
    }

    private fun parseMessage(raw: String): UIMessage {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(api, json.parseToJsonElement(raw).jsonObject) as UIMessage
    }
}
