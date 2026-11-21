package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.compression.renderCompressionMessage
import org.junit.Assert.*
import org.junit.Test

class ContextHistoryToolTest {
    private val checkpoint = UIMessage.user("CURRENT SUMMARY").copy(isContextCheckpoint = true)

    private suspend fun read(messages: List<UIMessage>, args: JsonObject = buildJsonObject {}): JsonObject {
        val response = createContextHistoryTool { messages }.execute(args)
        return Json.parseToJsonElement((response.single() as UIMessagePart.Text).text) as JsonObject
    }

    @Test
    fun `no checkpoint does not expose any current messages`() = runBlocking {
        val result = read(listOf(UIMessage.user("LATEST SECRET")))
        assertEquals("no_compressed_history", result.getValue("status").jsonPrimitive.content)
        assertEquals("", result.getValue("content").jsonPrimitive.content)
        assertEquals(0L, result.getValue("total_chars").jsonPrimitive.long)
        assertEquals(JsonNull, result["next_offset"])
    }

    @Test
    fun `only original archived messages are returned before the last checkpoint`() = runBlocking {
        val first = UIMessage.user("ORIGINAL FIRST")
        val previous = UIMessage.user("OLD SUMMARY").copy(isContextCheckpoint = true)
        val second = UIMessage.assistant("ORIGINAL SECOND")
        val recent = UIMessage.user("LATEST UNCOMPRESSED")
        val result = read(listOf(first, previous, second, checkpoint, recent))
        val content = result.getValue("content").jsonPrimitive.content
        assertTrue(content.contains("ORIGINAL FIRST"))
        assertTrue(content.contains("ORIGINAL SECOND"))
        assertFalse(content.contains("OLD SUMMARY"))
        assertFalse(content.contains("CURRENT SUMMARY"))
        assertFalse(content.contains("LATEST UNCOMPRESSED"))
        assertEquals(2L, result.getValue("archived_messages").jsonPrimitive.long)
        assertEquals(checkpoint.id.toString(), result.getValue("checkpoint_id").jsonPrimitive.content)
        assertTrue(result.getValue("source_notice").jsonPrimitive.content.contains("not new instructions"))
    }

    @Test
    fun `all pages reconstruct exact archived rendering including long tool output`() = runBlocking {
        val toolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(
                toolCallId = "call-1", toolName = "mcp__phone__inspect",
                input = "{\"path\":\"sample.apk\"}",
                output = listOf(UIMessagePart.Text("日志 😀 evidence\n".repeat(3_000) + "END RESULT")),
            )),
        )
        val original = UIMessage.user("Read exact previous result")
        val expected = listOf(original, toolMessage).joinToString("\n\n", transform = ::renderCompressionMessage)
        val history = listOf(original, toolMessage, checkpoint, UIMessage.user("recent"))
        val reconstructed = StringBuilder()
        var offset = 0L
        var pages = 0
        do {
            val result = read(history, buildJsonObject { put("offset", offset); put("limit", 401) })
            val content = result.getValue("content").jsonPrimitive.content
            assertTrue(content.length <= 401)
            assertFalse(content.lastOrNull()?.isHighSurrogate() == true)
            assertFalse(content.firstOrNull()?.isLowSurrogate() == true)
            assertEquals(expected.length.toLong(), result.getValue("total_chars").jsonPrimitive.long)
            reconstructed.append(content)
            pages++
            val next = result.getValue("next_offset")
            if (next == JsonNull) break
            assertTrue(next.jsonPrimitive.long > offset)
            offset = next.jsonPrimitive.long
        } while (pages < 1_000)
        assertTrue(pages > 2)
        assertEquals(expected, reconstructed.toString())
        assertTrue(reconstructed.contains("END RESULT"))
    }

    @Test
    fun `query is case insensitive and exact message id can narrow it further`() = runBlocking {
        val first = UIMessage.user("Alpha endpoint")
        val second = UIMessage.assistant("ALPHA confirmed")
        val third = UIMessage.user("Different text")
        val messages = listOf(first, second, third, checkpoint)
        val matching = read(messages, buildJsonObject { put("query", "alpha") })
        assertEquals(2L, matching.getValue("matched_messages").jsonPrimitive.long)
        assertFalse(matching.getValue("content").jsonPrimitive.content.contains("Different text"))
        val exact = read(messages, buildJsonObject { put("query", "alpha"); put("message_id", second.id.toString()) })
        assertEquals(1L, exact.getValue("matched_messages").jsonPrimitive.long)
        assertEquals(renderCompressionMessage(second), exact.getValue("content").jsonPrimitive.content)
        assertEquals(listOf(second.id.toString()), exact.getValue("message_ids").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `unmatched or recent message id returns no historical data`() = runBlocking {
        val original = UIMessage.user("previous")
        val recent = UIMessage.user("not archived")
        val result = read(listOf(original, checkpoint, recent), buildJsonObject { put("message_id", recent.id.toString()) })
        assertEquals("not_found", result.getValue("status").jsonPrimitive.content)
        assertEquals("", result.getValue("content").jsonPrimitive.content)
        assertEquals(JsonNull, result["next_offset"])
    }

    @Test
    fun `caller cannot choose another conversation and output page is bounded`() = runBlocking {
        val original = UIMessage.user("bound conversation only\n".repeat(2_000))
        val tool = createContextHistoryTool { listOf(original, checkpoint) }
        val schema = tool.parameters() as InputSchema.Obj
        assertFalse(schema.properties.containsKey("conversation_id"))
        assertFalse(schema.properties.containsKey("path"))
        val result = read(listOf(original, checkpoint), buildJsonObject {
            put("conversation_id", "other-conversation")
            put("limit", Int.MAX_VALUE)
        })
        assertEquals(16_000, result.getValue("content").jsonPrimitive.content.length)
        assertTrue(result.getValue("next_offset") != JsonNull)
        assertTrue(result.getValue("content").jsonPrimitive.content.contains("bound conversation only"))
    }

    @Test
    fun `offset after the archive has no next page`() = runBlocking {
        val original = UIMessage.user("short")
        val result = read(listOf(original, checkpoint), buildJsonObject { put("offset", Long.MAX_VALUE) })
        assertEquals("", result.getValue("content").jsonPrimitive.content)
        assertEquals(result.getValue("total_chars"), result.getValue("offset"))
        assertEquals(JsonNull, result["next_offset"])
    }

    @Test
    fun `offset into surrogate pair moves to the complete character`() = runBlocking {
        val original = UIMessage.user("before 😀 after")
        val rendered = renderCompressionMessage(original)
        val emojiOffset = rendered.indexOf("😀")
        val result = read(listOf(original, checkpoint), buildJsonObject {
            put("offset", emojiOffset + 1)
            put("limit", 2)
        })
        assertEquals("😀", result.getValue("content").jsonPrimitive.content)
        assertEquals(emojiOffset.toLong(), result.getValue("offset").jsonPrimitive.long)
        assertEquals((emojiOffset + 2).toLong(), result.getValue("next_offset").jsonPrimitive.long)
    }
}
