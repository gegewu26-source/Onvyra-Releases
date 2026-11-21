package me.rerere.androidcontrol.slider

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Human-like slider drag trajectory, fusing slidex's four-phase minimum-jerk
 * velocity model with SliderCaptchaSolver's cubic-bezier arc, Gaussian finger
 * tremor and end dwell. Output strictly satisfies ShellService.injectStroke:
 * 2..1024 points, times[0]==0, non-decreasing, total <= 10000 ms and every
 * point inside the given logical display bounds.
 */
internal object SliderTrajectory {

    data class Stroke(val xs: IntArray, val ys: IntArray, val times: LongArray) {
        val size: Int get() = times.size
        override fun equals(other: Any?): Boolean = other is Stroke &&
            xs.contentEquals(other.xs) && ys.contentEquals(other.ys) && times.contentEquals(other.times)
        override fun hashCode(): Int = xs.contentHashCode() * 31 + times.contentHashCode()
    }

    /** Minimum-jerk ease: slow start, mid acceleration, soft landing. */
    private fun minimumJerk(t: Double): Double = 10 * t * t * t - 15 * t * t * t * t + 6 * t * t * t * t * t

    /** Cubic bezier for the Y arc; control points bend the path off-axis. */
    private fun bezierY(t: Double, y0: Double, c1: Double, c2: Double, y1: Double): Double {
        val u = 1 - t
        return u * u * u * y0 + 3 * u * u * t * c1 + 3 * u * t * t * c2 + t * t * t * y1
    }

