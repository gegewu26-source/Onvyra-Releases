package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationToolsPrivacyTest {
    private val assistant = Uuid.random()
    private fun fixture(locked: Boolean = false, owner: Uuid = assistant) = Conversation(
        assistantId = owner,
        title = "private title",
        isLocked = locked,
        messageNodes = listOf(MessageNode(messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("private body"))),
        ))),
    )

    @Test fun anUnlockedOwnedConversationStillReturnsTheRealReadToolPayload() = runBlocking {
        val conversation = fixture()
        val tool = memoryConversationReadTool(setOf(assistant)) { id ->
            assertEquals(conversation.id, id)
            conversation
        }
        val output = tool.execute(buildJsonObject { put("conversation_id", conversation.id.toString()) })
        val payload = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("conversation_read", tool.name)
        assertEquals("private title", payload["title"]!!.jsonPrimitive.content)
        assertEquals("private body", payload["messages"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun aKnownLockedConversationIdProducesTheSameErrorAsANonexistentId() = runBlocking {
        val locked = fixture(locked = true)
        suspend fun errorFor(conversation: Conversation?): Throwable? {
            val tool = memoryConversationReadTool(setOf(assistant)) { conversation }
            return runCatching { tool.execute(buildJsonObject {
                put("conversation_id", locked.id.toString())
            }) }.exceptionOrNull()
        }
        val denied = errorFor(locked)
        val missing = errorFor(null)
        assertNotNull(denied)
        assertEquals("conversation not found", denied!!.message)
        assertEquals(missing!!::class, denied::class)
        assertEquals(missing.message, denied.message)
        assertFalse(denied.message!!.contains(locked.title))
    }

    @Test fun anUnlockedConversationOfAnotherAssistantRemainsUnavailable() = runBlocking {
        val conversation = fixture(owner = Uuid.random())
        val tool = memoryConversationReadTool(setOf(assistant)) { conversation }
        val failure = runCatching { tool.execute(buildJsonObject {
            put("conversation_id", conversation.id.toString())
        }) }.exceptionOrNull()
        assertEquals("conversation not found", failure?.message)
    }

    @Test fun anInvalidIdNeverLoadsAConversation() = runBlocking {
        var reads = 0
        val tool = memoryConversationReadTool(setOf(assistant)) { reads++; fixture() }
        val failure = runCatching { tool.execute(buildJsonObject {
            put("conversation_id", "invalid-id")
        }) }.exceptionOrNull()
        assertEquals("conversation not found", failure?.message)
        assertEquals(0, reads)
    }
}
