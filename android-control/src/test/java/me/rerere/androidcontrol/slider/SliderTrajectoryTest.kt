package me.rerere.androidcontrol.slider

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SliderTrajectoryTest {
    private val bounds = intArrayOf(0, 0, 720, 1280)

    private fun buildStroke(x1: Int = 60, y1: Int = 900, x2: Int = 560, y2: Int = 900, seed: Int = 7): SliderTrajectory.Stroke =
        SliderTrajectory.build(x1, y1, x2, y2, bounds, kotlin.random.Random(seed))

    @Test fun satisfiesInjectStrokeContract() {
        repeat(50) { seed ->
            val s = buildStroke(seed = seed)
            assertTrue("points 2..1024", s.size in 2..1024)
            assertEquals("arrays equal length", s.size, s.xs.size)
            assertEquals("arrays equal length", s.size, s.ys.size)
            assertEquals("times[0]==0", 0L, s.times[0])
            assertTrue("total 1..10000ms, was ${s.times.last()}", s.times.last() in 1..10_000)
            for (i in 1 until s.size) {
                assertTrue("non-decreasing at $i", s.times[i] >= s.times[i - 1])
            }
            for (i in 0 until s.size) {
                assertTrue("x in bounds at $i", s.xs[i] in 0 until 720)
                assertTrue("y in bounds at $i", s.ys[i] in 0 until 1280)
            }
        }
    }

    @Test fun endpointsAreExact() {
        repeat(20) { seed ->
            val s = buildStroke(seed = seed)
            assertEquals("starts on handle", 60, s.xs[0])
            assertEquals("ends on target", 560, s.xs[s.size - 1])
            assertEquals("y target kept", 900, s.ys[s.ys.size - 1])
        }
    }

    @Test fun noTeleportSteps() {
        repeat(50) { seed ->
            val s = buildStroke(seed = seed)
            for (i in 1 until s.size) {
                assertTrue("single step ${abs(s.xs[i] - s.xs[i - 1])}px at $i",
                    abs(s.xs[i] - s.xs[i - 1]) <= 40)
            }
        }
    }

    @Test fun speedIsNotUniform() {
        // The same CV>0.25 gate the fixtures apply to a real drag stream.
        repeat(30) { seed ->
            val s = buildStroke(seed = seed)
            val speeds = ArrayList<Float>()
            for (i in 1 until s.size) {
                val dx = s.xs[i] - s.xs[i - 1]
                val dt = (s.times[i] - s.times[i - 1]).coerceAtLeast(1)
                if (dx != 0) speeds.add(abs(dx).toFloat() / dt * 1000f)
            }
            assertTrue("has movement samples, got ${speeds.size}", speeds.size >= 8)
            val mean = speeds.average()
            assertTrue("mean speed $mean positive", mean > 0)
            val sd = sqrt(speeds.map { (it - mean) * (it - mean) }.average())
            assertTrue("CV ${sd / mean} must exceed 0.25", sd / mean > 0.25)
        }
    }

    @Test fun yTremorPresent() {
        // At least 8 samples with |dy|>=1, like the fixture judgement.
        repeat(30) { seed ->
            val s = buildStroke(seed = seed)
            var tremor = 0
            for (i in 1 until s.size) {
                if (abs(s.ys[i] - s.ys[i - 1]) >= 1) tremor++
            }
            assertTrue("tremor samples $tremor >= 8", tremor >= 8)
        }
    }

    @Test fun endDwellAtLeast300ms() {
        // Same semantics as the fixture judgement: every sample within the
        // final 300ms stays within 3px of the end position, and the last
        // movement (dx != 0) happens at least 300ms before the end.
        repeat(30) { seed ->
            val s = buildStroke(seed = seed)
            val endX = s.xs[s.size - 1]
            val total = s.times[s.times.size - 1]
            var i = s.size - 1
            while (i >= 0 && s.times[i] >= total - 300) {
                assertTrue("dwell drift ${abs(s.xs[i] - endX)}px", abs(s.xs[i] - endX) <= 3)
                i--
            }
            // Last x movement must be outside the dwell window: any sample
            // inside the window that still moves would already fail above.
            // The window itself must exist (some sample precedes it).
            assertTrue("dwell window exists", i >= 0)
            // Stationary tail: at least 300ms between the last moving sample
            // and the final UP, allowing the window's leading gap.
            val lastMoveIdx = (0 until s.size).lastOrNull { k ->
                k > 0 && s.xs[k] != s.xs[k - 1]
            } ?: -1
            if (lastMoveIdx >= 0) {
                assertTrue("stationary tail ${total - s.times[lastMoveIdx]}ms >= 300",
                    total - s.times[lastMoveIdx] >= 300)
            }
        }
    }

    @Test fun totalDurationInsideChallengeWindow() {
        repeat(50) { seed ->
            val s = buildStroke(seed = seed)
            assertTrue("duration ${s.times.last()} in 500..5000", s.times.last() in 500..5000)
        }
    }

    @Test fun clampsPointsInsideDisplayBounds() {
        // Target beyond the right edge: every point must remain in bounds.
        val s = SliderTrajectory.build(10, 10, 2000, 10, bounds, kotlin.random.Random(3))
        for (i in 0 until s.size) {
            assertTrue(s.xs[i] in 0 until 720)
            assertTrue(s.ys[i] in 0 until 1280)
        }
    }

    @Test fun leftwardOvershootFollowsTheDirectionOfTravel() {
        val s = SliderTrajectory.build(560, 900, 60, 900, bounds,
            kotlin.random.Random(7), overshoot = true)
        assertTrue("leftward drag passes its target before returning", s.xs.min() < 60)
        assertEquals(60, s.xs.last())
    }
}
