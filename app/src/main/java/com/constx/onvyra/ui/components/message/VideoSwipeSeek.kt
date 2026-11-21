package com.constx.onvyra.ui.components.message

import kotlin.math.roundToLong

/** A full viewport swipe covers the clip, capped at two minutes for long videos. */
internal fun videoSwipeSeekTarget(
    startMs: Long,
    durationMs: Long,
    horizontalFraction: Float,
): Long {
    if (durationMs <= 0L) return 0L
    val start = startMs.coerceIn(0L, durationMs)
    if (!horizontalFraction.isFinite() || horizontalFraction == 0f) return start
    val delta = horizontalFraction.toDouble() * durationMs.coerceAtMost(120_000L)
    // Clamp the delta before adding it, preserving large Long positions without overflow.
    if (delta >= (durationMs - start).toDouble()) return durationMs
    if (delta <= -start.toDouble()) return 0L
    val shift = delta.roundToLong()
    if (shift >= durationMs - start) return durationMs
    if (shift <= -start) return 0L
    return start + shift
}
