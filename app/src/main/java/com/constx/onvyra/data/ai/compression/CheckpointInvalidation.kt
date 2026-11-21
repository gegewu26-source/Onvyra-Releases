package com.constx.onvyra.data.ai.compression

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.MessageNode

/**
 * An existing checkpoint may cover only the original selected messages before it.
 * Editing, removing, inserting or selecting a different original invalidates later
 * summaries. Keep their nodes and candidates in place so a streaming writer's
 * indices remain stable. Invalidated IDs can never be revived by an older snapshot.
 */
internal fun invalidateChangedContextCheckpoints(previous: Conversation, updated: Conversation): Conversation {
    if (previous === updated || previous.messageNodes.isEmpty() || updated.messageNodes.isEmpty()) return updated

    val oldCheckpointBoundaries = HashMap<kotlin.uuid.Uuid, Int>()
    val invalidatedIds = HashSet<kotlin.uuid.Uuid>()
    var originalsBefore = 0
    previous.messageNodes.forEach { node ->
        node.messages.forEach { message ->
            if (message.isContextCheckpoint) {
                oldCheckpointBoundaries[message.id] = originalsBefore
                if (message.contextCheckpointInvalidated) invalidatedIds += message.id
            }
        }
        if (!node.currentMessage.isContextCheckpoint) originalsBefore++
    }
    if (oldCheckpointBoundaries.isEmpty()) return updated

    // O(n) over the common original prefix. Unchanged message/parts references are
    // fast paths; no per-frame hashing or concatenation of entire transcripts.
    var previousIndex = 0
    var updatedIndex = 0
    var commonOriginals = 0
    while (true) {
        while (previousIndex < previous.messageNodes.size &&
            previous.messageNodes[previousIndex].currentMessage.isContextCheckpoint) previousIndex++
        while (updatedIndex < updated.messageNodes.size &&
            updated.messageNodes[updatedIndex].currentMessage.isContextCheckpoint) updatedIndex++
        if (previousIndex >= previous.messageNodes.size || updatedIndex >= updated.messageNodes.size) break
        if (!sameOriginalContent(
                previous.messageNodes[previousIndex].currentMessage,
                updated.messageNodes[updatedIndex].currentMessage,
            )) break
        commonOriginals++
        previousIndex++
        updatedIndex++
    }

    var changedNodes: MutableList<MessageNode>? = null
    var updatedOriginalsBefore = 0
    updated.messageNodes.forEachIndexed { nodeIndex, node ->
        var changedMessages: MutableList<UIMessage>? = null
        node.messages.forEachIndexed { candidateIndex, message ->
            val boundary = oldCheckpointBoundaries[message.id]
            val invalidated = message.isContextCheckpoint && (
                message.contextCheckpointInvalidated || message.id in invalidatedIds ||
                    (candidateIndex == node.selectIndex && boundary != null &&
                        (boundary > commonOriginals || updatedOriginalsBefore > commonOriginals ||
                            boundary != updatedOriginalsBefore))
                )
            if (invalidated && !message.contextCheckpointInvalidated) {
                val messages = changedMessages ?: node.messages.toMutableList().also { changedMessages = it }
                messages[candidateIndex] = message.copy(contextCheckpointInvalidated = true)
            }
        }
        if (!node.currentMessage.isContextCheckpoint) updatedOriginalsBefore++
        changedMessages?.let { messages ->
            val nodes = changedNodes ?: updated.messageNodes.toMutableList().also { changedNodes = it }
            nodes[nodeIndex] = node.copy(messages = messages)
        }
    }
    return changedNodes?.let { updated.copy(messageNodes = it) } ?: updated
}

private fun sameOriginalContent(first: UIMessage, second: UIMessage): Boolean {
    if (first === second) return true
    return first.id == second.id && first.role == second.role && samePartsWithoutReasoning(first.parts, second.parts)
}

private fun samePartsWithoutReasoning(first: List<UIMessagePart>, second: List<UIMessagePart>): Boolean {
    if (first === second) return true
    var left = 0
    var right = 0
    while (true) {
        while (left < first.size && first[left] is UIMessagePart.Reasoning) left++
        while (right < second.size && second[right] is UIMessagePart.Reasoning) right++
        if (left == first.size || right == second.size) return left == first.size && right == second.size
        val a = first[left]
        val b = second[right]
        val equal = if (a === b) true else if (a is UIMessagePart.Tool && b is UIMessagePart.Tool) {
            a.toolCallId == b.toolCallId && a.toolName == b.toolName && a.input == b.input &&
                a.approvalState == b.approvalState && a.metadata == b.metadata &&
                samePartsWithoutReasoning(a.output, b.output)
        } else a == b
        if (!equal) return false
        left++
        right++
    }
}
