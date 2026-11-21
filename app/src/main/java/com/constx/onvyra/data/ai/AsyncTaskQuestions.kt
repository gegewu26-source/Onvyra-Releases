package com.constx.onvyra.data.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/** Child waits produce answers; only the owning generation loop may publish conversation changes. */
internal class AsyncTaskQuestions(private val scope: CoroutineScope) {
    private data class Entry(val question: UIMessagePart.Tool, val answer: Deferred<UIMessagePart.Tool>)
    private var entry: Entry? = null
    val pendingQuestion: UIMessagePart.Tool? get() = entry?.question
    val hasPending: Boolean get() = entry != null

    fun start(question: UIMessagePart.Tool, awaitAnswer: suspend () -> UIMessagePart.Tool): Boolean {
        if (entry != null) return false
        entry = Entry(question, scope.async(start = CoroutineStart.UNDISPATCHED) { awaitAnswer() })
        return true
    }

    /** Cancelling this wait for a task update must not cancel the actual answer child. */
    suspend fun awaitReady() { entry?.answer?.await() }

    suspend fun takeCompleted(): UIMessagePart.Tool? {
        val pending = entry ?: return null
        if (!pending.answer.isCompleted) return null
        val answered = pending.answer.await()
        check(answered.toolCallId == pending.question.toolCallId && answered.toolName == "ask_user" &&
            answered.approvalState is ToolApprovalState.Answered && answered.isExecuted)
        entry = null
        return answered
    }

    fun cancel() {
        entry?.answer?.cancel()
        entry = null
    }
}
