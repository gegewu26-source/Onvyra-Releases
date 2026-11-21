package com.constx.onvyra.service

/** Screen coordinates are relative to the usable area, excluding system bars and display cutouts. */
internal data class FloatingBallBounds(
    val width: Int,
    val height: Int,
    val ballSize: Int,
) {
    val maxX: Int get() = (width - ballSize).coerceAtLeast(0)
    val maxY: Int get() = (height - ballSize).coerceAtLeast(0)

    fun clampX(x: Int): Int = x.coerceIn(0, maxX)
    fun clampY(y: Int): Int = y.coerceIn(0, maxY)
    fun nearestEdgeX(x: Int): Int = if (clampX(x) <= maxX / 2) 0 else maxX

    fun tuckedX(onRight: Boolean, visibleSize: Int): Int {
        val visible = visibleSize.coerceIn(1, minOf(ballSize, width).coerceAtLeast(1))
        return if (onRight) width - visible else visible - ballSize
    }

    // Clip the virtual circle to the display instead of asking WindowManager for an offscreen window.
    fun visibleX(x: Int): Int = x.coerceIn(0, (width - 1).coerceAtLeast(0))
    fun visibleWidth(x: Int): Int =
        (minOf(width, x + ballSize) - visibleX(x)).coerceIn(1, ballSize)
}
