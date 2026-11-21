package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.constx.onvyra.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMetadataPersistenceTest {
    @Test fun staleGenerationWaitsForArchiveCommitAndRetainsArchiveLockPinAndProject() = runBlocking {
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(initial.id, initial, this, {})
        val databaseEntered = CompletableDeferred<Unit>()
        val allowDatabase = CompletableDeferred<Unit>()
        val folder = Uuid.random()
        var stored = initial
        var saveEntered = false
        val menu = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(
                update = { it.copy(isPinned = true, isArchived = true, isLocked = true, folderId = folder) },
                persist = { databaseEntered.complete(Unit); allowDatabase.await(); stored = it },
            )
        }
        databaseEntered.await()
        val generation = launch(start = CoroutineStart.UNDISPATCHED) {
            session.withPersistence {
                saveEntered = true
                val merged = session.preserveMetadata(initial.copy(title = "late generated title"))
                session.updateConversation(merged)
                stored = merged
            }
        }
        assertFalse("whole save must not overtake the suspended metadata database write", saveEntered)
        allowDatabase.complete(Unit)
        menu.join(); generation.join()
        assertEquals("late generated title", stored.title)
        assertTrue(stored.isPinned)
        assertTrue(stored.isArchived)
        assertTrue(stored.isLocked)
        assertEquals(folder, stored.folderId)
    }

    @Test fun archiveQueuedBehindAnOldSaveWinsAndAnExplicitRestoreStaysRestored() = runBlocking {
        val initial = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val session = ConversationSession(initial.id, initial, this, {})
        val saveEntered = CompletableDeferred<Unit>()
        val allowSave = CompletableDeferred<Unit>()
        var metadataEntered = false
        var stored = initial
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            session.withPersistence {
                saveEntered.complete(Unit); allowSave.await(); stored = initial
            }
        }
        saveEntered.await()
        val archive = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(update = { it.copy(isArchived = true) }, persist = {
                metadataEntered = true; stored = it
            })
        }
        assertFalse(metadataEntered)
        allowSave.complete(Unit)
        save.join(); archive.join()
        assertTrue(stored.isArchived)
        val oldArchivedSnapshot = stored
        session.updateMetadata(update = { it.copy(isArchived = false) }, persist = { stored = it })
        val merged = session.preserveMetadata(oldArchivedSnapshot.copy(title = "older result"))
        assertFalse("a late archived snapshot cannot undo Restore", merged.isArchived)
        assertFalse(stored.isArchived)
        assertEquals("older result", merged.title)
    }
}
