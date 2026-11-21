package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.ConversationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** Gates correspond to the database suspension points used by ChatService whole saves. */
class ConversationChatConfigurationTest {
    @Test
    fun `late whole save preserves explicitly selected reasoning search model and injections`() = runBlocking {
        val initial = configuredConversation()
        val session = ConversationSession(initial.id, initial, this, {})
        val mode = Uuid.random()
        val lorebook = Uuid.random()
        val selected = initial.copy(
            config = initial.config!!.copy(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.OFF,
                enableWebSearch = true, builtInSearch = true),
            modeInjectionIds = setOf(mode), lorebookIds = setOf(lorebook),
        )
        session.updateChatConfiguration(update = { selected }, persist = {})
        val staleSave = initial.copy(title = "new title",
            messageNodes = initial.messageNodes + UIMessage.user("new message").let {
                com.constx.onvyra.data.model.MessageNode.of(it)
            })
        val committed = session.preserveChatConfiguration(staleSave)
        assertEquals(selected.config, committed.config)
        assertEquals(setOf(mode), committed.modeInjectionIds)
        assertEquals(setOf(lorebook), committed.lorebookIds)
        assertEquals(staleSave.messageNodes, committed.messageNodes)
        assertEquals("new title", committed.title)
    }

    @Test
    fun `chat configuration waits for an in flight whole write then persists last`() = runBlocking {
        val initial = configuredConversation()
        val session = ConversationSession(initial.id, initial, this, {})
        val allowWholeWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        var persisted = initial
        val whole = launch(start = CoroutineStart.UNDISPATCHED) {
            session.withPersistence {
                allowWholeWrite.await()
                persisted = session.preserveChatConfiguration(initial)
                session.updateConversation(persisted)
                writes += "whole"
            }
        }
        val configuration = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateChatConfiguration(
                update = { it.copy(config = it.config!!.copy(reasoningLevel = ReasoningLevel.OFF)) },
                persist = { persisted = persisted.copy(config = it.config); writes += "configuration" },
            )
        }
        assertFalse(configuration.isCompleted)
        allowWholeWrite.complete(Unit)
        whole.join()
        configuration.join()
        assertEquals(listOf("whole", "configuration"), writes)
        assertEquals(ReasoningLevel.OFF, persisted.config!!.reasoningLevel)
        assertEquals(ReasoningLevel.OFF, session.state.value.config!!.reasoningLevel)
    }

    @Test
    fun `whole write waits for configuration persistence and keeps its latest state`() = runBlocking {
        val initial = configuredConversation()
        val session = ConversationSession(initial.id, initial, this, {})
        val allowConfigurationWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        var persisted = initial
        val configuration = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateChatConfiguration(
                update = { it.copy(config = it.config!!.copy(reasoningLevel = ReasoningLevel.LOW)) },
                persist = { allowConfigurationWrite.await(); persisted = it; writes += "configuration" },
            )
        }
        val whole = launch(start = CoroutineStart.UNDISPATCHED) {
            session.withPersistence {
                persisted = session.preserveChatConfiguration(initial.copy(title = "late title"))
                session.updateConversation(persisted)
                writes += "whole"
            }
        }
        assertFalse(whole.isCompleted)
        allowConfigurationWrite.complete(Unit)
        configuration.join()
        whole.join()
        assertEquals(listOf("configuration", "whole"), writes)
        assertEquals("late title", persisted.title)
        assertEquals(ReasoningLevel.LOW, persisted.config!!.reasoningLevel)
    }

    @Test
    fun `send waits for a registered update even before its coroutine starts`() = runBlocking {
        val initial = configuredConversation().copy(config = null)
        val session = ConversationSession(initial.id, initial, this, {})
        val allowSettingsWrite = CompletableDeferred<Unit>()
        var defaultLevel = ReasoningLevel.HIGH
        val configuration = launch(start = CoroutineStart.LAZY) {
            session.updateChatConfiguration(update = { it }, persist = {
                allowSettingsWrite.await()
                defaultLevel = ReasoningLevel.OFF
            })
        }
        session.trackChatConfigurationUpdate(configuration)
        var sentLevel: ReasoningLevel? = null
        val send = launch(start = CoroutineStart.UNDISPATCHED) {
            session.awaitChatConfigurationUpdates()
            sentLevel = defaultLevel
        }
        assertFalse(send.isCompleted)
        assertNull(sentLevel)
        allowSettingsWrite.complete(Unit)
        configuration.join()
        send.join()
        assertEquals(ReasoningLevel.OFF, sentLevel)
        assertNull("An empty chat must continue following assistant defaults", session.state.value.config)
    }

    @Test
    fun `new configuration registered while waiting is also completed before sending`() = runBlocking {
        val initial = configuredConversation()
        val session = ConversationSession(initial.id, initial, this, {})
        val allowFirst = CompletableDeferred<Unit>()
        val allowSecond = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.LAZY) { allowFirst.await() }
        session.trackChatConfigurationUpdate(first)
        val send = launch(start = CoroutineStart.UNDISPATCHED) { session.awaitChatConfigurationUpdates() }
        val second = launch(start = CoroutineStart.LAZY) { first.join(); allowSecond.await() }
        session.trackChatConfigurationUpdate(second)
        allowFirst.complete(Unit)
        first.join()
        kotlinx.coroutines.yield()
        assertFalse(send.isCompleted)
        allowSecond.complete(Unit)
        second.start()
        second.join()
        send.join()
        assertTrue(send.isCompleted)
    }

    @Test
    fun `fork and another assistant keep their intended initial configuration`() = runBlocking {
        val initial = configuredConversation()
        val unedited = ConversationSession(initial.id, initial.copy(config = null), this, {})
        assertSame(initial, unedited.preserveChatConfiguration(initial))
        val session = ConversationSession(initial.id, initial, this, {})
        session.updateChatConfiguration(update = { it.copy(config = it.config!!.copy(reasoningLevel = ReasoningLevel.OFF)) },
            persist = {})
        val moved = initial.copy(assistantId = Uuid.random(), config = initial.config!!.copy(reasoningLevel = ReasoningLevel.LOW))
        assertSame(moved, session.preserveChatConfiguration(moved))
        session.updateConversation(moved)
        assertSame(moved, session.preserveChatConfiguration(moved))
    }

    @Test
    fun `workspace switch clears old directory but a same workspace manual cd is preserved`() = runBlocking {
        val initial = configuredConversation().copy(workspaceCwd = "/old")
        val session = ConversationSession(initial.id, initial, this, {})
        session.updateChatConfiguration(update = { it.copy(config = it.config!!.copy(reasoningLevel = ReasoningLevel.LOW)) },
            persist = {})
        assertEquals("/manual", session.preserveChatConfiguration(initial.copy(workspaceCwd = "/manual")).workspaceCwd)
        session.updateChatConfiguration(update = { it.copy(config = it.config!!.copy(workspaceId = Uuid.random()),
            workspaceCwd = null) }, persist = {})
        assertNull(session.preserveChatConfiguration(initial).workspaceCwd)
    }

    @Test
    fun `configuration protection retains current committed translation during a late save`() = runBlocking {
        val initial = configuredConversation()
        val session = ConversationSession(initial.id, initial, this, {})
        session.updateChatConfiguration(update = { it.copy(config = it.config!!.copy(reasoningLevel = ReasoningLevel.OFF)) },
            persist = {})
        session.updateConversation(session.state.value.copy(messageNodes = initial.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.copy(translation = "latest translation", isTranslating = false) })
        }))
        val committed = session.preserveChatConfiguration(initial).withLatestTranslationsFrom(session.state.value)
        assertEquals(ReasoningLevel.OFF, committed.config!!.reasoningLevel)
        assertEquals("latest translation", committed.currentMessages.single().translation)
    }

    private fun configuredConversation() = Conversation(assistantId = Uuid.random(),
        messageNodes = listOf(com.constx.onvyra.data.model.MessageNode.of(UIMessage.user("existing"))),
        config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH,
            workspaceId = Uuid.random()))
}
