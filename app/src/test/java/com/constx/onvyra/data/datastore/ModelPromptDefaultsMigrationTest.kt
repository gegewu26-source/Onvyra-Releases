package com.constx.onvyra.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.constx.onvyra.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_OCR_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_TITLE_PROMPT
import com.constx.onvyra.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import com.constx.onvyra.data.ai.prompts.LEGACY_DEFAULT_COMPRESS_PROMPT
import com.constx.onvyra.data.ai.prompts.PREVIOUS_DEFAULT_COMPRESS_PROMPT
import com.constx.onvyra.data.ai.prompts.PREVIOUS_DEFAULT_OCR_PROMPT
import com.constx.onvyra.data.ai.prompts.PREVIOUS_DEFAULT_SUGGESTION_PROMPT
import com.constx.onvyra.data.ai.prompts.PREVIOUS_DEFAULT_TITLE_PROMPT
import com.constx.onvyra.data.ai.prompts.PREVIOUS_DEFAULT_TRANSLATION_PROMPT
import com.constx.onvyra.data.datastore.migration.ModelPromptDefaultsMigration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPromptDefaultsMigrationTest {
    private val migration = ModelPromptDefaultsMigration()

    @Test fun shippedDefaultsUpgradeWithoutTouchingOtherSettings() = runBlocking {
        val original = mutablePreferencesOf(
            SettingsStore.TITLE_PROMPT to PREVIOUS_DEFAULT_TITLE_PROMPT,
            SettingsStore.TRANSLATION_PROMPT to PREVIOUS_DEFAULT_TRANSLATION_PROMPT,
            SettingsStore.OCR_PROMPT to PREVIOUS_DEFAULT_OCR_PROMPT,
            SettingsStore.SUGGESTION_PROMPT to PREVIOUS_DEFAULT_SUGGESTION_PROMPT,
            SettingsStore.COMPRESS_PROMPT to PREVIOUS_DEFAULT_COMPRESS_PROMPT,
            SettingsStore.IMAGE_GENERATION_PROMPT to "custom image guidance",
            SettingsStore.VIDEO_GENERATION_PROMPT to "custom video guidance",
            SettingsStore.PROVIDERS to "provider configuration",
        )
        assertTrue(migration.shouldMigrate(original))
        val result = migration.migrate(original)
        assertEquals(DEFAULT_TITLE_PROMPT, result[SettingsStore.TITLE_PROMPT])
        assertEquals(DEFAULT_TRANSLATION_PROMPT, result[SettingsStore.TRANSLATION_PROMPT])
        assertEquals(DEFAULT_OCR_PROMPT, result[SettingsStore.OCR_PROMPT])
        assertEquals(DEFAULT_SUGGESTION_PROMPT, result[SettingsStore.SUGGESTION_PROMPT])
        assertEquals(DEFAULT_COMPRESS_PROMPT, result[SettingsStore.COMPRESS_PROMPT])
        assertEquals("custom image guidance", result[SettingsStore.IMAGE_GENERATION_PROMPT])
        assertEquals("custom video guidance", result[SettingsStore.VIDEO_GENERATION_PROMPT])
        assertEquals(original[SettingsStore.PROVIDERS], result[SettingsStore.PROVIDERS])
        assertFalse(migration.shouldMigrate(result))
        assertEquals(result, migration.migrate(result))
    }

    @Test fun customEmptyAndNearlyMatchingPromptsArePreservedExactly() = runBlocking {
        val original = mutablePreferencesOf(
            SettingsStore.TITLE_PROMPT to PREVIOUS_DEFAULT_TITLE_PROMPT + "\n",
            SettingsStore.TRANSLATION_PROMPT to "My custom {source_text} to {target_lang}",
            SettingsStore.OCR_PROMPT to "",
            SettingsStore.SUGGESTION_PROMPT to "  ",
            SettingsStore.COMPRESS_PROMPT to "Keep my {content}; {target_tokens}; {locale}; {additional_context}",
        )
        assertFalse(migration.shouldMigrate(original))
        assertEquals(original, migration.migrate(original))
    }

    @Test fun oldestCompressionDefaultAlsoUpgrades() = runBlocking {
        val original = mutablePreferencesOf(SettingsStore.COMPRESS_PROMPT to LEGACY_DEFAULT_COMPRESS_PROMPT)
        assertEquals(DEFAULT_COMPRESS_PROMPT, migration.migrate(original)[SettingsStore.COMPRESS_PROMPT])
    }

    @Test fun missingKeysUseCurrentDefaultsWithoutWritingAnyPreferences() = runBlocking {
        val original = emptyPreferences()
        assertFalse(migration.shouldMigrate(original))
        assertEquals(original, migration.migrate(original))
        val settings = original.toSettings()
        assertEquals(DEFAULT_TITLE_PROMPT, settings.titlePrompt)
        assertEquals(DEFAULT_TRANSLATION_PROMPT, settings.translatePrompt)
        assertEquals(DEFAULT_OCR_PROMPT, settings.ocrPrompt)
        assertEquals(DEFAULT_SUGGESTION_PROMPT, settings.suggestionPrompt)
        assertEquals(DEFAULT_COMPRESS_PROMPT, settings.compressPrompt)
    }
}
