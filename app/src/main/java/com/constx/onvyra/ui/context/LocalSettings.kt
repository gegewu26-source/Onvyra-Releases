package com.constx.onvyra.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.constx.onvyra.data.datastore.Settings

val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
