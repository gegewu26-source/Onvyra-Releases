package com.constx.onvyra.data.ai

import com.constx.onvyra.data.model.deferTaskAnswer
import com.constx.onvyra.data.model.isAwaitingTaskAnswer
import com.constx.onvyra.data.model.withCompletedQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AsyncTaskQuestionsTest {
    private fun question(id: String = "choice") = UIMessagePart.Tool(id, "ask_user", "{}",
        approvalState = ToolApprovalState.Pending).deferTaskAnswer()
    private fun answered(question: UIMessagePart.Tool) = question.copy(
        approvalState = ToolApprovalState.Answered("chosen"), output = listOf(UIMessagePart.Text("chosen")))

    @Test fun independentWorkContinuesInTheSameTaskAndOnlyCompletionWaitsForAnAnswer() = runTest {
        val answer = CompletableDeferred<UIMessagePart.Tool>()
        val independentEffect = CompletableDeferred<Unit>()
        val q = question()
        var effectCount = 0
        var final: UIMessagePart.Tool? = null
        val task = launch {
            val questions = AsyncTaskQuestions(this)
            try {
                assertTrue(questions.start(q) { answer.await() })
                assertTrue(q.isExecuted) // Provider has a complete acknowledgement result.
                assertTrue(q.isAwaitingTaskAnswer) // The composer still owns this real question.
                effectCount++
                independentEffect.complete(Unit)
                assertNull(questions.takeCompleted())
                questions.awaitReady()
                final = questions.takeCompleted()
                assertNull(questions.takeCompleted())
            } finally { questions.cancel() }
        }
        runCurrent()
        assertTrue(independentEffect.isCompleted)
        assertEquals(1, effectCount)
        assertTrue(task.isActive)
        assertNull(final)
        answer.complete(answered(q))
        task.join()
        assertEquals("choice", final?.toolCallId)
        assertEquals(1, effectCount)
        assertFalse(task.isActive)
    }

    @Test fun taskUpdatesCancelOnlyTheIdleWaitAndEarlyAnswerIsDeliveredExactlyOnce() = runTest {
        val answer = CompletableDeferred<UIMessagePart.Tool>()
        val q = question()
        val questions = AsyncTaskQuestions(this)
        questions.start(q) { answer.await() }
        val idleWait = async { questions.awaitReady() }
        runCurrent()
        idleWait.cancelAndJoin()
        assertTrue(questions.hasPending)
        answer.complete(answered(q))
        runCurrent()
        assertTrue(questions.hasPending)
        assertEquals(q.toolCallId, questions.takeCompleted()?.toolCallId)
        assertFalse(questions.hasPending)
        assertNull(questions.takeCompleted())
        questions.cancel()
    }

    @Test fun stoppingTheTaskCancelsItsAnswerChildAndNewTaskCanRestoreTheSavedQuestion() = runTest {
        val q = question()
        var stopped = false
        val task = launch {
            val questions = AsyncTaskQuestions(this)
            try {
                questions.start(q) {
                    try { CompletableDeferred<UIMessagePart.Tool>().await() }
                    finally { stopped = true }
                }
                questions.awaitReady()
            } finally { questions.cancel() }
        }
        runCurrent()
        task.cancelAndJoin()
        assertTrue(stopped)
        val replacement = AsyncTaskQuestions(this)
        assertTrue(replacement.start(q) { answered(q) })
        assertEquals(q.toolCallId, replacement.takeCompleted()?.toolCallId)
        replacement.cancel()
    }

    @Test fun aSecondQuestionCannotStartAndAnswersMergeIntoCurrentWorkRatherThanOldSnapshot() = runTest {
        val q = question()
        val answer = CompletableDeferred<UIMessagePart.Tool>()
        val questions = AsyncTaskQuestions(this)
        assertTrue(questions.start(q) { answer.await() })
        assertFalse(questions.start(question("duplicate")) { error("Must not create another wait") })
        val first = UIMessage.assistant("").copy(parts = listOf(q))
        val completedEffect = UIMessagePart.Tool("download", "netdisk_download", "{}",
            output = listOf(UIMessagePart.Text("submitted exactly once")))
        val current = listOf(first, UIMessage.assistant("independent work").copy(parts = listOf(completedEffect)))
        answer.complete(answered(q))
        runCurrent()
        val merged = current.withCompletedQuestion(requireNotNull(questions.takeCompleted()))
        assertSame(current.last(), merged.last())
        assertFalse(merged.first().getTools().single().isAwaitingTaskAnswer)
        assertEquals(completedEffect, merged.last().getTools().single())
        questions.cancel()
    }
}
