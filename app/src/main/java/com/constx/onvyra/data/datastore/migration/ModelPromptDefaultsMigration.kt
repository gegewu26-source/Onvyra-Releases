package com.constx.onvyra.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
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
import com.constx.onvyra.data.datastore.SettingsStore

/** Only exact shipped defaults are upgraded; custom edits and empty values stay untouched. */
class ModelPromptDefaultsMigration : DataMigration<Preferences> {
    private data class Upgrade(
        val key: Preferences.Key<String>,
        val previous: Set<String>,
        val current: String,
    )

    private val upgrades = listOf(
        Upgrade(SettingsStore.TITLE_PROMPT, setOf(PREVIOUS_DEFAULT_TITLE_PROMPT), DEFAULT_TITLE_PROMPT),
        Upgrade(SettingsStore.TRANSLATION_PROMPT, setOf(PREVIOUS_DEFAULT_TRANSLATION_PROMPT), DEFAULT_TRANSLATION_PROMPT),
        Upgrade(SettingsStore.OCR_PROMPT, setOf(PREVIOUS_DEFAULT_OCR_PROMPT), DEFAULT_OCR_PROMPT),
        Upgrade(SettingsStore.SUGGESTION_PROMPT, setOf(PREVIOUS_DEFAULT_SUGGESTION_PROMPT), DEFAULT_SUGGESTION_PROMPT),
        Upgrade(SettingsStore.COMPRESS_PROMPT, setOf(LEGACY_DEFAULT_COMPRESS_PROMPT, PREVIOUS_DEFAULT_COMPRESS_PROMPT), DEFAULT_COMPRESS_PROMPT),
    )

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        upgrades.any { upgrade -> currentData[upgrade.key]?.let { it in upgrade.previous } == true }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val updated = currentData.toMutablePreferences()
        upgrades.forEach { upgrade ->
            if (currentData[upgrade.key]?.let { it in upgrade.previous } == true) {
                updated[upgrade.key] = upgrade.current
            }
        }
        return updated.toPreferences()
    }

    override suspend fun cleanUp() {}
}
