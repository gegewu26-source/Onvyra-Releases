package com.constx.onvyra.data.datastore.migration

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import com.constx.onvyra.data.datastore.DEFAULT_MODE_INJECTIONS
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.model.InjectionPosition
import com.constx.onvyra.data.model.BUILTIN_PROMPT_MODE_ID
import com.constx.onvyra.data.model.PromptInjection
import com.constx.onvyra.utils.JsonInstant
import kotlin.uuid.Uuid

internal const val BUILTIN_WUZHA_MODE_ASSET = "mode_injections/wuzha.txt"
internal val BUILTIN_WUZHA_MODE_ID = BUILTIN_PROMPT_MODE_ID

/** Installs the locked mode and its assistant defaults once; chat toggles stay authoritative. */
class BuiltinWuzhaModeMigration internal constructor(
    private val loadContent: () -> String,
) : DataMigration<Preferences> {
    constructor(context: Context) : this({
        context.assets.open(BUILTIN_WUZHA_MODE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    })

    private val installed = booleanPreferencesKey("builtin_wuzha_mode_locked_v2")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[installed] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (currentData[installed] == true) return currentData

        val original = currentData[SettingsStore.MODE_INJECTIONS]
        val entries = if (original == null) {
            JsonInstant.encodeToJsonElement(DEFAULT_MODE_INJECTIONS) as JsonArray
        } else {
            // Preserve every existing JSON entry, including fields unknown to this version.
            // A malformed list is left untouched rather than replaced with defaults.
            runCatching { JsonInstant.parseToJsonElement(original) as JsonArray }
                .getOrNull() ?: return currentData
        }
        fun isBundled(entry: kotlinx.serialization.json.JsonElement): Boolean {
            val id = runCatching {
                (entry as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { Uuid.parse(it) }
            }.getOrNull()
            return id == BUILTIN_WUZHA_MODE_ID
        }

        // Preserve custom modes and their unknown fields. The bundled mode now comes
        // from the app asset, so previously edited/disabled copies become read-only.
        val bundledMode = JsonInstant.encodeToJsonElement(builtinWuzhaMode(loadContent()))
        val firstBundled = entries.indexOfFirst(::isBundled)
        val modes = entries.filterNot(::isBundled).toMutableList()
        modes.add(if (firstBundled < 0) modes.size else firstBundled.coerceAtMost(modes.size), bundledMode)

        val assistants = currentData[SettingsStore.ASSISTANTS]?.let { originalAssistants ->
            // Do not damage unreadable assistant data or mark a partially applied migration complete.
            val parsed = runCatching { JsonInstant.parseToJsonElement(originalAssistants) as JsonArray }
                .getOrNull() ?: return currentData
            val updated = parsed.map { entry ->
                val assistant = entry as? JsonObject ?: return currentData
                val selected = assistant["modeInjectionIds"]?.let {
                    it as? JsonArray ?: return currentData
                } ?: JsonArray(emptyList())
                val hasBuiltin = selected.any {
                    runCatching { Uuid.parse(it.jsonPrimitive.content) }.getOrNull() == BUILTIN_WUZHA_MODE_ID
                }
                JsonObject(assistant + ("modeInjectionIds" to if (hasBuiltin) selected else JsonArray(
                    selected + JsonPrimitive(BUILTIN_WUZHA_MODE_ID.toString())
                )))
            }
            JsonArray(updated).toString()
        }
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.MODE_INJECTIONS] = JsonArray(modes).toString()
        if (assistants != null) prefs[SettingsStore.ASSISTANTS] = assistants
        // Ordinary starts never enable a mode that the user switched off in chat.
        prefs[installed] = true
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun builtinWuzhaMode(content: String) = PromptInjection.ModeInjection(
    id = BUILTIN_WUZHA_MODE_ID,
    name = "无闸",
    enabled = true,
    position = InjectionPosition.AFTER_SYSTEM_PROMPT,
    content = content,
    role = MessageRole.SYSTEM,
)
