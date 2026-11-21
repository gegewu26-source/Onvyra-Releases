package com.constx.onvyra.service

import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.ConversationConfig
import com.constx.onvyra.data.model.MessageNode
import com.constx.onvyra.data.model.deferTaskAnswer
import com.constx.onvyra.data.model.isAwaitingTaskAnswer
import com.constx.onvyra.data.model.submittedTaskAnswer
import com.constx.onvyra.data.model.withSubmittedTaskAnswer
import com.constx.onvyra.utils.JsonInstant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationCheckpointSnapshotTest {
    private fun tool(id: String, approval: ToolApprovalState = ToolApprovalState.Auto,
        output: List<UIMessagePart> = emptyList()) = UIMessagePart.Tool(
        toolCallId = id, toolName = "synthetic_operation", input = "{}",
        approvalState = approval, output = output,
    )

    private fun conversation(message: UIMessage) = Conversation.ofId(Uuid.random())
        .updateCurrentMessages(listOf(UIMessage.user("Long running task"), message))

    private fun UIMessage.onlyText() = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    @Test fun recreatedSessionLoadsProgressEvenThoughGenerationNeverFinished() = runTest {
        val initial = Conversation.ofId(Uuid.random()).updateCurrentMessages(listOf(UIMessage.user("Execute the task")))
        val session = ConversationSession(initial.id, initial, this, {})
        session.initialize { initial }
        var serialized = JsonInstant.encodeToString(initial)
        val writer = GenerationCheckpointWriter(this, save = {
            serialized = JsonInstant.encodeToString(session.state.value.generationCheckpointSnapshot())
        })
        session.updateConversation(initial.updateCurrentMessages(initial.currentMessages +
            UIMessage.assistant("Files one through twenty are processed").copy(parts = listOf(
                UIMessagePart.Text("Files one through twenty are processed"),
                tool("completed", output = listOf(UIMessagePart.Text("saved file"))),
                tool("not_yet_confirmed"),
            ))))
        writer.changed()
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        // Abrupt process loss has no finishGeneration callback. Only the checkpoint is loaded.
        writer.close()
        val recreated = ConversationSession(initial.id, Conversation.ofId(initial.id), this, {})
        recreated.initialize { JsonInstant.decodeFromString<Conversation>(serialized) }
        assertEquals(2, recreated.state.value.currentMessages.size)
        val restored = recreated.state.value.currentMessages.last()
        assertEquals("Files one through twenty are processed", restored.onlyText())
        assertEquals("saved file", (restored.getTools().first().output.single() as UIMessagePart.Text).text)
        assertFalse(restored.getTools().last().canResumeExecution)
    }

    @Test fun savedProgressSurvivesSerializationWithoutChangingTheLiveStream() {
        val completed = tool("finished", output = listOf(UIMessagePart.Text("operation completed")))
        val inFlight = tool("in_flight", ToolApprovalState.Approved)
        val reasoning = UIMessagePart.Reasoning("already examined twenty files", finishedAt = null)
        val liveReply = UIMessage.assistant("partial result").copy(
            parts = listOf(reasoning, UIMessagePart.Text("partial result"), completed, inFlight),
            usage = TokenUsage(promptTokens = 20_000, completionTokens = 100),
            translation = "a saved translation", modelId = Uuid.random(),
        )
        val live = conversation(liveReply)
        val checkpoint = live.generationCheckpointSnapshot()
        val reopened = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(checkpoint))
        val reply = reopened.currentMessages.last()
        assertEquals("Long running task", reopened.currentMessages.first().toText())
        assertEquals("partial result", reply.onlyText())
        assertEquals(completed, reply.getTools().first())
        assertTrue(reply.getTools().last().isExecuted)
        assertTrue(reply.getTools().last().approvalState is ToolApprovalState.Denied)
        assertNotNull(reply.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
        assertEquals(liveReply.usage, reply.usage)
        assertEquals(liveReply.modelId, reply.modelId)
        assertEquals(liveReply.translation, reply.translation)
        assertEquals(liveReply, live.currentMessages.last())
        assertNull(reasoning.finishedAt)
        assertFalse(inFlight.isExecuted)
    }

    @Test fun restartSnapshotClosesUnconfirmedActionsWithoutReplayingThem() {
        val proposals = listOf(ToolApprovalState.Auto, ToolApprovalState.Approved,
            ToolApprovalState.Denied("previous decision"), ToolApprovalState.Answered("previous answer"))
            .mapIndexed { index, approval -> tool("proposal_$index", approval) }
        val snapshot = conversation(UIMessage.assistant("").copy(parts = proposals))
            .generationCheckpointSnapshot().currentMessages.last()
        assertEquals(proposals.map { it.toolCallId }, snapshot.getTools().map { it.toolCallId })
        snapshot.getTools().forEach {
            assertTrue("An unconfirmed action needs a result that tells the model it was interrupted", it.isExecuted)
            assertFalse("Opening saved progress must not automatically execute a side effect again", it.canResumeExecution)
            assertTrue(it.approvalState is ToolApprovalState.Denied)
        }
    }

    @Test fun completedModelRequestStillCheckpointsTheToolWorkThatFollowsIt() {
        val modelRequestFinishedAt = LocalDateTime(2026, 11, 18, 12, 0)
        val completed = tool("confirmed", output = listOf(UIMessagePart.Text("file already saved")))
        val running = tool("approved_but_unconfirmed", ToolApprovalState.Approved)
        val remote = UIMessagePart.ServerTool("remote_in_progress", "remote", status = ServerToolStatus.IN_PROGRESS)
        val liveReply = UIMessage.assistant("").copy(
            parts = listOf(completed, running, remote), finishedAt = modelRequestFinishedAt,
        )
        val live = conversation(liveReply)
        val saved = live.generationCheckpointSnapshot().currentMessages.last()
        assertEquals("A finished model response must not imply that every tool has finished", modelRequestFinishedAt, saved.finishedAt)
        assertEquals(completed, saved.getTools().first())
        val unknown = saved.getTools().last()
        assertTrue(unknown.isExecuted)
        assertFalse(unknown.canResumeExecution)
        assertTrue(unknown.approvalState is ToolApprovalState.Denied)
        assertEquals(ServerToolStatus.FAILED, saved.parts.filterIsInstance<UIMessagePart.ServerTool>().single().status)
        assertEquals(liveReply, live.currentMessages.last())
        assertFalse(running.isExecuted)
        assertEquals(ServerToolStatus.IN_PROGRESS, remote.status)
    }

    @Test fun explicitApprovalWaitQuestionAndReceivedAnswerRemainAvailable() {
        val approval = tool("manual_approval", ToolApprovalState.Pending)
        val waiting = tool("question").copy(toolName = "ask_user").deferTaskAnswer()
        val received = tool("answered_question").copy(toolName = "ask_user")
            .deferTaskAnswer().withSubmittedTaskAnswer("Use the second option", emptyList())
        val snapshot = conversation(UIMessage.assistant("").copy(parts = listOf(approval, waiting, received)))
            .generationCheckpointSnapshot().currentMessages.last().getTools()
        assertEquals(listOf(approval, waiting, received), snapshot)
        assertTrue(snapshot[0].isPending)
        assertTrue(snapshot[1].isAwaitingTaskAnswer)
        assertEquals("Use the second option", snapshot[2].submittedTaskAnswer()?.payload)
    }

    @Test fun inlineImagePreviewsDoNotPreventOtherProgressFromBeingSaved() {
        val nested = tool("image_result", output = listOf(
            UIMessagePart.Image("data:image/png;base64,synthetic"),
            UIMessagePart.Text("generated image is still being stored"),
        ))
        val onlyImage = tool("image_only", output = listOf(UIMessagePart.Image("data:image/png;base64,partial")))
        val localImage = UIMessagePart.Image("file:///synthetic/completed.png")
        val live = conversation(UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Text("completed work"), UIMessagePart.Image("data:image/png;base64,partial"),
            localImage, nested, onlyImage,
        )))
        val saved = live.generationCheckpointSnapshot()
        val reply = saved.currentMessages.last()
        assertEquals("completed work", reply.onlyText())
        assertEquals(listOf(localImage), reply.parts.filterIsInstance<UIMessagePart.Image>())
        assertTrue(reply.getTools().all { it.isExecuted })
        assertTrue(reply.getTools().flatMap { it.output }.none { it is UIMessagePart.Image && it.url.startsWith("data:") })
        assertTrue(reply.getTools().first().output.contains(UIMessagePart.Text("generated image is still being stored")))
        assertTrue("The original preview is still available to the live renderer", live.currentMessages.last().hasBase64Part())
    }

    @Test fun branchSelectionConfigurationAndCompletedServerResultsArePreserved() {
        val done = UIMessagePart.ServerTool("remote_done", "remote", output = JsonPrimitive("done"),
            status = ServerToolStatus.COMPLETED)
        val running = UIMessagePart.ServerTool("remote_running", "remote", status = ServerToolStatus.IN_PROGRESS)
        val selected = UIMessage.assistant("current partial").copy(parts = listOf(done, running))
        val alternative = UIMessage.assistant("previous branch")
        val node = MessageNode(messages = listOf(alternative, selected), selectIndex = 1, isFavorite = true)
        val live = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node),
            isPinned = true, isLocked = true, folderId = Uuid.random(), workspaceCwd = "/workspace/work",
            config = ConversationConfig(chatModelId = Uuid.random(), enabledSkills = setOf("fixture")))
        val saved = live.generationCheckpointSnapshot()
        assertEquals(live.id, saved.id)
        assertEquals(live.folderId, saved.folderId)
        assertEquals(live.config, saved.config)
        assertEquals(live.workspaceCwd, saved.workspaceCwd)
        assertTrue(saved.isPinned)
        assertTrue(saved.isLocked)
        assertEquals(node.id, saved.messageNodes.single().id)
        assertEquals(1, saved.messageNodes.single().selectIndex)
        assertTrue(saved.messageNodes.single().isFavorite)
        assertEquals(alternative.id, saved.messageNodes.single().messages.first().id)
        val serverTools = saved.currentMessages.single().parts.filterIsInstance<UIMessagePart.ServerTool>()
        assertEquals(done, serverTools.first())
        assertEquals(ServerToolStatus.FAILED, serverTools.last().status)
        assertEquals(ServerToolStatus.IN_PROGRESS, running.status)
    }
}
