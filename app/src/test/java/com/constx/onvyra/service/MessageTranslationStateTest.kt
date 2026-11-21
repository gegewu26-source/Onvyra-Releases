package com.constx.onvyra.service

import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.toMessageNode
import com.constx.onvyra.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageTranslationStateTest {
    @Test
    fun `translation commits during a whole save are persisted after that snapshot`() = runBlocking {
        val message = UIMessage.user("source").copy(translation = "old")
        var stored = Conversation.ofId(Uuid.random(), messages = listOf(message.toMessageNode()))
        val session = ConversationSession(stored.id, stored, this, {})
        val staleSnapshot = stored
        val snapshotCaptured = CompletableDeferred<Unit>()
        val allowDatabaseWrite = CompletableDeferred<Unit>()
        val wholeSave = launch(start = CoroutineStart.UNDISPATCHED) {
            session.withPersistence {
                val snapshot = synchronized(session) {
                    staleSnapshot.withLatestTranslationsFrom(session.state.value).also(session::updateConversation)
                }
                snapshotCaptured.complete(Unit)
                allowDatabaseWrite.await()
                stored = snapshot
            }
        }
        snapshotCaptured.await()
        synchronized(session) {
            session.updateConversation(session.state.value.updateCurrentMessages(listOf(
                message.copy(translation = "new result"))))
        }
        val translationSave = launch(start = CoroutineStart.UNDISPATCHED) {
            session.withPersistence {
                stored = stored.updateCurrentMessages(listOf(message.copy(translation = "new result")))
            }
        }
        assertFalse(translationSave.isCompleted)
        allowDatabaseWrite.complete(Unit)
        wholeSave.join()
        translationSave.join()
        assertEquals("new result", stored.currentMessages.single().translation)
        assertEquals("new result", session.state.value.currentMessages.single().translation)
    }

    @Test
    fun `whole save rechecks translation after suspended preparation and cannot revive a clear`() = runBlocking {
        val message = UIMessage.user("source").copy(translation = "old")
        val initial = Conversation.ofId(Uuid.random(), messages = listOf(message.toMessageNode()))
        val session = ConversationSession(initial.id, initial, this, {})
        val preparing = CompletableDeferred<Unit>()
        val resumeSave = CompletableDeferred<Unit>()
        var stored = initial
        val wholeSave = launch(start = CoroutineStart.UNDISPATCHED) {
            session.withPersistence {
                preparing.complete(Unit)
                resumeSave.await()
                stored = synchronized(session) {
                    initial.withLatestTranslationsFrom(session.state.value).also(session::updateConversation)
                }
            }
        }
        preparing.await()
        synchronized(session) {
            session.updateConversation(initial.updateCurrentMessages(listOf(message.copy(translation = null))))
        }
        resumeSave.complete(Unit)
        wholeSave.join()
        assertNull(stored.currentMessages.single().translation)
        assertNull(session.state.value.currentMessages.single().translation)
    }

    @Test
    fun `generation snapshots preserve the latest translation and request status`() {
        val user = UIMessage.user("source")
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("partial")))
        val current = Conversation.ofId(Uuid.random(), messages = listOf(
            user.copy(translation = "completed", translationError = "retry failed").toMessageNode(),
            reply.copy(translation = "older result", isTranslating = true).toMessageNode(),
        )).copy(isPinned = true)

        val next = current.updateCurrentMessagesPreservingTranslations(listOf(user,
            reply.copy(parts = listOf(UIMessagePart.Text("finished reply")))))

        assertTrue(next.isPinned)
        assertEquals("completed", next.currentMessages[0].translation)
        assertEquals("retry failed", next.currentMessages[0].translationError)
        assertEquals("finished reply", next.currentMessages[1].toText())
        assertEquals("older result", next.currentMessages[1].translation)
        assertTrue(next.currentMessages[1].isTranslating)
    }

    @Test
    fun `generation snapshots cannot revive an explicitly cleared translation`() {
        val old = UIMessage.user("source").copy(translation = "old translation", isTranslating = true)
        val current = Conversation.ofId(Uuid.random(), messages = listOf(
            old.copy(translation = null, isTranslating = false).toMessageNode()))
        val next = current.updateCurrentMessagesPreservingTranslations(listOf(old))
        assertNull(next.currentMessages.single().translation)
        assertFalse(next.currentMessages.single().isTranslating)
    }

    @Test
    fun `only completed translations survive serialization and forked conversations`() {
        val message = UIMessage.user("source").copy(translation = "valid result",
            isTranslating = true, translationError = "previous failure")
        val encoded = JsonInstant.encodeToString(message)
        val fields = JsonInstant.parseToJsonElement(encoded).jsonObject
        assertFalse(fields.containsKey("isTranslating"))
        assertFalse(fields.containsKey("translationError"))
        val decoded = JsonInstant.decodeFromString<UIMessage>(encoded)
        assertEquals("valid result", decoded.translation)
        assertFalse(decoded.isTranslating)
        assertNull(decoded.translationError)

        val source = Conversation.ofId(Uuid.random(), messages = listOf(message.toMessageNode()))
        val fork = createForkConversation(source, source.messageNodes)
        assertEquals("valid result", fork.currentMessages.single().translation)
        assertFalse(fork.currentMessages.single().isTranslating)
        assertNull(fork.currentMessages.single().translationError)
    }
}
