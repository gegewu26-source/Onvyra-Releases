package com.constx.onvyra.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import com.constx.onvyra.data.datastore.migration.BUILTIN_WUZHA_MODE_ID
import com.constx.onvyra.data.datastore.migration.BuiltinWuzhaModeMigration
import com.constx.onvyra.data.datastore.migration.builtinWuzhaMode
import com.constx.onvyra.data.model.InjectionPosition
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.PromptInjection
import com.constx.onvyra.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BuiltinWuzhaModeMigrationTest {
    private val content = "Exact content\r\n\n  preserve spaces  \n"
    private fun migration() = BuiltinWuzhaModeMigration { content }

    @Test fun freshInstallRetainsDefaultsAndAddsOneEnabledSystemMode() = runBlocking {
        val migration = migration()
        val prefs = migration.migrate(emptyPreferences())
        val modes = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(prefs[SettingsStore.MODE_INJECTIONS]!!)
        assertEquals(DEFAULT_MODE_INJECTIONS, modes.dropLast(1))
        val bundled = modes.last()
        assertEquals(BUILTIN_WUZHA_MODE_ID, bundled.id)
        assertEquals("无闸", bundled.name)
        assertTrue(bundled.enabled)
        assertTrue(BUILTIN_WUZHA_MODE_ID in Assistant().modeInjectionIds)
        assertEquals(MessageRole.SYSTEM, bundled.role)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, bundled.position)
        assertEquals(content, bundled.content)
        assertFalse(migration.shouldMigrate(prefs))
    }

    @Test fun existingEntriesUnknownFieldsAndUnrelatedSettingsArePreserved() = runBlocking {
        val existing = PromptInjection.ModeInjection(
            name = "无闸", content = "User-owned same-name mode", enabled = false,
            position = InjectionPosition.BOTTOM_OF_CHAT, role = MessageRole.ASSISTANT,
        )
        val originalEntry = JsonObject(
            JsonInstant.encodeToJsonElement(existing).jsonObject + ("futureField" to JsonPrimitive("keep"))
        )
        val prefs = mutablePreferencesOf(
            SettingsStore.MODE_INJECTIONS to JsonArray(listOf(originalEntry)).toString(),
            SettingsStore.PROVIDERS to "unchanged-provider-configuration",
            SettingsStore.ASSISTANTS to "[]",
            SettingsStore.TITLE_PROMPT to "unchanged-title-prompt",
        )
        val result = migration().migrate(prefs)
        val entries = JsonInstant.parseToJsonElement(result[SettingsStore.MODE_INJECTIONS]!!).jsonArray
        assertEquals(2, entries.size)
        assertEquals(originalEntry, entries.first())
        prefs.asMap().filterKeys { it != SettingsStore.MODE_INJECTIONS }.forEach { (key, value) ->
            assertEquals(value, result.asMap()[key])
        }
    }

    @Test fun explicitEmptyListDoesNotRestoreOtherDefaultModes() = runBlocking {
        val prefs = mutablePreferencesOf(SettingsStore.MODE_INJECTIONS to "[]")
        val result = migration().migrate(prefs)
        val modes = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(result[SettingsStore.MODE_INJECTIONS]!!)
        assertEquals(listOf(builtinWuzhaMode(content)), modes)
    }

    @Test fun upgradeLocksTheBundledModeWithoutDuplicatingItsStableId() = runBlocking {
        val edited = builtinWuzhaMode("Previously editable").copy(name = "Renamed", enabled = false)
        val lowerCase = JsonInstant.encodeToString(listOf(edited))
        val upperCase = lowerCase.replace(BUILTIN_WUZHA_MODE_ID.toString(), BUILTIN_WUZHA_MODE_ID.toString().uppercase())
        listOf(lowerCase, upperCase).forEach { originalJson ->
            val prefs = mutablePreferencesOf(SettingsStore.MODE_INJECTIONS to originalJson)
            val migration = migration()
            val result = migration.migrate(prefs)
            val modes = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(result[SettingsStore.MODE_INJECTIONS]!!)
            assertEquals(listOf(builtinWuzhaMode(content)), modes)
            assertFalse(migration.shouldMigrate(result))
        }
    }

    @Test fun chatToggleRemainsOffAcrossSubsequentStarts() = runBlocking {
        val migration = migration()
        val prefs = migration.migrate(emptyPreferences()).toMutablePreferences()
        val switchedOff = Assistant(modeInjectionIds = emptySet())
        prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(listOf(switchedOff))
        assertFalse(migration.shouldMigrate(prefs))
        assertEquals(prefs, migration.migrate(prefs))
        val restored = JsonInstant.decodeFromString<List<Assistant>>(migration.migrate(prefs)[SettingsStore.ASSISTANTS]!!).single()
        assertTrue(restored.modeInjectionIds.isEmpty())
    }

    @Test fun previousInstallMarkerStillUpgradesAndPreservesCustomAssistantFields() = runBlocking {
        val customId = PromptInjection.ModeInjection().id.toString()
        val rawAssistant = JsonInstant.parseToJsonElement("""{"name":"My assistant","modeInjectionIds":["$customId"],"futureField":"keep"}""").jsonObject
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("builtin_wuzha_mode_installed_v1") to true,
            SettingsStore.MODE_INJECTIONS to "[]",
            SettingsStore.ASSISTANTS to JsonArray(listOf(rawAssistant)).toString(),
        )
        val migration = migration()
        assertTrue(migration.shouldMigrate(prefs))
        val result = migration.migrate(prefs)
        val assistant = JsonInstant.parseToJsonElement(result[SettingsStore.ASSISTANTS]!!).jsonArray.single().jsonObject
        assertEquals(rawAssistant["name"], assistant["name"])
        assertEquals(rawAssistant["futureField"], assistant["futureField"])
        assertEquals(JsonArray(listOf(JsonPrimitive(customId), JsonPrimitive(BUILTIN_WUZHA_MODE_ID.toString()))), assistant["modeInjectionIds"])
        assertFalse(migration.shouldMigrate(result))
    }

    @Test fun unreadableAssistantBindingsAreNeverReplacedOrMarkedComplete() = runBlocking {
        listOf("not json", "{}", "[null]", """[{"modeInjectionIds":false}]""").forEach { original ->
            val prefs = mutablePreferencesOf(SettingsStore.ASSISTANTS to original)
            val migration = migration()
            assertEquals(prefs, migration.migrate(prefs))
            assertTrue(migration.shouldMigrate(prefs))
        }
    }

    @Test fun invalidExistingListIsNeverReplaced() = runBlocking {
        listOf("not json", "{}", "null").forEach { original ->
            val prefs = mutablePreferencesOf(SettingsStore.MODE_INJECTIONS to original)
            val migration = BuiltinWuzhaModeMigration { error("Invalid settings must not load an asset") }
            assertEquals(prefs, migration.migrate(prefs))
            assertTrue(migration.shouldMigrate(prefs))
        }
    }

    @Test fun bundledAssetMatchesTheUserProvidedUtf8FileBytes() {
        val bytes = File("src/main/assets/mode_injections/wuzha.txt").readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(26839, bytes.size)
        assertEquals("56e365c73d64b9e7e6712abb0a0474ffe56757e45174a948dea9a447094cd815", hash)
        assertTrue(bytes.contentEquals(bytes.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8)))
    }
}
