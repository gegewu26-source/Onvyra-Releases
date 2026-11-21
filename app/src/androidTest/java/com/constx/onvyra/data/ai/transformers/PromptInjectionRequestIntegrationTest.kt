package com.constx.onvyra.data.ai.transformers

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.AppScope
import com.constx.onvyra.data.ai.GenerationLoop
import com.constx.onvyra.data.datastore.NetworkSetting
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.InjectionPosition
import com.constx.onvyra.data.model.MessageNode
import com.constx.onvyra.data.model.PromptInjection
import com.constx.onvyra.data.model.bindConfig
import com.constx.onvyra.data.model.getAssistantOf
import com.constx.onvyra.data.repository.WorkspaceRepository
import com.constx.onvyra.data.repository.ConversationRepository
import com.constx.onvyra.service.ChatService
import com.constx.onvyra.ui.components.ai.ModeInjectionsContent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/** Verifies the actual /v1 JSON body, without relying on what a model says about its instructions. */
@RunWith(AndroidJUnit4::class)
class PromptInjectionRequestIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun globallyDisabledModesStayHiddenAndNeverReachTheSameChatRequest() {
        val settingsStore = getKoin().get<SettingsStore>()
        val mode = PromptInjection.ModeInjection(
            name = "Paused mode fixture",
            content = "selected-mode-from-extension-selector",
            enabled = false,
        )
        val unboundPaused = PromptInjection.ModeInjection(name = "Unbound paused fixture", enabled = false)
        val fixtureModeIds = setOf(mode.id, unboundPaused.id)
        val model = Model(modelId = "gpt6luna")
        val assistant = Assistant(chatModelId = model.id, streamOutput = false,
            modeInjectionIds = setOf(mode.id), localTools = emptyList())
        val provider = fixtureSettings(model, assistant, listOf(mode)).providers.single()
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("current-question")))),
            modeInjectionIds = setOf(mode.id),
        )
        val chatService = getKoin().get<ChatService>()
        val conversations = getKoin().get<ConversationRepository>()
        val appScope = getKoin().get<AppScope>()
        val events = CopyOnWriteArrayList<Boolean>()
        val originalAssistantId = runBlocking {
            val originalAssistantId = settingsStore.awaitLoaded().assistantId
            settingsStore.update { it.copy(
                modeInjections = it.modeInjections + listOf(mode, unboundPaused),
                assistants = it.assistants + assistant,
                providers = it.providers + provider,
            ) }
            conversations.insertConversation(conversation.bindConfig(settingsStore.settingsFlow.value))
            chatService.addConversationReference(conversation.id)
            // Initialize through the production binding entry point without sending a model request.
            chatService.updateChatAssistant(conversation.id) { it }
            originalAssistantId
        }
        try {
            compose.setContent {
                val settings = settingsStore.settingsFlow.collectAsState().value
                val currentConversation = chatService.getConversationFlow(conversation.id).collectAsState().value
                val currentAssistant = settings.getAssistantOf(currentConversation)
                MaterialExpressiveTheme {
                    ModeInjectionsContent(
                        modeInjections = settings.modeInjections.filter { it.id in fixtureModeIds },
                        selectedIds = currentAssistant.modeInjectionIds,
                        onToggle = { id, checked ->
                            assertTrue("only an enabled mode can be selected",
                                settingsStore.settingsFlow.value.modeInjections.single { it.id == id }.enabled)
                            appScope.launch {
                                chatService.updateChatAssistant(conversation.id) { current ->
                                    current.copy(modeInjectionIds = if (checked) current.modeInjectionIds + id
                                        else current.modeInjectionIds - id)
                                }
                                events += checked
                            }
                        },
                    )
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(mode.name).assertDoesNotExist()
            compose.onNodeWithText(unboundPaused.name).assertDoesNotExist()
            assertTrue(events.isEmpty())
            assertFalse("opening the extension list must not enable a globally disabled mode",
                settingsStore.settingsFlow.value.modeInjections.single { it.id == mode.id }.enabled)
            val initialSettings = settingsStore.settingsFlow.value
            val initialConversation = chatService.getConversationFlow(conversation.id).value
            val initialRequests = runBlocking {
                generate(initialSettings, initialSettings.getAssistantOf(initialConversation), model,
                    initialConversation.currentMessages)
            }
            assertFalse(initialRequests.single().getValue("messages").toString().contains(mode.content))

            // Restore it through the management state. The list itself never changes enabled.
            runBlocking {
                settingsStore.update { it.copy(modeInjections = it.modeInjections.map { current ->
                    if (current.id == mode.id) current.copy(enabled = true) else current
                }) }
            }
            compose.waitForIdle()
            compose.onNodeWithText(mode.name).assertExists()
            compose.onNodeWithText(unboundPaused.name).assertDoesNotExist()
            // It was already bound before global disable; remove it, then explicitly reselect
            // it in this same persisted chat through the real ChatService callback.
            compose.onNodeWithText(mode.name).performClick()
            compose.waitUntil(10_000) { events.size == 1 }
            assertEquals(listOf(false), events.toList())
            compose.onNodeWithText(mode.name).performClick()
            compose.waitUntil(10_000) { events.size == 2 }
            assertEquals(listOf(false, true), events.toList())
            val enabledSettings = settingsStore.settingsFlow.value
            val enabledConversation = chatService.getConversationFlow(conversation.id).value
            assertTrue(enabledSettings.getAssistantOf(enabledConversation).modeInjectionIds.contains(mode.id))
            val requests = runBlocking {
                generate(enabledSettings, enabledSettings.getAssistantOf(enabledConversation), model,
                    enabledConversation.currentMessages)
            }
            assertTrue(requests.single().getValue("messages").toString().contains(mode.content))

            runBlocking {
                settingsStore.update { it.copy(modeInjections = it.modeInjections.map { current ->
                    if (current.id == mode.id) current.copy(enabled = false) else current
                }) }
            }
            compose.waitForIdle()
            compose.onNodeWithText(mode.name).assertDoesNotExist()
            compose.onNodeWithText(unboundPaused.name).assertDoesNotExist()
            assertEquals("management disable must not cause another chat toggle", 2, events.size)
            val disabledSettings = settingsStore.settingsFlow.value
            val disabledConversation = chatService.getConversationFlow(conversation.id).value
            assertTrue("global disable must work even while the chat retains its binding",
                disabledSettings.getAssistantOf(disabledConversation).modeInjectionIds.contains(mode.id))
            assertFalse("the chat list must respect the management page's global disable",
                disabledSettings.modeInjections.single { it.id == mode.id }.enabled)
            val closedRequests = runBlocking {
                generate(disabledSettings, disabledSettings.getAssistantOf(disabledConversation), model,
                    disabledConversation.currentMessages)
            }
            assertFalse(closedRequests.single().getValue("messages").toString().contains(mode.content))
        } finally {
            chatService.removeConversationReference(conversation.id)
            runBlocking {
                conversations.deleteConversation(chatService.getConversationFlow(conversation.id).value)
                settingsStore.update { it.copy(
                    modeInjections = it.modeInjections.filter { existing -> existing.id !in fixtureModeIds },
                    assistants = it.assistants.filter { existing -> existing.id != assistant.id },
                    providers = it.providers.filter { existing -> existing.id != provider.id },
                    assistantId = if (it.assistantId == assistant.id) originalAssistantId else it.assistantId,
                ) }
            }
        }
    }

    @Test
    fun selectedModesReachTheRequestAtEveryPositionWithAnEmptySystemPrompt() = runBlocking {
        for (position in InjectionPosition.entries) {
            val marker = "active-mode-${position.name}"
            val mode = PromptInjection.ModeInjection(content = marker, position = position)
            val model = Model(modelId = "gpt6luna")
            val assistant = Assistant(
                chatModelId = model.id,
                streamOutput = false,
                systemPrompt = "",
                contextMessageLimit = 1,
                modeInjectionIds = setOf(mode.id),
                localTools = emptyList(),
            )
            val settings = fixtureSettings(model, assistant, listOf(mode))
            val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(settings)
            val requests = generate(
                settings = settings,
                assistant = settings.getAssistantOf(conversation),
                model = model,
                messages = listOf(
                    UIMessage.user("discarded-old-input"),
                    UIMessage.assistant("discarded-old-reply"),
                    UIMessage.user("current-question"),
                ),
            )

            val wire = requests.single().getValue("messages").jsonArray.toString()
            val systemPosition = position == InjectionPosition.BEFORE_SYSTEM_PROMPT || position == InjectionPosition.AFTER_SYSTEM_PROMPT
            assertEquals("$position must survive all input transformers and context truncation", if (systemPosition) 2 else 1,
                Regex(Regex.escape(marker)).findAll(wire).count())
            assertTrue(wire.contains("current-question"))
            assertFalse(wire.contains("discarded-old-input"))
        }
    }

    @Test
    fun conversationSelectionAndCustomSystemPromptSurviveCheckpointAndTemplateProcessing() = runBlocking {
        val model = Model(modelId = "gemini3.8")
        val selected = PromptInjection.ModeInjection(content = "selected-conversation-mode")
        val oldDefault = PromptInjection.ModeInjection(content = "old-assistant-default")
        val assistant = Assistant(
            chatModelId = model.id,
            streamOutput = false,
            systemPrompt = "replaced-assistant-system",
            allowConversationSystemPrompt = true,
            modeInjectionIds = setOf(oldDefault.id),
            localTools = emptyList(),
            // The synthetic system/injection must bypass a user-message template.
            messageTemplate = "[{{ role }}] {{ message }}",
        )
        val settings = fixtureSettings(model, assistant, listOf(selected, oldDefault))
        val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList()).bindConfig(settings)
            .copy(modeInjectionIds = setOf(selected.id), customSystemPrompt = "conversation-system")
        val requests = generate(
            settings = settings,
            assistant = settings.getAssistantOf(conversation),
            model = model,
            conversationSystemPrompt = conversation.customSystemPrompt,
            messages = listOf(
                UIMessage.user("discarded-before-checkpoint"),
                UIMessage.user("summary-checkpoint").copy(isContextCheckpoint = true),
                UIMessage.user("latest-question"),
            ),
        )

        val messages = requests.single().getValue("messages").jsonArray.map { it.jsonObject }
        val system = messages.single { it.getValue("role").jsonPrimitive.content == "system" }
            .getValue("content").jsonPrimitive.content
        assertTrue(system.contains("conversation-system"))
        assertTrue(system.contains("selected-conversation-mode"))
        assertFalse(system.contains("[system]"))
        assertFalse(system.contains("replaced-assistant-system"))
        val wire = messages.toString()
        assertFalse(wire.contains("old-assistant-default"))
        assertFalse(wire.contains("discarded-before-checkpoint"))
        assertTrue(wire.contains("summary-checkpoint"))
        assertTrue(wire.contains("[user] latest-question"))
    }

    @Test
    fun modeIsDeliveredOncePerRoleOnEveryToolRoundAndDisappearsWhenDeselected() = runBlocking {
        val model = Model(modelId = "gpt6luna", abilities = listOf(ModelAbility.TOOL))
        val mode = PromptInjection.ModeInjection(content = "persistent-mode-instruction")
        val assistant = Assistant(
            chatModelId = model.id,
            streamOutput = false,
            systemPrompt = "base-system",
            modeInjectionIds = setOf(mode.id),
            localTools = emptyList(),
        )
        val settings = fixtureSettings(model, assistant, listOf(mode))
        val tool = Tool(
            name = "fixture_tool",
            description = "Returns a local fixture result",
            parameters = { InputSchema.Obj(buildJsonObject { }, emptyList()) },
            execute = { listOf(UIMessagePart.Text("fixture-tool-result")) },
        )
        val requests = generate(
            settings = settings,
            assistant = assistant,
            model = model,
            messages = listOf(UIMessage.user("use-fixture-tool")),
            tools = listOf(tool),
        )

        assertEquals(2, requests.size)
        requests.forEach { request ->
            val wire = request.getValue("messages").jsonArray.toString()
            val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
            for (role in listOf("system", "user")) {
                val text = messages.filter { it.getValue("role").jsonPrimitive.content == role }.toString()
                assertEquals("one mode delivery per role in each tool round", 1,
                    Regex("persistent-mode-instruction").findAll(text).count())
            }
            assertTrue(wire.contains("base-system"))
        }
        assertTrue(requests.last().getValue("messages").toString().contains("fixture-tool-result"))

        val deselected = generate(
            settings = settings,
            assistant = assistant.copy(modeInjectionIds = emptySet()),
            model = model,
            messages = listOf(UIMessage.user("normal-question")),
        )
        assertFalse(deselected.single().getValue("messages").toString().contains(mode.content))
    }

    @Test
    fun compatibilityIsOnlyAppliedToOpenAiChatCompletions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = Model(modelId = "fixture-model")
        val mode = PromptInjection.ModeInjection(content = "mode compatibility requirement")
        val assistant = Assistant(modeInjectionIds = setOf(mode.id))
        val chat = ProviderSetting.OpenAI(models = listOf(model))
        val providers = listOf(chat, chat.copy(useResponseApi = true), ProviderSetting.Google(models = listOf(model)))
        providers.forEachIndexed { index, provider ->
            val settings = Settings(providers = listOf(provider), modeInjections = listOf(mode))
            val result = PromptInjectionTransformer.transform(
                TransformerContext(context, model, assistant, settings),
                listOf(UIMessage.user("current question")),
            )
            assertEquals(if (index == 0) 1 else 0, result.count {
                it.role == me.rerere.ai.core.MessageRole.USER && it.isSynthetic && it.toText() == mode.content
            })
            assertTrue(result.first().toText().contains(mode.content))
        }
    }

    private fun fixtureSettings(
        model: Model,
        assistant: Assistant,
        modes: List<PromptInjection.ModeInjection>,
    ) = Settings(
        providers = listOf(ProviderSetting.OpenAI(
            name = "Fixture /v1 relay",
            baseUrl = "https://injection-fixture.example.test/v1",
            apiKey = "",
            models = listOf(model),
        )),
        assistants = listOf(assistant),
        assistantId = assistant.id,
        chatModelId = model.id,
        modeInjections = modes,
        networkSetting = NetworkSetting(enableAutoRetry = false),
    )

    private suspend fun generate(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        messages: List<UIMessage>,
        conversationSystemPrompt: String? = null,
        tools: List<Tool> = emptyList(),
    ): List<JsonObject> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requests = CopyOnWriteArrayList<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "injection-fixture.example.test")
            check(request.url.encodedPath == "/v1/chat/completions")
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            requests += Json.parseToJsonElement(body).jsonObject
            val callTool = tools.isNotEmpty() && requests.size == 1
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("Fixture")
                .body(completion(model.modelId, callTool).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        try {
            val transformers = listOf(
                TimeReminderTransformer,
                PromptInjectionTransformer,
                PlaceholderTransformer,
                DocumentAsPromptTransformer,
                OcrTransformer,
                TemplateTransformer(templateEngine(assistant.messageTemplate), getKoin().get<SettingsStore>()),
                WorkspaceReminderTransformer(getKoin().get<WorkspaceRepository>()),
            )
            GenerationLoop(context, ProviderManager(client, context), Json).generateText(
                settings = settings,
                model = model,
                assistant = assistant,
                conversationId = Uuid.random(),
                conversationSystemPrompt = conversationSystemPrompt,
                messages = messages,
                inputTransformers = transformers,
                tools = tools,
                maxSteps = 3,
            ).toList()
            return requests.toList()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun templateEngine(template: String): PebbleEngine = PebbleEngine.Builder().loader(object : Loader<String> {
        override fun getReader(cacheKey: String?): Reader = StringReader(template)
        override fun setCharset(charset: String?) = Unit
        override fun setPrefix(prefix: String?) = Unit
        override fun setSuffix(suffix: String?) = Unit
        override fun resolveRelativePath(relativePath: String?, anchorPath: String?): String? = relativePath
        override fun createCacheKey(templateName: String?): String? = templateName
        override fun resourceExists(templateName: String?): Boolean = true
    }).build()

    private fun completion(model: String, callTool: Boolean): String = buildJsonObject {
        put("id", "mode-fixture-completion")
        put("object", "chat.completion")
        put("model", model)
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", 0)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", if (callTool) JsonNull else kotlinx.serialization.json.JsonPrimitive("fixture-reply"))
                    if (callTool) put("tool_calls", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "fixture-call")
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "fixture_tool")
                                put("arguments", "{}")
                            })
                        })
                    })
                })
                put("finish_reason", if (callTool) "tool_calls" else "stop")
            })
        })
    }.toString()
}
