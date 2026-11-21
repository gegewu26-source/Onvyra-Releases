package me.rerere.androidcontrol

import kotlin.math.roundToInt

/** Bound the upload, retaining the full protected capture only for local validation/OCR. */
internal fun controlUploadSize(width: Int, height: Int, longestSide: Int = 1280): Pair<Int, Int> {
    require(width > 0 && height > 0 && longestSide > 0)
    val scale = minOf(1.0, longestSide.toDouble() / maxOf(width, height))
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

/** Native sequences never start image capture; an explicitly entered visual session may observe its action. */
internal fun shouldObserveVisualAction(visualSession: Boolean, batch: Boolean, tool: String,
    nativeActionable: Int, requested: Boolean?): Boolean = !batch &&
    tool in setOf("tap", "swipe", "solve_slider", "drag", "press_key") && nativeActionable == 0 &&
    requested != false && (visualSession || requested == true)
