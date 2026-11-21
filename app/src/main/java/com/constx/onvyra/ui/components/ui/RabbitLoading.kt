package com.constx.onvyra.ui.components.ui

import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.constx.onvyra.ui.context.LocalSettings

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier, playful: Boolean = false) {
    val useAppIconStyleLoadingIndicator = LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator

    if (useAppIconStyleLoadingIndicator) {
        MascotIcon(
            modifier = if (playful) modifier.size(width = 104.dp, height = 44.dp) else modifier,
            animated = true,
            playful = playful,
        )
    } else {
        ContainedLoadingIndicator(modifier = if (playful) modifier.size(28.dp) else modifier)
    }
}
