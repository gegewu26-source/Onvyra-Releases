package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Internet
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import com.constx.onvyra.R
import com.constx.onvyra.Screen
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.utils.plus

@Composable
fun SettingPreferencesPage() {
    val navController = LocalNavController.current
    var languageDialogVisible by remember { mutableStateOf(false) }
    // Read Configuration so a locale update refreshes the summary as well as strings.
    LocalConfiguration.current
    val appLanguageTag = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag().orEmpty()
    val selectedLanguage = appLanguageName(appLanguageTag)
    if (languageDialogVisible) {
        AppLanguageDialog(appLanguageTag, onDismiss = { languageDialogVisible = false })
    }
    Scaffold(
        topBar = { SettingsHomeTopBar(stringResource(R.string.setting_page_preferences)) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("appearance_experience") {
                SettingsHomeGroup(stringResource(R.string.setting_page_preferences_appearance_experience)) {
                    SettingsHomeRow(
                        icon = HugeIcons.Sun01,
                        title = stringResource(R.string.setting_page_preferences_theme),
                        onClick = { navController.navigate(Screen.SettingPreferencesTheme) },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.Notification01,
                        title = stringResource(R.string.setting_page_preferences_notification),
                        onClick = { navController.navigate(Screen.SettingPreferencesNotification) },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.Settings03,
                        title = stringResource(R.string.setting_page_preferences_general),
                        onClick = { navController.navigate(Screen.SettingPreferencesGeneral) },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.PaintBoard,
                        title = stringResource(R.string.setting_page_preferences_ui),
                        onClick = { navController.navigate(Screen.SettingPreferencesUI) },
                    )
                }
            }
            item("system_network") {
                SettingsHomeGroup(stringResource(R.string.setting_page_preferences_system_network)) {
                    SettingsHomeRow(
                        icon = HugeIcons.LanguageCircle,
                        title = stringResource(R.string.setting_page_preferences_language),
                        summary = selectedLanguage,
                        onClick = { languageDialogVisible = true },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.Internet,
                        title = stringResource(R.string.setting_page_preferences_network),
                        onClick = { navController.navigate(Screen.SettingPreferencesNetwork) },
                    )
                    SettingsHomeDivider()
                    SettingsHomeRow(
                        icon = HugeIcons.SmartPhone01,
                        title = stringResource(R.string.setting_page_preferences_floating_window),
                        onClick = { navController.navigate(Screen.SettingAdvanced) },
                    )
                }
            }
        }
    }
}
