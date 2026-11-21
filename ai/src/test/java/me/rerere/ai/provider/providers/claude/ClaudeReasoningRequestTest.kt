package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class ClaudeReasoningRequestTest {
    private val provider = ClaudeProvider(OkHttpClient())

    private fun params(id: String, level: ReasoningLevel, maxTokens: Int? = null, bodies: List<CustomBody> = emptyList()) =
        TextGenerationParams(
            model = Model(modelId = id, abilities = listOf(ModelAbility.REASONING)),
            reasoningLevel = level, maxTokens = maxTokens, customBody = bodies,
        )

    private fun body(params: TextGenerationParams): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest", ProviderSetting.Claude::class.java, List::class.java,
            TextGenerationParams::class.java, Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(provider, ProviderSetting.Claude(), listOf(UIMessage.user("question")), params, false) as JsonObject
    }

    @Test
    fun `legacy Claude uses enabled with a valid budget rather than unsupported adaptive`() {
        listOf("claude-sonnet-4-5-20250929", "claude-opus-4-5", "claude-3-7-sonnet-20250219").forEach { id ->
            val request = body(params(id, ReasoningLevel.LOW))
            val thinking = request["thinking"]!!.jsonObject
            assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
            assertEquals(1_024, thinking["budget_tokens"]!!.jsonPrimitive.int)
            assertNull(request["output_config"])
        }
    }

    @Test
    fun `legacy thinking budget remains below max tokens`() {
        val request = body(params("claude-sonnet-4-5", ReasoningLevel.MAX, maxTokens = 2_048))
        assertEquals(2_047, request["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertEquals(2_048, request["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `legacy budget respects custom output limit as well`() {
        val request = body(params("claude-opus-4-5", ReasoningLevel.MAX, bodies = listOf(
            CustomBody("max_tokens", JsonPrimitive(4_096)),
        )))
        assertEquals(4_095, request["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `auto leaves legacy and adaptive model defaults without a forced thinking mode`() {
        listOf("claude-sonnet-4-5", "claude-opus-4-6", "claude-opus-5-5").forEach { id ->
            val request = body(params(id, ReasoningLevel.AUTO))
            assertNull(request["thinking"])
            assertNull(request["output_config"])
        }
    }

    @Test
    fun `Claude 4 point 6 maps unsupported xhigh to high while max is supported`() {
        listOf("claude-sonnet-4-6", "claude-opus-4-6").forEach { id ->
            val request = body(params(id, ReasoningLevel.XHIGH))
            assertEquals("adaptive", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("high", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertFalse(claudeReasoningCapabilities(Model(modelId = id))!!.supportedLevels.contains(ReasoningLevel.XHIGH))
            assertEquals("max", body(params(id, ReasoningLevel.MAX))["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `Claude 4 point 7 and later supported xhigh remains xhigh`() {
        listOf("claude-opus-4-7", "claude-opus-4-8", "claude-opus-5", "claude-sonnet-5").forEach { id ->
            assertEquals("xhigh", body(params(id, ReasoningLevel.XHIGH))["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `models without thinking off use low adaptive for old persisted off`() {
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-mythos-preview").forEach { id ->
            val request = body(params(id, ReasoningLevel.OFF))
            assertEquals("adaptive", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("low", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertFalse(claudeReasoningCapabilities(Model(modelId = id))!!.supportsOff)
        }
    }

    @Test
    fun `off clears incompatible previous high effort on supported modern models`() {
        listOf("claude-opus-5", "claude-haiku-5-5").forEach { id ->
            val request = body(params(id, ReasoningLevel.OFF))
            assertEquals("disabled", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("low", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `explicit effort cannot be overridden by custom thinking config and keeps format`() {
        val request = body(params("claude-opus-4-6", ReasoningLevel.LOW, bodies = listOf(
            CustomBody("thinking", buildJsonObject { put("type", "disabled"); put("budget_tokens", 9999) }),
            CustomBody("output_config", buildJsonObject {
                put("effort", "max")
                put("format", buildJsonObject { put("type", "json_schema") })
            }),
        )))
        assertEquals("adaptive", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(request["thinking"]!!.jsonObject["budget_tokens"])
        assertEquals("low", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", request["output_config"]!!.jsonObject["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auto preserves custom thinking defaults`() {
        val request = body(params("claude-sonnet-4-5", ReasoningLevel.AUTO, bodies = listOf(
            CustomBody("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", 2048) }),
        )))
        assertEquals("enabled", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2048, request["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `known reasoning model does not require the old ability marker`() {
        val request = body(TextGenerationParams(Model(modelId = "claude-opus-4-6"), reasoningLevel = ReasoningLevel.LOW))
        assertEquals("low", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown Claude aliases do not gain an adaptive or off guarantee`() {
        listOf("claude99", "my-claude-opus-5", "claude-opus-7").forEach { id ->
            assertNull(claudeReasoningCapabilities(Model(modelId = id)))
            assertFalse(reasoningCapabilities(Model(modelId = id), ProviderSetting.Claude()).isKnown)
        }
    }
    @Test
    fun `unmarked unknown model keeps custom thinking configuration`() {
        val request = body(TextGenerationParams(Model(modelId = "plain-model"), customBody = listOf(
            CustomBody("thinking", buildJsonObject { put("type", "enabled"); put("budget_tokens", 2048) }),
        )))
        assertEquals("enabled", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2048, request["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `legacy incompatible top p is omitted only while explicit thinking is enabled`() {
        val original = params("claude-sonnet-4-5", ReasoningLevel.LOW).copy(topP = 0.7f)
        assertNull(body(original)["top_p"])
        assertEquals("0.7", body(original.copy(reasoningLevel = ReasoningLevel.OFF))["top_p"]!!.jsonPrimitive.content)
        assertEquals("0.7", body(original.copy(reasoningLevel = ReasoningLevel.AUTO))["top_p"]!!.jsonPrimitive.content)
        assertEquals("0.95", body(original.copy(topP = 0.95f))["top_p"]!!.jsonPrimitive.content)
        assertEquals(0.7f, original.topP)
    }

}
