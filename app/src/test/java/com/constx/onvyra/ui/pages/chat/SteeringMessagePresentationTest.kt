package com.constx.onvyra.ui.pages.chat

import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.MessageNode
import com.constx.onvyra.data.model.toMessageNode
import com.constx.onvyra.service.MessageQueueState
import com.constx.onvyra.service.QueuedMessage
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SteeringMessagePresentationTest {
    private val conversation = Conversation.ofId(Uuid.random())
    private fun input(text: String, steering: Boolean = true) = QueuedMessage(
        parts = listOf(UIMessagePart.Text(text)), steering = steering,
    )

    @Test
    fun `accepted instructions appear as user bubbles with attachments and stable ids`() {
        val queued = input("改成小狗").copy(parts = listOf(
            UIMessagePart.Text("改成小狗"), UIMessagePart.Image("file:///reference.png"),
        ))

        val result = presentSteeringMessages(conversation, MessageQueueState(messages = listOf(queued)))

        val node = result.pendingNodes.single()
        assertEquals(queued.id, node.id)
        assertEquals(queued.id, node.currentMessage.id)
        assertEquals(MessageRole.USER, node.currentMessage.role)
        assertEquals(queued.parts, node.currentMessage.parts)
        assertTrue(result.queueForPanel.messages.isEmpty())
        assertTrue(conversation.messageNodes.isEmpty())
    }

    @Test
    fun `a committed instruction replaces its pending bubble without duplication`() {
        val queued = input("不要发布")
        val state = MessageQueueState(messages = listOf(queued))
        val before = presentSteeringMessages(conversation, state).pendingNodes.single()
        val committed = UIMessage(id = queued.id, role = MessageRole.USER, parts = queued.parts)
            .toMessageNode().copy(id = queued.id)

        val after = presentSteeringMessages(conversation.copy(messageNodes = listOf(committed)), state)

        assertEquals(before.id, committed.id)
        assertTrue(after.pendingNodes.isEmpty())
        assertTrue(after.queueForPanel.messages.isEmpty())
    }

    @Test
    fun `stopped or failed instructions return to the recoverable queue panel`() {
        val state = MessageQueueState(messages = listOf(input("补充任务")), paused = true)

        val result = presentSteeringMessages(conversation, state)

        assertTrue(result.pendingNodes.isEmpty())
        assertSame(state, result.queueForPanel)
        assertTrue(result.queueForPanel.paused)
    }

    @Test
    fun `an empty queue keeps all queue state without pending bubbles`() {
        val state = MessageQueueState()

        val result = presentSteeringMessages(conversation, state)

        assertTrue(result.pendingNodes.isEmpty())
        assertSame(state, result.queueForPanel)
    }

    @Test
    fun `ordinary queued messages keep their order and queue controls without steering`() {
        val first = input("稍后发送", steering = false)
        val second = input("接着发送", steering = false)
        val state = MessageQueueState(messages = listOf(first, second))

        val result = presentSteeringMessages(conversation, state)

        assertTrue(result.pendingNodes.isEmpty())
        assertSame(state, result.queueForPanel)
        assertEquals(listOf(first, second), result.queueForPanel.messages)
        assertFalse(result.queueForPanel.paused)
    }

    @Test
    fun `queues without active steering do not inspect message history`() {
        val history = object : AbstractList<MessageNode>() {
            override val size: Int get() = error("Message history must not be inspected")
            override fun get(index: Int): MessageNode = error("Message history must not be inspected")
        }
        val historyConversation = conversation.copy(messageNodes = history)
        val queues = listOf(
            MessageQueueState(),
            MessageQueueState(messages = listOf(input("稍后发送", steering = false))),
            MessageQueueState(messages = listOf(input("已暂停的指令")), paused = true),
        )

        queues.forEach { state ->
            val result = presentSteeringMessages(historyConversation, state)

            assertTrue(result.pendingNodes.isEmpty())
            assertSame(state, result.queueForPanel)
        }
    }

    @Test
    fun `ordinary queued voice or send without answer messages retain the queue controls`() {
        val normal = input("先读完", steering = false)
        val steering = input("也查看第二页")
        val result = presentSteeringMessages(conversation,
            MessageQueueState(messages = listOf(normal, steering)))

        assertEquals(listOf(steering.id), result.pendingNodes.map { it.currentMessage.id })
        assertEquals(listOf(normal), result.queueForPanel.messages)
        assertFalse(result.queueForPanel.paused)
    }

    @Test
    fun `acknowledged alternate versions are hidden and duplicate pending ids keep the first entry`() {
        val committed = input("committed")
        val pending = input("pending")
        val duplicate = pending.copy(parts = listOf(UIMessagePart.Text("duplicate")))
        val branch = MessageNode(messages = listOf(
            UIMessage.user("selected branch"),
            UIMessage(id = committed.id, role = MessageRole.USER, parts = committed.parts),
        ))
        val state = MessageQueueState(messages = listOf(committed, pending, duplicate))

        val result = presentSteeringMessages(conversation.copy(messageNodes = listOf(branch)), state)

        val pendingMessage = result.pendingNodes.single().currentMessage
        assertEquals(pending.id, pendingMessage.id)
        assertEquals(pending.parts, pendingMessage.parts)
        assertTrue(result.queueForPanel.messages.isEmpty())
    }

    @Test
    fun `rapid instructions keep submission order while acknowledged entries disappear individually`() {
        val first = input("生成风景")
        val second = input("改成海边")
        val third = input("加一只小猫")
        val state = MessageQueueState(messages = listOf(first, second, third))

        val before = presentSteeringMessages(conversation, state)
        assertEquals(listOf(first.id, second.id, third.id), before.pendingNodes.map { it.currentMessage.id })

        val persisted = conversation.copy(messageNodes = listOf(
            UIMessage(id = first.id, role = MessageRole.USER, parts = first.parts).toMessageNode(),
        ))
        val after = presentSteeringMessages(persisted, state)
        assertEquals(listOf(second.id, third.id), after.pendingNodes.map { it.currentMessage.id })
    }
}
