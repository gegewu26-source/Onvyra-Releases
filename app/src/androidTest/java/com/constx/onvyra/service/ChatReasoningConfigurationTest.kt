package com.constx.onvyra.service

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.bindConfig
import com.constx.onvyra.data.model.getAssistantOf
import com.constx.onvyra.data.model.getChatModelOf
import com.constx.onvyra.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid
import java.io.File

/** Actual service and stores, with send-without-answer so no model or network is contacted. */
@RunWith(AndroidJUnit4::class)
class ChatReasoningConfigurationTest {
    @Test
    fun pendingSliderAndModelUpdateFinishBeforeAnImmediateNewChatSendIsBound() = withFixture {
        val conversation = newChat()
        assertNull(conversation.config)
        val gate = CompletableDeferred<Unit>()
        val update = scope.launch(start = CoroutineStart.LAZY) {
            gate.await()
            service.updateChatAssistant(conversation.id) { it.copy(reasoningLevel = ReasoningLevel.OFF) }
            service.updateChatModel(conversation.id, modelB.id)
            service.updateChatSearch(conversation.id, enableWebSearch = true, builtInSearch = true)
        }
        try {
            service.trackChatConfigurationUpdate(conversation.id, update)
            service.sendMessage(conversation.id, listOf(UIMessagePart.Text("first input")), answer = false)
            withTimeout(5_000) { service.getGenerationJobStateFlow(conversation.id).first { it != null } }
            assertTrue(service.getConversationFlow(conversation.id).value.currentMessages.isEmpty())
            assertNull(service.getConversationFlow(conversation.id).value.config)
            gate.complete(Unit)
            update.join()
            withTimeout(5_000) { service.getGenerationJobStateFlow(conversation.id).first { it == null } }
            val stored = repository.getConversationById(conversation.id)!!
            assertEquals("first input", stored.currentMessages.single().toText())
            assertEquals(ReasoningLevel.OFF, stored.config!!.reasoningLevel)
            assertEquals(modelB.id, stored.config!!.chatModelId)
            assertTrue(stored.config!!.enableWebSearch)
            assertTrue(stored.config!!.builtInSearch)
            assertEquals(ReasoningLevel.OFF,
                settingsStore.settingsFlow.value.assistants.single { it.id == assistant.id }.reasoningLevel)
        } finally {
            gate.complete(Unit)
            update.join()
        }
    }

    @Test
    fun staleMessageSaveKeepsReasoningModelAndBothSearchSelectionsInMemoryAndStorage() = withFixture {
        val conversation = Conversation.ofId(Uuid.random(), assistant.id)
            .updateCurrentMessages(listOf(UIMessage.user("existing input")))
            .bindConfig(settingsStore.settingsFlow.value)
        ids += conversation.id
        repository.insertConversation(conversation)
        service.addConversationReference(conversation.id)
        service.initializeConversation(conversation.id)
        val oldSnapshot = service.getConversationFlow(conversation.id).value
        service.updateChatAssistant(conversation.id) {
            it.copy(reasoningLevel = ReasoningLevel.OFF, chatModelId = modelB.id)
        }
        service.updateChatSearch(conversation.id, enableWebSearch = false, builtInSearch = true)
        val expected = service.getConversationFlow(conversation.id).value.config!!
        service.saveConversation(conversation.id, oldSnapshot.copy(title = "renamed from an old snapshot"))
        val memory = service.getConversationFlow(conversation.id).value
        val stored = repository.getConversationById(conversation.id)!!
        assertEquals(expected, memory.config)
        assertEquals(expected, stored.config)
        assertEquals(ReasoningLevel.OFF, stored.config!!.reasoningLevel)
        assertEquals(modelB.id, stored.config!!.chatModelId)
        assertEquals(false, stored.config!!.enableWebSearch)
        assertTrue(stored.config!!.builtInSearch)
        assertEquals("renamed from an old snapshot", stored.title)
        assertEquals("existing input", stored.currentMessages.single().toText())
        // Changes in a started chat must still leave the assistant default alone.
        assertEquals(ReasoningLevel.HIGH,
            settingsStore.settingsFlow.value.assistants.single { it.id == assistant.id }.reasoningLevel)
    }