    /**
     * Build a stroke from (x1,y1) to (x2,y2). [bounds] is the logical display
     * rect (left, top, right, bottom) every sampled point must stay inside.
     */
    fun build(
        x1: Int, y1: Int, x2: Int, y2: Int,
        bounds: IntArray,
        random: Random = Random.Default,
        overshoot: Boolean = random.nextDouble() < 0.4,
    ): Stroke {
        require(bounds.size == 4) { "bounds must be ltrb" }
        require(x2 != x1 || y2 != y1) { "degenerate stroke" }
        val left = bounds[0]; val top = bounds[1]; val right = bounds[2]; val bottom = bounds[3]
        val clampedX1 = x1.coerceIn(left, right - 1)
        val clampedY1 = y1.coerceIn(top, bottom - 1)
        var targetX = x2.coerceIn(left, right - 1)
        val targetY = y2.coerceIn(top, bottom - 1)

        // --- Phase parameters (SliderCaptchaSolver behavior ranges) ---
        val preHoldMs = random.nextInt(80, 161)          // grip hesitation 80~160ms
        val overshootPx = if (overshoot && abs(targetX - clampedX1) > 60) {
            random.nextInt(4, 9) * if (targetX > clampedX1) 1 else -1
        } else 0
        val overHoldMs = if (overshootPx != 0) random.nextInt(50, 91) else 0
        val endHoldMs = random.nextInt(350, 651)         // confirm dwell 350~650ms
        val steps = random.nextInt(35, 61)               // main travel samples
        val arcAmp = (random.nextDouble() * 6 - 3).coerceIn(-3.0, 3.0)  // bezier arc, +/-3px
        val tremorSigma = 1.2                            // Gaussian micro-tremor sigma
        val tremorAmp = 2                                // hard cap +/-2px

        val xEnd = if (overshootPx != 0) targetX + overshootPx else targetX

        val pts = ArrayList<IntArray>(128)
        val ts = ArrayList<Long>(128)
        var t = 0L

        fun put(x: Int, y: Int) {
            val cx = x.coerceIn(left, right - 1)
            val cy = y.coerceIn(top, bottom - 1)
            pts.add(intArrayOf(cx, cy))
            ts.add(t)
        }

        // Phase 1: press + pre-drag hold with 2~3 micro-jitter points.
        put(clampedX1, clampedY1)
        repeat(random.nextInt(2, 4)) {
            t += random.nextLong(20, 55)
            put(clampedX1 + random.nextInt(-1, 2), clampedY1 + random.nextInt(-1, 2))
        }
        t = t.coerceAtLeast(preHoldMs.toLong())

        // Phase 2: main travel, minimum-jerk eased X positions with an
        // independent time base. Sampling dx and dt from the same easing
        // would make every per-step speed equal the mean (self-cancelling
        // non-uniformity), so timestamps use a separately perturbed profile:
        // mid-travel speed-up plus random human slowdowns.
        val cy1 = clampedY1 + arcAmp
        val cy2 = targetY - arcAmp * 0.6
        val distance = abs(xEnd - clampedX1)
        val travelRange = when {
            distance < 120 -> 700.0 to 1100.0
            distance < 320 -> 900.0 to 1600.0
            else -> 1200.0 to 2200.0
        }
        val travelMs = travelRange.first + (travelRange.second - travelRange.first) * random.nextDouble()
        for (i in 1..steps) {
            val tau = i.toDouble() / steps
            val eased = minimumJerk(tau)
            val bx = clampedX1 + (xEnd - clampedX1) * eased
            // Arc is strongest mid-travel and vanishes at both ends.
            val by = bezierY(tau, clampedY1.toDouble(), cy1, cy2, targetY.toDouble())
            val envelope = sin(PI * tau) // tremor envelope 0 -> 1 -> 0
            val ty = by + clampGauss(random, tremorSigma) * envelope
            val jitterX = if (i < steps) clampGauss(random, tremorSigma) * envelope * 0.5 else 0.0
            // Independent dt: base uniform slice, a mid-glide speed-up
            // (sin envelope compresses timestamps mid-travel) and occasional
            // 2.5~4x human slowdowns (SliderCaptchaSolver behavior).
            var slice = travelMs / steps
            slice *= 1.0 - 0.35 * sin(PI * tau)          // mid-travel faster
            if (random.nextInt(100) < 4) slice *= 2.5 + random.nextDouble() * 1.5
            slice += random.nextDouble() * 6.0            // per-step jitter
            t = (t + slice.coerceAtLeast(3.0).toLong())
            put((bx + jitterX).toInt(), ty.toInt())
        }
        // Cap accumulated time so later phases still fit under 10s.
        if (t > 6000) t = 6000

        // Phase 3: optional overshoot dwell + snapback.
        if (overshootPx != 0) {
            t += overHoldMs
            put(xEnd, targetY + random.nextInt(-1, 2))
            // Snap back in 3~4 short easing steps.
            val snapSteps = random.nextInt(3, 5)
            val startX = xEnd
            for (i in 1..snapSteps) {
                val tau = i.toDouble() / snapSteps
                t += random.nextLong(25, 60)
                put((startX + (targetX - startX) * tau).toInt(), targetY)
            }
        }
        if (t > 9200) t = 9200

        // Phase 4: end hold — same spot, monotonically increasing timestamps.
        // Dwell uses its own endHoldMs budget (350~650ms); the 10s cap only
        // trims it when earlier phases already consumed most of the window.
        val holdSteps = 6
        val holdSlice = (endHoldMs / holdSteps).coerceAtLeast(20)
        val dwellStart = t
        for (i in 1..holdSteps) {
            if (t + holdSlice > 9_800L) break
            t += holdSlice
            put(targetX, targetY + if (random.nextInt(5) == 0) random.nextInt(-1, 2) else 0)
        }
        // Guarantee the dwell window covers >= 300ms of near-stationary tail
        // whenever the 10s cap still allows it.
        if (t - dwellStart < 320L && dwellStart + 320L <= 9_800L) {
            t = dwellStart + 320L
            put(targetX, targetY)
        }
        // Final point exactly on target, strictly increasing time, 1..10000ms.
        t = (t + random.nextLong(30, 80)).coerceAtMost(10_000L)
        put(targetX, targetY)
        if (ts.last() == 0L) t = 1L.also { put(targetX, targetY) }

        // Enforce injectStroke invariants: strictly increasing after index 0
        // is allowed to be equal (>=), but the final must be >= 1ms.
        val xs = IntArray(pts.size) { pts[it][0] }
        val ys = IntArray(pts.size) { pts[it][1] }
        var time = ts[0]
        val times = LongArray(ts.size)
        for (i in ts.indices) {
            if (i > 0 && ts[i] < times[i - 1]) time = times[i - 1]
            else time = ts[i]
            times[i] = time
        }
        if (times.last() < 1L) times[times.lastIndex] = 1L
        return Stroke(xs, ys, times)
    }

    /** Gaussian tremor clamped to +/- [ampLimit]. */
    private fun clampGauss(random: Random, sigma: Double, ampLimit: Int = 2): Double {
        val v = random.nextDouble() * 2 - 1  // uniform fallback keeps it simple and bounded
        val g = v * sigma
        return g.coerceIn(-ampLimit.toDouble(), ampLimit.toDouble())
    }
}
