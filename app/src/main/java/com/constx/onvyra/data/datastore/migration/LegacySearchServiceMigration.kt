package com.constx.onvyra.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.utils.JsonInstant
import me.rerere.search.SearchServiceOptions

/** One-time removal of the RikkaHub services the user has explicitly asked to remove. */
class LegacySearchServiceMigration : DataMigration<Preferences> {
    // A separate marker also updates installations where v1 only removed the third, duplicate entry.
    private val checked = booleanPreferencesKey("removed_rikkahub_search_checked_v2")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[checked] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (currentData[checked] == true) return currentData
        val prefs = currentData.toMutablePreferences()
        val original = prefs[SettingsStore.SEARCH_SERVICES]
        if (original != null) {
            val migrated = migrateLegacySearchServices(original, prefs[SettingsStore.SEARCH_SELECTED] ?: 0)
            if (migrated.first != original) {
                prefs[SettingsStore.SEARCH_SERVICES] = migrated.first
                prefs[SettingsStore.SEARCH_SELECTED] = migrated.second
            }
        }
        // Mark even a nonmatch: a list the user creates later must never trigger this removal.
        prefs[checked] = true
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun migrateLegacySearchServices(servicesJson: String, selected: Int): Pair<String, Int> =
    runCatching {
        val original = JsonInstant.parseToJsonElement(servicesJson) as? JsonArray
            ?: return@runCatching servicesJson to selected
        val retainedIndices = original.indices.filter { index ->
            val option = original[index] as? JsonObject
            val type = (option?.get("type") as? JsonPrimitive)?.contentOrNull
            val name = (option?.get("name") as? JsonPrimitive)?.contentOrNull
            // "Onvyra" uses bing_local. Other custom scripts, including similarly named ones,
            // must keep their identity, contents and credentials.
            type != "rikkahub" &&
                !(type == "custom_js" && name?.trim()?.equals("RikkaHub", ignoreCase = true) == true)
        }
        if (retainedIndices.size == original.size) return@runCatching servicesJson to selected
        // Preserve the original objects, including fields unknown to this build.
        val entries = retainedIndices.map { original[it] }.toMutableList()
        if (entries.isEmpty()) {
            // The local Onvyra provider requires no credentials and keeps the selected index valid.
            entries += JsonInstant.parseToJsonElement(
                JsonInstant.encodeToString<SearchServiceOptions>(SearchServiceOptions.DEFAULT)
            )
        }
        val nextSelected = retainedIndices.indexOf(selected).takeIf { it >= 0 } ?: 0
        JsonInstant.encodeToString(JsonArray(entries)) to nextSelected
    }.getOrDefault(servicesJson to selected)
