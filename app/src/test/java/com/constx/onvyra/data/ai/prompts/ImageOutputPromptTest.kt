package com.constx.onvyra.data.ai.prompts

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOutputPromptTest {
    @Test
    fun `ordinary text chat receives no capability prompt`() {
        assertEquals("", buildImageOutputPrompt(Model()))
    }

    @Test
    fun `model names never enable the capability prompt without configured image output`() {
        for (modelId in listOf("gpt6luna", "gemini3.8", "gemini-3-pro-image")) {
            val model = Model(
                modelId = modelId,
                outputModalities = listOf(Modality.TEXT),
            )

            assertEquals("", buildImageOutputPrompt(model))
        }
    }

    @Test
    fun `configured image output describes client support without promising provider success`() {
        val prompt = buildImageOutputPrompt(
            Model(
                modelId = "custom-provider-alias",
                outputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            )
        )

        assertTrue(prompt.contains("can display images returned by the provider"))
        assertTrue(prompt.contains("capabilities actually available in this session"))
        assertTrue(prompt.contains("If the provider lacks that capability or generation fails"))
        assertTrue(prompt.contains("Never invent generated images, image URLs, or successful generation"))
    }
}
