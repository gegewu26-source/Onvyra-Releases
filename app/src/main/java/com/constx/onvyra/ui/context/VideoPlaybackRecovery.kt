package com.constx.onvyra.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.constx.onvyra.data.media.VideoPreviewPayload

/** Only the currently resumed chat supplies an automatic model continuation. */
internal val LocalVideoPlaybackRecovery = staticCompositionLocalOf<((VideoPreviewPayload) -> Unit)?> { null }
