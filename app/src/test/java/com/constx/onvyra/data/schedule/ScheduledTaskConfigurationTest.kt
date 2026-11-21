package com.constx.onvyra.data.schedule

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class ScheduledTaskConfigurationTest {
    private val model = Model(modelId = "wb-cn-deepseek-v4.1-flash")
    private val otherModel = Model(modelId = "gpt-other")
    private val provider = ProviderSetting.OpenAI(models = listOf(model), name = "WorkBuddy")
    private val otherProvider = ProviderSetting.OpenAI(models = listOf(otherModel), name = "Other")
    private val assistant = Assistant(name = "定时助手", chatModelId = model.id)
    private val otherAssistant = Assistant(name = "其他助手", chatModelId = otherModel.id)
    private val settings get() = Settings(
        assistants = listOf(assistant, otherAssistant),
        assistantId = otherAssistant.id,
        chatModelId = otherModel.id,
        providers = listOf(provider, otherProvider),
    )
    private fun snapshot() = snapshotScheduledTask(
        ScheduledTask(title = "test", assistantId = assistant.id.toString(), prompt = "test", firstRunAtMillis = 1_000),
        settings,
    )

    @Test fun bindsExactAssistantModelAndProviderWithoutChangingCurrentAssistant() {
        val before = settings
        val task = snapshotScheduledTask(
            ScheduledTask(title = "test", assistantId = assistant.id.toString(), prompt = "test", firstRunAtMillis = 1_000), before,
        )
        assertEquals(model.id.toString(), task.modelId)
        assertEquals(provider.id.toString(), task.providerId)
        assertEquals("wb-cn-deepseek-v4.1-flash", task.modelPublicId)
        assertEquals(model.id, task.conversationConfig!!.chatModelId)
        assertEquals(otherAssistant.id, before.assistantId)
        requireScheduledTaskConfiguration(task, before)
    }

    @Test fun deletedModelDoesNotFallbackToAnotherProviderOrGlobalModel() {
        val task = snapshot()
        val changed = settings.copy(providers = listOf(otherProvider))
        assertThrows(IllegalStateException::class.java) { requireScheduledTaskConfiguration(task, changed) }
    }

    @Test fun disabledProviderFailsExplicitly() {
        val task = snapshot()
        val changed = settings.copy(providers = listOf(provider.copy(enabled = false), otherProvider))
        assertThrows(IllegalStateException::class.java) { requireScheduledTaskConfiguration(task, changed) }
    }

    @Test fun reusedModelUuidWithDifferentPublicModelCannotChangeTaskRoute() {
        val task = snapshot()
        val changed = settings.copy(providers = listOf(provider.copy(models = listOf(model.copy(modelId = "gpt-other"))), otherProvider))
        assertThrows(IllegalStateException::class.java) { requireScheduledTaskConfiguration(task, changed) }
    }

    @Test fun changedEndpointOrModelProviderOverrideRequiresExplicitResave() {
        val task = snapshot()
        val changedEndpoint = settings.copy(providers = listOf(provider.copy(baseUrl = "https://example.invalid/v1"), otherProvider))
        assertThrows(IllegalStateException::class.java) { requireScheduledTaskConfiguration(task, changedEndpoint) }
        val overwritten = model.copy(providerOverwrite = otherProvider)
        val changedOverride = settings.copy(providers = listOf(provider.copy(models = listOf(overwritten)), otherProvider))
        assertThrows(IllegalStateException::class.java) { requireScheduledTaskConfiguration(task, changedOverride) }
    }

    @Test fun laterGlobalOrAssistantSelectionDoesNotSilentlyReplaceSnapshotModel() {
        val task = snapshot()
        val changed = settings.copy(assistantId = otherAssistant.id, assistants = listOf(assistant.copy(chatModelId = otherModel.id), otherAssistant))
        requireScheduledTaskConfiguration(task, changed)
        assertEquals(model.id, task.conversationConfig!!.chatModelId)
    }
}
