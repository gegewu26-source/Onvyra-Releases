package com.constx.onvyra.service

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.ChatExtensionSelection
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.Lorebook
import com.constx.onvyra.data.model.PromptInjection
import com.constx.onvyra.data.model.QuickMessage
import com.constx.onvyra.data.model.bindConfig
import com.constx.onvyra.data.model.getAssistantOf
import com.constx.onvyra.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import kotlin.uuid.Uuid

/** Exercises the actual chat service and persistent stores; no generation, network, or UI is used. */
@RunWith(AndroidJUnit4::class)
class ChatExtensionDefaultsTest {
    @Test
    fun selectedExtensionsBecomeNewChatDefaultsWithoutOverwritingOtherConversationSnapshots() = runBlocking {
        withTimeout(60_000) {
            val settingsStore = getKoin().get<SettingsStore>()
            val repository = getKoin().get<ConversationRepository>()
            val service = getKoin().get<ChatService>()
            val original = settingsStore.awaitLoaded()
            val mode = PromptInjection.ModeInjection(name = "Extension defaults fixture", content = "Fixture mode")
            val loreA = Lorebook(name = "Extension defaults A")
            val loreB = Lorebook(name = "Extension defaults B")
            val quick = QuickMessage(title = "Extension defaults fixture", content = "Fixture quick message")
            val modelA = Model(modelId = "extension-fixture-a")
            val modelB = Model(modelId = "extension-fixture-b")
            val provider = ProviderSetting.OpenAI(
                name = "Extension defaults fixture",
                baseUrl = "https://unused.example.test/v1",
                apiKey = "",
                models = listOf(modelA, modelB),
            )
            val assistant = Assistant(
                name = "Extension defaults fixture",
                chatModelId = modelA.id,
                enableMemory = false,
                enabledSkills = emptySet(),
            )
            val otherAssistant = assistant.copy(id = Uuid.random(), name = "Unchanged fixture assistant")
            val selectedSkills = setOf("skill-creator")
            val conversations = mutableListOf<Uuid>()

            fun defaults(settings: Settings = settingsStore.settingsFlow.value): Assistant =
                settings.assistants.single { it.id == assistant.id }

            fun assertExtensions(value: Assistant, modes: Set<Uuid>, lores: Set<Uuid>, skills: Set<String>) {
                assertEquals("mode selections", modes, value.modeInjectionIds)
                assertEquals("lorebook selections", lores, value.lorebookIds)
                assertEquals("skill selections", skills, value.enabledSkills)
            }

            suspend fun startedChat(): Conversation {
                val conversation = Conversation.ofId(Uuid.random(), assistant.id)
                    .updateCurrentMessages(listOf(UIMessage.user("Stored fixture message")))
                    .bindConfig(settingsStore.settingsFlow.value)
                conversations += conversation.id
                repository.insertConversation(conversation)
                service.initializeConversation(conversation.id)
                return conversation
            }

            suspend fun newChat(): Conversation {
                val id = Uuid.random()
                conversations += id
                service.initializeConversation(id)
                return service.getConversationFlow(id).value.also { assertNull(it.config) }
            }

            suspend fun persistedAssistant(id: Uuid, settings: Settings = settingsStore.settingsFlow.value): Assistant {
                val conversation = repository.getConversationById(id)
                assertNotNull("Conversation must have been saved", conversation)
                assertEquals("Configuration changes must retain messages", "Stored fixture message",
                    conversation!!.currentMessages.single().toText())
                return settings.getAssistantOf(conversation)
            }

            try {
                settingsStore.update { settings ->
                    settings.copy(
                        assistantId = assistant.id,
                        assistants = settings.assistants + listOf(assistant, otherAssistant),
                        providers = settings.providers + provider,
                        modeInjections = settings.modeInjections + mode,
                        lorebooks = settings.lorebooks + listOf(loreA, loreB),
                        quickMessages = settings.quickMessages + quick,
                    )
                }
                val first = startedChat()
                val old = startedChat()

                // Concurrent selections must operate on the latest session snapshot rather
                // than each replacing it from the same earlier state while Room suspends.
                coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val changes = listOf(
                        async(Dispatchers.Default) {
                            start.await()
                            service.updateChatAssistant(first.id, ChatExtensionSelection.MODE_INJECTIONS) {
                                it.copy(modeInjectionIds = setOf(mode.id))
                            }
                        },
                        async(Dispatchers.Default) {
                            start.await()
                            service.updateChatAssistant(first.id, ChatExtensionSelection.LOREBOOKS) {
                                it.copy(lorebookIds = setOf(loreA.id))
                            }
                        },
                        async(Dispatchers.Default) {
                            start.await()
                            service.updateChatAssistant(first.id, ChatExtensionSelection.SKILLS) {
                                it.copy(enabledSkills = selectedSkills, quickMessageIds = setOf(quick.id))
                            }
                        },
                    )
                    start.complete(Unit)
                    changes.awaitAll()
                }
                assertExtensions(defaults(), setOf(mode.id), setOf(loreA.id), selectedSkills)
                assertEquals(setOf(quick.id), defaults().quickMessageIds)
                assertExtensions(persistedAssistant(first.id), setOf(mode.id), setOf(loreA.id), selectedSkills)
                assertExtensions(persistedAssistant(old.id), emptySet(), emptySet(), emptySet())
                val inherited = newChat()
                assertExtensions(settingsStore.settingsFlow.value.getAssistantOf(inherited),
                    setOf(mode.id), setOf(loreA.id), selectedSkills)
                assertEquals(setOf(quick.id), settingsStore.settingsFlow.value.getAssistantOf(inherited).quickMessageIds)

                // The older chat has empty extension snapshots. A model/reasoning change must
                // preserve the more recent selections saved by the first chat.
                service.updateChatAssistant(old.id) {
                    it.copy(chatModelId = modelB.id, reasoningLevel = ReasoningLevel.HIGH)
                }
                assertExtensions(defaults(), setOf(mode.id), setOf(loreA.id), selectedSkills)
                val oldAfterModel = persistedAssistant(old.id)
                assertEquals(modelB.id, oldAfterModel.chatModelId)
                assertEquals(ReasoningLevel.HIGH, oldAfterModel.reasoningLevel)
                assertEquals(modelA.id, defaults().chatModelId)
                assertEquals(ReasoningLevel.AUTO, defaults().reasoningLevel)

                // Changing only the lorebook in that stale chat may only replace that default.
                service.updateChatAssistant(old.id) { it.copy(lorebookIds = setOf(loreB.id)) }
                assertExtensions(defaults(), setOf(mode.id), setOf(loreB.id), selectedSkills)
                assertExtensions(persistedAssistant(old.id), emptySet(), setOf(loreB.id), emptySet())
                assertExtensions(persistedAssistant(first.id), setOf(mode.id), setOf(loreA.id), selectedSkills)
                assertExtensions(settingsStore.settingsFlow.value.getAssistantOf(newChat()),
                    setOf(mode.id), setOf(loreB.id), selectedSkills)
                assertExtensions(settingsStore.settingsFlow.value.assistants.single { it.id == otherAssistant.id },
                    emptySet(), emptySet(), emptySet())

                // The old chat already has no mode selected. Explicitly selecting that empty
                // set still changes the default; the first chat retains its bound mode.
                service.updateChatAssistant(old.id, ChatExtensionSelection.MODE_INJECTIONS) { it }
                assertExtensions(defaults(), emptySet(), setOf(loreB.id), selectedSkills)
                assertExtensions(persistedAssistant(first.id), setOf(mode.id), setOf(loreA.id), selectedSkills)
                // Selecting the first chat's unchanged mode again must remember it, while
                // preserving the newer lorebook default selected in the other chat.
                service.updateChatAssistant(first.id, ChatExtensionSelection.MODE_INJECTIONS) { it }
                assertExtensions(defaults(), setOf(mode.id), setOf(loreB.id), selectedSkills)
                assertExtensions(settingsStore.settingsFlow.value.getAssistantOf(newChat()),
                    setOf(mode.id), setOf(loreB.id), selectedSkills)

                // Read a fresh DataStore from the actual persisted bytes, then reload the
                // conversation from Room after discarding the service's in-memory sessions.
                val reloaded = reloadPersistedSettings()
                assertExtensions(defaults(reloaded), setOf(mode.id), setOf(loreB.id), selectedSkills)
                assertEquals(setOf(quick.id), defaults(reloaded).quickMessageIds)
                assertExtensions(persistedAssistant(first.id, reloaded),
                    setOf(mode.id), setOf(loreA.id), selectedSkills)
                service.cleanup()
                service.initializeConversation(first.id)
                assertExtensions(settingsStore.settingsFlow.value.getAssistantOf(service.getConversationFlow(first.id).value),
                    setOf(mode.id), setOf(loreA.id), selectedSkills)

                // Explicitly deselecting extensions is also a remembered choice, including an
                // empty set. Existing chats keep the snapshots they had already saved.
                service.updateChatAssistant(first.id) {
                    it.copy(modeInjectionIds = emptySet(), lorebookIds = emptySet(), enabledSkills = emptySet())
                }
                assertExtensions(defaults(), emptySet(), emptySet(), emptySet())
                assertExtensions(settingsStore.settingsFlow.value.getAssistantOf(newChat()),
                    emptySet(), emptySet(), emptySet())
                assertExtensions(persistedAssistant(old.id), emptySet(), setOf(loreB.id), emptySet())
                assertEquals(setOf(quick.id), defaults().quickMessageIds)
                assertExtensions(defaults(reloadPersistedSettings()), emptySet(), emptySet(), emptySet())
            } finally {
                withContext(NonCancellable) {
                    service.cleanup()
                    try {
                        conversations.forEach { id ->
                            repository.getConversationById(id)?.let { repository.deleteConversation(it) }
                        }
                    } finally {
                        settingsStore.update { original }
                    }
                }
            }
        }
    }

    private suspend fun reloadPersistedSettings(): Settings {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = File(context.cacheDir, "extension-reload-${System.nanoTime()}.preferences_pb")
        context.preferencesDataStoreFile("settings").copyTo(snapshot)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        return try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { snapshot })
            SettingsStore(dataStore, scope).awaitLoaded()
        } finally {
            scope.cancel()
            job.join()
            snapshot.delete()
        }
    }
}
