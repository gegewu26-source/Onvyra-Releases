package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatCompletionsImageOutputRequestTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    @Test
    fun `configured image output requests both modalities on a compatible gateway`() {
        for (stream in listOf(false, true)) {
            val body = buildRequest(stream = stream, imageOutput = true)

            assertEquals(listOf("image", "text"), body["modalities"]?.jsonArray
                ?.map { it.jsonPrimitive.content })
            assertEquals(stream.toString(), body["stream"]?.jsonPrimitive?.content)
            assertFalse(body.containsKey("session_id"))
        }
    }

    @Test
    fun `text only models do not acquire image output parameters`() {
        for (baseUrl in listOf(GATEWAY_URL, OPENROUTER_URL)) {
            for (stream in listOf(false, true)) {
                val body = buildRequest(baseUrl = baseUrl, stream = stream, imageOutput = false)

                assertFalse(body.containsKey("modalities"))
            }
        }
    }

    @Test
    fun `session id remains exclusive to OpenRouter`() {
        for (stream in listOf(false, true)) {
            val routerBody = buildRequest(
                baseUrl = OPENROUTER_URL,
                stream = stream,
                imageOutput = true,
            )
            val gatewayBody = buildRequest(stream = stream, imageOutput = true)

            assertEquals(SESSION_ID, routerBody["session_id"]?.jsonPrimitive?.content)
            assertEquals(routerBody["modalities"], gatewayBody["modalities"])
            assertFalse(gatewayBody.containsKey("session_id"))
        }
    }

    @Test
    fun `custom modalities still override the configured image output defaults`() {
        val imageOnly = buildJsonArray { add("image") }
        for (stream in listOf(false, true)) {
            val body = buildRequest(
                stream = stream,
                imageOutput = true,
                customBody = listOf(CustomBody("modalities", imageOnly)),
            )

            assertEquals(imageOnly, body["modalities"])
        }
    }

    private fun buildRequest(
        baseUrl: String = GATEWAY_URL,
        stream: Boolean,
        imageOutput: Boolean,
        customBody: List<CustomBody> = emptyList(),
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true

        return method.invoke(
            api,
            listOf(UIMessage.user("Generate a picture of a blue circle.")),
            TextGenerationParams(
                model = Model(
                    modelId = "gateway-model-alias",
                    outputModalities = if (imageOutput) {
                        listOf(Modality.TEXT, Modality.IMAGE)
                    } else {
                        listOf(Modality.TEXT)
                    },
                ),
                customBody = customBody,
                sessionId = SESSION_ID,
            ),
            ProviderSetting.OpenAI(baseUrl = baseUrl),
            stream,
        ) as JsonObject
    }

    private companion object {
        const val GATEWAY_URL = "https://relay.example.test/v1"
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1"
        const val SESSION_ID = "test-conversation-session"
    }
}
