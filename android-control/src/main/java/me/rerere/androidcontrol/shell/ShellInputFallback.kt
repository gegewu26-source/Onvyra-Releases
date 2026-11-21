package me.rerere.androidcontrol.shell

import kotlin.math.abs

/** A CLI replay is permitted only before native DOWN, and only for a representable straight stroke. */
internal fun shellStrokeFallbackArguments(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): List<String>? {
    val n = times.size
    if (displayId <= 0 || n !in 2..1024 || xs.size != n || ys.size != n || times[0] != 0L ||
        times.last() !in 1L..10_000L || (1 until n).any { times[it] < times[it - 1] } ||
        xs.any { it < 0 } || ys.any { it < 0 }) return null
    val duration = times.last()
    val stationary = xs.all { it == xs[0] } && ys.all { it == ys[0] }
    // input swipe is linear in time; do not replace curves or nonuniform movement with another gesture.
    if (!stationary && (1 until n - 1).any { i ->
            val fraction = times[i].toDouble() / duration
            abs(xs[i] - (xs[0] + (xs.last().toDouble() - xs[0]) * fraction)) > 2.0 ||
                abs(ys[i] - (ys[0] + (ys.last().toDouble() - ys[0]) * fraction)) > 2.0
        }) return null
    val prefix = listOf("touchscreen", "-d", displayId.toString())
    return if (stationary && duration <= 150L) prefix + listOf("tap", xs[0].toString(), ys[0].toString())
    else prefix + listOf("swipe", xs[0].toString(), ys[0].toString(), xs.last().toString(), ys.last().toString(), duration.toString())
}
