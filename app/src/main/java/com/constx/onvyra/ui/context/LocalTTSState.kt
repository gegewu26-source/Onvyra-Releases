package com.constx.onvyra.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.constx.onvyra.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
