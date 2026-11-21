package com.constx.onvyra.ui.components.message

import java.util.Locale

/** One supported rendition advertised by the current media, not a synthetic upscale option. */
internal data class VideoQualityOption(
    val id: String,
    val label: String,
    val height: Int? = null,
    val bitrate: Int? = null,
)

internal data class VideoTrackDescription(val id: String, val width: Int, val height: Int, val bitrate: Int)

internal fun videoResolutionHeight(width: Int, height: Int): Int? = when {
    width > 0 && height > 0 -> minOf(width, height)
    height > 0 -> height
    width > 0 -> width
    else -> null
}

internal fun videoQualityOptions(tracks: List<VideoTrackDescription>): List<VideoQualityOption> = tracks
    .groupBy { track -> videoResolutionHeight(track.width, track.height)?.let { "height:$it" }
        ?: track.bitrate.takeIf { it > 0 }?.let { "bitrate:$it" } ?: "original" }
    .values.map { sameSize -> sameSize.maxBy { it.bitrate } }
    .map { track ->
        val height = videoResolutionHeight(track.width, track.height)
        val bitrate = track.bitrate.takeIf { it > 0 }
        VideoQualityOption(track.id, when {
            height != null -> "${height}p"
            bitrate != null -> String.format(Locale.ROOT, "%.1f Mbps", bitrate / 1_000_000.0)
            else -> "原始画质"
        }, height, bitrate)
    }.sortedWith(compareByDescending<VideoQualityOption> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 })
