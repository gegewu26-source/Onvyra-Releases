package com.constx.onvyra.data.ai.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.GenerationChunk
import com.constx.onvyra.data.ai.GenerationLoop
import com.constx.onvyra.data.datastore.NetworkSetting
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.Assistant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.CopyOnWriteArrayList

/** Uses installed application wiring and the public generation flow, without real API requests. */
@RunWith(AndroidJUnit4::class)
class WebFetchIntegrationTest {
    @Test
    fun productionFactoryAlwaysIncludesOneReaderWithoutSearchCredentials() = runBlocking {
        val factory = getKoin().get<ChatToolFactory>()
        val settings = Settings(searchServices = emptyList(), mcpServers = emptyList())
        val assistant = Assistant(localTools = emptyList(), enableMemory = false, enabledSkills = emptySet())
        val model = Model(modelId = "gemini3.8", abilities = listOf(ModelAbility.TOOL))

        for (searchEnabled in listOf(false, true)) {
            for (nativeSearch in listOf(false, true)) {
                val tools = factory.createTools(
                    settings = settings,
                    assistant = assistant.copy(enableWebSearch = searchEnabled),
                    model = model.copy(tools = if (nativeSearch) setOf(BuiltInTools.Search) else emptySet()),
                )
                assertEquals("reader must not depend on either search switch", 1, tools.count { it.name == "web_fetch" })
                assertEquals(searchEnabled && !nativeSearch, tools.any { it.name == "search_web" })
            }
        }
    }

    @Test
    fun openAiCompatibleGenerationReadsThePageAndReturnsItsContentToTheModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pageUrl = "https://public.example.test/about"
        val summary = "This website provides a document editor and a notes organizer."

        // These are custom relay aliases: explicit TOOL capability, rather than registry-name
        // matching, controls whether the real provider puts function declarations on the wire.
        for (alias in listOf("gpt6luna", "gemini3.8")) {
            val model = Model(modelId = alias, abilities = listOf(ModelAbility.TOOL))
            val modelRequests = CopyOnWriteArrayList<JsonObject>()
            val pageRequests = CopyOnWriteArrayList<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when (request.url.host) {
                    "model.example.test" -> {
                        val buffer = Buffer()
                        request.body!!.writeTo(buffer)
                        val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                        modelRequests += payload
                        if (modelRequests.size == 1) {
                            response(request, completion(alias, buildJsonObject {
                                put("role", "assistant")
                                put("content", JsonNull)
                                put("tool_calls", buildJsonArray {
                                    add(buildJsonObject {
                                        put("id", "read_page_1")
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", "web_fetch")
                                            put("arguments", buildJsonObject { put("url", pageUrl) }.toString())
                                        })
                                    })
                                })
                            }, "tool_calls"))
                        } else {
                            response(request, completion(alias, buildJsonObject {
                                put("role", "assistant")
                                put("content", summary)
                            }, "stop"))
                        }
                    }
                    "public.example.test" -> {
                        pageRequests += request
                        response(request, """
                            <!doctype html><html lang="en"><head><title>Useful Workspace</title>
                            <meta name="description" content="Edit documents and organize notes in one place.">
                            </head><body><main><h1>Useful Workspace</h1>
                            <p>This website provides a document editor and a notes organizer.</p>
                            <a href="/guide">Read the guide</a></main></body></html>
                        """.trimIndent(), "text/html; charset=utf-8")
                    }
                    else -> response(request, "Unexpected fixture URL", status = 400)
                }
            }.build()
            val assistant = Assistant(
                chatModelId = model.id,
                streamOutput = false,
                enableMemory = false,
                enableWebSearch = false,
                localTools = emptyList(),
            )
            val provider = ProviderSetting.OpenAI(
                name = "Fixture relay",
                baseUrl = "https://model.example.test/v1",
                apiKey = "",
                models = listOf(model),
            )
            val settings = Settings(
                providers = listOf(provider),
                assistants = listOf(assistant),
                chatModelId = model.id,
                assistantId = assistant.id,
                searchServices = emptyList(),
                networkSetting = NetworkSetting(enableAutoRetry = false),
            )
            val result = GenerationLoop(context, ProviderManager(client, context), Json)
                .generateText(
                    settings = settings,
                    model = model,
                    assistant = assistant,
                    messages = listOf(UIMessage.user("What does this website do? $pageUrl")),
                    tools = listOf(createWebFetchTool(WebPageFetcher(client))),
                    maxSteps = 3,
                )
                .filterIsInstance<GenerationChunk.Messages>()
                .last()

            assertEquals("$alias must complete one tool round trip", 2, modelRequests.size)
            val advertised = modelRequests.first().getValue("tools").jsonArray
                .map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
            assertEquals(listOf("web_fetch"), advertised)
            assertEquals(1, pageRequests.size)
            assertEquals("GET", pageRequests.single().method)
            assertNull("reader must not forward model authorization", pageRequests.single().header("Authorization"))
            assertNull(pageRequests.single().header("Cookie"))

            val toolMessage = modelRequests.last().getValue("messages").jsonArray.map { it.jsonObject }
                .single { it.getValue("role").jsonPrimitive.content == "tool" }
            assertEquals("read_page_1", toolMessage.getValue("tool_call_id").jsonPrimitive.content)
            val pageResult = Json.parseToJsonElement(toolMessage.getValue("content").jsonPrimitive.content).jsonObject
            assertEquals("Useful Workspace", pageResult.getValue("title").jsonPrimitive.content)
            assertEquals(pageUrl, pageResult.getValue("finalUrl").jsonPrimitive.content)
            assertEquals("200", pageResult.getValue("status").jsonPrimitive.content)
            assertTrue(pageResult.getValue("content").jsonPrimitive.content.contains("document editor"))
            assertEquals("https://public.example.test/guide", pageResult.getValue("links").jsonArray.single()
                .jsonObject.getValue("url").jsonPrimitive.content)
            assertFalse(pageResult.containsKey("error"))
            assertTrue(result.messages.last().toText().contains(summary))
            val executed = result.messages.flatMap { it.getTools() }.single()
            assertEquals("web_fetch", executed.toolName)
            assertTrue(executed.isExecuted)
            assertTrue((executed.output.single() as UIMessagePart.Text).text.contains("Useful Workspace"))
        }
    }

    private fun completion(model: String, message: JsonObject, finishReason: String): String = buildJsonObject {
        put("id", "fixture-completion")
        put("object", "chat.completion")
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("message", message)
                put("finish_reason", finishReason)
            })
        })
    }.toString()

    private fun response(request: Request, body: String, type: String = "application/json", status: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Fixture")
            .body(body.toResponseBody(type.toMediaType()))
            .build()
}
