package com.constx.onvyra.data.model

import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting

/** Required endpoint defaults, matching the first choices in the media creation editor. */
internal fun MediaGenerationProviderSetting.mediaCreationRequiredDefaults(kind: MediaKind): MediaCreationParams =
    if (this is MediaGenerationProviderSetting.MiniMax && kind == MediaKind.VIDEO) {
        MediaCreationParams(resolution = "768P", aspectRatio = "16:9", durationSeconds = 5)
    } else {
        MediaCreationParams()
    }
