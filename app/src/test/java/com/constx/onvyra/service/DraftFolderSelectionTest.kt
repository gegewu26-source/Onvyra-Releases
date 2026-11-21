package com.constx.onvyra.service

import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.Folder
import com.constx.onvyra.data.model.toMessageNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DraftFolderSelectionTest {
    @Test fun selectionIsAssistantScopedAndDefaultAndDeletionClearTheDestination() {
        val selection = DraftFolderSelection()
        val assistant = Uuid.random()
        val folder = Uuid.random()
        selection.select(assistant, folder)
        assertEquals(folder, selection.selectedFor(assistant))
        assertNull(selection.selectedFor(Uuid.random()))
        selection.clear(Uuid.random())
        assertEquals(folder, selection.selectedFor(assistant))
        selection.clear(folder)
        assertNull(selection.selectedFor(assistant))
        selection.select(assistant, folder)
        selection.select(assistant, null)
        assertNull(selection.selectedFor(assistant))
    }

    @Test fun eachNewSessionCapturesItsDestinationWithoutMovingAnAlreadyOpenedSession() = runBlocking {
        val assistant = Uuid.random()
        val firstFolder = Uuid.random()
        val nextFolder = Uuid.random()
        val selection = DraftFolderSelection().apply { select(assistant, firstFolder) }
        val manager = ConversationSessionManager(this, { id ->
            Conversation.ofId(id, assistantId = assistant).copy(folderId = selection.selectedFor(assistant))
        }, { _, _ -> })
        val first = manager.getOrCreate(Uuid.random())
        selection.select(assistant, nextFolder)
        val next = manager.getOrCreate(Uuid.random())
        assertEquals(firstFolder, first.state.value.folderId)
        assertEquals(nextFolder, next.state.value.folderId)
        selection.select(assistant, null)
        assertNull(manager.getOrCreate(Uuid.random()).state.value.folderId)
        assertEquals(firstFolder, manager.getOrCreate(first.id).state.value.folderId)
        manager.cleanup()
    }

    @Test fun onlyAnExistingFolderOwnedByTheDraftAssistantCanBeInherited() {
        val assistant = Uuid.random()
        val folder = Folder(assistantId = assistant, name = "test")
        assertEquals(folder.id, validDraftFolder(assistant, folder.id, folder))
        assertNull(validDraftFolder(assistant, folder.id, null)) // Deleted after selection.
        assertNull(validDraftFolder(Uuid.random(), folder.id, folder))
        assertNull(validDraftFolder(assistant, Uuid.random(), folder))
        assertNull(validDraftFolder(assistant, null, folder))
    }

    @Test fun savedOrStartedOrTemporaryConversationsNeverFollowTheDrawer() {
        val draft = Conversation.ofId(Uuid.random(), assistantId = Uuid.random(), newConversation = true)
        assertTrue(draft.canFollowSelectedFolder(false, false, emptyList()))
        assertFalse(draft.canFollowSelectedFolder(true, false, emptyList()))
        assertFalse(draft.canFollowSelectedFolder(false, true, emptyList()))
        assertFalse(draft.copy(newConversation = false).canFollowSelectedFolder(false, false, emptyList()))
        assertFalse(draft.copy(title = "saved name").canFollowSelectedFolder(false, false, emptyList()))
        val started = draft.copy(messageNodes = listOf(message(MessageRole.USER, "hello").toMessageNode()))
        assertFalse(started.canFollowSelectedFolder(false, false, emptyList()))
    }

    @Test fun pristinePresetMessagesDoNotPreventSelectingANewDraftDestination() {
        val preset = listOf(message(MessageRole.ASSISTANT, "welcome"))
        val draft = Conversation.ofId(Uuid.random(), assistantId = Uuid.random(), newConversation = true)
            .updateCurrentMessages(preset)
        assertTrue(draft.canFollowSelectedFolder(false, false, preset))
        assertFalse(draft.copy(messageNodes = draft.messageNodes + message(MessageRole.USER, "hello").toMessageNode())
            .canFollowSelectedFolder(false, false, preset))
    }

    @Test fun immediateFirstSendWaitsForTheFolderChoiceAndPreservesItAgainstAnOldSnapshot() = runBlocking {
        val initial = Conversation.ofId(Uuid.random(), assistantId = Uuid.random(), newConversation = true)
        val session = ConversationSession(initial.id, initial, this, {})
        val folder = Uuid.random()
        val lookupStarted = CompletableDeferred<Unit>()
        val lookupFinished = CompletableDeferred<Unit>()
        val queuedFolderUpdate = launch(start = CoroutineStart.LAZY) {
            lookupStarted.complete(Unit)
            lookupFinished.await()
            session.updateMetadata(update = { it.copy(folderId = folder) }, persist = {})
        }
        session.trackChatConfigurationUpdate(queuedFolderUpdate)
        queuedFolderUpdate.start()
        lookupStarted.await()
        var saved: Conversation? = null
        val send = launch(start = CoroutineStart.UNDISPATCHED) {
            session.awaitChatConfigurationUpdates()
            session.withPersistence {
                saved = session.preserveMetadata(initial.copy(messageNodes =
                    listOf(message(MessageRole.USER, "first message").toMessageNode())))
            }
        }
        assertNull(saved)
        lookupFinished.complete(Unit)
        send.join()
        assertEquals(folder, saved?.folderId)
        assertEquals("first message", (saved!!.currentMessages.single().parts.single() as UIMessagePart.Text).text)
    }

    private fun message(role: MessageRole, text: String) = UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))
}
