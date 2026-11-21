package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.reasoningCapabilities
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GoogleReasoningRequestTest {
    private val provider = GoogleProvider(OkHttpClient())
    private val interactions = InteractionsAPI(OkHttpClient())

    private fun params(id: String, level: ReasoningLevel, bodies: List<CustomBody> = emptyList()) =
        TextGenerationParams(
            model = Model(modelId = id, abilities = listOf(ModelAbility.REASONING)),
            reasoningLevel = level, customBody = bodies,
        )

    private fun body(params: TextGenerationParams): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, listOf(UIMessage.user("question")), params) as JsonObject
    }

    private fun thinking(params: TextGenerationParams) =
        body(params)["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject

    private fun interactionThinking(id: String, level: ReasoningLevel) = interactions.buildRequestBody(
        listOf(UIMessage.user("question")), params(id, level), stream = false,
    )["generation_config"]!!.jsonObject

    @Test
    fun `Gemini 3 point 8 only sends supported levels for every persisted selection`() {
        ReasoningLevel.entries.forEach { level ->
            val config = thinking(params("gemini-3.8-flash", level))
            assertFalse(config.containsKey("thinkingBudget"))
            if (level == ReasoningLevel.AUTO) assertNull(config["thinkingLevel"])
            else assertTrue(config["thinkingLevel"]!!.jsonPrimitive.content in listOf("low", "medium", "high"))
        }
    }

    @Test
    fun `Gemini 4 old off maps to supported low and does not claim disabled thinking`() {
        val config = thinking(params("gemini-4-argon", ReasoningLevel.OFF))
        assertEquals("low", config["thinkingLevel"]!!.jsonPrimitive.content)
        assertTrue(config["includeThoughts"]!!.jsonPrimitive.boolean)
        val caps = reasoningCapabilities(Model(modelId = "gemini-4-argon"), ProviderSetting.Google())
        assertFalse(caps.supportsOff)
        assertEquals(ReasoningLevel.LOW, caps.effectiveLevel(ReasoningLevel.OFF))
    }

    @Test
    fun `Gemini 3 Pro maps unavailable middle level to high`() {
        val config = thinking(params("gemini-3-pro-preview", ReasoningLevel.MEDIUM))
        assertEquals("high", config["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH),
            googleReasoningCapabilities(Model(modelId = "gemini-3-pro-preview"))!!.supportedLevels)
    }

    @Test
    fun `Gemini 2 point 5 Flash sends a real off budget`() {
        val config = thinking(params("gemini-2.5-flash", ReasoningLevel.OFF))
        assertEquals(0, config["thinkingBudget"]!!.jsonPrimitive.int)
        assertFalse(config["includeThoughts"]!!.jsonPrimitive.boolean)
        assertNull(config["thinkingLevel"])
    }

    @Test
    fun `Gemini 2 point 5 Pro cannot silently omit off and revert to dynamic thinking`() {
        val config = thinking(params("gemini-2.5-pro", ReasoningLevel.OFF))
        assertEquals(ReasoningLevel.LOW.budgetTokens, config["thinkingBudget"]!!.jsonPrimitive.int)
        assertFalse(googleReasoningCapabilities(Model(modelId = "gemini-2.5-pro"))!!.supportsOff)
    }

    @Test
    fun `Gemini flash budgets clamp to model maximum and Pro keeps larger budget`() {
        listOf("gemini-2.5-flash", "gemini-2.5-flash-lite-preview-06-17").forEach { id ->
            assertEquals(24_576, thinking(params(id, ReasoningLevel.MAX))["thinkingBudget"]!!.jsonPrimitive.int)
        }
        assertEquals(32_000, thinking(params("gemini-2.5-pro", ReasoningLevel.MAX))["thinkingBudget"]!!.jsonPrimitive.int)
    }

    @Test
    fun `Interactions levels and capabilities differ from budget protocol on 2 point 5`() {
        val model = Model(modelId = "gemini-2.5-flash")
        assertTrue(reasoningCapabilities(model, ProviderSetting.Google()).supportsOff)
        val caps = reasoningCapabilities(model, ProviderSetting.Google(useInteractionsApi = true))
        assertFalse(caps.supportsOff)
        assertEquals(listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH), caps.supportedLevels)
        assertEquals("low", interactionThinking(model.modelId, ReasoningLevel.OFF)["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("high", interactionThinking(model.modelId, ReasoningLevel.MAX)["thinking_level"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Interactions does not send invalid minimal on Gemini 3 point 8 and 4`() {
        listOf("gemini-3.8-flash", "gemini-4-argon").forEach { id ->
            val config = interactionThinking(id, ReasoningLevel.OFF)
            assertEquals("low", config["thinking_level"]!!.jsonPrimitive.content)
            assertEquals("auto", config["thinking_summaries"]!!.jsonPrimitive.content)
        }
        assertEquals("high", interactionThinking("gemini-3-pro-preview", ReasoningLevel.MEDIUM)["thinking_level"]!!.jsonPrimitive.content)
    }

    @Test
    fun `explicit slider takes precedence only over custom thinking fields`() {
        val request = body(params("gemini-3.8-flash", ReasoningLevel.LOW, listOf(
            CustomBody("generationConfig", buildJsonObject {
                put("temperature", 0.4)
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", "high")
                    put("thinkingBudget", 32_000)
                    put("includeThoughts", false)
                })
            }),
            CustomBody("my_extension", JsonPrimitive("keep")),
        )))
        val generation = request["generationConfig"]!!.jsonObject
        val thinking = generation["thinkingConfig"]!!.jsonObject
        assertEquals("low", thinking["thinkingLevel"]!!.jsonPrimitive.content)
        assertNull(thinking["thinkingBudget"])
        assertTrue(thinking["includeThoughts"]!!.jsonPrimitive.boolean)
        assertEquals("0.4", generation["temperature"]!!.jsonPrimitive.content)
        assertEquals("keep", request["my_extension"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auto preserves custom default thinking config`() {
        val config = thinking(params("gemini-2.5-flash", ReasoningLevel.AUTO, listOf(
            CustomBody("generationConfig", buildJsonObject {
                put("thinkingConfig", buildJsonObject { put("thinkingBudget", 12_345) })
            }),
        )))
        assertEquals(12_345, config["thinkingBudget"]!!.jsonPrimitive.int)
    }

    @Test
    fun `known model does not require old reasoning ability marker`() {
        val config = thinking(TextGenerationParams(Model(modelId = "gemini-3.8-flash"), reasoningLevel = ReasoningLevel.LOW))
        assertEquals("low", config["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown custom aliases stay unverified instead of acquiring guessed Gemini profile`() {
        listOf("gemini3.8", "my-gemini-3.8-fast", "gemini-9-pro").forEach { id ->
            assertNull(googleReasoningCapabilities(Model(modelId = id)))
            assertFalse(reasoningCapabilities(Model(modelId = id), ProviderSetting.Google()).isKnown)
        }
    }
    @Test
    fun `unmarked unknown model preserves custom protocol config without taking slider ownership`() {
        val request = body(TextGenerationParams(
            Model(modelId = "plain-model"), customBody = listOf(
                CustomBody("generationConfig", buildJsonObject {
                    put("thinkingConfig", buildJsonObject { put("thinkingBudget", 777) })
                }),
            ),
        ))
        assertEquals(777, request["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int)
    }

    @Test
    fun `native provider does not borrow capabilities from the other protocol family`() {
        assertFalse(reasoningCapabilities(Model(modelId = "claude-opus-4-6"), ProviderSetting.Google()).isKnown)
        assertFalse(reasoningCapabilities(Model(modelId = "gemini-3.8-flash"), ProviderSetting.Claude()).isKnown)
    }

}
