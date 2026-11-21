package com.constx.onvyra.data.ai.prompts

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model

fun buildImageOutputPrompt(model: Model): String {
    if (Modality.IMAGE !in model.outputModalities) return ""

    return """
        Image output is enabled for this model in this client. This chat can display images returned by the provider.
        When the user requests an image, use image-generation capabilities actually available in this session and return their real output. Do not infer that image generation is unavailable merely because the interface is a chat.
        If the provider lacks that capability or generation fails, explain the actual limitation. Never invent generated images, image URLs, or successful generation.
    """.trimIndent()
}
