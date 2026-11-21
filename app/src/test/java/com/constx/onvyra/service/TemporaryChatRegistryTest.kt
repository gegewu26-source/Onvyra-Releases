package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.TemporaryChatMode
import com.constx.onvyra.data.model.forTemporaryChat
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class TemporaryChatRegistryTest {
    @Test fun temporaryChoiceIsIsolatedAndOffRestoresTheOriginalEmptyPage() {
        val registry = TemporaryChatRegistry()
        val first = emptyConversation()
        val second = emptyConversation()
        val flow = registry.modeFlow(first.id)
        assertEquals(TemporaryChatMode.OFF, flow.value)
        assertFalse(registry.blocksStorage(first.id))
        registry.change(first.id, TemporaryChatMode.NON_PERSONALIZED, first)
        assertEquals(TemporaryChatMode.NON_PERSONALIZED, flow.value)
        assertTrue(registry.blocksStorage(first.id))
        assertFalse(registry.blocksStorage(second.id))
        registry.change(first.id, TemporaryChatMode.PERSONALIZED, first.copy(title = "not the original"))
        assertSame("personalized selection recovers the initial presets rather than the nonpersonalized empty page",
            first, registry.original(first.id))
        assertSame(first, registry.change(first.id, TemporaryChatMode.OFF, first))
        assertFalse(registry.blocksStorage(first.id))
        assertFalse(registry.close(first.id))
    }

    @Test fun closedIdsRemainBlockedAgainstLateWritesAndCannotBeReopened() {
        val registry = TemporaryChatRegistry()
        val conversation = emptyConversation()
        registry.change(conversation.id, TemporaryChatMode.PERSONALIZED, conversation)
        assertTrue(registry.close(conversation.id))
        assertTrue(registry.isClosed(conversation.id))
        assertTrue(registry.blocksStorage(conversation.id))
        assertFalse(registry.close(conversation.id))
        assertThrows(IllegalStateException::class.java) {
            registry.change(conversation.id, TemporaryChatMode.OFF, conversation)
        }
        assertTrue(registry.blocksStorage(conversation.id))
    }

    @Test fun nonPersonalizedRequestsRetainModelParametersButRemovePersonalization() {
        val source = Assistant(chatModelId = Uuid.random(), name = "personal name", systemPrompt = "secret persona",
            enableMemory = true, useGlobalMemory = true, enableRecentChatsReference = true,
            messageTemplate = "secret {{ message }}", presetMessages = listOf(UIMessage.user("preset secret")),
            modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
            enabledSkills = setOf("private-skill"), mcpServers = setOf(Uuid.random()), workspaceId = Uuid.random(),
            reasoningLevel = ReasoningLevel.HIGH, androidControlPermissionMode = AndroidControlPermissionMode.FULL_ACCESS,
            temperature = .7f, topP = .8f, maxTokens = 1000, customHeaders = listOf(CustomHeader("X-Test", "local")))
        val isolated = source.forTemporaryChat(TemporaryChatMode.NON_PERSONALIZED)
        assertEquals(source.id, isolated.id)
        assertEquals(source.chatModelId, isolated.chatModelId)
        assertEquals(source.reasoningLevel, isolated.reasoningLevel)
        assertEquals(source.androidControlPermissionMode, isolated.androidControlPermissionMode)
        assertEquals(source.customHeaders, isolated.customHeaders)
        assertEquals(source.maxTokens, isolated.maxTokens)
        assertEquals(source.temperature, isolated.temperature)
        assertEquals(source.topP, isolated.topP)
        assertEquals("", isolated.systemPrompt)
        assertEquals("{{ message }}", isolated.messageTemplate)
        assertFalse(isolated.enableMemory)
        assertFalse(isolated.enableRecentChatsReference)
        assertTrue(isolated.presetMessages.isEmpty())
        assertTrue(isolated.modeInjectionIds.isEmpty())
        assertTrue(isolated.lorebookIds.isEmpty())
        assertTrue(isolated.enabledSkills.isEmpty())
        assertTrue(isolated.mcpServers.isEmpty())
        assertTrue(isolated.localTools.isEmpty())
        assertNull(isolated.workspaceId)
        assertSame(source, source.forTemporaryChat(TemporaryChatMode.PERSONALIZED))
        assertSame(source, source.forTemporaryChat(TemporaryChatMode.OFF))
    }

    @Test fun closingBeforeJobCancellationBlocksItsNonCancellableFinalSave() = runBlocking {
        val registry = TemporaryChatRegistry()
        val conversation = emptyConversation()
        registry.change(conversation.id, TemporaryChatMode.PERSONALIZED, conversation)
        val session = ConversationSession(conversation.id, conversation, this, {})
        val started = CompletableDeferred<Unit>()
        var attemptedLateSave = false
        var persisted = false
        val generation = launch {
            try { started.complete(Unit); awaitCancellation() }
            finally { session.finishGeneration { attemptedLateSave = true; persisted = !registry.blocksStorage(it.id) } }
        }
        session.setJob(generation)
        started.await()
        assertTrue(registry.close(conversation.id))
        session.cleanup()
        generation.join()
        assertTrue(generation.isCancelled)
        assertTrue(attemptedLateSave)
        assertFalse(persisted)
    }

    private fun emptyConversation() = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
}
