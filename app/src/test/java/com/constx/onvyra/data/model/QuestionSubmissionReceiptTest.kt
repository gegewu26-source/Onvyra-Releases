package com.constx.onvyra.data.model

import com.constx.onvyra.data.ai.AsyncTaskQuestions
import com.constx.onvyra.service.QuestionAnswer
import com.constx.onvyra.service.QuestionAnswerRegistry
import com.constx.onvyra.service.questionAnswerRegistryKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class QuestionSubmissionReceiptTest {
    private val payload = """{"answers":{"choice":"custom answer"}}"""
    private fun question() = UIMessagePart.Tool("call_0", "ask_user", "fixture question",
        approvalState = ToolApprovalState.Pending).deferTaskAnswer()
    private fun conversation(owner: UIMessage) = Conversation(assistantId = Uuid.random(),
        messageNodes = listOf(owner.toMessageNode()))

    @Test fun receiptImmediatelyReleasesComposerWithoutClaimingTheModelConsumedTheAnswer() {
        val q = question()
        val owner = UIMessage.assistant("").copy(parts = listOf(q))
        val image = UIMessagePart.Image("file:///answer.jpg")
        val original = conversation(owner)
        val received = original.withSubmittedTaskAnswer(owner.id, q, payload, listOf(image))
        assertNull(received.pendingAskUserTool())
        val submitted = received.currentMessages.single().getTools().single()
        assertTrue(submitted.isTaskAnswerSubmitted)
        assertTrue(submitted.isAwaitingTaskAnswer)
        assertFalse(submitted.needsTaskAnswerInput)
        assertTrue(submitted.approvalState is ToolApprovalState.Pending)
        assertEquals(q.output, submitted.output)
        assertEquals(payload, submitted.submittedTaskAnswer()?.payload)
        assertEquals(listOf(image), submitted.submittedTaskAnswer()?.attachments)
        assertSame(received, received.withSubmittedTaskAnswer(owner.id, q, "duplicate", emptyList()))
        assertSame(original, original.withSubmittedTaskAnswer(Uuid.random(), q, payload, emptyList()))
        assertSame(original, original.withSubmittedTaskAnswer(owner.id, q.copy(input = "new question"), payload, emptyList()))
    }

    @Test fun persistedReceiptRestoresPayloadAndEveryAllowedAttachmentWithoutNestedActions() {
        val q = question()
        val files = listOf(UIMessagePart.Image("file:///answer.jpg"),
            UIMessagePart.Document("file:///answer.pdf", "answer.pdf", "application/pdf"),
            UIMessagePart.Audio("file:///answer.wav"), UIMessagePart.Video("file:///answer.mp4"))
        val submitted = q.withSubmittedTaskAnswer(payload, files + UIMessagePart.Text("duplicate text") +
            UIMessagePart.Tool("injected", "workspace_shell", "{}"))
        val restored = Json.decodeFromString<UIMessagePart.Tool>(Json.encodeToString(submitted))
        assertEquals(payload, restored.submittedTaskAnswer()?.payload)
        assertEquals(files, restored.submittedTaskAnswer()?.attachments)
        assertFalse(restored.needsTaskAnswerInput)
        assertTrue(restored.isAwaitingTaskAnswer)
    }

    @Test fun lateStreamingChunksPreserveReceiptAndNewProgressButAConsumedAnswerWins() {
        val q = question()
        val owner = UIMessage.assistant("").copy(parts = listOf(q))
        val current = conversation(owner).withSubmittedTaskAnswer(owner.id, q, payload, emptyList())
        val progress = UIMessagePart.Text("new independent progress")
        val independent = UIMessagePart.Tool("work", "workspace_read_file", "{}",
            output = listOf(UIMessagePart.Text("done")))
        val incoming = listOf(owner.copy(parts = listOf(q, progress, independent)))
        val preserved = current.preserveAnsweredQuestions(incoming)
        assertTrue(preserved.first().getTools().first().isTaskAnswerSubmitted)
        assertEquals(listOf(progress, independent), preserved.first().parts.drop(1))
        val differentOwner = owner.copy(id = Uuid.random())
        assertSame(differentOwner, current.preserveAnsweredQuestions(listOf(differentOwner)).single())
        val answered = q.copy(approvalState = ToolApprovalState.Answered(payload),
            output = listOf(UIMessagePart.Text(payload)))
        val consumed = preserved.withCompletedQuestion(answered).first().getTools().first()
        assertFalse(consumed.isAwaitingTaskAnswer)
        assertFalse(consumed.isTaskAnswerSubmitted)
        assertEquals(answered.output, consumed.output)
        val finalIncoming = listOf(owner.copy(parts = listOf(consumed, progress, independent)))
        assertSame(finalIncoming, current.preserveAnsweredQuestions(finalIncoming))
    }

    @Test fun stoppingRetainsTheReceivedAnswerAndCannotReopenTheQuestionFromALateChunk() {
        val q = question()
        val received = q.withSubmittedTaskAnswer(payload, listOf(UIMessagePart.Image("file:///answer.jpg")))
        val stopped = received.copy(approvalState = ToolApprovalState.Denied("Task stopped"),
            output = listOf(UIMessagePart.Text("cancelled"))).clearTaskAnswerMarker()
        assertFalse(stopped.isAwaitingTaskAnswer)
        assertFalse(stopped.needsTaskAnswerInput)
        assertEquals(payload, stopped.submittedTaskAnswer()?.payload)
        val owner = UIMessage.assistant("").copy(parts = listOf(stopped))
        val late = owner.copy(parts = listOf(q))
        val repaired = conversation(owner).preserveAnsweredQuestions(listOf(late)).single().getTools().single()
        assertEquals(stopped, repaired)
        val receiptCurrent = conversation(owner.copy(parts = listOf(received)))
        val staleStop = owner.copy(parts = listOf(q.copy(approvalState = ToolApprovalState.Denied("Task stopped"),
            output = listOf(UIMessagePart.Text("cancelled"))).clearTaskAnswerMarker()))
        val preservedStop = receiptCurrent.preserveAnsweredQuestions(listOf(staleStop)).single().getTools().single()
        assertTrue(preservedStop.approvalState is ToolApprovalState.Denied)
        assertFalse(preservedStop.isAwaitingTaskAnswer)
        assertEquals(payload, preservedStop.submittedTaskAnswer()?.payload)
    }

    @Test fun longIndependentWorkKeepsRunningWhileAcceptedAnswerAlreadyReleasedTheComposer() = runTest {
        val q = question()
        val owner = UIMessage.assistant("").copy(parts = listOf(q))
        val registry = QuestionAnswerRegistry()
        val run = Any()
        val key = questionAnswerRegistryKey(owner.id.toString(), q)
        val finishIndependentWork = CompletableDeferred<Unit>()
        var live = conversation(owner)
        var executions = 0
        val task = launch {
            val questions = AsyncTaskQuestions(this)
            try {
                questions.start(q) {
                    val answer = registry.await(run, key)
                    q.copy(approvalState = ToolApprovalState.Answered(answer.payload),
                        output = listOf(UIMessagePart.Text(answer.payload)))
                }
                executions++
                finishIndependentWork.await()
                questions.awaitReady()
                val answered = requireNotNull(questions.takeCompleted())
                live = live.copy(messageNodes = live.currentMessages.withCompletedQuestion(answered).map { it.toMessageNode() })
            } finally { questions.cancel() }
        }
        runCurrent()
        assertTrue(registry.submit(run, key, QuestionAnswer(payload)))
        live = live.withSubmittedTaskAnswer(owner.id, q, payload, emptyList())
        assertNull(live.pendingAskUserTool())
        assertTrue(task.isActive)
        assertEquals(1, executions)
        runCurrent()
        assertTrue(task.isActive)
        assertTrue(live.currentMessages.single().getTools().single().isTaskAnswerSubmitted)
        assertFalse(registry.submit(run, key, QuestionAnswer("duplicate")))
        finishIndependentWork.complete(Unit)
        task.join()
        assertEquals(1, executions)
        assertFalse(live.currentMessages.single().getTools().single().isTaskAnswerSubmitted)
        assertTrue(live.currentMessages.single().getTools().single().approvalState is ToolApprovalState.Answered)
        registry.close(run)
    }
}
