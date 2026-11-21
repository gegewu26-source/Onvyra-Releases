package com.constx.onvyra.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
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

/** Captures real compatible /v1 request bodies without any paid model or server. */
@RunWith(AndroidJUnit4::class)
class GenerationLoopVideoRecoveryTest {
    @Test fun recoveryPromptIsTemporaryAndPersistsAcrossAToolRoundWithoutReplacingThePreviousAnswer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = Model(modelId = "video-recovery-fixture", abilities = listOf(ModelAbility.TOOL))
        val originalSystem = "ORIGINAL_ASSISTANT_STYLE"
        val runtime = "PLAYBACK_RECOVERY_RUNTIME_FIXTURE: the previous source failed; find a different actual video page."
        val assistant = Assistant(chatModelId = model.id, streamOutput = false,
            systemPrompt = originalSystem, contextMessageLimit = 0)
        val originalUser = UIMessage.user("Find and play the requested movie.")
        val originalAnswer = UIMessage.assistant("ORIGINAL_PLAYBACK_ANSWER_MUST_REMAIN")
        val original = listOf(originalUser, originalAnswer)
        val requests = CopyOnWriteArrayList<JsonObject>()
        val toolExecutions = AtomicInteger()
        val tool = Tool(
            name = "video_find_sources",
            description = "Fixture video source search",
            parameters = { InputSchema.Obj(properties = buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
            }, required = listOf("query")) },
            execute = {
                toolExecutions.incrementAndGet()
                listOf(UIMessagePart.Text("""{"status":"candidates","items":[{"title":"Fixture movie","url":"https://movie.example.test/watch/new.html"}]}"""))
            },
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "video-recovery-fixture.example.test")
            check(request.url.encodedPath == "/v1/chat/completions")
            val captured = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            requests += captured
            val first = requests.size == 1
            val response = buildJsonObject {
                put("id", "video-recovery-response-${requests.size}")
                put("object", "chat.completion")
                put("model", model.modelId)
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("index", 0)
                    put("finish_reason", if (first) "tool_calls" else "stop")
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", if (first) JsonNull else JsonPrimitive("NEW_RECOVERY_ANSWER"))
                        if (first) put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("id", "video-recovery-find-call")
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "video_find_sources")
                                put("arguments", "{\"query\":\"Fixture movie alternative source\"}")
                            })
                        }) })
                    })
                }) })
            }.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val provider = ProviderSetting.OpenAI(baseUrl = "https://video-recovery-fixture.example.test/v1",
                apiKey = "", models = listOf(model))
            val settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
                assistantId = assistant.id, chatModelId = model.id,
                networkSetting = NetworkSetting(enableAutoRetry = false))
            val chunks = GenerationLoop(context, ProviderManager(client, context), Json).generateText(
                settings = settings, model = model, messages = original, assistant = assistant,
                tools = listOf(tool), maxSteps = 3, runtimeSystemPrompt = runtime, startNewMessage = true,
            ).toList()
            assertEquals("The recovery run must execute its search once, then continue generation", 1, toolExecutions.get())
            assertEquals(2, requests.size)
            for (request in requests) {
                val sent = request.getValue("messages").jsonArray.map { it.jsonObject }
                val system = sent.filter { it["role"]?.jsonPrimitive?.content in setOf("system", "developer") }
                    .joinToString("\n") { it.getValue("content").toString() }
                assertTrue(system.contains(originalSystem))
                assertEquals("A tool round must receive exactly one copy of the recovery instruction", 1,
                    Regex(Regex.escape("PLAYBACK_RECOVERY_RUNTIME_FIXTURE")).findAll(system).count())
                assertTrue("Original answer must remain in the provider history", sent.any {
                    it["role"]?.jsonPrimitive?.content == "assistant" &&
                        it["content"].toString().contains("ORIGINAL_PLAYBACK_ANSWER_MUST_REMAIN")
                })
            }
            assertTrue("The continuation request must include the actual search result",
                requests.last().getValue("messages").toString().contains("https://movie.example.test/watch/new.html"))
            val updates = chunks.filterIsInstance<GenerationChunk.Messages>()
            assertTrue(updates.isNotEmpty())
            for (update in updates) {
                assertEquals(original, update.messages.take(2))
                assertTrue("The recovery must append a distinct assistant message", update.messages.size >= 3)
                assertNotEquals(originalAnswer.id, update.messages.last().id)
                assertFalse("Runtime system text must not become stored chat history",
                    update.messages.any { it.isSynthetic || it.role == MessageRole.SYSTEM || it.toText().contains("PLAYBACK_RECOVERY_RUNTIME_FIXTURE") })
            }
            val final = updates.last().messages
            assertEquals(3, final.size)
            assertTrue(final.last().toText().contains("NEW_RECOVERY_ANSWER"))
            assertEquals("The prior playback answer must keep its identity", originalAnswer.id, final[1].id)
            assertEquals(originalSystem, assistant.systemPrompt)
            assertEquals(original, listOf(originalUser, originalAnswer))
            println("VIDEO_RECOVERY_REQUEST=PASS; REQUESTS=${requests.size}; SEARCH_EXECUTIONS=${toolExecutions.get()}; ORIGINAL_ASSISTANT_ID_RETAINED=true; NEW_ASSISTANT=true; RUNTIME_PROMPT_COPIES_PER_REQUEST=1; NO_SYNTHETIC_HISTORY=true")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
