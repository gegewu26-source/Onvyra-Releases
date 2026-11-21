package com.constx.onvyra.data.ai

import com.constx.onvyra.data.model.isAwaitingTaskAnswer
import kotlinx.serialization.json.JsonObject

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import kotlin.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Preserve completed effects even if cancellation discards buffered UI chunks. */
class GenerationProgress {
    private val latest = AtomicReference<List<UIMessage>?>(null)
    fun record(messages: List<UIMessage>) { latest.set(messages.toList()) }
    fun interruptedMessages(): List<UIMessage>? = latest.get()?.finishSupersededToolCalls(taskRetained = false)
}

/** Signals belong to one conversation, while only its active generation consumes them. */
class GenerationSteering {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private var acknowledgedRevision = 0L

    @Synchronized
    fun signal(): Long = (mutableRevision.value + 1).also { mutableRevision.value = it }

    @get:Synchronized
    val hasPending: Boolean get() = mutableRevision.value > acknowledgedRevision

    fun snapshotRevision(): Long = mutableRevision.value

    /** A signal arriving during the consumer's suspension remains pending. */
    @Synchronized
    fun acknowledgeThrough(revision: Long) {
        acknowledgedRevision = maxOf(acknowledgedRevision, minOf(revision, mutableRevision.value))
    }

    /**
     * Interrupt only a model request, never an already executing tool. The child is joined before
     * returning so its last chunk or finally block cannot overwrite the next request's history.
     * False means a new user instruction won; failures and ordinary cancellation propagate.
     */
    suspend fun runRequest(block: suspend () -> Unit): Boolean = coroutineScope {
        if (hasPending) return@coroutineScope false
        val request = async { block() }
        val interruption = async(start = CoroutineStart.UNDISPATCHED) {
            revision.first { synchronized(this@GenerationSteering) { it > acknowledgedRevision } }
        }
        try {
            select {
                interruption.onAwait {
                    request.cancelAndJoin()
                    false
                }
                request.onAwait { true }
            }
        } finally {
            interruption.cancelAndJoin()
            request.cancelAndJoin()
        }
    }
}

/** Keep completed effects in history, but never execute an old proposal after a correction. */
internal fun List<UIMessage>.finishSupersededToolCalls(taskRetained: Boolean = true): List<UIMessage> = map { message ->
    val changed = message.parts.mapNotNull { part ->
        when {
            !taskRetained && part is UIMessagePart.Tool && part.isAwaitingTaskAnswer -> part.copy(
                approvalState = ToolApprovalState.Denied("Task stopped"),
                metadata = part.metadata?.let { JsonObject(it - "awaiting_user_answer") },
                output = listOf(UIMessagePart.Text("""{"status":"cancelled","reason":"task_stopped"}""")),
            )
            // The provider has not finished these inline bytes. They are a transient preview,
            // not an image that can safely enter persisted history or a replacement request.
            message.role == MessageRole.ASSISTANT && message.finishedAt == null &&
                part is UIMessagePart.Image && part.url.startsWith("data:") -> null
            part is UIMessagePart.ServerTool && !part.isFinished -> part.copy(status = ServerToolStatus.FAILED)
            part is UIMessagePart.Tool && !part.isExecuted -> part.copy(
                approvalState = ToolApprovalState.Denied("Superseded by a newer user instruction"),
                output = listOf(UIMessagePart.Text(
                    if (taskRetained) """{"status":"superseded","reason":"new_user_instruction","task_retained":true,"display_retained":true,"message":"This unstarted proposal has been replaced by a task update. Keep the phone session and completed results. Read the latest user instruction, revise the remaining plan, and continue from the current screen."}"""
                    else """{"status":"cancelled","reason":"generation_interrupted","message":"Execution ended before this proposed action completed. Keep already completed results and inspect the current state before any future retry."}""",
                )),
            )
            else -> part
        }
    }
    if (changed == message.parts && (message.role != MessageRole.ASSISTANT || message.finishedAt != null)) message else message.copy(
        parts = changed,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    ).finishReasoning()
}
