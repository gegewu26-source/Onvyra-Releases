package com.constx.onvyra.data.model

import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import com.constx.onvyra.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class AndroidControlPermissionConfigurationTest {
    private val assistant = Assistant()
    private fun settings(value: Assistant) = Settings(assistantId = value.id, assistants = listOf(value))
    private fun conversation() = Conversation(assistantId = assistant.id, messageNodes = emptyList())

    @Test fun oldSerializedAssistantAndConversationKeepTheAutonomousDefault() {
        assertEquals(AndroidControlPermissionMode.AUTONOMOUS, Json.decodeFromString<Assistant>("{}").androidControlPermissionMode)
        assertEquals(AndroidControlPermissionMode.AUTONOMOUS, Json.decodeFromString<ConversationConfig>("{}").androidControlPermissionMode)
        val stored = assistant.copy(androidControlPermissionMode = AndroidControlPermissionMode.FULL_ACCESS)
        assertEquals(stored.androidControlPermissionMode,
            Json.decodeFromString<Assistant>(Json.encodeToString(Assistant.serializer(), stored)).androidControlPermissionMode)
    }

    @Test fun selectionStaysOnItsConversationAndBecomesTheDefaultForANewChat() {
        val initialSettings = settings(assistant)
        val originalChat = conversation().bindConfig(initialSettings)
        val before = initialSettings.getAssistantOf(originalChat)
        val selected = before.copy(androidControlPermissionMode = AndroidControlPermissionMode.FULL_ACCESS)
        val editedChat = originalChat.withAssistantUpdate(selected, initialSettings)
        val newDefault = assistant.withChatExtensionDefaults(before, selected)
        val newSettings = settings(newDefault)
        val newChat = conversation().bindConfig(newSettings)

        assertEquals(AndroidControlPermissionMode.FULL_ACCESS, newSettings.getAssistantOf(editedChat).androidControlPermissionMode)
        assertEquals(AndroidControlPermissionMode.FULL_ACCESS, newChat.config!!.androidControlPermissionMode)
        assertEquals(AndroidControlPermissionMode.AUTONOMOUS, newSettings.getAssistantOf(originalChat).androidControlPermissionMode)
    }

    @Test fun unrelatedEditsInAnOlderChatCannotReplaceTheLatestApprovalDefault() {
        val latestDefaults = assistant.copy(androidControlPermissionMode = AndroidControlPermissionMode.FULL_ACCESS)
        val olderChatView = assistant.copy(androidControlPermissionMode = AndroidControlPermissionMode.AUTONOMOUS)
        val reasoningChange = olderChatView.copy(reasoningLevel = ReasoningLevel.LOW)
        val defaults = latestDefaults.withChatExtensionDefaults(olderChatView, reasoningChange)

        assertEquals(AndroidControlPermissionMode.FULL_ACCESS, defaults.androidControlPermissionMode)
        assertEquals(assistant.reasoningLevel, defaults.reasoningLevel)
    }

    @Test fun allThreeModesRoundTripAsConversationConfiguration() {
        AndroidControlPermissionMode.entries.forEach { mode ->
            val config = ConversationConfig(androidControlPermissionMode = mode)
            val encoded = Json.encodeToString(ConversationConfig.serializer(), config)
            assertEquals(config, Json.decodeFromString<ConversationConfig>(encoded))
        }
    }
}
