package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.datastore.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ExistingProviderMediaBridgeTest {
    @Test fun ordinaryModelIdsResolveExactlyAndKeepTheirProviderOverride() {
        val override = ProviderSetting.OpenAI(baseUrl = "https://override.example/v1", apiKey = "override-key")
        val selected = Model(modelId = "image-model", type = ModelType.IMAGE, providerOverwrite = override)
        val sameName = Model(modelId = selected.modelId)
        val owner = ProviderSetting.OpenAI(models = listOf(sameName, selected), apiKey = "owner-key")
        val settings = Settings(providers = listOf(owner), chatImageModelId = selected.id)
        val (provider, model) = settings.selectedMediaModel(MediaKind.IMAGE)!!
        assertEquals(selected.id, provider.id)
        assertEquals(selected.id, model.id)
        assertEquals(selected.modelId, model.modelId)
        assertEquals(override.baseUrl, provider.baseUrl)
        assertEquals(override.apiKey, provider.apiKey)
        // Background recovery resolves the original model ID, independently of a later UI selection.
        assertEquals(selected.id, settings.copy(chatImageModelId = sameName.id)
            .existingProviderMediaSelection(provider.id, MediaKind.IMAGE)!!.model.id)
        assertNull(settings.copy(providers = listOf(owner.copy(enabled = false))).selectedMediaModel(MediaKind.IMAGE))
    }

    @Test fun videoSelectionAllowsGenericCompatibleEndpointsWithoutGuessingModelNames() {
        val model = Model(modelId = "my-custom-video-model")
        val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://relay.example/v1")
        assertTrue(model.supportsMediaGeneration(provider, MediaKind.VIDEO))
        val selected = Settings(providers = listOf(provider), chatVideoModelId = model.id).selectedMediaModel(MediaKind.VIDEO)!!
        assertTrue(selected.first is MediaGenerationProviderSetting.OpenAI)
        assertEquals(provider.baseUrl, selected.first.baseUrl)
        assertEquals(model.modelId, selected.second.modelId)
        assertFalse(model.copy(type = ModelType.EMBEDDING).supportsMediaGeneration(provider, MediaKind.VIDEO))
        assertFalse(model.supportsMediaGeneration(ProviderSetting.Google(), MediaKind.VIDEO))
        assertFalse(model.supportsMediaGeneration(ProviderSetting.Google(), MediaKind.IMAGE))
        assertTrue(model.copy(type = ModelType.IMAGE).supportsMediaGeneration(ProviderSetting.Google(), MediaKind.IMAGE))
    }

    @Test fun videoCustomHeadersAndBodiesAreScopedToTheConfiguredApiOrigin() {
        val model = Model(modelId = "video", customHeaders = listOf(CustomHeader("X-Scope", "model")),
            customBodies = listOf(CustomBody("quality", JsonPrimitive("high"))))
        val provider = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://relay.example/v1",
            customHeaders = listOf(CustomHeader("X-Scope", "provider"), CustomHeader("X-Project", "project")))
        val selection = Settings(providers = listOf(provider)).existingProviderMediaSelection(model.id, MediaKind.VIDEO)!!
        val captured = mutableListOf<Request>()
        val client = selection.mediaClient(OkHttpClient()).newBuilder().addInterceptor { chain ->
            captured += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        client.newCall(Request.Builder().url("https://relay.example/v1/videos")
            .post("""{"model":"video","prompt":"A landscape"}""".toRequestBody("application/json".toMediaType())).build())
            .execute().close()
        client.newCall(Request.Builder().url("https://cdn.example/result.mp4").build()).execute().close()
        assertEquals("model", captured[0].header("X-Scope"))
        assertEquals("project", captured[0].header("X-Project"))
        val sent = Json.parseToJsonElement(Buffer().also { captured[0].body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("high"), sent["quality"])
        assertEquals(JsonPrimitive("A landscape"), sent["prompt"])
        assertNull(captured[1].header("X-Scope"))
        assertNull(captured[1].header("X-Project"))
    }
}
