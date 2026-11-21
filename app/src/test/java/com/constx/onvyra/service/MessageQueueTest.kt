package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test fun `whole task stop removes only its own queued inputs and releases a later new task`() {
        val queue = MessageQueue()
        queue.enqueue(text("old pending task"), steering = true)
        val oldIds = queue.takeSteering().map { it.id }.toSet()
        queue.pause()
        queue.enqueue(text("new task after stop"), steering = true)
        queue.finishWholeTaskStop(oldIds)
        assertFalse(queue.state.value.paused)
        assertEquals(text("new task after stop"), queue.takeNext()!!.parts)
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test fun `whole stop with no newer input stays idle without replaying the old plan`() {
        val queue = MessageQueue()
        queue.enqueue(text("cancel this queued action"))
        val ids = queue.state.value.messages.map { it.id }.toSet()
        queue.pause()
        queue.finishWholeTaskStop(ids)
        assertTrue(queue.state.value.paused)
        assertTrue(queue.state.value.messages.isEmpty())
        assertNull(queue.takeNext())
    }

    @Test
    fun `running inputs are consumed in order without waiting for queued voice turns`() {
        val queue = MessageQueue()
        val voice = CompletableDeferred<String?>()
        queue.enqueue(text("voice next turn"), reply = voice)
        queue.enqueue(text("also save it"), steering = true)
        queue.enqueue(text("do not publish"), steering = true)
        assertEquals(listOf(text("also save it"), text("do not publish")), queue.takeSteering().map { it.parts })
        assertEquals(3, queue.state.value.messages.size)
        assertTrue(queue.takeSteering().isEmpty())
        assertFalse(voice.isCompleted)
        queue.acknowledgeSteering(queue.state.value.messages.drop(1).map { it.id }.toSet())
        assertEquals(text("voice next turn"), queue.takeNext()!!.parts)
    }

    @Test
    fun `accepted steering retains attachments and stable identity until history acknowledges it`() {
        val queue = MessageQueue()
        val parts = text("use this instead") + UIMessagePart.Image("file:///replacement.png")
        queue.enqueue(parts, steering = true)
        val id = queue.takeSteering().single().id
        assertEquals(id, queue.state.value.messages.single().id)
        assertEquals(parts, queue.state.value.messages.single().parts)
        assertNull(queue.takeNext())
        assertNull(queue.remove(id))
        assertNull(queue.beginEdit(id))
        queue.acknowledgeSteering(setOf(id))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rapid follow ups cannot be lost by acknowledging an earlier history chunk`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"), steering = true)
        val first = queue.takeSteering().single()
        queue.enqueue(text("second"), steering = true)
        queue.enqueue(text("third"), steering = true)
        queue.acknowledgeSteering(setOf(first.id))
        assertEquals(listOf(text("second"), text("third")), queue.takeSteering().map { it.parts })
    }

    @Test
    fun `stopping preserves uncommitted steering and does not restart it automatically`() {
        val queue = MessageQueue()
        queue.enqueue(text("change the subject"), steering = true)
        val id = queue.takeSteering().single().id
        queue.pause()
        queue.recoverUncommittedSteering()
        assertTrue(queue.takeSteering().isEmpty())
        assertNull(queue.takeNext())
        assertFalse(queue.state.value.messages.single().dispatched)
        queue.resume()
        assertEquals(id, queue.takeNext()!!.id)
    }

    @Test
    fun `a correction can supersede an approval without releasing unrelated queued turns`() {
        val queue = MessageQueue()
        queue.enqueue(text("later voice turn"))
        assertNull(queue.takeNext(steeringOnly = true))
        queue.enqueue(text("cancel that action"), steering = true)
        assertEquals(text("cancel that action"), queue.takeNext(steeringOnly = true)!!.parts)
        assertEquals(text("later voice turn"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeNext()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeNext()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = MessageQueue()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("edited"), queue.takeNext()!!.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        val id = queue.takeNext()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = MessageQueue()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeNext())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = MessageQueue()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = MessageQueue()
        val second = MessageQueue()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeNext())
        assertEquals(text("second"), second.takeNext()!!.parts)
    }
}
