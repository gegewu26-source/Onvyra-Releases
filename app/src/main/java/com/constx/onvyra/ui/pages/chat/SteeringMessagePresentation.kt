package com.constx.onvyra.ui.pages.chat

import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.MessageNode
import com.constx.onvyra.data.model.toMessageNode
import com.constx.onvyra.service.MessageQueueState
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

internal data class SteeringMessagePresentation(
    val pendingNodes: List<MessageNode>,
    val queueForPanel: MessageQueueState,
)

/** Accepted steering input appears in history while the running atomic action finishes. */
internal fun presentSteeringMessages(
    conversation: Conversation,
    queue: MessageQueueState,
): SteeringMessagePresentation {
    if (queue.paused || queue.messages.none { it.steering }) {
        return SteeringMessagePresentation(emptyList(), queue)
    }

    val pendingIds = queue.messages.mapNotNullTo(HashSet()) { message ->
        message.id.takeIf { message.steering }
    }
    for (node in conversation.messageNodes) {
        for (message in node.messages) pendingIds.remove(message.id)
        if (pendingIds.isEmpty()) break
    }

    val pendingNodes = queue.messages.asSequence()
        .filter { it.steering && it.id in pendingIds }
        .distinctBy { it.id }
        .map { queued ->
            UIMessage(id = queued.id, role = MessageRole.USER, parts = queued.parts)
                .toMessageNode().copy(id = queued.id)
        }.toList()
    return SteeringMessagePresentation(
        pendingNodes = pendingNodes,
        queueForPanel = queue.copy(messages = queue.messages.filterNot { it.steering }),
    )
}
