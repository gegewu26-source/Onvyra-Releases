package com.constx.onvyra.service

import com.constx.onvyra.data.model.MediaCreationParams
import org.junit.Assert.*
import org.junit.Test

class GifAnimationPlanTest {
    @Test fun `native animation supports nine and sixteen frames while storyboard stays bounded`() {
        for (frames in listOf(2, 9, 16)) {
            val plan = gifAnimationPlan(MediaCreationParams(outputFormat = "gif", frameCount = frames,
                frameDurationMs = 125), "gpt-image-2.6-flare", true)
            assertEquals("native", plan.mode)
            assertEquals(frames, plan.frames)
            assertEquals(130, plan.delayMs)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gifAnimationPlan(MediaCreationParams(outputFormat = "gif", frameCount = 9), "gpt-6-luna", false)
        }
    }
    @Test fun `only documented native gif model families select native automatically`() {
        val params = MediaCreationParams(outputFormat = "gif")
        assertEquals("native", gifAnimationPlan(params, "gpt-image-2.6-flare", true).mode)
        assertEquals("native", gifAnimationPlan(params, "gpt-image-2.6-sunburst", true).mode)
        for (model in listOf("gpt-image-2.5-flare", "gpt-image-2", "gateway-alias", "gpt-6.1-sol")) {
            assertEquals("storyboard", gifAnimationPlan(params, model, true).mode)
        }
        assertEquals("storyboard", gifAnimationPlan(params, "gpt-image-2.6-flare", false).mode)
    }

    @Test fun `explicit native override preserves endpoint choice without retry planning`() {
        val plan = gifAnimationPlan(MediaCreationParams(outputFormat = "gif", animationMode = "native", frameCount = 8),
            "custom-gif-model", true)
        assertEquals("native", plan.mode); assertEquals(8, plan.frames)
        assertThrows(IllegalArgumentException::class.java) {
            gifAnimationPlan(MediaCreationParams(outputFormat = "gif", animationMode = "native"), "chat-model", false)
        }
    }

    @Test fun `storyboard default is four frames in one output with explicit motion guidance`() {
        val plan = gifAnimationPlan(MediaCreationParams(outputFormat = "gif"), "gpt-6-luna", false)
        assertEquals(4, plan.frames); assertEquals(200, plan.delayMs)
        assertEquals(2, plan.columns); assertEquals(2, plan.rows)
        val prompt = plan.storyboardPrompt("A cat waves its paw")
        assertTrue(prompt.contains("ONE raster")); assertTrue(prompt.contains("4-frame"))
        assertTrue(prompt.contains("A cat waves its paw"))
        assertTrue(prompt.contains("Do not draw one large image"))
    }
}
