package me.rerere.androidcontrol.slider

import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Mirrors the judgement in android-control-fixture SliderChallengeActivity.judge()
 * and runs SliderTrajectory.build() output through it. This is the acceptance
 * bar for "the slider actually passes the challenge", not just "a stroke exists".
 */
class SliderTrajectoryChallengeTest {

    private fun judge(xs: IntArray, ys: IntArray, times: LongArray): String? {
        val n = times.size
        if (n < 12) return "too_few_points"
        val totalMs = times.last()
        if (totalMs < 500 || totalMs > 5000) return "duration_${totalMs}ms"
        var maxStep = 0f
        val speeds = ArrayList<Float>()
        var tremorCount = 0
        for (i in 1 until n) {
            val dx = (xs[i] - xs[i - 1]).toFloat()
            val dy = abs(ys[i] - ys[i - 1]).toFloat()
            val dt = (times[i] - times[i - 1]).toFloat().coerceAtLeast(1f)
            maxStep = maxOf(maxStep, abs(dx))
            if (dy >= 1f) tremorCount++
            if (dx != 0f) speeds.add(abs(dx) / dt * 1000f)
        }
        if (maxStep > 40f) return "teleport_${maxStep.toInt()}px"
        if (tremorCount < 8) return "no_y_tremor_$tremorCount"
        if (speeds.size < 8) return "too_few_moves"
        val mean = speeds.average()
        if (mean <= 0) return "no_movement"
        val sd = sqrt(speeds.map { (it - mean) * (it - mean) }.average())
        if (sd / mean <= 0.25) return "uniform_speed_${sd / mean}"
        val endX = xs.last().toFloat()
        var i = n - 1
        while (i >= 0 && times[i] >= totalMs - 300) {
            if (abs(xs[i] - endX) > 3f) return "no_end_dwell"
            i--
        }
        return null
    }

    @Test fun trajectoryPassesTheFixtureJudgeAcrossManySeedsAndDistances() {
        val bounds = intArrayOf(0, 0, 720, 1600)
        val failures = ArrayList<String>()
        for (distance in listOf(120, 200, 280, 360, 480)) {
            for (seed in 1L..60L) {
                val random = Random(seed)
                val stroke = SliderTrajectory.build(46, 1200, 46 + distance, 1200, bounds, random)
                val verdict = judge(stroke.xs, stroke.ys, stroke.times)
                if (verdict != null) failures.add("d=$distance seed=$seed -> $verdict")
            }
        }
        if (failures.isNotEmpty()) {
            println("SLIDER_JUDGE_FAILURES=${failures.size}")
            failures.take(20).forEach { println("  $it") }
        }
        assertNull("trajectories must satisfy the challenge judge; failures: ${failures.take(10)}",
            failures.firstOrNull())
    }
}