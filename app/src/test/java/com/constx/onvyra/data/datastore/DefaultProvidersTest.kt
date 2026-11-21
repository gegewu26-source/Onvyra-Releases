package com.constx.onvyra.data.datastore

import com.constx.onvyra.data.datastore.migration.LEGACY_CHAT_PROVIDER_PRESETS
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DefaultProvidersTest {
    @Test fun `fresh defaults offer only OpenAI and keep system speech`() {
        val provider = DEFAULT_PROVIDERS.single()
        assertTrue(provider is ProviderSetting.OpenAI)
        provider as ProviderSetting.OpenAI
        assertEquals(DEFAULT_OPENAI_PROVIDER_ID, provider.id)
        assertEquals("OpenAI", provider.name)
        assertEquals("https://api.openai.com/v1", provider.baseUrl)
        assertEquals("", provider.apiKey)
        assertTrue(provider.models.isEmpty())
        assertTrue(provider.builtIn)
        assertTrue(DEFAULT_TTS_PROVIDERS.single() is TTSProviderSetting.SystemTTS)
    }

    @Test fun `normalizing repeatedly never reinstalls removed bundled providers`() {
        val settings = Settings(providers = emptyList(), ttsProviders = emptyList()).normalized().normalized()
        assertEquals(listOf(DEFAULT_OPENAI_PROVIDER_ID), settings.providers.map { it.id })
        assertEquals(listOf(DEFAULT_SYSTEM_TTS_ID), settings.ttsProviders.map { it.id })
    }

    @Test fun `custom providers and historical configured models keep their identities`() {
        val legacy = LEGACY_CHAT_PROVIDER_PRESETS.filterIsInstance<ProviderSetting.OpenAI>().first()
        val model = Model(modelId = "user-model")
        val configured = legacy.copy(apiKey = "test-only-key", models = listOf(model))
        val manual = ProviderSetting.Google(id = Uuid.random(), name = "Gemini", apiKey = "test-only-google")
        val settings = Settings(providers = listOf(configured, manual), chatModelId = model.id).normalized()
        val retained = settings.providers.first { it.id == configured.id } as ProviderSetting.OpenAI
        assertEquals(configured.apiKey, retained.apiKey)
        assertEquals(listOf(model.id), retained.models.map { it.id })
        assertFalse(retained.builtIn)
        assertEquals(manual, settings.providers.first { it.id == manual.id })
        assertEquals(model.id, settings.chatModelId)
        assertEquals(setOf(configured.id, manual.id, DEFAULT_OPENAI_PROVIDER_ID), settings.providers.map { it.id }.toSet())
    }
}
