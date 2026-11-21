package com.constx.onvyra.data.ai.compression

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextCompressionRequestsTest {
    @Test fun `valid complete nonstream response has no retry`() = runTest {
        val summary = requestCompressionText(
            generate = { TextGenerationResult("id", "model", UIMessage.assistant("saved facts"), "completed") },
            stream = { error("Streaming should not be used after a successful summary") },
        )
        assertEquals("saved facts", summary)
    }

    @Test fun `upstream EOF falls back to a completed text stream`() = runTest {
        var calls = 0
        val summary = requestCompressionText(
            generate = { throw IllegalStateException("Failed to get response: 500 upstream EOF") },
            stream = {
                calls++
                flowOf(StreamChunk.TextDelta("text", "saved facts"), StreamChunk.Finish("STOP"))
            },
        )
        assertEquals("saved facts", summary)
        assertEquals(1, calls)
    }

    @Test fun `a truncated fallback cannot be committed as a summary`() = runTest {
        val upstream = IllegalStateException("Failed to get response: 500 upstream EOF")
        val failure = runCatching {
            requestCompressionText(
                generate = { throw upstream },
                stream = { flowOf(StreamChunk.TextDelta("text", "incomplete"), StreamChunk.Finish()) },
            )
        }.exceptionOrNull()
        assertEquals(upstream.message, failure?.message)
        assertEquals("压缩接口未返回完整的流式摘要", failure?.suppressed?.singleOrNull()?.message)
    }

    @Test fun `cancellation and quota failures do not retry`() = runTest {
        for (failure in listOf(CancellationException("user stopped"), IllegalStateException("HTTP 429 quota"))) {
            val caught = runCatching {
                requestCompressionText(
                    generate = { throw failure },
                    stream = { error("Cancellation or quota must not retry") },
                )
            }.exceptionOrNull()
            assertEquals(failure.javaClass, caught?.javaClass)
            assertEquals(failure.message, caught?.message)
            assertEquals(0, caught?.suppressed?.size)
        }
    }
}
