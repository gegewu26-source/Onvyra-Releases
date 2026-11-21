package com.constx.onvyra.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ChatGeneratedImageHistoryTransformerTest {
    @Test fun textModelReceivesSuccessTextWhileSavedChatKeepsGeneratedImageFiles() {
        val tool = UIMessagePart.Tool("call", "generate_media", "{\"kind\":\"image\"}", listOf(
            UIMessagePart.Text("{\"status\":\"succeeded\",\"output_count\":1}"), UIMessagePart.Image("file:///fixture/image.gif")))
        val original = UIMessage.assistant("").copy(parts = listOf(tool))
        val transformed = chatGeneratedImageRequestHistory(listOf(original)).single()
        assertEquals(original.id, transformed.id)
        val next = transformed.parts.single() as UIMessagePart.Tool
        assertEquals(tool.input, next.input)
        assertFalse(next.output.any { it is UIMessagePart.Image })
        assertTrue(next.output.toString().contains("succeeded"))
        assertTrue(tool.output.any { it is UIMessagePart.Image })
    }
}
