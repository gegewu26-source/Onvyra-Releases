package com.constx.onvyra.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.constx.onvyra.data.datastore.migration.LEGACY_AIHUBMIX_TTS_PRESET
import com.constx.onvyra.data.datastore.migration.LEGACY_CHAT_PROVIDER_PRESETS
import com.constx.onvyra.data.datastore.migration.LegacyProviderPresetMigration
import com.constx.onvyra.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class LegacyProviderPresetMigrationTest {
    private fun chatJson(providers: List<ProviderSetting>) = JsonInstant.encodeToString(providers)
    private fun ttsJson(providers: List<TTSProviderSetting>) = JsonInstant.encodeToString(providers)

    @Test fun `upgrade removes untouched historical presets and retains OpenAI and model choices`() = runBlocking {
        val choice = Uuid.random().toString()
        val prefs = mutablePreferencesOf(
            SettingsStore.PROVIDERS to chatJson(DEFAULT_PROVIDERS + LEGACY_CHAT_PROVIDER_PRESETS),
            SettingsStore.SELECT_MODEL to choice,
            SettingsStore.FAST_MODEL to choice,
            SettingsStore.TRANSLATE_MODEL to choice,
            SettingsStore.COMPRESS_MODEL to choice,
        )
        val migration = LegacyProviderPresetMigration()
        val result = migration.migrate(prefs)
        val retained = JsonInstant.decodeFromString<List<ProviderSetting>>(result[SettingsStore.PROVIDERS]!!)
        assertEquals(listOf(DEFAULT_OPENAI_PROVIDER_ID), retained.map { it.id })
        prefs.asMap().filterKeys { it != SettingsStore.PROVIDERS }.forEach { (key, value) ->
            assertEquals(value, result.asMap()[key])
        }
        assertFalse(migration.shouldMigrate(result))
        assertEquals(listOf(DEFAULT_OPENAI_PROVIDER_ID), result.toSettings().normalized().providers.map { it.id })
    }

    @Test fun `credentials models URLs enabled flags and custom names preserve historical entries`() = runBlocking {
        val preset = LEGACY_CHAT_PROVIDER_PRESETS.filterIsInstance<ProviderSetting.OpenAI>().first()
        val customized = listOf(
            preset.copy(apiKey = "test-only-key"),
            preset.copy(models = listOf(Model(modelId = "user-model"))),
            preset.copy(baseUrl = "https://custom.example.test/v1"),
            preset.copy(enabled = !preset.enabled),
            preset.copy(name = "My gateway"),
            preset.copy(useResponseApi = !preset.useResponseApi),
            preset.copy(chatCompletionsPath = "/custom/chat"),
            preset.copy(balanceOption = preset.balanceOption.copy(enabled = !preset.balanceOption.enabled)),
            preset.copy(id = Uuid.random()),
        )
        customized.forEach { provider ->
            val original = chatJson(listOf(provider))
            val result = LegacyProviderPresetMigration().migrate(mutablePreferencesOf(SettingsStore.PROVIDERS to original))
            assertEquals(original, result[SettingsStore.PROVIDERS])
        }
    }

    @Test fun `unknown top level and nested fields keep whole original provider objects`() = runBlocking {
        val expected = JsonInstant.encodeToJsonElement<ProviderSetting>(LEGACY_CHAT_PROVIDER_PRESETS.first()).jsonObject
        val future = JsonObject(expected + ("futureField" to JsonPrimitive("keep")))
        val balance = expected.getValue("balanceOption").jsonObject
        val nested = JsonObject(expected + ("balanceOption" to JsonObject(balance + ("futureBalance" to JsonPrimitive(7)))))
        val unknownType = JsonObject(mapOf("type" to JsonPrimitive("future_provider"), "configuration" to JsonPrimitive("keep")))
        val original = JsonArray(listOf(future, nested, unknownType)).toString()
        val result = LegacyProviderPresetMigration().migrate(mutablePreferencesOf(SettingsStore.PROVIDERS to original))
        assertEquals(original, result[SettingsStore.PROVIDERS])
    }

    @Test fun `mixed lists preserve custom raw fields and remove only unused presets`() = runBlocking {
        val preset = JsonInstant.encodeToJsonElement<ProviderSetting>(LEGACY_CHAT_PROVIDER_PRESETS.first())
        val custom = JsonObject(preset.jsonObject + ("apiKey" to JsonPrimitive("test-only-key")) +
            ("futureField" to JsonPrimitive("keep")))
        val result = LegacyProviderPresetMigration().migrate(mutablePreferencesOf(
            SettingsStore.PROVIDERS to JsonArray(listOf(preset, custom)).toString(),
        ))
        assertEquals(listOf(custom), JsonInstant.parseToJsonElement(result[SettingsStore.PROVIDERS]!!).jsonArray)
    }

    @Test fun `unused AiHubMix TTS is removed and its selection returns to system speech`() = runBlocking {
        val prefs = mutablePreferencesOf(
            SettingsStore.TTS_PROVIDERS to ttsJson(DEFAULT_TTS_PROVIDERS + LEGACY_AIHUBMIX_TTS_PRESET),
            SettingsStore.SELECTED_TTS_PROVIDER to LEGACY_AIHUBMIX_TTS_PRESET.id.toString(),
        )
        val result = LegacyProviderPresetMigration().migrate(prefs)
        val tts = JsonInstant.decodeFromString<List<TTSProviderSetting>>(result[SettingsStore.TTS_PROVIDERS]!!)
        assertEquals(listOf(DEFAULT_SYSTEM_TTS_ID), tts.map { it.id })
        assertEquals(DEFAULT_SYSTEM_TTS_ID.toString(), result[SettingsStore.SELECTED_TTS_PROVIDER])

        val configured = LEGACY_AIHUBMIX_TTS_PRESET.copy(apiKey = "test-only-tts")
        val customPrefs = mutablePreferencesOf(
            SettingsStore.TTS_PROVIDERS to ttsJson(listOf(configured)),
            SettingsStore.SELECTED_TTS_PROVIDER to configured.id.toString(),
        )
        val retained = LegacyProviderPresetMigration().migrate(customPrefs)
        assertEquals(customPrefs[SettingsStore.TTS_PROVIDERS], retained[SettingsStore.TTS_PROVIDERS])
        assertEquals(configured.id.toString(), retained[SettingsStore.SELECTED_TTS_PROVIDER])
    }

    @Test fun `future TTS configuration and manual same name entries remain untouched`() = runBlocking {
        val expected = JsonInstant.encodeToJsonElement<TTSProviderSetting>(LEGACY_AIHUBMIX_TTS_PRESET).jsonObject
        val future = JsonObject(expected + ("futureVoiceSetting" to JsonPrimitive(true)))
        val manual = JsonInstant.encodeToJsonElement<TTSProviderSetting>(LEGACY_AIHUBMIX_TTS_PRESET.copy(id = Uuid.random()))
        val original = JsonArray(listOf(future, manual)).toString()
        val result = LegacyProviderPresetMigration().migrate(mutablePreferencesOf(SettingsStore.TTS_PROVIDERS to original))
        assertEquals(original, result[SettingsStore.TTS_PROVIDERS])
    }

    @Test fun `removing an unused duplicate never deselects its retained configured TTS entry`() = runBlocking {
        val configured = LEGACY_AIHUBMIX_TTS_PRESET.copy(apiKey = "test-only-tts")
        val prefs = mutablePreferencesOf(
            SettingsStore.TTS_PROVIDERS to ttsJson(listOf(LEGACY_AIHUBMIX_TTS_PRESET, configured)),
            SettingsStore.SELECTED_TTS_PROVIDER to configured.id.toString(),
        )
        val result = LegacyProviderPresetMigration().migrate(prefs)
        val retained = JsonInstant.decodeFromString<List<TTSProviderSetting>>(result[SettingsStore.TTS_PROVIDERS]!!)
        assertEquals(listOf(configured), retained)
        assertEquals(configured.id.toString(), result[SettingsStore.SELECTED_TTS_PROVIDER])
    }

    @Test fun `marker protects provider configuration subsequently added or imported by user`() = runBlocking {
        val migration = LegacyProviderPresetMigration()
        val first = migration.migrate(emptyPreferences()).toMutablePreferences()
        first[SettingsStore.PROVIDERS] = chatJson(LEGACY_CHAT_PROVIDER_PRESETS)
        first[SettingsStore.TTS_PROVIDERS] = ttsJson(listOf(LEGACY_AIHUBMIX_TTS_PRESET))
        assertFalse(migration.shouldMigrate(first))
        assertEquals(first, migration.migrate(first))
    }

    @Test fun `malformed lists are neither replaced nor partially migrated`() = runBlocking {
        listOf("not json", "{}", "null").forEach { invalid ->
            val migration = LegacyProviderPresetMigration()
            val badChat = mutablePreferencesOf(SettingsStore.PROVIDERS to invalid,
                SettingsStore.TTS_PROVIDERS to ttsJson(listOf(LEGACY_AIHUBMIX_TTS_PRESET)))
            assertEquals(badChat, migration.migrate(badChat))
            assertTrue(migration.shouldMigrate(badChat))
            val badTts = mutablePreferencesOf(SettingsStore.PROVIDERS to chatJson(LEGACY_CHAT_PROVIDER_PRESETS),
                SettingsStore.TTS_PROVIDERS to invalid)
            assertEquals(badTts, migration.migrate(badTts))
            assertTrue(migration.shouldMigrate(badTts))
        }
    }
}
