package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GeneratedImageMimeTypeTest {
    @Test
    fun gifOutputRejectsStreamingAndMalformedFrameOptionsBeforeNetworkCalls() {
        validateGifImageParameters(buildJsonObject {
            put("output_format", "gif")
            put("stream", false)
            put("partial_images", 0)
            put("frame_count", 9)
            put("frame_duration_ms", 200)
        })
        for (extra in listOf(
            buildJsonObject { put("output_format", "gif"); put("stream", true) },
            buildJsonObject { put("output_format", "gif"); put("partial_images", "invalid") },
            buildJsonObject { put("output_format", "gif"); put("frame_count", "9") },
            buildJsonObject { put("output_format", "gif"); put("output_compression", 80) },
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateGifImageParameters(extra) }
        }
    }

    @Test
    fun gifHeaderOverridesMissingOrIncorrectProviderMime() {
        assertEquals("image/gif", generatedImageMimeType("R0lGODlhAQAB", null))
        assertEquals("image/gif", generatedImageMimeType("data:image/png;base64,R0lGODlhAQAB", "png"))
        assertEquals("image/gif", generatedImageMimeType("AAAA", "gif"))
        assertEquals("image/webp", generatedImageMimeType("AAAA", "webp"))
    }
}
