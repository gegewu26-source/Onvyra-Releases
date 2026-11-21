package com.constx.onvyra.ui.components.message

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageGenerationStatsTest {
    private val start = LocalDateTime(2026, 1, 1, 12, 0, 0)
    private val later = LocalDateTime(2026, 1, 1, 12, 0, 10)

    @Test
    fun `duration is shown even when provider does not report usage`() {
        val message = UIMessage.assistant("answer").copy(createdAt = start, finishedAt = later)
        val stats = message.generationStats(now = later, loading = false)
        assertEquals(10_000L, stats.elapsedMillis)
        assertNull(stats.totalTokens)
        assertNull(stats.averageTokensPerSecond)
        assertTrue(stats.showUsageNotice)
    }

    @Test
    fun `active tool continuation ticks past the preceding model response`() {
        val message = UIMessage.assistant("tool call").copy(createdAt = start, finishedAt = start)
        val stats = message.generationStats(now = later, loading = true)
        assertEquals(10_000L, stats.elapsedMillis)
        assertNull(stats.averageTokensPerSecond)
    }

    @Test
    fun `average speed includes the whole turn and fallback total sums input and output`() {
        val message = UIMessage.assistant("answer").copy(
            createdAt = start,
            finishedAt = later,
            usage = TokenUsage(promptTokens = 100, completionTokens = 50),
        )
        val stats = message.generationStats(now = later, loading = false)
        assertEquals(150L, stats.totalTokens)
        assertEquals(5.0, stats.averageTokensPerSecond!!, 0.001)
    }

    @Test
    fun `missing zero and backwards duration never produce invalid speeds`() {
        val message = UIMessage.assistant("answer").copy(
            createdAt = later,
            usage = TokenUsage(completionTokens = 50),
        )
        assertNull(message.generationStats(now = later, loading = false).averageTokensPerSecond)
        assertNull(message.copy(finishedAt = later).generationStats(now = later, loading = false).averageTokensPerSecond)
        val backwards = message.copy(finishedAt = start).generationStats(now = later, loading = false)
        assertNull(backwards.elapsedMillis)
        assertNull(backwards.averageTokensPerSecond)
    }

    @Test
    fun `completed native and delegated images show time without a missing usage notice`() {
        val image = UIMessagePart.Image("file:///generated-cat.png")
        for (parts in listOf(
            listOf(image),
            listOf(UIMessagePart.Tool("native", "generate_native_image", "{}", output = listOf(image))),
            listOf(UIMessagePart.Tool("fallback", "generate_media", "{}", output = listOf(image))),
        )) {
            val message = UIMessage.assistant("").copy(parts = parts, createdAt = start, finishedAt = later)
            val stats = message.generationStats(now = later, loading = false)
            assertFalse(stats.showUsageNotice)
            assertEquals(10_000L, stats.elapsedMillis)
            assertNull(stats.totalTokens)
            assertNull(stats.averageTokensPerSecond)
            assertTrue(message.generationStats(now = later, loading = true).showUsageNotice)
        }
    }

    @Test
    fun `failed image calls and screenshots retain the ordinary missing usage notice`() {
        for (parts in listOf(
            listOf(UIMessagePart.Image("data:image/png;base64,")),
            listOf(UIMessagePart.Tool("failed", "generate_native_image", "{}", output = listOf(UIMessagePart.Text("failed")))),
            listOf(UIMessagePart.Tool("capture", "screenshot", "{}", output = listOf(UIMessagePart.Image("file:///screen.png")))),
        )) {
            val message = UIMessage.assistant("").copy(parts = parts, createdAt = start, finishedAt = later)
            assertTrue(message.generationStats(now = later, loading = false).showUsageNotice)
        }
    }

    @Test
    fun `image generation retains provider reported usage`() {
        val message = UIMessage.assistant("").copy(
            parts = listOf(UIMessagePart.Image("file:///generated-cat.png")),
            createdAt = start, finishedAt = later,
            usage = TokenUsage(promptTokens = 100, completionTokens = 50),
        )
        val stats = message.generationStats(now = later, loading = false)
        assertEquals(150L, stats.totalTokens)
        assertEquals(5.0, stats.averageTokensPerSecond!!, 0.001)
        assertFalse(stats.showUsageNotice)
    }
}
