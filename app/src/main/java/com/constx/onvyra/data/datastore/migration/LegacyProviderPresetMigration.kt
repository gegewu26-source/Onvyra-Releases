package com.constx.onvyra.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.constx.onvyra.data.datastore.DEFAULT_SYSTEM_TTS_ID
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.utils.JsonInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.provider.ProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/** Removes only unused historical presets once; user providers and selections stay intact. */
class LegacyProviderPresetMigration : DataMigration<Preferences> {
    private val checked = booleanPreferencesKey("legacy_provider_presets_removed_v1")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[checked] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (currentData[checked] == true) return currentData
        val providers = currentData[SettingsStore.PROVIDERS]
        val ttsProviders = currentData[SettingsStore.TTS_PROVIDERS]
        // Do not replace unreadable data or mark a partially applied migration complete.
        val providerEntries = providers?.let(::parseEntries)
        if (providers != null && providerEntries == null) return currentData
        val ttsEntries = ttsProviders?.let(::parseEntries)
        if (ttsProviders != null && ttsEntries == null) return currentData

        val retainedProviders = providerEntries?.filterNot(::isUnusedLegacyChatPreset)
        val retainedTts = ttsEntries?.filterNot(::isUnusedLegacyTtsPreset)
        val prefs = currentData.toMutablePreferences()
        if (providerEntries != null && retainedProviders?.size != providerEntries.size) {
            prefs[SettingsStore.PROVIDERS] = JsonArray(retainedProviders.orEmpty()).toString()
        }
        if (ttsEntries != null && retainedTts?.size != ttsEntries.size) {
            prefs[SettingsStore.TTS_PROVIDERS] = JsonArray(retainedTts.orEmpty()).toString()
            val selected = prefs[SettingsStore.SELECTED_TTS_PROVIDER]
                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (selected == LEGACY_AIHUBMIX_TTS_PRESET.id &&
                retainedTts.orEmpty().none { (it as? JsonObject)?.providerId() == selected }) {
                prefs[SettingsStore.SELECTED_TTS_PROVIDER] = DEFAULT_SYSTEM_TTS_ID.toString()
            }
        }
        // Entries subsequently added or imported by the user are never removed again.
        prefs[checked] = true
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

private fun parseEntries(raw: String): JsonArray? =
    runCatching { JsonInstant.parseToJsonElement(raw) as? JsonArray }.getOrNull()

private fun JsonObject.providerId(): Uuid? = (get("id") as? JsonPrimitive)?.contentOrNull
    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val legacyChatFingerprints by lazy {
    LEGACY_CHAT_PROVIDER_PRESETS.associate { provider ->
        provider.id to (JsonInstant.encodeToJsonElement<ProviderSetting>(provider) as JsonObject)
    }
}

private val legacyTtsFingerprint by lazy {
    JsonInstant.encodeToJsonElement<TTSProviderSetting>(LEGACY_AIHUBMIX_TTS_PRESET) as JsonObject
}

private fun isUnusedLegacyChatPreset(element: JsonElement): Boolean {
    val entry = element as? JsonObject ?: return false
    val id = entry.providerId() ?: return false
    val expected = legacyChatFingerprints[id] ?: return false
    if (containsUnknownFields(entry, expected)) return false
    return runCatching {
        val provider = JsonInstant.decodeFromJsonElement<ProviderSetting>(entry)
        JsonInstant.encodeToJsonElement<ProviderSetting>(provider) == expected
    }.getOrDefault(false)
}

private fun isUnusedLegacyTtsPreset(element: JsonElement): Boolean {
    val entry = element as? JsonObject ?: return false
    if (entry.providerId() != LEGACY_AIHUBMIX_TTS_PRESET.id || containsUnknownFields(entry, legacyTtsFingerprint)) return false
    return runCatching {
        val provider = JsonInstant.decodeFromJsonElement<TTSProviderSetting>(entry)
        JsonInstant.encodeToJsonElement<TTSProviderSetting>(provider) == legacyTtsFingerprint
    }.getOrDefault(false)
}

/** Unknown fields may carry configuration from a newer build, so preserve the whole entry. */
private fun containsUnknownFields(actual: JsonElement, expected: JsonElement): Boolean = when {
    actual is JsonObject && expected is JsonObject -> actual.any { (key, value) ->
        key !in expected || containsUnknownFields(value, expected.getValue(key))
    }
    actual is JsonArray && expected is JsonArray -> actual.indices.any { index ->
        index >= expected.size || containsUnknownFields(actual[index], expected[index])
    }
    else -> false
}
