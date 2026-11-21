package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class OpenAIReasoningRequestTest {
    private val client = OkHttpClient()
    private val chat = ChatCompletionsAPI(client, KeyRoulette.default())
    private val response = ResponseAPI(client)
    private val gateway = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:8080/v1")
    private val messages = listOf(UIMessage.user("hello"))

    private fun params(id: String, level: ReasoningLevel, bodies: List<CustomBody> = emptyList(), abilities: List<ModelAbility> = emptyList()) =
        TextGenerationParams(model = Model(modelId = id, abilities = abilities), reasoningLevel = level, customBody = bodies)

    private fun chatBody(params: TextGenerationParams, provider: ProviderSetting.OpenAI = gateway, stream: Boolean = true): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(chat, messages, params, provider, stream) as JsonObject
    }

    @Test fun `confirmed real gateway alias emits correct wire fields for every level and stream mode`() {
        val efforts = mapOf(ReasoningLevel.LOW to "low", ReasoningLevel.MEDIUM to "high", ReasoningLevel.HIGH to "high", ReasoningLevel.XHIGH to "high", ReasoningLevel.MAX to "max")
        for (stream in listOf(false, true)) for (level in ReasoningLevel.entries) {
            val body = chatBody(params("deepseekv4.1f", level), stream = stream)
            when (level) {
                ReasoningLevel.AUTO -> {
                    assertFalse(body.containsKey("thinking"))
                    assertFalse(body.containsKey("reasoning_effort"))
                }
                ReasoningLevel.OFF -> {
                    assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                    assertFalse(body.containsKey("reasoning_effort"))
                }
                else -> {
                    assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                    assertEquals(efforts[level], body["reasoning_effort"]!!.jsonPrimitive.content)
                }
            }
            assertEquals("deepseekv4.1f", body["model"]!!.jsonPrimitive.content)
            assertEquals(stream, body["stream"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun `confirmed wb wire aliases send native thinking controls on custom hosts without rewriting model`() {
        val custom = gateway.copy(baseUrl = "http://10.182.186.140:8788/v1")
        val efforts = mapOf(ReasoningLevel.LOW to "low", ReasoningLevel.MEDIUM to "high", ReasoningLevel.HIGH to "high", ReasoningLevel.XHIGH to "high", ReasoningLevel.MAX to "max")
        for (alias in listOf("wb-deepseek-v4.1-flash", "wb-cn-deepseek-v4.1-flash")) {
            for (stream in listOf(false, true)) for (level in ReasoningLevel.entries) {
                val body = chatBody(params(alias, level, abilities = listOf(ModelAbility.REASONING)), custom, stream)
                assertEquals(alias, alias, body["model"]!!.jsonPrimitive.content)
                assertEquals(alias, stream, body["stream"]!!.jsonPrimitive.boolean)
                when (level) {
                    ReasoningLevel.AUTO -> {
                        assertFalse(alias, body.containsKey("thinking"))
                        assertFalse(alias, body.containsKey("reasoning_effort"))
                    }
                    ReasoningLevel.OFF -> {
                        assertEquals(alias, "disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                        assertFalse(alias, body.containsKey("reasoning_effort"))
                    }
                    else -> {
                        assertEquals(alias, "enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
                        assertEquals(alias, efforts[level], body["reasoning_effort"]!!.jsonPrimitive.content)
                    }
                }
            }
        }
    }

    @Test fun `confirmed wb auto keeps configured defaults and openrouter retains its own wire protocol`() {
        val router = gateway.copy(baseUrl = "https://openrouter.ai/api/v1")
        for (alias in listOf("wb-deepseek-v4.1-flash", "wb-cn-deepseek-v4.1-flash")) {
            val defaults = listOf(CustomBody("thinking", buildJsonObject { put("type", "disabled") }), CustomBody("reasoning_effort", JsonPrimitive("high")))
            val auto = chatBody(params(alias, ReasoningLevel.AUTO, defaults))
            assertEquals(alias, "disabled", auto["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(alias, "high", auto["reasoning_effort"]!!.jsonPrimitive.content)
            for (level in listOf(ReasoningLevel.OFF, ReasoningLevel.MAX)) {
                val body = chatBody(params(alias, level), router)
                assertEquals(alias, alias, body["model"]!!.jsonPrimitive.content)
                assertEquals(alias, if (level == ReasoningLevel.OFF) "none" else "max", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertFalse(alias, body.containsKey("thinking"))
                assertFalse(alias, body.containsKey("reasoning_effort"))
            }
            val routerAuto = chatBody(params(alias, ReasoningLevel.AUTO), router)
            assertFalse(alias, routerAuto.containsKey("thinking"))
            assertFalse(alias, routerAuto.containsKey("reasoning_effort"))
            assertFalse(alias, routerAuto.containsKey("reasoning"))
        }
    }

    @Test fun `openrouter retains its own protocol when routing deepseek`() {
        val provider = gateway.copy(baseUrl = "https://openrouter.ai/api/v1")
        val off = chatBody(params("deepseek/deepseek-v4-flash", ReasoningLevel.OFF), provider)
        assertEquals("none", off["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(off.containsKey("thinking"))
        val max = chatBody(params("deepseek/deepseek-v4-flash", ReasoningLevel.MAX), provider)
        assertEquals("max", max["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(max.containsKey("thinking"))
    }

    @Test fun `unknown aliases keep generic compatible effort and require original ability opt in`() {
        val modelParams = params("opaque-model", ReasoningLevel.XHIGH, abilities = listOf(ModelAbility.REASONING))
        val body = chatBody(modelParams)
        assertEquals("xhigh", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("thinking"))
        assertFalse(chatBody(modelParams.copy(model = modelParams.model.copy(abilities = emptyList()))).containsKey("reasoning_effort"))
    }

    @Test fun `explicit off and low cannot be undone by a fixed custom request body`() {
        val bodies = listOf(
            CustomBody("reasoning_effort", JsonPrimitive("max")),
            CustomBody("thinking", buildJsonObject { put("type", "enabled"); put("display", "summarized") }),
            CustomBody("reasoning", buildJsonObject { put("effort", "max"); put("summary", "detailed") }),
            CustomBody("temperature", JsonPrimitive(0.4)),
        )
        val off = chatBody(params("deepseekv4.1f", ReasoningLevel.OFF, bodies))
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", off["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertFalse(off.containsKey("reasoning_effort"))
        assertEquals("detailed", off["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals(0.4, off["temperature"]!!.jsonPrimitive.double, 0.0)
        val low = chatBody(params("deepseekv4.1f", ReasoningLevel.LOW, bodies))
        assertEquals("low", low["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun `auto preserves user configured model defaults`() {
        val bodies = listOf(CustomBody("thinking", buildJsonObject { put("type", "disabled") }), CustomBody("reasoning_effort", JsonPrimitive("high")))
        val body = chatBody(params("deepseekv4.1f", ReasoningLevel.AUTO, bodies))
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun `stale max and off values are mapped before both openai request protocols`() {
        val noMax = params("gpt-5.4", ReasoningLevel.MAX)
        assertEquals("xhigh", chatBody(noMax)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("xhigh", response.buildRequestBody(gateway, messages, noMax, false)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val noOff = params("gpt-6.1-sol", ReasoningLevel.OFF)
        assertEquals("low", chatBody(noOff)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("low", response.buildRequestBody(gateway, messages, noOff, true)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun `image model never receives chat reasoning controls`() {
        val image = params("gpt-6.1-iso", ReasoningLevel.MAX).copy(
            model = Model(modelId = "gpt-6.1-iso", type = me.rerere.ai.provider.ModelType.IMAGE,
                abilities = listOf(ModelAbility.REASONING))
        )
        val chatBody = chatBody(image)
        assertFalse(chatBody.containsKey("reasoning_effort"))
        assertFalse(chatBody.containsKey("thinking"))
        assertFalse(response.buildRequestBody(gateway, messages, image, true).containsKey("reasoning"))
    }

    @Test fun `deepseek responses uses none low high max rather than chat thinking object`() {
        for (level in ReasoningLevel.entries) {
            val body = response.buildRequestBody(gateway, messages, params("deepseekv4.1f", level), true)
            val effort = body["reasoning"]!!.jsonObject["effort"]?.jsonPrimitive?.content
            assertEquals(when (level) {
                ReasoningLevel.OFF -> "none"
                ReasoningLevel.AUTO -> null
                ReasoningLevel.LOW -> "low"
                ReasoningLevel.MAX -> "max"
                else -> "high"
            }, effort)
            assertFalse(body.containsKey("thinking"))
        }
    }

    @Test fun `responses explicit effort survives nested overrides without dropping other properties`() {
        val bodies = listOf(CustomBody("reasoning", buildJsonObject { put("effort", "max"); put("summary", "detailed") }))
        val body = response.buildRequestBody(gateway, messages, params("gpt-6-luna", ReasoningLevel.LOW, bodies), false)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("detailed", body["reasoning"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
    }

    @Test fun `nested google and claude depth controls are protected without deleting sibling config`() {
        val original = buildJsonObject { putJsonObject("generationConfig") {
            put("temperature", 0.7)
            putJsonObject("thinkingConfig") { put("thinkingLevel", "low"); put("includeThoughts", false) }
        }; putJsonObject("output_config") { put("effort", "low") } }
        val bodies = listOf(
            CustomBody("generationConfig", buildJsonObject { put("topP", 0.9); putJsonObject("thinkingConfig") { put("thinkingLevel", "high"); put("thinkingBudget", 32000); put("includeThoughts", true); put("extra", "kept") } }),
            CustomBody("output_config", buildJsonObject { put("effort", "max"); putJsonObject("format") { put("type", "json_schema") } }),
        )
        val result = original.mergeReasoningCustomBody(bodies, ReasoningLevel.LOW)
        val config = result["generationConfig"]!!.jsonObject
        assertEquals(0.7, config["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.9, config["topP"]!!.jsonPrimitive.double, 0.0)
        assertEquals("low", config["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertFalse(config["thinkingConfig"]!!.jsonObject["includeThoughts"]!!.jsonPrimitive.boolean)
        assertFalse(config["thinkingConfig"]!!.jsonObject.containsKey("thinkingBudget"))
        assertEquals("kept", config["thinkingConfig"]!!.jsonObject["extra"]!!.jsonPrimitive.content)
        assertEquals("low", result["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(result["output_config"]!!.jsonObject.containsKey("format"))
    }

    @Test fun `model catalogue reads documented active effort metadata safely`() {
        val listed = buildJsonObject { putJsonObject("effort") { putJsonArray("supported_levels") { add("low"); add("high"); add("max") } } }
        assertEquals(listOf("low", "high", "max"), advertisedReasoningEfforts(listed))
        assertNull(advertisedReasoningEfforts(buildJsonObject { put("id", "opaque") }))
        assertNull(advertisedReasoningEfforts(buildJsonObject { put("effort", "not-an-object") }))
        assertNull(advertisedReasoningEfforts(buildJsonObject { putJsonObject("effort") { putJsonArray("supported_levels") { add("none"); add("future-level") } } }))
    }
    @Test fun `reasoning enabled strips incompatible sampling fields even after custom merge`() {
        val bodies = listOf(CustomBody("temperature", JsonPrimitive(0.7)), CustomBody("top_p", JsonPrimitive(0.8)), CustomBody("top_logprobs", JsonPrimitive(3)), CustomBody("logprobs", JsonPrimitive(true)), CustomBody("include", buildJsonArray { add("message.output_text.logprobs"); add("reasoning.encrypted_content") }))
        for (id in listOf("gpt-5.2", "gpt-5.4", "gpt-5.5", "gpt-5.6-sol", "gpt6luna", "gpt-6.1-sol")) {
            val request = params(id, ReasoningLevel.HIGH, bodies).copy(temperature = 0.5f, topP = 0.6f)
            val chatBody = chatBody(request)
            assertFalse(id, chatBody.containsKey("temperature"))
            assertFalse(id, chatBody.containsKey("top_p"))
            assertFalse(id, chatBody.containsKey("top_logprobs"))
            assertFalse(id, chatBody.containsKey("logprobs"))
            val responseBody = response.buildRequestBody(gateway, messages, request, true)
            assertFalse(id, responseBody.containsKey("temperature"))
            assertFalse(id, responseBody.containsKey("top_p"))
            assertFalse(id, responseBody.containsKey("top_logprobs"))
            assertEquals(listOf("reasoning.encrypted_content"), responseBody["include"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun `none and default none retain valid sampling while auto medium removes invalid fields`() {
        val bodies = listOf(CustomBody("temperature", JsonPrimitive(0.7)), CustomBody("top_p", JsonPrimitive(0.8)))
        for (level in listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO)) {
            val request = params("gpt-5.4", level, bodies)
            assertEquals(0.7, chatBody(request)["temperature"]!!.jsonPrimitive.double, 0.0)
            assertEquals(0.8, response.buildRequestBody(gateway, messages, request, false)["top_p"]!!.jsonPrimitive.double, 0.0)
        }
        assertFalse(chatBody(params("gpt-6-sol", ReasoningLevel.AUTO, bodies)).containsKey("temperature"))
        val customNone = bodies + CustomBody("reasoning_effort", JsonPrimitive("none"))
        assertTrue(chatBody(params("gpt-6-sol", ReasoningLevel.AUTO, customNone)).containsKey("temperature"))
    }

    @Test fun `sampling cleanup does not guess unknown aliases or affect ordinary model settings`() {
        val request = params("opaque-model", ReasoningLevel.HIGH, listOf(CustomBody("temperature", JsonPrimitive(0.7)), CustomBody("top_p", JsonPrimitive(0.8))), listOf(ModelAbility.REASONING))
        assertTrue(chatBody(request).containsKey("temperature"))
        assertTrue(response.buildRequestBody(gateway, messages, request, false).containsKey("top_p"))
        val ordinary = params("gpt-4.1", ReasoningLevel.OFF, listOf(CustomBody("thinking", buildJsonObject { put("type", "enabled") })))
        assertEquals("enabled", chatBody(ordinary)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `glm direct and openrouter requests use their respective valid protocol`() {
        val low = chatBody(params("glm-5.3", ReasoningLevel.OFF))
        assertEquals("low", low["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("enabled", low["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val max = chatBody(params("glm-5.3", ReasoningLevel.XHIGH))
        assertEquals("max", max["reasoning_effort"]!!.jsonPrimitive.content)
        val old = chatBody(params("glm-4.7", ReasoningLevel.LOW))
        assertEquals("enabled", old["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(old.containsKey("reasoning_effort"))
        val router = chatBody(params("glm-5.3", ReasoningLevel.MAX), gateway.copy(baseUrl = "https://openrouter.ai/api/v1"))
        assertEquals("max", router["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(router.containsKey("thinking"))
    }
    @Test fun `actual user gpt aliases encode every requested depth in both protocols and retain route id`() {
        val aliases = listOf("gpt6a", "6.1sol", "gpt6.1sol", "6luna", "gpt6luna")
        for (alias in aliases) for (level in ReasoningLevel.entries) {
            val request = params(alias, level, listOf(CustomBody("temperature", JsonPrimitive(0.7)), CustomBody("top_p", JsonPrimitive(0.8))))
            val chatBody = chatBody(request)
            val responseBody = response.buildRequestBody(gateway, messages, request, true)
            val expected = when {
                level == ReasoningLevel.AUTO -> null
                level == ReasoningLevel.OFF && alias !in listOf("6luna", "gpt6luna") -> "low"
                else -> level.effort
            }
            assertEquals(alias, alias, chatBody["model"]!!.jsonPrimitive.content)
            assertEquals(alias, alias, responseBody["model"]!!.jsonPrimitive.content)
            assertEquals(alias, expected, chatBody["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals(alias, expected, responseBody["reasoning"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
            assertEquals(alias, expected == "none", chatBody.containsKey("temperature"))
            assertEquals(alias, expected == "none", responseBody.containsKey("top_p"))
        }
    }

    @Test fun `actual user glm aliases preserve route ids while sending supported depths`() {
        for (alias in listOf("glm5.3", "glm5.3flash")) for (level in ReasoningLevel.entries) {
            val request = params(alias, level)
            val body = chatBody(request)
            val responseBody = response.buildRequestBody(gateway, messages, request, true)
            val expected = when (level) {
                ReasoningLevel.AUTO -> null
                ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
                ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                else -> "high"
            }
            assertEquals(alias, alias, body["model"]!!.jsonPrimitive.content)
            assertEquals(alias, alias, responseBody["model"]!!.jsonPrimitive.content)
            assertEquals(alias, expected, body["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals(alias, expected, responseBody["reasoning"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
            if (level != ReasoningLevel.AUTO) assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }
}
