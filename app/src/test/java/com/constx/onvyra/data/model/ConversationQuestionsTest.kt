package com.constx.onvyra.data.model

import com.constx.onvyra.service.questionAttachments
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationQuestionsTest {
    private fun question(id: String = "question") = UIMessagePart.Tool(
        toolCallId = id, toolName = "ask_user", input = "{}", approvalState = ToolApprovalState.Pending)

    @Test fun `only a real pending question in the current selected reply captures the composer`() {
        val pending = question()
        val oldBranch = UIMessage.assistant("old").copy(parts = listOf(pending))
        val current = UIMessage.assistant("current")
        val conversation = Conversation(assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(oldBranch, current), selectIndex = 1)))
        assertNull(conversation.pendingAskUserTool())
        val selected = conversation.copy(messageNodes = listOf(MessageNode(messages = listOf(oldBranch, current), selectIndex = 0)))
        assertEquals(pending, selected.pendingAskUserTool())
        val laterUser = selected.copy(messageNodes = selected.messageNodes + UIMessage.user("new task").toMessageNode())
        // Follow-up input does not close a real pending question from this task.
        assertEquals(pending, laterUser.pendingAskUserTool())
    }

    @Test fun `ordinary tool approvals and answered questions never take over the input`() {
        val done = question().copy(approvalState = ToolApprovalState.Answered("answer"), output = listOf(UIMessagePart.Text("answer")))
        val permission = question("permission").copy(toolName = "workspace_write_file")
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.assistant("").copy(parts = listOf(done, permission)).toMessageNode()))
        assertNull(conversation.pendingAskUserTool())
    }

    @Test fun `completing one question preserves earlier effects and other unanswered questions`() {
        val first = question("first")
        val second = question("second")
        val effect = question("effect").copy(toolName = "netdisk_download", output = listOf(UIMessagePart.Text("submitted")))
        val source = listOf(UIMessage.user("do the task"), UIMessage.assistant("").copy(parts = listOf(effect, first, second)))
        val answered = first.copy(approvalState = ToolApprovalState.Answered("choice"), output = listOf(UIMessagePart.Text("choice")))
        val result = source.withCompletedQuestion(answered)
        assertSame(source.first(), result.first())
        assertEquals(listOf(effect, answered, second), result.last().parts)
        assertTrue(result.last().getTools().first { it.toolCallId == "second" }.isPending)
    }

    @Test fun `answer attachments keep images but cannot inject nested tool calls or extra answer text`() {
        val image = UIMessagePart.Image("file:///fixture.jpg")
        assertEquals(listOf(image), questionAttachments(listOf(UIMessagePart.Text("duplicate"), question(), image)))
    }

    @Test fun `late pending chunks cannot roll back an accepted answer or its attachments`() {
        val pending = question()
        val image = UIMessagePart.Image("file:///answer.jpg")
        val answered = pending.copy(approvalState = ToolApprovalState.Answered("choice"),
            output = listOf(UIMessagePart.Text("choice"), image))
        val latest = UIMessage.assistant("").copy(parts = listOf(answered, question("next")))
        val current = Conversation(assistantId = Uuid.random(), messageNodes = listOf(latest.toMessageNode()))
        val stale = listOf(latest.copy(parts = listOf(pending, question("next"))))
        assertEquals(latest, current.preserveAnsweredQuestions(stale).single())
        val changedQuestion = listOf(latest.copy(parts = listOf(pending.copy(input = "new question"))))
        assertSame(changedQuestion, current.preserveAnsweredQuestions(changedQuestion))
    }

    @Test fun `deferred question survives independent messages and late chunks cannot reopen its answer`() {
        val pending = question().deferTaskAnswer()
        assertTrue(pending.isExecuted)
        assertFalse(pending.isPending)
        assertTrue(pending.isAwaitingTaskAnswer)
        val first = UIMessage.assistant("").copy(parts = listOf(pending))
        val later = UIMessage.assistant("independent progress")
        val conversation = Conversation(assistantId = Uuid.random(),
            messageNodes = listOf(first.toMessageNode(), later.toMessageNode()))
        assertEquals(pending, conversation.pendingAskUserTool())
        val answered = pending.copy(approvalState = ToolApprovalState.Answered("choice"),
            output = listOf(UIMessagePart.Text("choice")))
        val completed = conversation.copy(messageNodes = listOf(
            first.copy(parts = listOf(answered)).toMessageNode(), later.toMessageNode()))
        assertNull(completed.pendingAskUserTool())
        val repaired = completed.preserveAnsweredQuestions(listOf(first, later))
        assertEquals(answered, repaired.first().getTools().single())
        assertSame(later, repaired.last())
    }

    @Test fun `answer merge cannot rewrite reused ids with different input or another tool or an old answer`() {
        val pending = question("call_0").copy(input = "new-question").deferTaskAnswer()
        val oldAnswered = pending.copy(input = "old-question", approvalState = ToolApprovalState.Answered("old answer"),
            output = listOf(UIMessagePart.Text("old answer")))
        val otherTool = pending.copy(toolName = "workspace_read_file", output = listOf(UIMessagePart.Text("file data")))
        val differentQuestion = question("call_0").copy(input = "different-question").deferTaskAnswer()
        val source = listOf(UIMessage.assistant("").copy(parts = listOf(oldAnswered, otherTool, differentQuestion)),
            UIMessage.assistant("").copy(parts = listOf(pending)))
        val answered = pending.copy(approvalState = ToolApprovalState.Answered("new answer"),
            output = listOf(UIMessagePart.Text("new answer")))
        val merged = source.withCompletedQuestion(answered)
        assertSame(source.first(), merged.first())
        assertEquals(listOf(oldAnswered, otherTool, differentQuestion), merged.first().parts)
        assertEquals("new answer", (merged.last().getTools().single().approvalState as ToolApprovalState.Answered).answer)
        assertFalse(merged.last().getTools().single().isAwaitingTaskAnswer)
    }

    @Test fun `answered history cannot supply an answer to a new message that reuses the same call id and input`() {
        val pending = question("call_0").deferTaskAnswer()
        val answered = pending.copy(approvalState = ToolApprovalState.Answered("earlier choice"),
            output = listOf(UIMessagePart.Text("earlier choice")))
        val oldMessage = UIMessage.assistant("").copy(parts = listOf(answered))
        val newMessage = UIMessage.assistant("").copy(parts = listOf(pending))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(oldMessage.toMessageNode()))
        val incoming = listOf(oldMessage, newMessage)
        assertSame(incoming, conversation.preserveAnsweredQuestions(incoming))
        assertTrue(conversation.preserveAnsweredQuestions(incoming).last().getTools().single().isAwaitingTaskAnswer)
        // A stale chunk for the actual old message is still repaired.
        val stale = listOf(oldMessage.copy(parts = listOf(pending)), newMessage)
        val repaired = conversation.preserveAnsweredQuestions(stale)
        assertEquals(answered, repaired.first().getTools().single())
        assertSame(newMessage, repaired.last())
    }
}
