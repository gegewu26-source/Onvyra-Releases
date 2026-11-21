package com.constx.onvyra.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.data.datastore.NetworkSetting
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.Assistant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs production GenerationLoop -> OpenAIProvider -> outgoing HTTP body, intercepted before DNS. */
@RunWith(AndroidJUnit4::class)
class ReasoningRequestIntegrationTest {
    @Test
    fun userDeepseekAliasReachesTheWireAtEveryLevelInStreamingAndNonStreaming() = runBlocking {
        val custom = listOf(
            CustomBody("reasoning_effort", JsonPrimitive("high")),
            CustomBody("thinking", buildJsonObject { put("type", "enabled") }),
            CustomBody("fixture_option", JsonPrimitive("preserved")),
        )
        for (stream in listOf(false, true)) {
            for (level in ReasoningLevel.entries) {
                val body = generate("deepseekv4.1f", level, stream, custom)
                assertEquals("preserved", body.getValue("fixture_option").jsonPrimitive.content)
                assertEquals("deepseekv4.1f", body.getValue("model").jsonPrimitive.content)
                val thinking = body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                val effort = body["reasoning_effort"]?.jsonPrimitive?.content
                if (level == ReasoningLevel.OFF) {
                    assertEquals("disabled", thinking)
                    assertNull(effort)
                } else {
                    assertEquals("enabled", thinking)
                    assertEquals(when (level) {
                        ReasoningLevel.LOW -> "low"
                        ReasoningLevel.MAX -> "max"
                        else -> "high" // AUTO keeps this model's explicit custom default.
                    }, effort)
                }
            }
        }
    }

    @Test
    fun gptModelsReceiveOnlyTheirActualEffortsAndIllegalSamplingIsRemoved() = runBlocking {
        val custom = listOf(CustomBody("temperature", JsonPrimitive(0.7)),
            CustomBody("top_p", JsonPrimitive(0.8)))
        val disabled = generate("gpt-6-luna", ReasoningLevel.OFF, false, custom)
        assertEquals("none", disabled["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals(0.7, disabled.getValue("temperature").jsonPrimitive.content.toDouble(), 0.00001)
        val maximum = generate("gpt-6-luna", ReasoningLevel.MAX, true, custom)
        assertEquals("max", maximum["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(maximum.containsKey("temperature"))
        assertFalse(maximum.containsKey("top_p"))
        val older = generate("gpt-5.1", ReasoningLevel.MAX, false)
        assertEquals("high", older["reasoning_effort"]?.jsonPrimitive?.content)
        for (model in listOf("gpt6a", "6.1sol", "6luna")) {
            for (stream in listOf(false, true)) {
                for (level in ReasoningLevel.entries) {
                    val body = generate(model, level, stream, custom)
                    assertEquals(model, body.getValue("model").jsonPrimitive.content)
                    val expected = when (level) {
                        ReasoningLevel.AUTO -> null
                        ReasoningLevel.OFF -> if (model == "6luna") "none" else "low"
                        else -> level.effort
                    }
                    assertEquals("$model / $level / stream=$stream", expected,
                        body["reasoning_effort"]?.jsonPrimitive?.content)
                    assertEquals(expected == "none", body.containsKey("temperature"))
                    assertEquals(expected == "none", body.containsKey("top_p"))
                }
            }
        }
    }

    @Test
    fun glmSwitchOnlyTwoEffortAndAlwaysThinkingModelsUseDifferentRequestControls() = runBlocking {
        val disabled = generate("glm-5.1", ReasoningLevel.OFF, false)
        assertEquals("disabled", disabled["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(disabled["reasoning_effort"])
        val enabled = generate("glm-5.1", ReasoningLevel.MAX, true)
        assertEquals("enabled", enabled["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(enabled["reasoning_effort"])
        assertEquals("high", generate("glm-5.2", ReasoningLevel.LOW, false)["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("max", generate("glm-5.2", ReasoningLevel.XHIGH, true)["reasoning_effort"]?.jsonPrimitive?.content)
        val alwaysOn = generate("glm-5.3", ReasoningLevel.OFF, false)
        assertEquals("enabled", alwaysOn["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("low", alwaysOn["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("max", generate("glm-5.3", ReasoningLevel.MAX, true)["reasoning_effort"]?.jsonPrimitive?.content)
        for (model in listOf("glm5.3", "glm5.3flash")) {
            for (stream in listOf(false, true)) {
                for (level in ReasoningLevel.entries) {
                    val body = generate(model, level, stream)
                    assertEquals(model, body.getValue("model").jsonPrimitive.content)
                    val expected = when (level) {
                        ReasoningLevel.AUTO -> null
                        ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
                        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
                        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                    }
                    assertEquals("$model / $level / stream=$stream", expected,
                        body["reasoning_effort"]?.jsonPrimitive?.content)
                    assertEquals(if (level == ReasoningLevel.AUTO) null else "enabled",
                        body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
                }
            }
        }
    }

    private suspend fun generate(
        modelId: String,
        level: ReasoningLevel,
        stream: Boolean,
        custom: List<CustomBody> = emptyList(),
    ): JsonObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var captured: JsonObject? = null
        val model = Model(modelId = modelId, customBodies = custom)
        val assistant = Assistant(chatModelId = model.id, reasoningLevel = level,
            streamOutput = stream, systemPrompt = "", localTools = emptyList())
        val provider = ProviderSetting.OpenAI(models = listOf(model), apiKey = "",
            baseUrl = "https://reasoning-wire-fixture.invalid/v1")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "reasoning-wire-fixture.invalid")
            check(request.url.encodedPath == "/v1/chat/completions")
            check(captured == null) { "This fixture must not perform extra model requests" }
            captured = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(completion(modelId, stream).toResponseBody(
                    (if (stream) "text/event-stream" else "application/json").toMediaType()))
                .build()
        }.build()
        try {
            GenerationLoop(context, ProviderManager(client, context), Json).generateText(
                settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
                    networkSetting = NetworkSetting(enableAutoRetry = false)),
                model = model, messages = listOf(UIMessage.user("fixture question")), assistant = assistant,
                maxSteps = 1,
            ).toList()
            return checkNotNull(captured)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun completion(model: String, stream: Boolean): String {
        fun chunk(finished: Boolean) = buildJsonObject {
            put("id", "reasoning-fixture")
            put("object", if (stream) "chat.completion.chunk" else "chat.completion")
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    if (stream) {
                        put("delta", buildJsonObject {
                            if (!finished) { put("role", "assistant"); put("content", "fixture answer") }
                        })
                    } else put("message", buildJsonObject { put("role", "assistant"); put("content", "fixture answer") })
                    if (finished) put("finish_reason", "stop")
                })
            })
        }.toString()
        return if (stream) "data: ${chunk(false)}\n\ndata: ${chunk(true)}\n\ndata: [DONE]\n\n"
            else chunk(true)
    }
}
