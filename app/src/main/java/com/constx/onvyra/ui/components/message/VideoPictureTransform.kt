package com.constx.onvyra.ui.components.message

import kotlin.math.max

/** The picture moves inside a fixed viewport; playback controls do not use this transform. */
internal data class VideoPictureTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/** Content dimensions are the untransformed, aspect-correct FIT picture dimensions. */
internal fun videoCoverScale(
    contentWidth: Float,
    contentHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): Float {
    if (!validPictureDimensions(contentWidth, contentHeight, viewportWidth, viewportHeight)) return 1f
    return max(viewportWidth.toDouble() / contentWidth, viewportHeight.toDouble() / contentHeight)
        .coerceAtLeast(1.0).finitePictureFloat()
}

/** Re-clamp after layout/source changes without changing the fixed viewport or distorting pixels. */
internal fun clampVideoPicture(
    current: VideoPictureTransform,
    contentWidth: Float,
    contentHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    maxScale: Float = Float.MAX_VALUE,
): VideoPictureTransform {
    if (!validPictureDimensions(contentWidth, contentHeight, viewportWidth, viewportHeight)) return VideoPictureTransform()
    val maximum = maxScale.takeIf { it.isFinite() && it >= 1f } ?: 1f
    val scale = (current.scale.takeIf { it.isFinite() && it > 0f } ?: 1f).coerceIn(1f, maximum)
    val horizontalLimit = max(0.0, (contentWidth.toDouble() * scale - viewportWidth) / 2.0)
    val verticalLimit = max(0.0, (contentHeight.toDouble() * scale - viewportHeight) / 2.0)
    return VideoPictureTransform(
        scale = scale,
        offsetX = (current.offsetX.takeIf(Float::isFinite)?.toDouble() ?: 0.0)
            .coerceIn(-horizontalLimit, horizontalLimit).finitePictureFloat(),
        offsetY = (current.offsetY.takeIf(Float::isFinite)?.toDouble() ?: 0.0)
            .coerceIn(-verticalLimit, verticalLimit).finitePictureFloat(),
    )
}

/**
 * Pinch around a focus measured from the viewport's top-left corner, then pan.
 * The actual bounded scale ratio preserves the focus even when a gesture reaches a limit.
 */
internal fun transformVideoPicture(
    current: VideoPictureTransform,
    contentWidth: Float,
    contentHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    zoomFactor: Float,
    panX: Float,
    panY: Float,
    focusX: Float,
    focusY: Float,
    maxScale: Float,
): VideoPictureTransform {
    if (!validPictureDimensions(contentWidth, contentHeight, viewportWidth, viewportHeight)) return VideoPictureTransform()
    val bounded = clampVideoPicture(current, contentWidth, contentHeight, viewportWidth, viewportHeight, maxScale)
    val maximum = maxScale.takeIf { it.isFinite() && it >= 1f } ?: 1f
    val factor = zoomFactor.takeIf { it.isFinite() && it > 0f } ?: 1f
    val scale = (bounded.scale.toDouble() * factor).coerceIn(1.0, maximum.toDouble()).toFloat()
    val appliedZoom = scale.toDouble() / bounded.scale
    val centeredFocusX = (focusX.takeIf(Float::isFinite)?.toDouble() ?: viewportWidth.toDouble() / 2.0) - viewportWidth.toDouble() / 2.0
    val centeredFocusY = (focusY.takeIf(Float::isFinite)?.toDouble() ?: viewportHeight.toDouble() / 2.0) - viewportHeight.toDouble() / 2.0
    val translated = VideoPictureTransform(
        scale = scale,
        offsetX = (bounded.offsetX * appliedZoom + centeredFocusX * (1.0 - appliedZoom) +
            (panX.takeIf(Float::isFinite) ?: 0f)).finitePictureFloat(),
        offsetY = (bounded.offsetY * appliedZoom + centeredFocusY * (1.0 - appliedZoom) +
            (panY.takeIf(Float::isFinite) ?: 0f)).finitePictureFloat(),
    )
    return clampVideoPicture(translated, contentWidth, contentHeight, viewportWidth, viewportHeight, maximum)
}

private fun validPictureDimensions(vararg values: Float): Boolean = values.all { it.isFinite() && it > 0f }

/** Double intermediates avoid overflow while keeping every value passed to a View finite. */
private fun Double.finitePictureFloat(): Float = coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
