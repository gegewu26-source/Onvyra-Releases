package com.constx.onvyra.data.ai.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImageAnimationToolParametersTest {
    @Test fun `gif arguments retain requested frame count timing and mode`() {
        val params = Json.parseToJsonElement("""{"frame_count":8,"frame_duration_ms":125,"animation_mode":"storyboard"}""").jsonObject.animationParams("gif")
        assertEquals(8, params.frameCount); assertEquals(125, params.frameDurationMs)
        assertEquals("storyboard", params.animationMode); assertEquals("gif", params.outputFormat)
    }

    @Test fun `bad or non gif animation arguments are rejected before calling a model`() {
        for (value in listOf("""{"frame_count":"4"}""", """{"frame_count":40}""", """{"frame_duration_ms":1}""", """{"animation_mode":"fake"}""")) {
            assertThrows(IllegalArgumentException::class.java) { Json.parseToJsonElement(value).jsonObject.animationParams("gif") }
        }
        assertThrows(IllegalArgumentException::class.java) { buildJsonObject { put("frame_count", 4) }.animationParams("png") }
    }
}
