package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GoogleHostedImagePermissionTest {
    @Test fun bothGoogleProtocolsRestrictImageOutputsAfterCustomBodyMerge() {
        val params = TextGenerationParams(Model(modelId = "image-fixture", outputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
            allowHostedImageGeneration = false, customBody = listOf(
                CustomBody("generationConfig", buildJsonObject { put("responseModalities", buildJsonArray { add("IMAGE") }) }),
                CustomBody("response_format", buildJsonArray { add(buildJsonObject { put("type", "image") }) }),
            ))
        val method = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java)
        method.isAccessible = true
        val completion = method.invoke(GoogleProvider(OkHttpClient()), listOf(UIMessage.user("fixture")), params) as JsonObject
        assertEquals(listOf("TEXT"), completion["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
        val interactions = InteractionsAPI(OkHttpClient()).buildRequestBody(listOf(UIMessage.user("fixture")), params, false)
        assertEquals(listOf("text"), interactions["response_format"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }
}
