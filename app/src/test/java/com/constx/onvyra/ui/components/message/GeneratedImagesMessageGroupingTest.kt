package com.constx.onvyra.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class GeneratedImagesMessageGroupingTest {
    @Test fun generatedImagesAppearInChatBodyOutsideCollapsedThinkingSteps() {
        val image = UIMessagePart.Image("file:///fixture/one.png")
        val original = listOf(UIMessagePart.Reasoning("fixture reasoning"),
            UIMessagePart.Tool("call", "generate_media", "{}", listOf(UIMessagePart.Text("success"), image)),
            UIMessagePart.Text("Done"))
        val grouped = original.groupMessageParts()
        assertTrue(grouped[0] is MessagePartBlock.ThinkingBlock)
        assertEquals(listOf(image), (grouped[1] as MessagePartBlock.GeneratedImagesBlock).images)
        assertTrue(grouped[2] is MessagePartBlock.ContentBlock)
        assertEquals(3, original.size)
    }

    @Test fun pendingOrFailedGenerationKeepsItsNormalToolStatusAndUnrelatedToolsAreUntouched() {
        val pending = UIMessagePart.Tool("p", "generate_media", "{}")
        val failed = pending.copy(toolCallId = "f", output = listOf(UIMessagePart.Text("failed")))
        val other = pending.copy(toolCallId = "o", toolName = "screenshot", output = listOf(UIMessagePart.Image("file:///fixture/shot.png")))
        val grouped = listOf(pending, failed, other).groupMessageParts()
        assertEquals(1, grouped.size)
        assertEquals(3, (grouped.single() as MessagePartBlock.ThinkingBlock).steps.size)
    }
}
