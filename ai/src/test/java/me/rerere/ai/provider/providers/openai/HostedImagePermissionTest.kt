package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class HostedImagePermissionTest {
    private val client = OkHttpClient()
    private val api = ResponseAPI(client)
    private val model = Model(modelId = "gpt-6-luna", abilities = listOf(ModelAbility.TOOL))
    private val bodies = listOf(
        CustomBody("tools", buildJsonArray { add(buildJsonObject { put("type", "image_generation") }) }),
        CustomBody("tool_choice", buildJsonObject { put("type", "image_generation") }),
    )
    private fun body(params: TextGenerationParams) = api.buildRequestBody(ProviderSetting.OpenAI(), listOf(UIMessage.user("fixture")), params, false)
    private fun hasImage(body: JsonObject) = body["tools"]?.jsonArray?.any {
        it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "image_generation"
    } == true

    @Test fun hostApprovalPreventsNameInferredAndCustomInjectedImageTools() {
        val result = body(TextGenerationParams(model, customBody = bodies, allowHostedImageGeneration = false))
        assertFalse(hasImage(result))
        assertFalse(result.containsKey("tool_choice"))
    }

    @Test fun localNativeToolOwnsTheImageActionButApprovedSubrequestStillUsesTheSameModel() {
        val tool = Tool("generate_native_image", "fixture", execute = { emptyList() })
        val normal = body(TextGenerationParams(model, tools = listOf(tool)))
        assertFalse(hasImage(normal))
        assertTrue(normal["tools"]!!.jsonArray.any { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == tool.name })
        val approved = body(TextGenerationParams(model, forceImageGeneration = true, includeReasoning = false))
        assertTrue(hasImage(approved))
        assertEquals(model.modelId, approved["model"]!!.jsonPrimitive.content)
    }

    @Test fun ordinaryTextRequestsDoNotGainUnsupportedModalitiesWhenImagesAreRestricted() {
        val api = ChatCompletionsAPI(client, KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val result = method.invoke(api, listOf(UIMessage.user("fixture")),
            TextGenerationParams(Model(modelId = "ordinary-text"), allowHostedImageGeneration = false), ProviderSetting.OpenAI(), false) as JsonObject
        assertFalse(result.containsKey("modalities"))
    }
}
