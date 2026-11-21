package com.constx.onvyra.data.ai.compression

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.data.ai.GenerationChunk
import com.constx.onvyra.data.ai.GenerationLoop
import com.constx.onvyra.data.ai.tools.createContextHistoryTool
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
import java.util.concurrent.CopyOnWriteArrayList

/** Captures real /v1 request JSON against an in-process fake transport; never uses an API account. */
@RunWith(AndroidJUnit4::class)
class ContextCompressionRequestTest {
    private val model = Model(modelId = "compression-fixture", abilities = listOf(ModelAbility.TOOL))
    private val assistant = Assistant(chatModelId = model.id, streamOutput = false, contextMessageLimit = 2)
    private val source = listOf(UIMessage.user("ARCHIVED_EXACT_FACT"), UIMessage.assistant("old answer"),
        UIMessage.user("recent turn one"), UIMessage.assistant("recent answer"), UIMessage.user("latest request"))
    private val checkpoint = UIMessage.user("handoff summary").copy(isContextCheckpoint = true)

    @Test fun compressionRunsBeforeCountTruncationAndKeepsAllRecentMessages() = runBlocking {
        var callbacks = 0
        val (requests, chunks) = run { messages, prepared ->
            callbacks++
            assertTrue(prepared.any { it.toText().contains("ARCHIVED_EXACT_FACT") })
            messages.take(2) + checkpoint + messages.drop(2)
        }
        assertEquals(1, callbacks)
        val request = requests.single().toString()
        assertFalse(request.contains("ARCHIVED_EXACT_FACT"))
        assertTrue(request.contains("handoff summary"))
        assertTrue(request.contains("recent turn one"))
        assertTrue(request.contains("recent answer"))
        assertTrue(request.contains("latest request"))
        val finalMessages = (chunks.last() as GenerationChunk.Messages).messages
        assertEquals(source.take(2), finalMessages.take(2))
        assertEquals(checkpoint.id, finalMessages[2].id)
        assertEquals("fixture answer", finalMessages.last().toText())
    }

    @Test fun toolRoundCanRecoverArchivedOriginalWithoutRepeatingExecution() = runBlocking {
        var history = source
        var callbacks = 0
        var reads = 0
        val tool = createContextHistoryTool { reads++; history }
        val (requests, _) = run(tools = listOf(tool), callHistory = true) { messages, _ ->
            callbacks++
            (if (messages.any { it.isContextCheckpoint }) messages
                else messages.take(2) + checkpoint + messages.drop(2)).also { history = it }
        }
        assertEquals(2, callbacks)
        assertEquals(1, reads)
        assertEquals(2, requests.size)
        assertFalse(requests.first().getValue("messages").toString().contains("ARCHIVED_EXACT_FACT"))
        assertTrue(requests.last().getValue("messages").toString().contains("ARCHIVED_EXACT_FACT"))
        assertTrue(requests.last().getValue("messages").toString().contains("recent turn one"))
    }

    @Test fun compressionFailureDoesNotSendOrReplaceAnySourceMessage() = runBlocking {
        var requests = 0
        val failure = runCatching {
            run(onRequest = { requests++ }) { _, _ -> error("fixture compression failed") }
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, requests)
        assertFalse(source.any { it.isContextCheckpoint })
        assertEquals("ARCHIVED_EXACT_FACT", source.first().toText())
    }

    @Test fun noAutomaticHookRetainsExistingManualCountControl() = runBlocking {
        val (requests, _) = run(prepare = null)
        assertFalse(requests.single().getValue("messages").toString().contains("ARCHIVED_EXACT_FACT"))
        assertTrue(requests.single().getValue("messages").toString().contains("latest request"))
    }

    private suspend fun run(
        tools: List<Tool> = emptyList(),
        callHistory: Boolean = false,
        onRequest: () -> Unit = {},
        prepare: (suspend (List<UIMessage>, List<UIMessage>) -> List<UIMessage>)? = null,
    ): Pair<List<JsonObject>, List<GenerationChunk>> {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "compression-fixture.example.test")
            check(request.url.encodedPath == "/v1/chat/completions")
            onRequest()
            requests += Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val toolCall = callHistory && requests.size == 1
            val content = buildJsonObject {
                put("id", "fixture-compression")
                put("object", "chat.completion")
                put("model", model.modelId)
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("index", 0)
                    put("finish_reason", if (toolCall) "tool_calls" else "stop")
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", if (toolCall) JsonNull else JsonPrimitive("fixture answer"))
                        if (toolCall) put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("id", "history-fixture-call")
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "read_context_history")
                                put("arguments", "{\"query\":\"ARCHIVED_EXACT_FACT\"}")
                            })
                        }) })
                    })
                }) })
            }.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(content.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val provider = ProviderSetting.OpenAI(baseUrl = "https://compression-fixture.example.test/v1", apiKey = "", models = listOf(model))
            val settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
                assistantId = assistant.id, chatModelId = model.id, networkSetting = NetworkSetting(enableAutoRetry = false))
            val chunks = GenerationLoop(context, ProviderManager(client, context), Json).generateText(
                settings = settings, model = model, messages = source, assistant = assistant, tools = tools,
                prepareContext = prepare?.let { hook -> { source, prepared, _ -> hook(source, prepared) } }, maxSteps = 3,
            ).toList()
            return requests.toList() to chunks
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