    @Test
    fun explicitChatModelSelectionPersistsAsNewChatDefaultWhileExistingChatsStayIndependent() = withFixture {
        suspend fun startedChat(): Conversation {
            val conversation = Conversation.ofId(Uuid.random(), assistant.id)
                .updateCurrentMessages(listOf(UIMessage.user("existing model fixture")))
                .bindConfig(settingsStore.settingsFlow.value)
            ids += conversation.id
            repository.insertConversation(conversation)
            service.addConversationReference(conversation.id)
            service.initializeConversation(conversation.id)
            return conversation
        }
        val active = startedChat()
        val old = startedChat()
        val anotherAssistant = assistant.copy(id = Uuid.random())
        settingsStore.update { it.copy(assistants = it.assistants + anotherAssistant) }
        service.updateChatModel(active.id, modelB.id)
        assertEquals(modelB.id, service.getConversationFlow(active.id).value.config!!.chatModelId)
        assertEquals(modelA.id, service.getConversationFlow(old.id).value.config!!.chatModelId)
        assertEquals(modelB.id, repository.getConversationById(active.id)!!.config!!.chatModelId)
        assertEquals(modelA.id, repository.getConversationById(old.id)!!.config!!.chatModelId)
        assertEquals(modelA.id,
            settingsStore.settingsFlow.value.assistants.single { it.id == anotherAssistant.id }.chatModelId)

        // An ordinary edit in the old chat must not put its old model back into assistant defaults.
        service.updateChatAssistant(old.id) { it.copy(reasoningLevel = ReasoningLevel.OFF) }
        val reloaded = reloadPersistedSettings()
        val storedDefault = reloaded.assistants.single { it.id == assistant.id }
        assertEquals(modelB.id, storedDefault.chatModelId)
        assertEquals(ReasoningLevel.HIGH, storedDefault.reasoningLevel)
        assertEquals(modelA.id, reloaded.getChatModelOf(repository.getConversationById(old.id)!!)!!.id)

        val next = newChat()
        assertNull(next.config)
        assertEquals(modelB.id, reloaded.getAssistantOf(next).chatModelId)
        assertEquals(modelB.id, settingsStore.settingsFlow.value.getChatModelOf(next)!!.id)
        service.sendMessage(next.id, listOf(UIMessagePart.Text("new chat uses selected model")), answer = false)
        withTimeout(5_000) { service.getGenerationJobStateFlow(next.id).first { it == null } }
        assertEquals(modelB.id, repository.getConversationById(next.id)!!.config!!.chatModelId)
        assertEquals(modelA.id, repository.getConversationById(old.id)!!.config!!.chatModelId)
    }

    private suspend fun reloadPersistedSettings(): Settings {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = File(context.cacheDir, "reasoning-model-default-${System.nanoTime()}.preferences_pb")
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

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(30_000) {
            val fixture = Fixture(CoroutineScope(kotlin.coroutines.coroutineContext))
            fixture.initialize()
            try { fixture.block() } finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    private class Fixture(val scope: CoroutineScope) {
        val service = getKoin().get<ChatService>()
        val repository = getKoin().get<ConversationRepository>()
        val settingsStore = getKoin().get<SettingsStore>()
        val modelA = Model(modelId = "reasoning-config-fixture-a")
        val modelB = Model(modelId = "reasoning-config-fixture-b", tools = setOf(BuiltInTools.Search))
        val provider = ProviderSetting.OpenAI(models = listOf(modelA, modelB),
            baseUrl = "https://never-contact-reasoning-fixture.invalid/v1", apiKey = "")
        val assistant = Assistant(chatModelId = modelA.id, reasoningLevel = ReasoningLevel.HIGH,
            enableMemory = false, enabledSkills = emptySet())
        val ids = mutableListOf<Uuid>()
        lateinit var original: Settings

        suspend fun initialize() {
            original = settingsStore.awaitLoaded()
            settingsStore.update { it.copy(assistantId = assistant.id,
                assistants = it.assistants + assistant, providers = it.providers + provider) }
        }

        suspend fun newChat(): Conversation {
            val id = Uuid.random()
            ids += id
            service.addConversationReference(id)
            service.initializeConversation(id)
            return service.getConversationFlow(id).value
        }

        suspend fun close() {
            ids.forEach { id ->
                service.stopGeneration(id)
                repository.getConversationById(id)?.let { repository.deleteConversation(it) }
                service.removeConversationReference(id)
                service.errors.value.filter { it.conversationId == id }.forEach { service.dismissError(it.id) }
            }
            settingsStore.update { original }
        }
    }
}
