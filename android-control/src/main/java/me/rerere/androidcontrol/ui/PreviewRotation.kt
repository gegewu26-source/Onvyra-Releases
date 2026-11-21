package me.rerere.androidcontrol.ui

/** Logical display axes, after Android applies an app's requested rotation. */
internal data class PreviewDisplayGeometry(val width: Int, val height: Int, val rotation: Int) {
    init { require(width > 0 && height > 0 && rotation in 0..3) }
    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height
}

/**
 * A ROTATES_WITH_CONTENT producer keeps its original physical buffer axes. Undo SurfaceFlinger's
 * projection when presenting it upright. Accessibility screenshots and injected coordinates are
 * already logical: applying this transform to either would rotate them twice.
 */
internal fun nativeBufferPointInLogicalSpace(x: Float, y: Float, rotation: Int): Pair<Float, Float> =
    when (rotation) {
        0 -> x to y
        1 -> y to (1f - x)
        2 -> (1f - x) to (1f - y)
        3 -> (1f - y) to x
        else -> error("invalid_display_rotation")
    }

/** Three destination points for TextureView's view-space source triangle, with FIT_CENTER. */
internal fun nativePreviewDestinationPoints(
    viewWidth: Int, viewHeight: Int, display: PreviewDisplayGeometry,
): FloatArray {
    require(viewWidth > 0 && viewHeight > 0)
    val scale = minOf(viewWidth.toDouble() / display.width, viewHeight.toDouble() / display.height)
    val width = (display.width * scale).toFloat()
    val height = (display.height * scale).toFloat()
    val left = (viewWidth - width) / 2f
    val top = (viewHeight - height) / 2f
    return listOf(0f to 0f, 1f to 0f, 0f to 1f).flatMap { (x, y) ->
        val (logicalX, logicalY) = nativeBufferPointInLogicalSpace(x, y, display.rotation)
        listOf(left + logicalX * width, top + logicalY * height)
    }.toFloatArray()
}
