package com.constx.onvyra.ui.pages.setting

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.constx.onvyra.R
import java.util.Locale

@Composable
internal fun appLanguageName(tag: String): String = when {
    tag.isBlank() -> stringResource(R.string.setting_page_preferences_language_follow_system)
    else -> appLanguageOption(tag)?.nativeName
        ?: Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
}

@Composable
internal fun AppLanguageDialog(currentTag: String, onDismiss: () -> Unit) {
    val selected = appLanguageOption(currentTag)?.tag ?: currentTag
    fun select(tag: String) {
        onDismiss()
        // Empty app locales remove the override; Android continues following system changes.
        val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_preferences_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                LanguageChoice(
                    name = stringResource(R.string.setting_page_preferences_language_follow_system),
                    selected = currentTag.isBlank(),
                    onClick = { select("") },
                )
                AppLanguageOptions.forEach { option ->
                    LanguageChoice(option.nativeName, selected == option.tag) { select(option.tag) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun LanguageChoice(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}
