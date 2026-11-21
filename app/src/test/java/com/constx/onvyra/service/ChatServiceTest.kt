package com.constx.onvyra.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import com.constx.onvyra.data.ai.tools.shouldUseExternalWebSearch
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.ConversationConfig
import com.constx.onvyra.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `explicit model selection updates only the next chat model default of a started chat`() {
        val previous = Assistant(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH,
            enabledSkills = setOf("old-conversation-skill"))
        val stored = previous.copy(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.LOW,
            enabledSkills = setOf("latest-default-skill"))
        val conversation = Conversation(assistantId = previous.id, messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = previous.chatModelId,
                reasoningLevel = previous.reasoningLevel, enabledSkills = previous.enabledSkills))
        val selected = previous.copy(chatModelId = Uuid.random())
        val result = updatedStoredChatAssistant(conversation, stored, previous, selected, rememberChatModel = true)
        assertEquals(selected.chatModelId, result.chatModelId)
        assertEquals(ReasoningLevel.LOW, result.reasoningLevel)
        assertEquals(setOf("latest-default-skill"), result.enabledSkills)
        assertEquals(previous.chatModelId, conversation.config!!.chatModelId)
        assertEquals(ReasoningLevel.HIGH, conversation.config!!.reasoningLevel)
    }

    @Test
    fun `ordinary changes in an old chat retain the more recent default model`() {
        val previous = Assistant(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH)
        val stored = previous.copy(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.LOW)
        val conversation = Conversation(assistantId = previous.id, messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = previous.chatModelId, reasoningLevel = previous.reasoningLevel))
        val updated = previous.copy(reasoningLevel = ReasoningLevel.OFF, systemPrompt = "new prompt")
        val result = updatedStoredChatAssistant(conversation, stored, previous, updated)
        assertEquals(stored.chatModelId, result.chatModelId)
        assertEquals(stored.reasoningLevel, result.reasoningLevel)
        assertEquals("new prompt", result.systemPrompt)
    }

    @Test
    fun `explicit selection before the first message keeps the existing assistant default semantics`() {
        val previous = Assistant(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH)
        val conversation = Conversation(assistantId = previous.id, messageNodes = emptyList())
        val updated = previous.copy(chatModelId = Uuid.random())
        val result = updatedStoredChatAssistant(conversation, previous, previous, updated, rememberChatModel = true)
        assertEquals(updated, result)
        assertNull(conversation.config)
    }

    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            config = ConversationConfig(chatModelId = Uuid.random(), reasoningLevel = ReasoningLevel.HIGH),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.config, fork.config)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("Source conversation(1)", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `context checkpoint is inserted after the anchor node without touching other nodes`() {
        val nodes = List(4) { UIMessage.user("message $it").toMessageNode() }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)

        val result = insertContextCheckpoint(source, afterNodeId = nodes[1].id, summary = "summary")!!

        assertEquals(nodes.subList(0, 2), result.messageNodes.subList(0, 2))
        assertEquals(nodes.subList(2, 4), result.messageNodes.subList(3, 5))
        val checkpoint = result.messageNodes[2].currentMessage
        assertTrue(checkpoint.isContextCheckpoint)
        assertEquals("summary", checkpoint.toText())
        // 只有检查点之后的消息会继续发送给模型
        assertEquals(result.currentMessages.subList(2, 5), result.currentMessages.limitContext(0))
    }

    @Test
    fun `context checkpoint is not inserted when the anchor node is gone`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("message").toMessageNode()),
        )

        assertNull(insertContextCheckpoint(source, afterNodeId = Uuid.random(), summary = "summary"))
    }

    @Test
    fun `fork title increments existing numeric suffix instead of stacking`() {
        assertEquals("Chat(2)", forkConversationTitle("Chat(1)", emptySet()))
        assertEquals("Chat(4)", forkConversationTitle("Chat(1)", setOf("Chat(2)", "Chat(3)")))
        assertEquals("Chat(1)", forkConversationTitle("Chat", emptySet()))
        assertEquals("Chat(2)", forkConversationTitle("Chat", setOf("Chat(1)")))
        assertEquals("Chat(abc)(1)", forkConversationTitle("Chat(abc)", emptySet()))
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model, conversationId)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
        assertEquals(conversationId.toString(), params.sessionId)
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }
}
