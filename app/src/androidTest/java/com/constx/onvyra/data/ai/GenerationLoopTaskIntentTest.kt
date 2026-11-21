package com.constx.onvyra.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the real provider request, without treating a fixture response as model intelligence. */
@RunWith(AndroidJUnit4::class)
class GenerationLoopTaskIntentTest {
    @Test
    fun animeCorrectionRemainsInHistoryAndReceivesTaskGuidanceAfterPersonaAndToolPrompts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = Model(modelId = "task-intent-fixture", abilities = listOf(ModelAbility.TOOL))
        val persona = "NOVEL_PERSONA_FIXTURE: discuss novels when relevant."
        val toolMarker = "VIDEO_TOOL_GUIDANCE_FIXTURE"
        val assistant = Assistant(chatModelId = model.id, streamOutput = false,
            systemPrompt = persona, contextMessageLimit = 0)
        val original = listOf(
            UIMessage.user("搜索同名作品"),
            UIMessage.assistant("我先查小说。"),
            UIMessage.user("我要动漫，第一季第1集，不是小说，也不要解说。"),
        )
        val requests = mutableListOf<JsonObject>()
        val tool = Tool(
            name = "video_search",
            description = "Fixture work catalog",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
            systemPrompt = { _, _ -> toolMarker },
            execute = { error("The fixed provider response must not execute tools") },
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "task-intent-fixture.example.test")
            requests += Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""{"id":"intent-fixture","object":"chat.completion","model":"task-intent-fixture","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"fixture response"}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val provider = ProviderSetting.OpenAI(baseUrl = "https://task-intent-fixture.example.test/v1",
                apiKey = "", models = listOf(model))
            val settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
                assistantId = assistant.id, chatModelId = model.id,
                networkSetting = NetworkSetting(enableAutoRetry = false))
            val chunks = GenerationLoop(context, ProviderManager(client, context), Json).generateText(
                settings = settings, model = model, messages = original, assistant = assistant,
                tools = listOf(tool), maxSteps = 1,
            ).toList()

            assertEquals(1, requests.size)
            val sent = requests.single().getValue("messages").jsonArray.map { it.jsonObject }
            val system = sent.filter { it["role"]?.jsonPrimitive?.content in setOf("system", "developer") }
                .joinToString("\n") { it.getValue("content").toString() }
            val intentMarker = "Follow the latest actual user request and explicit correction"
            assertTrue(system.contains(persona))
            assertTrue(system.indexOf(intentMarker) > system.indexOf(toolMarker))
            assertEquals(1, Regex(Regex.escape(intentMarker)).findAll(system).count())
            assertTrue(system.contains("content_type=anime"))
            assertTrue(system.contains("commentary/解说"))
            val users = sent.filter { it["role"]?.jsonPrimitive?.content == "user" }
            assertEquals(2, users.size)
            assertTrue(users.last().getValue("content").toString().contains("我要动漫，第一季第1集"))
            val final = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages
            assertEquals(original, final.take(original.size))
            assertFalse(final.any { it.isSynthetic || it.toText().contains(intentMarker) })
            assertEquals(persona, assistant.systemPrompt)
            println("TASK_INTENT_REQUEST=PASS; REQUESTS=1; LATEST_CORRECTION_RETAINED=true; GUIDANCE_AFTER_TOOL_PROMPT=true; NO_HISTORY_MUTATION=true; LIVE_MODEL_BEHAVIOR_NOT_TESTED=true")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
