package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PhoneTaskCompletionTest {
    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val user = UIMessage.user("打开抖音，看完视频后总结")
        val phoneActions = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("open-1", "open_app", "{}", output = listOf(UIMessagePart.Text("opened"))),
                UIMessagePart.Tool("swipe-1", "swipe", "{}", output = listOf(UIMessagePart.Text("swiped"))),
            ),
        )
        val summary = UIMessage.assistant("已看完，下面是总结。")
        val history = listOf(user, phoneActions, summary)
        private val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id).updateCurrentMessages(history), scope, {})

        init { startNewRun() }

        fun startNewRun(): Any {
            val finish = CompletableDeferred<Unit>()
            session.setJob(scope.launch(start = CoroutineStart.LAZY) { finish.await() })
            return session.generationRunToken()
        }

        fun messages(messages: List<UIMessage>) {
            session.updateConversation(session.state.value.updateCurrentMessages(messages))
        }

        override fun close() {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test fun `a final summary after completed phone actions can release its display`() {
        Fixture().use { fixture ->
            val session = fixture.session
            assertTrue(session.isGenerating)
            assertTrue(session.canCompletePhoneTask(session.generationRunToken(), 0L))
        }
    }

    @Test fun `normal flow completion without the final task marker cannot release a display`() = runBlocking {
        Fixture().use { fixture ->
            val session = fixture.session
            var completionCause: Throwable? = IllegalStateException("not completed")
            var release = true
            flowOf(fixture.summary).onCompletion { cause ->
                completionCause = cause
                // Approval waits and step limits end their Flow normally without this marker.
                release = session.canCompletePhoneTask(session.generationRunToken(), null)
            }.collect { }
            assertNull(completionCause)
            assertFalse(release)
        }
    }

    @Test fun `a pending phone approval cannot be treated as a completed task`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val approval = UIMessagePart.Tool("tap-approval", "tap", "{}", approvalState = ToolApprovalState.Pending)
            fixture.messages(listOf(fixture.user, fixture.phoneActions,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(approval))))
            assertTrue(session.state.value.currentMessages.last().getTools().single().isPending)
            assertFalse(session.canCompletePhoneTask(session.generationRunToken(), 0L))
        }
    }

    @Test fun `ask user remains open until its answer is processed and the final reply arrives`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val run = session.generationRunToken()
            val question = UIMessagePart.Tool("question", "ask_user", "{}", approvalState = ToolApprovalState.Pending)
            fixture.messages(listOf(fixture.user, fixture.phoneActions,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(question))))
            assertFalse(session.canCompletePhoneTask(run, 0L))
            val answered = question.copy(approvalState = ToolApprovalState.Answered("继续"))
            fixture.messages(listOf(fixture.user, fixture.phoneActions,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(answered))))
            assertFalse(session.canCompletePhoneTask(run, 0L))
            fixture.messages(listOf(fixture.user, fixture.phoneActions,
                UIMessage(role = MessageRole.ASSISTANT,
                    parts = listOf(answered.copy(output = listOf(UIMessagePart.Text("继续"))))), fixture.summary))
            assertTrue(session.canCompletePhoneTask(run, 0L))
        }
    }

    @Test fun `unfinished server tools retain the display even beside a text summary`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val run = session.generationRunToken()
            val serverTool = UIMessagePart.ServerTool("server-image", "image_generation", status = ServerToolStatus.IN_PROGRESS)
            fixture.messages(listOf(fixture.user, fixture.phoneActions,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(serverTool)), fixture.summary))
            assertFalse(session.canCompletePhoneTask(run, 0L))
            fixture.messages(listOf(fixture.user, fixture.phoneActions,
                UIMessage(role = MessageRole.ASSISTANT,
                    parts = listOf(serverTool.copy(status = ServerToolStatus.COMPLETED))), fixture.summary))
            assertTrue(session.canCompletePhoneTask(run, 0L))
        }
    }

    @Test fun `a new supplemental instruction keeps the phone session while it is pending`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val run = session.generationRunToken()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("再看一个视频")), steering = true)
            session.steering.signal()
            assertTrue(session.steering.hasPending)
            assertFalse(session.canCompletePhoneTask(run, 0L))
        }
    }

    @Test fun `acknowledging an update does not make a stale summary current`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val run = session.generationRunToken()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("换一个视频再总结")), steering = true)
            val revision = session.steering.signal()
            val update = session.messageQueue.takeSteering().single()
            fixture.messages(fixture.history + UIMessage(id = update.id, role = MessageRole.USER, parts = update.parts))
            session.messageQueue.acknowledgeSteering(setOf(update.id))
            session.steering.acknowledgeThrough(revision)
            assertFalse(session.steering.hasPending)
            assertTrue(session.messageQueue.state.value.messages.isEmpty())
            assertFalse(session.canCompletePhoneTask(run, 0L))
            assertFalse(session.canCompletePhoneTask(run, revision)) // The latest input is not answered yet.
        }
    }

    @Test fun `a consumed and committed update can finish after its revised task is answered`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val run = session.generationRunToken()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("再看一个并补充总结")), steering = true)
            val revision = session.steering.signal()
            val update = session.messageQueue.takeSteering().single()
            fixture.messages(fixture.history + UIMessage(id = update.id, role = MessageRole.USER, parts = update.parts) +
                UIMessage.assistant("追加的视频也看完了，这是合并后的总结。"))
            session.messageQueue.acknowledgeSteering(setOf(update.id))
            session.steering.acknowledgeThrough(revision)
            assertFalse(session.canCompletePhoneTask(run, 0L))
            assertTrue(session.canCompletePhoneTask(run, revision))
        }
    }

    @Test fun `dispatched steering still retains the display until its history commit is acknowledged`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val run = session.generationRunToken()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("补充视频结尾")), steering = true)
            val revision = session.steering.signal()
            val update = session.messageQueue.takeSteering().single()
            session.steering.acknowledgeThrough(revision)
            assertTrue(session.messageQueue.state.value.messages.single().dispatched)
            assertFalse(session.steering.hasPending)
            assertFalse(session.canCompletePhoneTask(run, revision))
            fixture.messages(fixture.history + UIMessage(id = update.id, role = MessageRole.USER, parts = update.parts) +
                UIMessage.assistant("已经补充结尾。"))
            session.messageQueue.acknowledgeSteering(setOf(update.id))
            assertTrue(session.canCompletePhoneTask(run, revision))
        }
    }

    @Test fun `a paused queue cannot trigger natural completion cleanup`() {
        Fixture().use { fixture ->
            val session = fixture.session
            session.messageQueue.pause()
            assertTrue(session.messageQueue.state.value.messages.isEmpty())
            assertFalse(session.canCompletePhoneTask(session.generationRunToken(), 0L))
        }
    }

    @Test fun `an old run cannot release a replacement run in the same conversation`() {
        Fixture().use { fixture ->
            val session = fixture.session
            val oldRun = session.generationRunToken()
            val newRun = fixture.startNewRun()
            assertNotSame(oldRun, newRun)
            assertFalse(session.canCompletePhoneTask(oldRun, 0L))
            assertTrue(session.canCompletePhoneTask(newRun, 0L))
        }
    }

    @Test fun `a new instruction arriving during final persistence prevents display release`() = runBlocking {
        Fixture().use { fixture ->
            val session = fixture.session
            val run = session.generationRunToken()
            val saveStarted = CompletableDeferred<Unit>()
            val allowSave = CompletableDeferred<Unit>()
            var canReleaseAfterSave = true
            val finish = launch(start = CoroutineStart.UNDISPATCHED) {
                session.finishGeneration(messageId = fixture.summary.id) {
                    saveStarted.complete(Unit)
                    allowSave.await()
                }
                canReleaseAfterSave = session.canCompletePhoneTask(run, 0L)
            }
            saveStarted.await()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text("先别结束，再看下一条")), steering = true)
            session.steering.signal()
            allowSave.complete(Unit)
            finish.join()
            assertEquals(1, session.messageQueue.state.value.messages.size)
            assertFalse(canReleaseAfterSave)
        }
    }
}
