package com.constx.onvyra.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.constx.onvyra.data.model.isAwaitingTaskAnswer
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class GenerationSteeringTest {
    @Test fun `task updates preserve asynchronous questions while an explicit stop closes them`() {
        val question = UIMessagePart.Tool("question", "ask_user", "{}",
            output = listOf(UIMessagePart.Text("""{"status":"awaiting_user_answer"}""")),
            approvalState = ToolApprovalState.Pending,
            metadata = buildJsonObject { put("awaiting_user_answer", true) })
        val history = listOf(UIMessage.assistant("").copy(parts = listOf(question)), UIMessage.user("supplement"))
        val retained = history.finishSupersededToolCalls().first().getTools().single()
        assertTrue(retained.isAwaitingTaskAnswer)
        assertEquals(question, retained)
        val stopped = history.finishSupersededToolCalls(taskRetained = false).first().getTools().single()
        assertFalse(stopped.isAwaitingTaskAnswer)
        assertTrue(stopped.approvalState is ToolApprovalState.Denied)
        assertTrue(stopped.isExecuted)
    }

    @Test fun `ordinary requests finish without a steering signal`() = runTest {
        val steering = GenerationSteering()
        var completed = false
        assertTrue(steering.runRequest { completed = true })
        assertTrue(completed)
        assertFalse(steering.hasPending)
    }

    @Test fun `input already pending prevents starting an outdated request`() = runTest {
        val steering = GenerationSteering()
        steering.signal()
        assertFalse(steering.runRequest { error("Must not submit an outdated request") })
        assertTrue(steering.hasPending)
    }

    @Test fun `a running request is interrupted and joined before replanning`() = runTest {
        val steering = GenerationSteering()
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var cleanupFinished = false
        val running = async {
            steering.runRequest {
                try { started.complete(Unit); awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        finishCleanup.await()
                        cleanupFinished = true
                    }
                }
            }
        }
        started.await()
        steering.signal()
        cleanupStarted.await()
        assertFalse("The next request must not overtake old chunks or finally saves", running.isCompleted)
        finishCleanup.complete(Unit)
        assertFalse(running.await())
        assertTrue(cleanupFinished)
        assertTrue(steering.hasPending)
    }

    @Test fun `acknowledgement cannot consume an input arriving while a drain suspends`() {
        val steering = GenerationSteering()
        val first = steering.signal()
        val snapshot = steering.snapshotRevision()
        val second = steering.signal()
        assertEquals(first, snapshot)
        steering.acknowledgeThrough(snapshot)
        assertTrue(steering.hasPending)
        steering.acknowledgeThrough(second)
        assertFalse(steering.hasPending)
        steering.acknowledgeThrough(first)
        assertFalse("An old consumer cannot move acknowledgement backwards", steering.hasPending)
    }

    @Test fun `multiple fast inputs remain ordered through repeated drains`() = runTest {
        val steering = GenerationSteering()
        val waiting = mutableListOf<String>()
        val received = mutableListOf<String>()
        fun submit(text: String) { waiting += text; steering.signal() }
        submit("draw a cat")
        submit("use a blue background")
        val firstSnapshot = steering.snapshotRevision()
        val firstBatch = waiting.toList().also { waiting.clear() }
        // Model preparation or attachment handling can suspend between take and acknowledgement.
        val next = launch { submit("cancel the background, keep the cat") }
        next.join()
        received += firstBatch
        steering.acknowledgeThrough(firstSnapshot)
        assertTrue(steering.hasPending)
        while (steering.hasPending) {
            val snapshot = steering.snapshotRevision()
            received += waiting.toList().also { waiting.clear() }
            steering.acknowledgeThrough(snapshot)
        }
        assertEquals(listOf("draw a cat", "use a blue background", "cancel the background, keep the cat"), received)
        assertTrue(waiting.isEmpty())
    }

    @Test fun `external stop cancels the request and never turns into a steering retry`() = runTest {
        val steering = GenerationSteering()
        val started = CompletableDeferred<Unit>()
        var requestFinished = false
        var returned = false
        val generation = launch {
            steering.runRequest {
                try { started.complete(Unit); awaitCancellation() }
                finally { requestFinished = true }
            }
            returned = true
        }
        started.await()
        generation.cancelAndJoin()
        assertTrue(requestFinished)
        assertFalse(returned)
        assertFalse(steering.hasPending)
    }

    @Test fun `provider failure propagates without being mistaken for new input`() = runTest {
        val steering = GenerationSteering()
        val expected = IllegalStateException("provider fixture failed")
        val actual = runCatching { steering.runRequest { throw expected } }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy the exception across an async boundary.
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertFalse(steering.hasPending)
    }

    @Test fun `rapid signals interrupt the current request once and keep their latest revision`() = runTest {
        val steering = GenerationSteering()
        var cancellations = 0
        val started = CompletableDeferred<Unit>()
        val request = async {
            steering.runRequest {
                try { started.complete(Unit); awaitCancellation() }
                catch (cancelled: CancellationException) { cancellations++; throw cancelled }
            }
        }
        started.await()
        repeat(100) { steering.signal() }
        assertFalse(request.await())
        assertEquals(1, cancellations)
        assertEquals(100L, steering.snapshotRevision())
        steering.acknowledgeThrough(99L)
        assertTrue(steering.hasPending)
        steering.acknowledgeThrough(100L)
        assertTrue(steering.runRequest { })
        runCurrent()
    }

    @Test fun `tool boundary preserves completed effects and cancels all old proposals`() {
        val completed = UIMessagePart.Tool("done", "send_message", "{}",
            output = listOf(UIMessagePart.Text("already sent")))
        val proposed = UIMessagePart.Tool("next", "delete_item", "{}", approvalState = ToolApprovalState.Approved)
        val pending = UIMessagePart.Tool("approval", "publish", "{}", approvalState = ToolApprovalState.Pending)
        val user = UIMessage.user("send the message then delete and publish")
        val response = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completed, proposed, pending))
        val result = listOf(user, response).finishSupersededToolCalls()
        assertEquals(user, result.first())
        val tools = result.last().getTools()
        assertEquals(completed, tools.first())
        for (tool in tools.drop(1)) {
            assertTrue(tool.isExecuted)
            assertFalse(tool.isPending)
            assertFalse(tool.canResumeExecution)
            assertTrue(tool.approvalState is ToolApprovalState.Denied)
            assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("\"status\":\"superseded\""))
            assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("\"task_retained\":true"))
            assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("new_user_instruction"))
        }
        assertEquals(response.id, result.last().id)
        assertNotNull(result.last().finishedAt)
    }

    @Test fun `unfinished text response is finalized without removing its useful partial work`() {
        val text = UIMessagePart.Text("work completed so far")
        val reasoning = UIMessagePart.Reasoning("brief thought", finishedAt = null)
        val response = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning, text), finishedAt = null)
        val result = listOf(response).finishSupersededToolCalls().single()
        assertEquals(response.id, result.id)
        assertEquals(text, result.parts.last())
        assertNotNull(result.finishedAt)
        assertNotNull(result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
    }

    @Test fun `interrupted image previews are dropped while completed images and server tool results survive`() {
        val savedImage = UIMessagePart.Image("file:///fixture/already-completed.png")
        val preview = UIMessagePart.Image("data:image/png;base64,unfinished")
        val completedServerTool = UIMessagePart.ServerTool("done", "web_search", status = ServerToolStatus.COMPLETED)
        val pendingServerTool = UIMessagePart.ServerTool("running", "image_generation", status = ServerToolStatus.IN_PROGRESS)
        val interrupted = UIMessage(role = MessageRole.ASSISTANT, finishedAt = null,
            parts = listOf(savedImage, preview, completedServerTool, pendingServerTool))
        val completedImage = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(preview),
            finishedAt = LocalDateTime(2026, 11, 9, 12, 0))
        val result = listOf(interrupted, completedImage).finishSupersededToolCalls()
        assertEquals(listOf(savedImage), result.first().parts.filterIsInstance<UIMessagePart.Image>())
        assertEquals(completedServerTool, result.first().parts.filterIsInstance<UIMessagePart.ServerTool>().first())
        assertEquals(ServerToolStatus.FAILED, result.first().parts.filterIsInstance<UIMessagePart.ServerTool>().last().status)
        assertEquals(completedImage, result.last())
    }
}
