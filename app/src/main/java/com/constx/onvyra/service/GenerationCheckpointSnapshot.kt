package com.constx.onvyra.service

import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.isAwaitingTaskAnswer
import com.constx.onvyra.data.model.isTaskAnswerSubmitted
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import kotlin.time.Clock

/** Disk-only recovery view. Never publish it over the still-running in-memory conversation. */
internal fun Conversation.generationCheckpointSnapshot(): Conversation {
    val savedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return copy(messageNodes = messageNodes.map { node ->
        node.copy(messages = node.messages.map { message ->
            // finishedAt also marks the end of an individual model request. Its tools
            // may still be running, so it cannot be used as a task-completed flag.
            if (message.role != MessageRole.ASSISTANT) message else {
                message.copy(parts = message.parts.mapNotNull { part ->
                    when {
                        part is UIMessagePart.Image && part.url.startsWith("data:") -> null
                        part is UIMessagePart.ServerTool && !part.isFinished ->
                            part.copy(status = ServerToolStatus.FAILED)
                        part is UIMessagePart.Tool && part.isExecuted -> {
                            val output = part.output.filterNot { it is UIMessagePart.Image && it.url.startsWith("data:") }
                            if (output == part.output) part else part.copy(output = output.ifEmpty {
                                listOf(UIMessagePart.Text("图片尚未完成本地保存"))
                            })
                        }
                        part is UIMessagePart.Tool && !part.isPending &&
                            !part.isAwaitingTaskAnswer && !part.isTaskAnswerSubmitted -> interruptedGenerationTool(part)
                        else -> part
                    }
                }, finishedAt = message.finishedAt ?: savedAt).finishReasoning()
            }
        })
    })
}

internal fun interruptedGenerationTool(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    approvalState = ToolApprovalState.Denied("任务中断，执行结果尚未确认"),
    output = listOf(UIMessagePart.Text(
        """{"status":"interrupted","reason":"generation_interrupted","execution_status":"unknown","message":"No confirmed result was saved for this action. Preserve completed results and inspect the current state before retrying it."}""",
    )),
)

/** Only accessed while the session's persistence lock is held. */
internal class GenerationCheckpointState(var previous: Conversation? = null)

/** Token text grows freely; new replies, reasoning endings and tool outcomes are durable boundaries. */
internal data class GenerationCheckpointBoundary(
    val messageId: kotlin.uuid.Uuid?,
    val finishedReasoning: Int,
    val tools: List<Triple<String, Boolean, ToolApprovalState>>,
    val serverTools: List<Pair<String, ServerToolStatus>>,
)

internal fun Conversation.generationCheckpointBoundary(): GenerationCheckpointBoundary {
    val message = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
    return GenerationCheckpointBoundary(
        message?.id,
        message?.parts?.filterIsInstance<UIMessagePart.Reasoning>()?.count { it.finishedAt != null } ?: 0,
        message?.getTools()?.map { Triple(it.toolCallId, it.isExecuted, it.approvalState) }.orEmpty(),
        message?.parts?.filterIsInstance<UIMessagePart.ServerTool>()?.map { it.toolCallId to it.status }.orEmpty(),
    )
}
