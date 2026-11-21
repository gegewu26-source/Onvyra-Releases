package com.constx.onvyra.ui.context

import androidx.compose.runtime.compositionLocalOf
import com.constx.onvyra.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

