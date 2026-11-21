package com.constx.onvyra.data.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.constx.onvyra.data.datastore.migration.LegacySearchServiceMigration
import com.constx.onvyra.data.datastore.migration.migrateLegacySearchServices
import com.constx.onvyra.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Test

class LegacySearchServiceMigrationTest {
    private fun services(): List<SearchServiceOptions> = listOf(
        SearchServiceOptions.CustomJsOptions(name = "RikkaHub", searchScript = "function search() { return { items: [] }; }"),
        SearchServiceOptions.DoubaoOptions(apiKey = "test-only-doubao"),
        SearchServiceOptions.LegacyPresetOptions(apiKey = "test-only-rikkahub"),
        SearchServiceOptions.GrokOptions(apiKey = "test-only-grok"),
        SearchServiceOptions.BingLocalOptions(),
    )

    private fun encoded(options: List<SearchServiceOptions>) = JsonInstant.encodeToString(options)

    @Test fun removesBothAuthorizedRikkaHubEntriesAndPreservesOtherJsonFields() {
        val original = JsonInstant.parseToJsonElement(encoded(services())).jsonArray.toMutableList()
        original[1] = JsonObject(original[1].jsonObject + ("futureField" to JsonPrimitive("retained")))
        val (migrated, selected) = migrateLegacySearchServices(JsonArray(original).toString(), 2)
        assertEquals(original.filterIndexed { index, _ -> index != 0 && index != 2 }, JsonInstant.parseToJsonElement(migrated).jsonArray)
        assertEquals(0, selected)
    }

    @Test fun selectionTracksTheSameRemainingProvider() {
        val original = services()
        mapOf(1 to 0, 3 to 1, 4 to 2).forEach { (previous, expected) ->
            val (migrated, selected) = migrateLegacySearchServices(encoded(original), previous)
            assertEquals(expected, selected)
            assertEquals(original[previous], JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated)[selected])
        }
    }

    @Test fun removedOrInvalidSelectionFallsBackToFirstRemainingProvider() {
        listOf(0, 2, -1, 99).forEach { previous ->
            val (_, selected) = migrateLegacySearchServices(encoded(services()), previous)
            assertEquals(0, selected)
        }
    }

    @Test fun credentialBearingRikkaHubProvidersAreAlsoRemoved() {
        val original = services().toMutableList().apply {
            this[0] = SearchServiceOptions.LegacyPresetOptions(apiKey = "test-only-removed", depth = "advanced")
        }
        val (migrated, _) = migrateLegacySearchServices(encoded(original), 2)
        assertEquals(original.filterNot { it is SearchServiceOptions.LegacyPresetOptions }, JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated))
    }

    @Test fun exactCustomNameMatchesIgnoringCaseAndOuterWhitespaceOnly() {
        val removed = SearchServiceOptions.CustomJsOptions(name = "  rIkKaHuB  ")
        val retained = listOf(
            SearchServiceOptions.CustomJsOptions(name = "Onvyra", searchScript = "keep-onvyra"),
            SearchServiceOptions.CustomJsOptions(name = "My RikkaHub", searchScript = "keep-prefix"),
            SearchServiceOptions.CustomJsOptions(name = "RikkaHub backup", searchScript = "keep-suffix"),
            SearchServiceOptions.CustomJsOptions(name = "", searchScript = "keep-unnamed"),
        )
        val (migrated, selected) = migrateLegacySearchServices(encoded(listOf(removed) + retained), 3)
        assertEquals(retained, JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated))
        assertEquals(2, selected)
    }

    @Test fun reorderedAndAdditionalProvidersKeepTheirOwnIdentity() {
        val original = services().reversed() + SearchServiceOptions.CustomJsOptions(name = "user script")
        val retained = original.filterNot {
            it is SearchServiceOptions.LegacyPresetOptions || it is SearchServiceOptions.CustomJsOptions && it.name == "RikkaHub"
        }
        val (migrated, selected) = migrateLegacySearchServices(encoded(original), original.lastIndex)
        assertEquals(retained, JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated))
        assertEquals(retained.lastIndex, selected)
    }

    @Test fun removalOfAllServicesInstallsOnlyTheLocalOnvyraDefault() {
        val original = listOf<SearchServiceOptions>(
            SearchServiceOptions.LegacyPresetOptions(),
            SearchServiceOptions.CustomJsOptions(name = "RikkaHub"),
        )
        val (migrated, selected) = migrateLegacySearchServices(encoded(original), 1)
        val remaining = JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated)
        assertEquals(1, remaining.size)
        assertTrue(remaining.single() is SearchServiceOptions.BingLocalOptions)
        assertEquals(0, selected)
    }

    @Test fun unknownProviderObjectsArePreservedWithoutDecodingThem() {
        val original = JsonInstant.parseToJsonElement(encoded(services())).jsonArray.toMutableList()
        val futureProvider = JsonObject(mapOf("type" to JsonPrimitive("future_provider"), "unknown" to JsonPrimitive("retain")))
        original.add(futureProvider)
        val (migrated, selected) = migrateLegacySearchServices(JsonArray(original).toString(), original.lastIndex)
        val remaining = JsonInstant.parseToJsonElement(migrated).jsonArray
        assertEquals(futureProvider, remaining.last())
        assertEquals(remaining.lastIndex, selected)
    }

    @Test fun previousV1MarkerDoesNotSkipRemovalOfTheFirstRemainingEntry() = runBlocking {
        val migration = LegacySearchServiceMigration()
        val original = services().filterIndexed { index, _ -> index != 2 }
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("duplicate_rikkahub_search_checked_v1") to true,
            SettingsStore.SEARCH_SERVICES to encoded(original),
            SettingsStore.SEARCH_SELECTED to 2,
        )
        assertTrue(migration.shouldMigrate(prefs))
        val migrated = migration.migrate(prefs)
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(original.drop(1), JsonInstant.decodeFromString<List<SearchServiceOptions>>(migrated[SettingsStore.SEARCH_SERVICES]!!))
        assertEquals(1, migrated[SettingsStore.SEARCH_SELECTED])
    }

    @Test fun marksNonmatchingInstallationOnceSoFutureExplicitConfigurationIsNotRemoved() = runBlocking {
        val migration = LegacySearchServiceMigration()
        val prefs = mutablePreferencesOf(SettingsStore.SEARCH_SERVICES to "[]", SettingsStore.SEARCH_SELECTED to 0)
        assertTrue(migration.shouldMigrate(prefs))
        val checked = migration.migrate(prefs).toMutablePreferences()
        assertFalse(migration.shouldMigrate(checked))
        checked[SettingsStore.SEARCH_SERVICES] = encoded(services())
        assertEquals(checked, migration.migrate(checked))
    }

    @Test fun malformedAndAlreadyCleanConfigurationsAreLeftUntouched() {
        assertEquals("not json" to 99, migrateLegacySearchServices("not json", 99))
        listOf(emptyList(), services().filterIndexed { index, _ -> index != 0 && index != 2 }).forEach { options ->
            val json = encoded(options)
            assertEquals(json to 1, migrateLegacySearchServices(json, 1))
        }
    }
}
