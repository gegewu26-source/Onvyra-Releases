package com.constx.onvyra.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.TranslationHandler
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.bindConfig
import com.constx.onvyra.data.model.toMessageNode
import com.constx.onvyra.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Runs the production service/handler against a controlled local provider, never a user's endpoint. */
@RunWith(AndroidJUnit4::class)
class MessageTranslationIntegrationTest {
    @Test
    fun longTranslationPublishesOnceAndPreservesConcurrentEditsAndModelOptions() = withFixture {
        val emissions = CopyOnWriteArrayList<UIMessage>()
        val observer = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext)
            .launch(start = CoroutineStart.UNDISPATCHED) {
            service.getConversationFlow(conversation.id).collect { emissions += it.currentMessages.single() }
        }
        try {
            service.translateMessage(conversation.id, message, Locale.CHINESE)
            val call = provider.awaitCall(0)
            assertEquals(model.customHeaders, call.params.customHeaders)
            assertEquals(model.customBodies, call.params.customBody)
            assertEquals("translation:${conversation.id}:${message.id}", call.params.sessionId)
            assertEquals("previous valid translation", current().translation)
            assertTrue(current().isTranslating)
            call.send(StreamChunk.ReasoningDelta("thinking", "private reasoning"))
            val piece = "long translated text ".repeat(15)
            repeat(120) {
                call.send(StreamChunk.TextDelta("text", piece))
                assertEquals("no intermediate Markdown redraw", "previous valid translation", current().translation)
            }
            service.updateConversationState(conversation.id) { current -> current.copy(title = "renamed", isPinned = true) }
            call.finish()
            awaitFinished()
            assertEquals(piece.repeat(120), current().translation)
            assertNull(current().translationError)
            val final = service.getConversationFlow(conversation.id).value
            assertEquals("renamed", final.title)
            assertTrue(final.isPinned)
            // The translation save updates only its target field, including when persisted metadata changed independently.
            repository.updatePinStatus(conversation.id, true)
            awaitStored(piece.repeat(120))
            assertTrue(repository.getConversationById(conversation.id)!!.isPinned)
            assertTrue("only the final complete result is published",
                emissions.mapNotNull { it.translation }.distinct().size <= 2)
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun duplicateClicksShareOneRequestAndLanguageSwitchIgnoresCancelledFailure() = withFixture {
        service.translateMessage(conversation.id, message, Locale.CHINESE)
        val old = provider.awaitCall(0)
        old.failOnCancellation = true
        service.translateMessage(conversation.id, message, Locale.CHINESE)
        assertEquals(1, provider.calls.size)
        service.translateMessage(conversation.id, message, Locale.JAPANESE)
        val replacement = provider.awaitCall(1)
        withTimeout(5_000) { old.finished.await() }
        assertTrue(current().isTranslating)
        assertNull(current().translationError)
        replacement.send(StreamChunk.TextDelta("text", "new language result"))
        replacement.finish()
        awaitFinished()
        awaitStored("new language result")
        assertEquals("new language result", current().translation)
        assertFalse(service.errors.value.any { it.conversationId == conversation.id })
    }

    @Test
    fun explicitClearCancelsPendingRequestAndSurvivesReopening() = withFixture {
        service.translateMessage(conversation.id, message, Locale.CHINESE)
        val call = provider.awaitCall(0)
        call.failOnCancellation = true
        call.send(StreamChunk.TextDelta("text", "incomplete result"))
        service.clearTranslationField(conversation.id, message.id)
        withTimeout(5_000) { call.finished.await() }
        assertNull(current().translation)
        assertFalse(current().isTranslating)
        assertNull(current().translationError)
        awaitStored(null)
        assertFalse(service.errors.value.any { it.conversationId == conversation.id })
    }

    @Test
    fun lateWholeConversationSaveCannotOverwriteANewTranslationOrReviveAClear() = withFixture {
        val oldSnapshot = service.getConversationFlow(conversation.id).value
        service.translateMessage(conversation.id, message, Locale.CHINESE)
        provider.awaitCall(0).apply {
            send(StreamChunk.TextDelta("text", "new committed translation"))
            finish()
        }
        awaitFinished()
        awaitStored("new committed translation")
        service.saveConversation(conversation.id, oldSnapshot.copy(title = "saved old snapshot"))
        assertEquals("new committed translation", current().translation)
        assertEquals("new committed translation",
            repository.getConversationById(conversation.id)!!.currentMessages.single().translation)
        val translatedSnapshot = service.getConversationFlow(conversation.id).value
        service.clearTranslationField(conversation.id, message.id)
        awaitStored(null)
        service.saveConversation(conversation.id, translatedSnapshot)
        assertNull(current().translation)
        assertNull(repository.getConversationById(conversation.id)!!.currentMessages.single().translation)
    }

    @Test
    fun emptyReasoningOnlyAndFailedStreamsKeepPreviousTranslationAndReportFailure() = withFixture {
        service.translateMessage(conversation.id, message, Locale.CHINESE)
        provider.awaitCall(0).apply {
            send(StreamChunk.ReasoningDelta("thinking", "not the translation"))
            finish()
        }
        awaitFinished()
        assertEquals("previous valid translation", current().translation)
        assertNotNull(current().translationError)

        service.translateMessage(conversation.id, message, Locale.CHINESE)
        assertNull(current().translationError)
        provider.awaitCall(1).apply {
            send(StreamChunk.TextDelta("text", "partial discarded after failure"))
            finish(IOException("fixture network failure"))
        }
        awaitFinished()
        assertEquals("previous valid translation", current().translation)
        assertTrue(current().translationError.orEmpty().contains("fixture network failure"))
        assertEquals("previous valid translation",
            repository.getConversationById(conversation.id)!!.currentMessages.single().translation)
    }

    @Test
    fun changedSourceCannotReceiveAnOldTranslation() = withFixture {
        service.translateMessage(conversation.id, message, Locale.CHINESE)
        val call = provider.awaitCall(0)
        service.updateConversationState(conversation.id) { current ->
            current.copy(messageNodes = current.messageNodes.map { node ->
                node.copy(messages = node.messages.map { it.copy(parts = listOf(UIMessagePart.Text("changed source"))) })
            })
        }
        call.send(StreamChunk.TextDelta("text", "translation of obsolete source"))
        call.finish()
        awaitFinished()
        assertEquals("changed source", current().toText())
        assertEquals("previous valid translation", current().translation)
        assertNotNull(current().translationError)
    }

    @Test
    fun standaloneTranslatorStillStreamsOnlyAssistantTextAndQwenKeepsCustomOptions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ControlledProvider()
        val manager = ProviderManager(okhttp3.OkHttpClient(), context).apply { registerProvider("openai", provider) }
        val handler = TranslationHandler(manager)
        val model = fixtureModel()
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model))
        val settings = Settings(providers = listOf(providerSetting), translateModeId = model.id)
        val updates = CopyOnWriteArrayList<String>()
        val result = CompletableDeferred<List<String>>()
        val task = launch {
            result.complete(handler.translateText(settings, "source", Locale.CHINESE, sessionId = "standalone-session") {
                updates += it
            }.toList())
        }
        val call = provider.awaitCall(0)
        call.send(StreamChunk.ReasoningDelta("r", "thinking only"))
        assertTrue(updates.isEmpty())
        call.send(StreamChunk.TextDelta("t", "translated"))
        call.send(StreamChunk.ReasoningEnd("r"))
        call.send(StreamChunk.TextDelta("t", " text"))
        call.finish()
        task.join()
        assertEquals(listOf("translated", "translated text"), result.await())
        assertEquals(result.await(), updates.toList())
        assertEquals("standalone-session", call.params.sessionId)

        val mt = model.copy(modelId = "qwen-mt-turbo", customBodies = model.customBodies +
            CustomBody("translation_options", JsonPrimitive("must be replaced")))
        handler.translateText(settings.copy(providers = listOf(providerSetting.copy(models = listOf(mt))),
            translateModeId = mt.id), "source", Locale.JAPANESE, sessionId = "mt-session").toList()
        val params = provider.nonStreamingParams!!
        assertEquals(model.customHeaders, params.customHeaders)
        assertTrue(params.customBody.contains(model.customBodies.single()))
        assertEquals(1, params.customBody.count { it.key == "translation_options" })
        assertEquals("mt-session", params.sessionId)
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        fixture.initialize()
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val service = getKoin().get<ChatService>()
        val repository = getKoin().get<ConversationRepository>()
        val settingsStore = getKoin().get<SettingsStore>()
        val manager = getKoin().get<ProviderManager>()
        val originalProvider = manager.getProvider("openai")
        val provider = ControlledProvider()
        val model = fixtureModel()
        val providerSetting = ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://translation-fixture.invalid/v1")
        val assistant = Assistant(chatModelId = model.id)
        val message = UIMessage.user("source text").copy(translation = "previous valid translation")
        val conversation = Conversation(assistantId = assistant.id, messageNodes = listOf(message.toMessageNode()))
        lateinit var originalSettings: Settings

        suspend fun initialize() {
            originalSettings = settingsStore.awaitLoaded()
            manager.registerProvider("openai", provider)
            settingsStore.update { it.copy(providers = it.providers + providerSetting,
                assistants = it.assistants + assistant, translateModeId = model.id) }
            repository.insertConversation(conversation.bindConfig(settingsStore.settingsFlow.value))
            service.addConversationReference(conversation.id)
            service.updateChatAssistant(conversation.id) { it }
        }

        fun current(): UIMessage = service.getConversationFlow(conversation.id).value.currentMessages.single()

        suspend fun awaitFinished() {
            withTimeout(5_000) { service.getConversationFlow(conversation.id).first { !it.currentMessages.single().isTranslating } }
        }

        suspend fun awaitStored(expected: String?) {
            withTimeout(5_000) {
                while (repository.getConversationById(conversation.id)!!.currentMessages.single().translation != expected) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        suspend fun close() {
            service.clearTranslationField(conversation.id, message.id)
            provider.calls.forEach { it.finish() }
            manager.registerProvider("openai", originalProvider)
            repository.deleteConversation(service.getConversationFlow(conversation.id).value)
            service.removeConversationReference(conversation.id)
            service.errors.value.filter { it.conversationId == conversation.id }.forEach { service.dismissError(it.id) }
            settingsStore.update { it.copy(providers = it.providers.filterNot { it.id == providerSetting.id },
                assistants = it.assistants.filterNot { it.id == assistant.id },
                translateModeId = if (it.translateModeId == model.id) originalSettings.translateModeId else it.translateModeId) }
        }
    }

    private class ControlledProvider : Provider<ProviderSetting.OpenAI> {
        val calls = CopyOnWriteArrayList<Call>()
        var nonStreamingParams: TextGenerationParams? = null
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>,
            params: TextGenerationParams): TextGenerationResult {
            nonStreamingParams = params
            return TextGenerationResult("fixture", params.model.modelId,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("translated"))))
        }

        override suspend fun streamText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>,
            params: TextGenerationParams): Flow<StreamChunk> {
            val call = Call(params)
            calls += call
            return flow {
                try {
                    for (event in call.events) {
                        emit(event.chunk)
                        event.processed.complete(Unit)
                    }
                } finally {
                    call.finished.complete(Unit)
                    if (call.failOnCancellation) throw IOException("obsolete cancelled request failure")
                }
            }
        }

        suspend fun awaitCall(index: Int): Call = withTimeout(5_000) {
            while (calls.size <= index) kotlinx.coroutines.delay(10)
            calls[index]
        }
    }

    private data class Event(val chunk: StreamChunk, val processed: CompletableDeferred<Unit> = CompletableDeferred())
    private class Call(val params: TextGenerationParams) {
        val events = Channel<Event>(Channel.UNLIMITED)
        val finished = CompletableDeferred<Unit>()
        @Volatile var failOnCancellation = false
        suspend fun send(chunk: StreamChunk) {
            val event = Event(chunk)
            events.send(event)
            withTimeout(5_000) { event.processed.await() }
        }
        fun finish(error: Throwable? = null) { events.close(error) }
    }

    companion object {
        private fun fixtureModel() = Model(modelId = "translation-fixture", customHeaders = listOf(
            CustomHeader("X-Fixture-Translation", "test")), customBodies = listOf(
            CustomBody("fixture_option", JsonPrimitive(true))))
    }
}
