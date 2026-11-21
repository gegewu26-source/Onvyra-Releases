package me.rerere.androidcontrol.ui

import kotlin.math.floor
import kotlin.math.roundToInt

/** Fit both dimensions together: clamping only one side creates letterboxing or distorted content. */
internal fun fitPreviewSize(
    requestedWidth: Int,
    displayWidth: Int,
    displayHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    minWidth: Int = 1,
): Pair<Int, Int> {
    require(displayWidth > 0 && displayHeight > 0 && maxWidth > 0 && maxHeight > 0)
    val widthLimit = minOf(maxWidth,
        floor(maxHeight.toDouble() * displayWidth / displayHeight).coerceIn(1.0, Int.MAX_VALUE.toDouble()).toInt())
    val width = requestedWidth.coerceIn(minWidth.coerceIn(1, widthLimit), widthLimit)
    val height = (width.toDouble() * displayHeight / displayWidth).roundToInt().coerceIn(1, maxHeight)
    return width to height
}

/** Fractions cover the movable range, not the whole display or a left/right docking state. */
internal fun previewPositionFraction(position: Int, start: Int, travel: Int): Float =
    if (travel <= 0) 0f else ((position.toLong() - start).toDouble() / travel).toFloat().coerceIn(0f, 1f)

/** Native mini-window proportions: the whole source fits within half the usable screen height. */
internal fun compactPreviewSize(
    requestedWidth: Int,
    displayWidth: Int,
    displayHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    minWidth: Int = 1,
): Pair<Int, Int> = fitPreviewSize(requestedWidth, displayWidth, displayHeight, maxWidth,
    (maxHeight.toLong() / 2).coerceAtLeast(1).toInt(), minWidth)

/** Fit the whole source; callers may explicitly reserve an additional strip if needed. */
internal fun fitPreviewWindowSize(
    requestedWidth: Int,
    displayWidth: Int,
    displayHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    minWidth: Int = 1,
    bottomHandleHeight: Int = 0,
): Pair<Int, Int> {
    require(maxHeight > 0 && bottomHandleHeight >= 0)
    val handleHeight = bottomHandleHeight.coerceAtMost(maxHeight - 1)
    val content = fitPreviewSize(requestedWidth, displayWidth, displayHeight,
        maxWidth, maxHeight - handleHeight, minWidth)
    return content.first to content.second + handleHeight
}

internal data class PreviewResizeGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

/** Keep the top edge and horizontal center fixed; the bottom edge follows the finger. */
internal fun resizePreviewFromBottom(
    startX: Int,
    startY: Int,
    startWidth: Int,
    startHeight: Int,
    deltaY: Float,
    displayWidth: Int,
    displayHeight: Int,
    areaLeft: Int,
    areaTop: Int,
    areaRight: Int,
    areaBottom: Int,
    minWidth: Int = 1,
    bottomHandleHeight: Int = 0,
): PreviewResizeGeometry {
    require(startWidth > 0 && startHeight > 0 && deltaY.isFinite())
    require(areaRight > areaLeft && areaBottom > areaTop && bottomHandleHeight >= 0)
    val y = startY.coerceIn(areaTop, areaBottom - 1)
    val requested = ((startHeight.toDouble() - bottomHandleHeight + deltaY) * displayWidth / displayHeight)
        .coerceIn(1.0, Int.MAX_VALUE.toDouble()).roundToInt()
    val size = fitPreviewWindowSize(requested, displayWidth, displayHeight,
        areaRight - areaLeft, areaBottom - y, minWidth, bottomHandleHeight)
    val x = (startX.toDouble() + (startWidth.toDouble() - size.first) / 2.0).roundToInt()
        .coerceIn(areaLeft, areaRight - size.first)
    return PreviewResizeGeometry(x, y, size.first, size.second)
}

internal enum class PreviewDockSide { LEFT, RIGHT }

/** Permit a deliberate half-window edge drag, while keeping the other half reachable. */
internal fun previewDragX(x: Int, width: Int, areaLeft: Int, areaRight: Int): Int {
    require(width > 0 && areaRight > areaLeft)
    return x.coerceIn(areaLeft - width / 2, maxOf(areaLeft - width / 2, areaRight - 1 - width / 2))
}

/** A release must reach the half-outside threshold; ordinary near-edge drops stay windows. */
internal fun previewDockSide(x: Int, width: Int, areaLeft: Int, areaRight: Int): PreviewDockSide? = when {
    x <= areaLeft - width / 2 -> PreviewDockSide.LEFT
    // The rightmost touchable pixel is right - 1; a centered handle must reach it.
    x >= areaRight - 1 - width / 2 -> PreviewDockSide.RIGHT
    else -> null
}

/** Remember how much of a deliberately parked window is outside, independently of its size. */
internal fun previewOutsideFraction(x: Int, width: Int, areaLeft: Int, areaRight: Int): Float? {
    require(width > 0 && areaRight > areaLeft)
    return when {
        x < areaLeft -> ((x.toLong() - areaLeft).toDouble() / width).toFloat().coerceAtLeast(-.5f)
        x.toLong() + width > areaRight -> ((x.toLong() + width - areaRight).toDouble() / width)
            .toFloat().coerceAtMost(.5f)
        else -> null
    }
}

internal fun previewOutsideX(fraction: Float, width: Int, areaLeft: Int, areaRight: Int): Int {
    require(fraction.isFinite() && fraction != 0f && fraction in -0.5f..0.5f)
    val x = if (fraction < 0f) areaLeft + (fraction * width).roundToInt()
        else areaRight - width + (fraction * width).roundToInt()
    return previewDragX(x, width, areaLeft, areaRight)
}

internal fun previewIconX(side: PreviewDockSide, width: Int, areaLeft: Int, areaRight: Int,
                         halfHidden: Boolean): Int = when (side) {
    PreviewDockSide.LEFT -> areaLeft - if (halfHidden) width / 2 else 0
    PreviewDockSide.RIGHT -> areaRight - width + if (halfHidden) width / 2 else 0
}
