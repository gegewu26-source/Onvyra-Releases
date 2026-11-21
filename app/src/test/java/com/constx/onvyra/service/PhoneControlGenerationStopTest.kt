package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.constx.onvyra.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PhoneControlGenerationStopTest {
    @Test fun `phone close cancels the actual model job and its queued approval continuation`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), this, {})
        val modelStarted = CompletableDeferred<Unit>()
        val modelFinished = CompletableDeferred<Unit>()
        val model = launch(start = CoroutineStart.LAZY) {
            try { modelStarted.complete(Unit); awaitCancellation() }
            finally { modelFinished.complete(Unit) }
        }
        session.setJob(model)
        modelStarted.await()
        val phoneRun = session.generationRunToken()
        var approvalExecuted = false
        val approval = launch(start = CoroutineStart.LAZY) {
            afterPreviousGeneration(model) { approvalExecuted = true }
        }
        session.setJob(approval, cancelPrevious = false)

        val stopped = requireNotNull(session.cancelGenerationRun(phoneRun))
        assertTrue(model.isCancelled)
        assertTrue(approval.isCancelled)
        assertTrue(session.messageQueue.state.value.paused)
        stopped.forEach { it.join() }
        modelFinished.await()
        assertFalse(approvalExecuted)
        assertFalse(session.isGenerating)
        assertNull("Repeated close is idempotent", session.cancelGenerationRun(phoneRun))
        session.cleanup()
    }

    @Test fun `closed phone run cannot resume through a later approval but new user generation can run`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), this, {})
        val completed = launch(start = CoroutineStart.LAZY) {}
        session.setJob(completed)
        completed.join()
        val oldRun = session.generationRunToken()
        requireNotNull(session.cancelGenerationRun(oldRun))

        var resumed = false
        val staleApproval = launch(start = CoroutineStart.LAZY) { resumed = true }
        session.setJob(staleApproval, cancelPrevious = false)
        staleApproval.join()
        assertTrue(staleApproval.isCancelled)
        assertFalse(resumed)

        val newStarted = CompletableDeferred<Unit>()
        val newModel = launch(start = CoroutineStart.LAZY) { newStarted.complete(Unit); awaitCancellation() }
        session.setJob(newModel)
        newStarted.await()
        assertNotSame(oldRun, session.generationRunToken())
        assertNull("An old window must not cancel a newer user request", session.cancelGenerationRun(oldRun))
        assertTrue(newModel.isActive)
        session.cancelJobs().forEach { it.join() }
        session.cleanup()
    }

    @Test fun `phone close affects only the owning conversation and preserves its run across approval`() = runBlocking {
        val a = Uuid.random(); val b = Uuid.random()
        val first = ConversationSession(a, Conversation.ofId(a), this, {})
        val second = ConversationSession(b, Conversation.ofId(b), this, {})
        val firstJob = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        val otherJob = launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        first.setJob(firstJob); second.setJob(otherJob)
        val token = first.generationRunToken()
        val approval = launch(start = CoroutineStart.LAZY) { afterPreviousGeneration(firstJob) {} }
        first.setJob(approval, cancelPrevious = false)
        assertSame(token, first.generationRunToken())
        requireNotNull(first.cancelGenerationRun(token)).forEach { it.join() }
        assertTrue(otherJob.isActive)
        assertFalse(second.messageQueue.state.value.paused)
        second.cancelJobs().forEach { it.join() }
        first.cleanup(); second.cleanup()
    }
}
