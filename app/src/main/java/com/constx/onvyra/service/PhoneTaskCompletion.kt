package com.constx.onvyra.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart

/** A finished request is not necessarily a finished task (approvals and updates can still wait). */
internal fun ConversationSession.canCompletePhoneTask(run: Any, completedRevision: Long?): Boolean =
    synchronized(this) {
        if (completedRevision == null || !isGenerationRun(run) ||
            steering.snapshotRevision() != completedRevision || steering.hasPending) {
            return@synchronized false
        }
        val queue = messageQueue.state.value
        if (queue.paused || queue.messages.isNotEmpty()) return@synchronized false
        val messages = state.value.currentMessages
        messages.lastOrNull()?.role == MessageRole.ASSISTANT && messages.none { message ->
            message.parts.any { part ->
                (part is UIMessagePart.Tool && !part.isExecuted) ||
                    (part is UIMessagePart.ServerTool && !part.isFinished)
            }
        }
    }
