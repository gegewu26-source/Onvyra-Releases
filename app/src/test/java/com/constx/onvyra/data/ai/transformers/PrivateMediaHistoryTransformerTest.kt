package com.constx.onvyra.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaHistoryTransformerTest {
    private fun message(input: String, output: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
        UIMessagePart.Tool("call-1", "video_search", input, listOf(UIMessagePart.Text(output))),
    ))

    @Test fun closedAccessRedactsPreviousPrivateResultsWithoutChangingSavedHistoryOrToolIdentity() {
        val original = message("{\"title\":\"fixture\"}", "{\"catalog_source_id\":\"missav\",\"title\":\"fixture\"}")
        val result = redactPrivateMediaToolHistory(listOf(original), authorized = false).single()
        val tool = result.parts.single() as UIMessagePart.Tool
        assertEquals("call-1", tool.toolCallId)
        assertEquals("video_search", tool.toolName)
        assertEquals(original.id, result.id)
        assertEquals("{}", tool.input)
        assertFalse(tool.output.toString().contains("missav"))
        assertTrue(tool.output.toString().contains("unavailable"))
        assertTrue(original.parts.toString().contains("missav"))
    }

    @Test fun encodedPrivateUrlIsBlockedButOrdinaryResultsAndUserTextArePreserved() {
        val private = message("{\"url\":\"https://%6dissav-primary.private-media.invalid/cn/fixture\"}", "fixture")
        val ordinary = message("{\"title\":\"film\"}", "{\"original_url\":\"https://example.org/film\"}")
        val user = UIMessage.user("missav is a name I typed")
        val result = redactPrivateMediaToolHistory(listOf(private, ordinary, user), authorized = false)
        assertTrue(result[0].parts.toString().contains("unavailable"))
        assertEquals(ordinary, result[1])
        assertEquals(user, result[2])
    }

    @Test fun authorizedOwnerKeepsFullExistingToolContext() {
        val original = listOf(message("{}", "{\"catalog_source_id\":\"jable\"}"))
        assertSame(original, redactPrivateMediaToolHistory(original, authorized = true))
    }
}
