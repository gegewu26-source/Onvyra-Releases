package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.localFileUrls
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // Running conversations consume these at the next request/action boundary.
    val steering: Boolean = false,
    val dispatched: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

class MessageQueue {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null,
        steering: Boolean = false) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        mutableState.value = state.value.copy(
            messages = state.value.messages + QueuedMessage(
                parts = parts.toList(),
                answer = answer,
                reply = reply,
                steering = steering,
            ),
        )
    }

    @Synchronized
    fun takeNext(steeringOnly: Boolean = false): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = (if (steeringOnly) current.messages.firstOrNull { it.steering } else current.messages.firstOrNull())
            ?.takeUnless { it.isEditing || it.dispatched } ?: return null
        mutableState.value = current.copy(messages = current.messages.filterNot { it.id == next.id })
        return next
    }

    /** Keep the optimistic bubbles until their stable IDs appear in actual history. */
    @Synchronized
    fun takeSteering(): List<QueuedMessage> {
        if (state.value.paused) return emptyList()
        val next = state.value.messages.filter { it.steering && !it.isEditing && !it.dispatched }
        val ids = next.map { it.id }.toSet()
        mutableState.value = state.value.copy(messages = state.value.messages.map {
            if (it.id in ids) it.copy(dispatched = true) else it
        })
        return next
    }

    @Synchronized
    fun acknowledgeSteering(messageIds: Set<Uuid>) {
        mutableState.value = state.value.copy(messages = state.value.messages.filterNot {
            it.steering && it.dispatched && it.id in messageIds
        })
    }

    @Synchronized
    fun recoverUncommittedSteering() {
        mutableState.value = state.value.copy(messages = state.value.messages.map {
            if (it.dispatched) it.copy(dispatched = false) else it
        })
    }

    /** The stopped input stays in history; a later send starts a fresh task. */
    @Synchronized
    fun finishWholeTaskStop(stoppedIds: Set<Uuid>) {
        val remaining = state.value.messages.filterNot { it.id in stoppedIds }
        mutableState.value = state.value.copy(messages = remaining, paused = remaining.isEmpty())
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id && !it.dispatched } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing && !it.dispatched } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id && !it.dispatched } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) it.copy(
                    parts = parts?.toList() ?: it.parts,
                    isEditing = false
                ) else it
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }
}
