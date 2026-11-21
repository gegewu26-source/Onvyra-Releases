package com.constx.onvyra.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.uuid.Uuid

private const val ANSWER_SUBMITTED = "question_answer_submitted"
private const val ANSWER_PAYLOAD = "question_answer_payload"
private const val ANSWER_ATTACHMENTS = "question_answer_attachments"
private val answerReceiptKeys = setOf(ANSWER_SUBMITTED, ANSWER_PAYLOAD, ANSWER_ATTACHMENTS)

internal data class SubmittedTaskAnswer(val payload: String, val attachments: List<UIMessagePart>)

/** A durable receipt is distinct from an answer already consumed by the generation loop. */
internal fun UIMessagePart.Tool.submittedTaskAnswer(): SubmittedTaskAnswer? {
    if (toolName != "ask_user" || (metadata?.get(ANSWER_SUBMITTED) as? JsonPrimitive)?.booleanOrNull != true) return null
    val payload = (metadata?.get(ANSWER_PAYLOAD) as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val attachments = metadata?.get(ANSWER_ATTACHMENTS) as? JsonArray ?: return null
    return runCatching {
        SubmittedTaskAnswer(payload, Json.decodeFromJsonElement<List<UIMessagePart>>(attachments).filter {
            it is UIMessagePart.Image || it is UIMessagePart.Document || it is UIMessagePart.Audio || it is UIMessagePart.Video
        })
    }.getOrNull()
}

internal val UIMessagePart.Tool.isTaskAnswerSubmitted: Boolean get() = submittedTaskAnswer() != null
internal val UIMessagePart.Tool.needsTaskAnswerInput: Boolean get() = isAwaitingTaskAnswer && !isTaskAnswerSubmitted

internal fun UIMessagePart.Tool.withSubmittedTaskAnswer(payload: String, attachments: List<UIMessagePart>): UIMessagePart.Tool {
    val acknowledged = if (isPending) deferTaskAnswer() else this
    return acknowledged.copy(metadata = JsonObject(acknowledged.metadata.orEmpty() + mapOf(
        ANSWER_SUBMITTED to JsonPrimitive(true),
        ANSWER_PAYLOAD to JsonPrimitive(payload),
        ANSWER_ATTACHMENTS to Json.encodeToJsonElement(attachments.filter {
            it is UIMessagePart.Image || it is UIMessagePart.Document || it is UIMessagePart.Audio || it is UIMessagePart.Video
        }),
    )))
}

/** Apply only to the exact selected owner and unresolved call; late or duplicate receipts do nothing. */
internal fun Conversation.withSubmittedTaskAnswer(
    messageId: Uuid, question: UIMessagePart.Tool, payload: String, attachments: List<UIMessagePart>,
): Conversation {
    var changed = false
    val nodes = messageNodes.map { node ->
        val current = node.currentMessage
        if (current.id != messageId) return@map node
        val parts = current.parts.map { part ->
            if (part is UIMessagePart.Tool && part.needsTaskAnswerInput &&
                part.toolCallId == question.toolCallId && part.input == question.input) {
                changed = true
                part.withSubmittedTaskAnswer(payload, attachments)
            } else part
        }
        if (parts == current.parts) node else node.copy(messages = node.messages.map {
            if (it.id == messageId) it.copy(parts = parts) else it
        })
    }
    return if (changed) copy(messageNodes = nodes) else this
}

/** A question may already have an acknowledgement output while independent task work continues. */
internal val UIMessagePart.Tool.isAwaitingTaskAnswer: Boolean
    get() = toolName == "ask_user" && approvalState !is ToolApprovalState.Answered &&
        approvalState !is ToolApprovalState.Denied && (isPending ||
        (metadata?.get("awaiting_user_answer") as? JsonPrimitive)?.booleanOrNull == true)

internal fun UIMessagePart.Tool.deferTaskAnswer(): UIMessagePart.Tool = copy(
    approvalState = ToolApprovalState.Pending,
    metadata = JsonObject(metadata.orEmpty() + ("awaiting_user_answer" to JsonPrimitive(true))),
    output = listOf(UIMessagePart.Text("""{"status":"awaiting_user_answer","task_continues":true,"message":"The question is displayed to the user. No answer has been selected. Continue only work that does not depend on this answer. Never assume or fabricate the user's choice. If all remaining work needs the answer, report that briefly; the app will keep this task open until the answer arrives."}""")),
)

internal fun UIMessagePart.Tool.clearTaskAnswerMarker(): UIMessagePart.Tool =
    if (metadata?.containsKey("awaiting_user_answer") != true && metadata.orEmpty().keys.none { it in answerReceiptKeys }) this
    else copy(metadata = JsonObject((metadata.orEmpty() - "awaiting_user_answer").let {
        // On explicit stop retain the user's received answer in history. Consumed
        // answers already carry it (and prepared attachments) in their actual output.
        if (approvalState is ToolApprovalState.Answered) it - answerReceiptKeys else it
    }))

/** Only an actual, unresolved tool call can take ownership of the composer's answer. */
internal fun Conversation.pendingAskUserTool(): UIMessagePart.Tool? =
    currentMessages.asSequence().flatMap { it.getTools().asSequence() }
        .firstOrNull { it.needsTaskAnswerInput }

internal fun List<UIMessage>.withCompletedQuestion(answered: UIMessagePart.Tool): List<UIMessage> = map { message ->
    if (message.getTools().none { it.isAwaitingTaskAnswer && it.toolCallId == answered.toolCallId && it.input == answered.input }) message
    else message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && part.isAwaitingTaskAnswer &&
            part.toolCallId == answered.toolCallId && part.input == answered.input) answered.clearTaskAnswerMarker() else part
    })
}

/** A buffered pre-answer chunk must not make the composer ask an already answered question again. */
internal fun Conversation.preserveAnsweredQuestions(incoming: List<UIMessage>): List<UIMessage> {
    val answeredByMessage = currentMessages.associate { message -> message.id to message.getTools().filter {
        it.toolName == "ask_user" && (it.approvalState is ToolApprovalState.Answered && it.isExecuted || it.isTaskAnswerSubmitted)
    } }
    if (answeredByMessage.values.all { it.isEmpty() }) return incoming
    var changed = false
    val merged = incoming.map { message ->
      val answered = answeredByMessage[message.id].orEmpty()
      val parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolName == "ask_user" &&
            (part.isAwaitingTaskAnswer || part.approvalState is ToolApprovalState.Denied)) {
            answered.firstOrNull { it.toolCallId == part.toolCallId && it.input == part.input }?.let { confirmed ->
                if (part.approvalState is ToolApprovalState.Denied) {
                    // Stop remains a stop; retain only the user's received data,
                    // never restore a pending/answered lifecycle over a denial.
                    if (confirmed.isTaskAnswerSubmitted) part.copy(metadata = JsonObject(part.metadata.orEmpty() +
                        confirmed.metadata.orEmpty().filterKeys { it in answerReceiptKeys })) else part
                } else if (confirmed.approvalState is ToolApprovalState.Answered || confirmed.approvalState is ToolApprovalState.Denied) confirmed
                else part.copy(metadata = JsonObject(part.metadata.orEmpty() +
                    confirmed.metadata.orEmpty().filterKeys { it in answerReceiptKeys }))
            } ?: part
        } else part
      }
      if (parts == message.parts) message else message.copy(parts = parts).also { changed = true }
    }
    return if (changed) merged else incoming
}
