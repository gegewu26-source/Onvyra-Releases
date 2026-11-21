package com.constx.onvyra.data.ai

import com.constx.onvyra.service.runChatImageTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatImageSourcePropagationTest {
    @Test fun actualExtensionEngineReachesChatImageAndToolDetailsEvenWithoutUsage() = runBlocking {
        val source = buildJsonObject {
            put("route", "codex_images_extension"); put("requested_chat_model", "gpt-6.1-sol")
            put("actual_image_model", "gpt-image-2"); put("endpoint", "images/generations")
        }
        for (usage in listOf(null, MediaGenerationUsage(inputTokens = 20, outputTokens = 343, totalTokens = 363))) {
            val task = MediaGenerationTask("fixture", "same-provider", "gpt-image-2", MediaGenerationStatus.SUCCEEDED,
                listOf(MediaGenerationOutput(data = byteArrayOf(1), mimeType = "image/png")), usage = usage,
                metadata = buildJsonObject { put("onvyra_image_source", source) })
            val parts = runChatImageTask(create = { task }, query = { error("No asynchronous task") },
                save = { UIMessagePart.Image("file:///fixture.png", metadata = buildJsonObject { put("existing", true) }) })
            val image = parts.filterIsInstance<UIMessagePart.Image>().single()
            assertEquals(source, image.metadata!!["onvyra_image_source"])
            assertTrue(image.metadata!!["existing"]!!.jsonPrimitive.boolean)
            val details = Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
            assertEquals(source, details["image_source"])
            assertEquals(if (usage == null) null else TokenUsage(20, 343, 0, 363), parts.generatedImageTokenUsage())
        }
    }
}
