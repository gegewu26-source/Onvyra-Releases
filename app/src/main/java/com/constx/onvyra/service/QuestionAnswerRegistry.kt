package com.constx.onvyra.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import me.rerere.ai.ui.UIMessagePart
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.security.MessageDigest

/** Gateways may reuse call ids in later replies; a receipt belongs to one exact question owner. */
internal fun questionAnswerRegistryKey(ownerMessageId: String, question: UIMessagePart.Tool): String {
    val inputHash = MessageDigest.getInstance("SHA-256").digest(question.input.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "$ownerMessageId:${question.toolCallId}:$inputHash"
}

internal data class QuestionAnswer(
    val payload: String,
    val attachments: List<UIMessagePart> = emptyList(),
)

/** Answers belong to one generation run, including answers received before it starts waiting. */
internal class QuestionAnswerRegistry {
    private val lock = Any()
    private val answers = IdentityHashMap<Any, MutableMap<String, CompletableDeferred<QuestionAnswer>>>()
    private val closedRuns = mutableListOf<WeakReference<Any>>()

    fun submit(run: Any, callId: String, answer: QuestionAnswer): Boolean = synchronized(lock) {
        if (isClosed(run)) return@synchronized false
        entry(run, callId).complete(answer)
    }

    suspend fun await(run: Any, callId: String): QuestionAnswer {
        val pending = synchronized(lock) {
            if (isClosed(run)) throw CancellationException("Question run is closed")
            entry(run, callId)
        }
        try {
            return pending.await()
        } finally {
            synchronized(lock) {
                // Steering can cancel only this wait. Do not remove an answer that won the race,
                // or a replacement entry that was registered after this one was cancelled.
                if (!pending.isCompleted || pending.isCancelled) {
                    val runAnswers = answers[run]
                    if (runAnswers != null && runAnswers[callId] === pending) {
                        runAnswers.remove(callId)
                        if (runAnswers.isEmpty()) answers.remove(run)
                    }
                    pending.cancel()
                }
            }
        }
    }

    fun close(run: Any) {
        val pending = synchronized(lock) {
            if (!isClosed(run)) closedRuns += WeakReference(run)
            answers.remove(run)?.values?.toList().orEmpty()
        }
        pending.forEach { it.cancel() }
    }

    fun clear() {
        val pending = synchronized(lock) {
            answers.values.flatMap { it.values }.also {
                answers.clear()
                closedRuns.clear()
            }
        }
        pending.forEach { it.cancel() }
    }

    /** Called only while holding [lock]; completed entries prevent duplicate submissions. */
    private fun entry(run: Any, callId: String): CompletableDeferred<QuestionAnswer> =
        answers.getOrPut(run) { mutableMapOf() }.getOrPut(callId) { CompletableDeferred() }

    /** Keep late-answer protection without retaining every completed generation token. */
    private fun isClosed(run: Any): Boolean {
        closedRuns.removeAll { it.get() == null }
        return closedRuns.any { it.get() === run }
    }
}
