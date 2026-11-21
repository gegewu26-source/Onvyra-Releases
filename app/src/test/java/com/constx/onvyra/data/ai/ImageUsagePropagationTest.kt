package com.constx.onvyra.data.ai

import com.constx.onvyra.service.runChatImageTask
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.*
import me.rerere.mediagen.model.*
import org.junit.Assert.*
import org.junit.Test

class ImageUsagePropagationTest {
    private val reported = MediaGenerationUsage(totalTokens = 467, inputTokens = 124, outputTokens = 343, cachedTokens = 20)
    private val expected = TokenUsage(124, 343, 20, 467)
    private val output = MediaGenerationOutput(data = byteArrayOf(1), mimeType = "image/png")
    private fun task(id: String, usage: MediaGenerationUsage? = reported) = MediaGenerationTask(
        id, "fixture", "selected-model", MediaGenerationStatus.SUCCEEDED, listOf(output, output), usage = usage)
    private suspend fun imageParts(id: String) = runChatImageTask(create = { task(id) },
        query = { error("No poll") }, save = { UIMessagePart.Image("file:///fixture.png") })

    @Test fun completeUsageReachesNestedNativeAndFallbackResultsWithoutCountingEachImage() = runBlocking {
        val parts = imageParts("request-1")
        assertEquals(expected, parts.generatedImageTokenUsage())
        assertEquals(expected, listOf(UIMessagePart.Tool("tool-1", "generate_native_image", "{}", output = parts)).generatedImageTokenUsage())
        assertEquals(expected, listOf(UIMessagePart.Tool("tool-2", "generate_media", "{}", output = parts + parts)).generatedImageTokenUsage())
        assertNull(listOf(UIMessagePart.Tool("capture", "screenshot", "{}", output = parts)).generatedImageTokenUsage())
        val unknown = runChatImageTask(create = { task("unknown", null) }, query = { error("No poll") },
            save = { UIMessagePart.Image("file:///fixture.png") })
        assertNull(unknown.generatedImageTokenUsage())
    }

    @Test fun ordinaryToolContinuationAccumulatesIndependentRequestsOnceAndRetainsContextUsage() = runBlocking {
        val chat = TokenUsage(12, 3, 0, 15)
        val pending = UIMessagePart.Tool("call-1", "generate_media", "{}")
        val message = UIMessage.assistant("").copy(parts = listOf(pending), usage = chat, lastRequestUsage = chat)
        val first = message.withImageToolResults(listOf(pending.copy(output = imageParts("image-1"))))
        assertEquals(TokenUsage(136, 346, 20, 482), first.usage)
        assertEquals(chat, first.lastRequestUsage)
        assertEquals(first.usage, first.withImageToolResults(first.parts).usage)
        val second = first.withImageToolResults(first.parts + pending.copy(toolCallId = "call-2", output = imageParts("image-2")))
        assertEquals(TokenUsage(260, 689, 40, 949), second.usage)
        val handler = StreamChunkHandler()
        val next = handler.handle(listOf(second), StreamChunk.Usage(TokenUsage(10, 2, 0, 12))).last()
        assertEquals(TokenUsage(270, 691, 40, 961), next.usage)
        assertEquals(TokenUsage(10, 2, 0, 12), next.lastRequestUsage)
    }

    @Test fun explicitNativeRequestProgressReadsToolOutputsAfterFallbackAndNeverAddsSnapshotsTwice() = runBlocking {
        val parts = imageParts("fallback")
        val native = me.rerere.ai.core.Tool("generate_native_image", "fixture", execute = { emptyList() })
        val fallback = me.rerere.ai.core.Tool("generate_media", "fixture", execute = { parts })
        val updates = mutableListOf<TokenUsage?>()
        executeNativeImageRequest("cat", native, fallback) { updates += it.generatedImageTokenUsage() }
        assertEquals(expected, updates.last())
        assertEquals(1, updates.count { it != null })
    }
}
