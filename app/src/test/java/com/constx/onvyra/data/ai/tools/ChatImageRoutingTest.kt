package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.datastore.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.MediaKind
import org.junit.Assert.*
import org.junit.Test

class ChatImageRoutingTest {
    private val dedicated = Model(modelId = "dedicated-image", type = ModelType.IMAGE)
    private fun settings(current: Model, selected: Model = dedicated, provider: ProviderSetting? = null) = Settings(
        providers = listOf(provider ?: ProviderSetting.OpenAI(models = listOf(current, selected))),
        chatImageModelId = selected.id,
    )

    @Test fun reasoningOrVisionAloneCannotBeSelectedAsAnImageEndpoint() {
        val text = Model(modelId = "opaque-vision-chat", abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val s = settings(text)
        assertFalse(s.currentModelUsesNativeImageOutput(text))
        assertFalse(text.supportsMediaGeneration(s.providers.single(), MediaKind.IMAGE))
        // Explicit role selection is permitted for gateway aliases; vision/reasoning
        // alone still never turns the current chat into a native image generator.
        assertEquals(text.id, s.copy(chatImageModelId = text.id).selectedMediaModel(MediaKind.IMAGE)!!.second.id)
        assertEquals(dedicated.id, s.chatImageMediaSelection(text)!!.second.id)
    }

    @Test fun nativeChatImageOutputRetainsTheConfiguredImageFallback() {
        val native = Model(modelId = "custom-native-image-chat", outputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val s = settings(native, provider = ProviderSetting.OpenAI(
            models = listOf(native, dedicated), useResponseApi = true,
        ))
        assertTrue(s.currentModelUsesNativeImageOutput(native))
        assertTrue("Declared native images are automatically routed through Responses",
            settings(native, provider = ProviderSetting.OpenAI(
                models = listOf(native, dedicated), useResponseApi = false,
            )).currentModelUsesNativeImageOutput(native))
        val tools = createMediaGenerationTools(s, submit = { _, _, _, _ -> error("No studio calls") },
            getRecord = { error("No record reads") }, currentModel = native,
            generateChatImage = { _, _, _, _ -> error("Metadata check must not generate") })
        val fallback = tools.single { it.name == GENERATE_MEDIA_TOOL }
        val schema = fallback.parameters() as InputSchema.Obj
        assertEquals(listOf("image"), schema.properties.getValue("kind").jsonObject.getValue("enum")
            .jsonArray.map { it.jsonPrimitive.content })
        assertEquals(dedicated.id, s.chatImageMediaSelection(native)!!.second.id)
        val prompt = fallback.systemPrompt(native, emptyList())
        assertTrue(prompt.contains("first use generate_native_image"))
        assertTrue(prompt.contains("fails"))
        assertFalse(prompt.contains("Do not delegate images"))
    }

    @Test fun lunaAndSolFallbackUseOnlyTheSelectedFlareAndRunOncePerRequest() = runBlocking {
        for (id in listOf("gpt-6-luna", "gpt-6.1-sol")) {
            val native = Model(modelId = id, abilities = listOf(ModelAbility.TOOL))
            val flare = Model(modelId = "gpt-image-2.5-flare", type = ModelType.IMAGE)
            val s = settings(native, selected = flare)
            var calls = 0
            val tool = createMediaGenerationTools(s, submit = { _, _, _, _ -> error("No studio records") },
                getRecord = { error("No record lookup") }, currentModel = native,
                generateChatImage = { provider, selected, prompt, _ ->
                    calls++
                    assertEquals(flare.id, provider.id)
                    assertEquals(flare.id, selected.id)
                    assertEquals("gpt-image-2.5-flare", selected.modelId)
                    assertEquals("A cat", prompt)
                    listOf(UIMessagePart.Image("file:///fixture/flare.png"))
                }).single { it.name == GENERATE_MEDIA_TOOL }
            val first = tool.execute(buildJsonObject { put("kind", "image"); put("prompt", "A cat") })
            assertEquals("file:///fixture/flare.png", (first.single() as UIMessagePart.Image).url)
            val duplicate = tool.execute(buildJsonObject { put("kind", "image"); put("prompt", "A different wording of the same cat") })
            val result = Json.parseToJsonElement((duplicate.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("image_fallback_already_attempted", result.getValue("error_code").jsonPrimitive.content)
            assertEquals(1, calls)
        }
    }

    @Test fun nativeFallbackTextOnlySuccessIsReportedAsFailureAndCannotLoop() = runBlocking {
        val native = Model(modelId = "gpt-6-luna", abilities = listOf(ModelAbility.TOOL))
        val s = settings(native)
        var calls = 0
        val tool = createMediaGenerationTools(s, submit = { _, _, _, _ -> error("No studio calls") },
            getRecord = { error("No record lookup") }, currentModel = native,
            generateChatImage = { _, _, _, _ ->
                calls++
                listOf(UIMessagePart.Text("""{"status":"succeeded"}"""))
            }).single { it.name == GENERATE_MEDIA_TOOL }
        val args = buildJsonObject { put("kind", "image"); put("prompt", "A cat") }
        val first = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("failed", first.getValue("status").jsonPrimitive.content)
        tool.execute(args)
        assertEquals(1, calls)
    }

    @Test fun responsesBuiltInImageToolAndEffectiveProviderAreRespected() {
        val native = Model(modelId = "current", tools = setOf(BuiltInTools.ImageGeneration))
        val responses = ProviderSetting.OpenAI(models = listOf(native, dedicated), useResponseApi = true)
        assertTrue(settings(native, provider = responses).currentModelUsesNativeImageOutput(native))
        assertTrue(settings(native, provider = responses.copy(useResponseApi = false)).currentModelUsesNativeImageOutput(native))
        val claudeOverride = native.copy(providerOverwrite = ProviderSetting.Claude())
        assertFalse(settings(claudeOverride, provider = responses.copy(models = listOf(claudeOverride, dedicated)))
            .currentModelUsesNativeImageOutput(claudeOverride))
    }

    @Test fun anExplicitlySelectedImageModelUsesItselfInsteadOfTheDelegate() {
        val own = Model(modelId = "own-image", type = ModelType.IMAGE)
        assertEquals(own.id, settings(own).chatImageMediaSelection(own)!!.second.id)
    }

    @Test fun delegatedImagesReturnFilesInChatWithoutStudioSubmissionOrRecordLookup() = runBlocking {
        val text = Model(modelId = "text", abilities = listOf(ModelAbility.TOOL))
        val s = settings(text)
        var calls = 0
        val tool = createMediaGenerationTools(s, submit = { _, _, _, _ -> error("Must not create studio task") },
            getRecord = { error("Must not read a studio record") }, currentModel = text,
            generateChatImage = { provider, model, prompt, _ ->
                calls++
                assertEquals(dedicated.id, model.id)
                assertEquals(dedicated.id, provider.id)
                assertEquals("fixture prompt", prompt)
                listOf(UIMessagePart.Text("{\"status\":\"succeeded\",\"output_count\":1}"),
                    UIMessagePart.Image("file:///fixture/generated.gif"))
            }).single { it.name == GENERATE_MEDIA_TOOL }
        val result = tool.execute(buildJsonObject { put("kind", "image"); put("prompt", "fixture prompt") })
        assertEquals(1, calls)
        assertEquals("file:///fixture/generated.gif", (result.last() as UIMessagePart.Image).url)
        assertFalse(result.toString().contains("session_id"))
    }
}
