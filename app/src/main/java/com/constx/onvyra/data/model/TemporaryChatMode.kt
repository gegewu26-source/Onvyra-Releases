package com.constx.onvyra.data.model

/** Session-only choice: deliberately absent from Room, preferences and exported conversations. */
enum class TemporaryChatMode {
    OFF,
    PERSONALIZED,
    NON_PERSONALIZED;

    val isTemporary: Boolean get() = this != OFF
}

/** Preserve provider/model parameters while excluding user personalization from this request. */
fun Assistant.forTemporaryChat(mode: TemporaryChatMode): Assistant = when (mode) {
    TemporaryChatMode.OFF, TemporaryChatMode.PERSONALIZED -> this
    TemporaryChatMode.NON_PERSONALIZED -> copy(
        name = "", useAssistantAvatar = false, systemPrompt = "",
        enableMemory = false, useGlobalMemory = false, enableRecentChatsReference = false,
        messageTemplate = "{{ message }}", presetMessages = emptyList(), quickMessageIds = emptySet(),
        regexes = emptyList(), modeInjectionIds = emptySet(), lorebookIds = emptySet(),
        enabledSkills = emptySet(), mcpServers = emptySet(), localTools = emptyList(),
        workspaceId = null, enableTimeReminder = false, allowConversationSystemPrompt = false,
    )
}
