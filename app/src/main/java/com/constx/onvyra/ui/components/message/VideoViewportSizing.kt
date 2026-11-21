package com.constx.onvyra.ui.components.message

internal const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

/** Ratio of the displayed video, not necessarily the ratio of its encoded pixel counts. */
internal fun videoDisplayAspectRatio(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float = 1f,
    unappliedRotationDegrees: Int = 0,
): Float? {
    if (width <= 0 || height <= 0) return null
    // Missing/invalid pixel aspect metadata should not invalidate otherwise known dimensions.
    val pixelRatio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    var ratio = width.toDouble() * pixelRatio / height.toDouble()
    val rotation = ((unappliedRotationDegrees % 360) + 360) % 360
    if (rotation == 90 || rotation == 270) ratio = 1.0 / ratio
    return ratio.toFloat().takeIf { it.isFinite() && it > 0f }
}
