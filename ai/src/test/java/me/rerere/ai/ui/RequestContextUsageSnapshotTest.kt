package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import me.rerere.ai.core.RequestContextUsageSnapshot
import me.rerere.ai.core.RequestContextUsageReference
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.*
import org.junit.Test

class RequestContextUsageSnapshotTest {
    private val snapshot = RequestContextUsageSnapshot(systemTools = 100, messages = 200,
        mcpTools = 300, skills = 40, other = 50, systemPrompt = 60,
        preparedMessageCount = 4, sourceMessageCount = 3, responsePrefixTokens = 25)

    @Test fun `messages saved before context accounting decode without a snapshot`() {
        val legacy = """{"role":"assistant","parts":[],"lastRequestUsage":{"promptTokens":10}}"""
        val result = Json.decodeFromString<UIMessage>(legacy)
        assertNull(result.requestContextUsage)
        assertEquals(10, result.lastRequestUsage?.promptTokens)
    }

    @Test fun `category count snapshot survives persistence independently of cumulative usage`() {
        val message = UIMessage.assistant("fixture").copy(requestContextUsage = snapshot,
            usage = TokenUsage(promptTokens = 100_000), lastRequestUsage = TokenUsage(promptTokens = 1200))
        val encoded = Json.encodeToString(message)
        val result = Json.decodeFromString<UIMessage>(encoded)

        assertEquals(snapshot, result.requestContextUsage)
        assertEquals(750L, result.requestContextUsage?.estimatedInputTokens)
        assertEquals(100_000, result.usage?.promptTokens)
        assertEquals(1200, result.lastRequestUsage?.promptTokens)
        assertFalse(encoded.contains("estimatedInputTokens"))
    }

    @Test fun `confirmed reference and checkpoint persist without inflating local counts`() {
        val value = snapshot.copy(previousConfirmedContextTokens = 98_851, requestCheckpointId = "checkpoint-fixture",
            mcpDeclarationKey = "a".repeat(64))
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(
            UIMessage.assistant("fixture").copy(requestContextUsage = value)))
        assertEquals(value, restored.requestContextUsage)
        assertEquals(750L, restored.requestContextUsage?.estimatedInputTokens)
        val legacy = Json.decodeFromString<RequestContextUsageSnapshot>("""{"messages":100}""")
        assertNull(legacy.previousConfirmedContextTokens)
        assertNull(legacy.requestCheckpointId)
        assertNull(legacy.mcpDeclarationKey)
        assertNull(legacy.previousConfirmedContextUsage)
    }

    @Test fun `complete confirmed reference persists as one flat numeric record`() {
        val reference = RequestContextUsageReference(promptTokens = 124_000, completionTokens = 314,
            systemTools = 100, messages = 200, mcpTools = 300, skills = 40, other = 50, systemPrompt = 60,
            requestModelId = "fixture-model", requestCheckpointId = "fixture-checkpoint", cacheHitRate = 0.6671)
        val value = snapshot.copy(previousConfirmedContextUsage = reference)
        val encoded = Json.encodeToString(value)
        val restored = Json.decodeFromString<RequestContextUsageSnapshot>(encoded)
        assertEquals(reference, restored.previousConfirmedContextUsage)
        assertEquals(124_314L, restored.previousConfirmedContextUsage?.contextTokens)
        assertEquals(750L, restored.estimatedInputTokens)
        assertEquals(1, Regex("previousConfirmedContextUsage").findAll(encoded).count())
        assertFalse(encoded.contains("contextTokens"))
        assertTrue(encoded.length < 1000)
    }

    @Test fun `stream events preserve the current request snapshot while updating actual input usage`() {
        val base = UIMessage.assistant("").copy(requestContextUsage = snapshot)
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("hello"), base)
        messages = handler.handle(messages, StreamChunk.TextStart("text"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text", "answer"))
        messages = handler.handle(messages, StreamChunk.Usage(TokenUsage(promptTokens = 1000, completionTokens = 10)))
        messages = handler.handle(messages, StreamChunk.Finish())

        assertEquals(snapshot, messages.last().requestContextUsage)
        assertEquals(1000, messages.last().lastRequestUsage?.promptTokens)
    }

    @Test fun `nonstream result preserves the prepared snapshot and returns provider usage separately`() {
        val base = UIMessage.assistant("").copy(requestContextUsage = snapshot)
        val reply = TextGenerationResult(id = "fixture", model = "fixture-model",
            message = UIMessage.assistant("answer"), usage = TokenUsage(promptTokens = 1000, completionTokens = 10))
        val result = listOf(UIMessage.user("hello"), base).handleTextGenerationResult(reply).last()

        assertEquals(snapshot, result.requestContextUsage)
        assertEquals(1000, result.lastRequestUsage?.promptTokens)
        assertEquals("answer", result.toText())
    }
}
