package com.constx.onvyra.data.ai.compression

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.toMessageNode
import com.constx.onvyra.service.ConversationSession
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CheckpointInvalidationTest {
    private fun checkpoint(text: String) = UIMessage.user(text).copy(isContextCheckpoint = true)
    private fun conversation(vararg messages: UIMessage) = Conversation(
        assistantId = Uuid.random(), messageNodes = messages.map { it.toMessageNode() },
    )
    private fun edit(source: Conversation, index: Int, message: UIMessage): Conversation = source.copy(
        messageNodes = source.messageNodes.mapIndexed { i, node ->
            if (i == index) node.copy(messages = node.messages.toMutableList().also { it[node.selectIndex] = message }) else node
        },
    )

    @Test
    fun `editing earlier original invalidates existing later summaries without moving any node`() {
        val source = conversation(UIMessage.user("original"), checkpoint("first"), UIMessage.user("later"), checkpoint("second"))
        val updated = edit(source, 0, source.currentMessages[0].copy(parts = listOf(UIMessagePart.Text("corrected"))))
        val result = invalidateChangedContextCheckpoints(source, updated)
        assertTrue(result.currentMessages[1].contextCheckpointInvalidated)
        assertTrue(result.currentMessages[3].contextCheckpointInvalidated)
        assertEquals(updated.messageNodes.map { it.id }, result.messageNodes.map { it.id })
        assertEquals(updated.messageNodes.map { node -> node.messages.map { it.id } }, result.messageNodes.map { node -> node.messages.map { it.id } })
        assertEquals(updated.messageNodes.map { it.selectIndex }, result.messageNodes.map { it.selectIndex })
    }

    @Test
    fun `editing after one checkpoint invalidates only subsequent checkpoints`() {
        val source = conversation(UIMessage.user("one"), checkpoint("first"), UIMessage.assistant("two"), checkpoint("second"))
        val updated = edit(source, 2, source.currentMessages[2].copy(parts = listOf(UIMessagePart.Text("changed two"))))
        val result = invalidateChangedContextCheckpoints(source, updated)
        assertFalse(result.currentMessages[1].contextCheckpointInvalidated)
        assertTrue(result.currentMessages[3].contextCheckpointInvalidated)
    }

    @Test
    fun `selecting a different original branch invalidates the existing checkpoint`() {
        val source = conversation(UIMessage.assistant("old answer"), checkpoint("summary")).let {
            it.copy(messageNodes = listOf(
                it.messageNodes[0].copy(messages = listOf(it.currentMessages[0], UIMessage.assistant("alternate answer"))),
                it.messageNodes[1],
            ))
        }
        val updated = source.copy(messageNodes = listOf(source.messageNodes[0].copy(selectIndex = 1), source.messageNodes[1]))
        val result = invalidateChangedContextCheckpoints(source, updated)
        assertTrue(result.currentMessages[1].contextCheckpointInvalidated)
        assertEquals(listOf(1, 0), result.messageNodes.map { it.selectIndex })
        assertEquals(updated.messageNodes[0].messages, result.messageNodes[0].messages)
    }

    @Test
    fun `deleting an original invalidates summaries which covered it`() {
        val source = conversation(UIMessage.user("one"), UIMessage.assistant("two"), checkpoint("summary"), UIMessage.user("recent"))
        val updated = source.copy(messageNodes = source.messageNodes.filterIndexed { index, _ -> index != 1 })
        val result = invalidateChangedContextCheckpoints(source, updated)
        assertTrue(result.currentMessages[1].contextCheckpointInvalidated)
        assertEquals(updated.messageNodes.size, result.messageNodes.size)
    }

    @Test
    fun `regenerated original identity invalidates summary even when text is identical`() {
        val source = conversation(UIMessage.assistant("same answer"), checkpoint("summary"))
        val replacement = UIMessage.assistant("same answer")
        val result = invalidateChangedContextCheckpoints(source, edit(source, 0, replacement))
        assertTrue(result.currentMessages[1].contextCheckpointInvalidated)
    }

    @Test
    fun `translation usage times and visible reasoning changes do not invalidate factual history`() {
        val original = UIMessage.assistant("confirmed answer").copy(parts = listOf(
            UIMessagePart.Reasoning("earlier reasoning"), UIMessagePart.Text("confirmed answer"),
        ))
        val source = conversation(original, checkpoint("summary"))
        val changed = original.copy(
            translation = "translated answer", usage = TokenUsage(promptTokens = 42),
            finishedAt = LocalDateTime(2026, 10, 30, 1, 2),
            parts = listOf(UIMessagePart.Text("confirmed answer"), UIMessagePart.Reasoning("different reasoning")),
        )
        val updated = edit(source, 0, changed)
        assertSame(updated, invalidateChangedContextCheckpoints(source, updated))
        assertFalse(invalidateChangedContextCheckpoints(source, updated).currentMessages[1].contextCheckpointInvalidated)
    }

    @Test
    fun `tool result changes invalidate summaries but reasoning only tool changes do not`() {
        val tool = UIMessagePart.Tool("call", "inspect", "{}", listOf(UIMessagePart.Text("evidence"), UIMessagePart.Reasoning("thought")))
        val source = conversation(UIMessage.assistant("").copy(parts = listOf(tool)), checkpoint("summary"))
        val reasoningOnly = source.currentMessages[0].copy(parts = listOf(tool.copy(output = listOf(
            UIMessagePart.Reasoning("another thought"), UIMessagePart.Text("evidence"),
        ))))
        assertFalse(invalidateChangedContextCheckpoints(source, edit(source, 0, reasoningOnly)).currentMessages[1].contextCheckpointInvalidated)
        val changedResult = source.currentMessages[0].copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("different evidence")))))
        assertTrue(invalidateChangedContextCheckpoints(source, edit(source, 0, changedResult)).currentMessages[1].contextCheckpointInvalidated)
    }

    @Test
    fun `fresh checkpoint is valid while older one affected by an edit is invalidated`() {
        val source = conversation(UIMessage.user("old fact"), checkpoint("old summary"), UIMessage.user("recent"))
        val edited = edit(source, 0, source.currentMessages[0].copy(parts = listOf(UIMessagePart.Text("new fact"))))
        val updated = edited.copy(messageNodes = edited.messageNodes + checkpoint("new summary of corrected facts").toMessageNode())
        val result = invalidateChangedContextCheckpoints(source, updated)
        assertTrue(result.currentMessages[1].contextCheckpointInvalidated)
        assertFalse(result.currentMessages.last().contextCheckpointInvalidated)
    }

    @Test
    fun `old snapshot cannot reactivate previously invalidated selected or unselected checkpoint`() {
        val invalid = checkpoint("outdated").copy(contextCheckpointInvalidated = true)
        val alternate = UIMessage.user("different candidate")
        val source = conversation(UIMessage.user("fact"), invalid).let {
            it.copy(messageNodes = listOf(it.messageNodes[0], it.messageNodes[1].copy(messages = listOf(invalid, alternate), selectIndex = 1)))
        }
        val updated = source.copy(messageNodes = listOf(source.messageNodes[0], source.messageNodes[1].copy(
            messages = listOf(invalid.copy(contextCheckpointInvalidated = false), alternate), selectIndex = 0,
        )))
        val result = invalidateChangedContextCheckpoints(source, updated)
        assertTrue(result.messageNodes[1].messages[0].contextCheckpointInvalidated)
        assertEquals(updated.messageNodes[1].messages.map { it.id }, result.messageNodes[1].messages.map { it.id })
    }

    @Test
    fun `inserting a new original before an old checkpoint invalidates its changed boundary`() {
        val source = conversation(UIMessage.user("fact"), checkpoint("summary"))
        val updated = source.copy(messageNodes = listOf(source.messageNodes[0], UIMessage.user("inserted fact").toMessageNode(), source.messageNodes[1]))
        assertTrue(invalidateChangedContextCheckpoints(source, updated).currentMessages[2].contextCheckpointInvalidated)
    }

    @Test
    fun `appending recent messages and editing an unselected candidate keep summary valid`() {
        val source = conversation(UIMessage.user("fact"), checkpoint("summary")).let {
            it.copy(messageNodes = listOf(it.messageNodes[0].copy(messages = listOf(it.currentMessages[0], UIMessage.user("unused"))), it.messageNodes[1]))
        }
        val changedCandidate = source.messageNodes[0].copy(messages = listOf(source.currentMessages[0], UIMessage.user("different unused")))
        val updated = source.copy(messageNodes = listOf(changedCandidate, source.messageNodes[1], UIMessage.user("new request").toMessageNode()))
        assertSame(updated, invalidateChangedContextCheckpoints(source, updated))
    }

    @Test
    fun `session update preserves invalidated status across an older whole snapshot`() = runBlocking {
        val source = conversation(UIMessage.user("fact"), checkpoint("summary"))
        val session = ConversationSession(source.id, source, this, {})
        session.updateConversation(edit(source, 0, source.currentMessages[0].copy(parts = listOf(UIMessagePart.Text("correction")))))
        assertTrue(session.state.value.currentMessages[1].contextCheckpointInvalidated)
        session.updateConversation(source)
        assertTrue(session.state.value.currentMessages[1].contextCheckpointInvalidated)
        assertEquals(source.messageNodes.map { it.id }, session.state.value.messageNodes.map { it.id })
    }
}
