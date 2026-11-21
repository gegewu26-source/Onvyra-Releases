package me.rerere.mediagen.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.mediagen.model.*
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.*
import org.junit.Assert.*
import org.junit.Test

class ImageParameterIsolationTest {
    @Test fun studioLunaUsesResponsesWithoutSendingImageComplexityMaxOrChatReasoning() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = fakeClient(requests) { request ->
            val body = mediaGenerationJson.parseToJsonElement(request.bodyAsString()).jsonObject
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("gpt-6-luna", body["model"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("reasoning_effort"))
            val tool = body["tools"]!!.jsonArray.single().jsonObject
            assertEquals("image_generation", tool["type"]!!.jsonPrimitive.content)
            assertFalse(tool.containsKey("complexity"))
            assertEquals("high", tool["quality"]!!.jsonPrimitive.content)
            jsonResponse(request, """{"output":[{"type":"image_generation_call","result":"AQID"}]}""")
        }
        val result = OpenAIMediaGenerationProvider(client).create(
            MediaGenerationProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"),
            MediaGenerationModel("gpt-6-luna", MediaKind.IMAGE),
            MediaGenerationRequest(prompt = "fixture", extraParameters = buildJsonObject {
                put("reasoning_effort", "max"); put("complexity", "max"); put("quality", "high")
            })).getOrThrow()
        assertEquals(1, requests.size)
        assertEquals(1, result.outputs.size)
    }
    @Test fun studioAndChatImageApisBothRejectChatOnlyParametersBeforeSending() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = fakeClient(requests) { request ->
            val body = mediaGenerationJson.parseToJsonElement(request.bodyAsString()).jsonObject
            assertEquals("/v1/images/generations", request.url.encodedPath)
            assertEquals("gpt-image-2.5-flare", body["model"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("reasoning_effort"))
            assertFalse(body.containsKey("thinking"))
            assertEquals("high", body["quality"]!!.jsonPrimitive.content)
            jsonResponse(request, """{"data":[{"b64_json":"AQID"}]}""")
        }
        val result = OpenAIMediaGenerationProvider(client).create(
            MediaGenerationProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"),
            MediaGenerationModel("gpt-image-2.5-flare", MediaKind.IMAGE),
            MediaGenerationRequest(prompt = "fixture", extraParameters = buildJsonObject {
                put("reasoning_effort", "max"); put("thinking", true); put("quality", "high")
            }))
        assertTrue(result.toString(), result.isSuccess)
        assertEquals(1, requests.size)
    }
}
