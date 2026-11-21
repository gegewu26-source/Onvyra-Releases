package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesPayloadCompatibilityTest {
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://fixture.invalid/v1")
    private val params = TextGenerationParams(model = Model(modelId = "fixture-model"))
    private val chatCompletion = """
        {"id":"comp_fixture","object":"chat.completion","model":"fixture-model",
          "choices":[{"message":{"role":"assistant","content":"Resume from the last saved step."},"finish_reason":"stop"}],
          "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
    """.trimIndent()

    @Test fun `a valid text-only compatible reply can be used for summarization`() {
        val result = runBlocking { fixture(chatCompletion).generateText(provider, messages(), params) }
        assertEquals("Resume from the last saved step.", result.message.toText())
        assertEquals("stop", result.finishReason)
        assertEquals(17, result.usage?.totalTokens)
    }

    @Test fun `an explicit provider error in a successful HTTP response keeps its message`() {
        val error = assertThrows(HttpException::class.java) {
            runBlocking {
                fixture("""{"error":{"message":"Upstream ended with EOF"}}""")
                    .generateText(provider, messages(), params)
            }
        }
        assertEquals("Upstream ended with EOF", error.message)
    }

    @Test fun `empty and incompatible payloads carry the typed error`() {
        for (body in listOf("", "[]", "{}", """{"output":null}""", """{"output":[]}""", """{"object":"chat.completion.chunk","choices":[]}""")) {
            assertThrows(ResponsesPayloadException::class.java) {
                runBlocking { fixture(body).generateText(provider, messages(), params) }
            }
        }
    }

    @Test fun `a text-only compatibility reply cannot satisfy forced image generation`() {
        assertThrows(ResponsesPayloadException::class.java) {
            runBlocking {
                fixture(chatCompletion).generateText(provider, messages(), params.copy(forceImageGeneration = true))
            }
        }
    }

    @Test fun `an HTTP 500 remains an HTTP failure`() {
        val error = assertThrows(Exception::class.java) {
            runBlocking {
                fixture("""{"error":{"message":"upstream EOF"}}""", code = 500)
                    .generateText(provider, messages(), params)
            }
        }
        assertTrue(error.message.orEmpty().contains("Failed to get response: 500"))
        assertTrue(error.message.orEmpty().contains("upstream EOF"))
    }

    private fun messages() = listOf(UIMessage.user("Summarize the history."))

    private fun fixture(body: String, code: Int = 200): ResponseAPI {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return ResponseAPI(client)
    }
}
