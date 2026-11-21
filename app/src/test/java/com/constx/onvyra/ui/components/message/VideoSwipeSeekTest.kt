package com.constx.onvyra.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSwipeSeekTest {
    @Test fun rightSwipeMovesForwardByTheClipFraction() {
        assertEquals(3_200L, videoSwipeSeekTarget(2_000L, 6_000L, .2f))
    }

    @Test fun leftSwipeMovesBackwardByTheClipFraction() {
        assertEquals(800L, videoSwipeSeekTarget(2_000L, 6_000L, -.2f))
    }

    @Test fun longVideosUseATwoMinuteSpanPerViewport() {
        assertEquals(630_000L, videoSwipeSeekTarget(600_000L, 3_600_000L, .25f))
    }

    @Test fun theTwoMinuteBoundaryUsesTheSameScale() {
        assertEquals(90_000L, videoSwipeSeekTarget(60_000L, 120_000L, .25f))
    }

    @Test fun seekingPastEitherEdgeStopsAtTheEdge() {
        assertEquals(6_000L, videoSwipeSeekTarget(5_000L, 6_000L, .5f))
        assertEquals(0L, videoSwipeSeekTarget(1_000L, 6_000L, -.5f))
    }

    @Test fun noMovementPreservesTheClampedStart() {
        assertEquals(2_345L, videoSwipeSeekTarget(2_345L, 6_000L, 0f))
        assertEquals(0L, videoSwipeSeekTarget(-100L, 6_000L, 0f))
        assertEquals(6_000L, videoSwipeSeekTarget(9_000L, 6_000L, 0f))
    }

    @Test fun unknownOrInvalidDurationCannotBeSeeked() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { duration ->
            assertEquals(0L, videoSwipeSeekTarget(2_000L, duration, .5f))
        }
    }

    @Test fun invalidFractionsDoNotMoveThePlayhead() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { fraction ->
            assertEquals(2_000L, videoSwipeSeekTarget(2_000L, 6_000L, fraction))
        }
    }

    @Test fun extremeFiniteDragsClampWithoutOverflow() {
        assertEquals(Long.MAX_VALUE, videoSwipeSeekTarget(100L, Long.MAX_VALUE, Float.MAX_VALUE))
        assertEquals(0L, videoSwipeSeekTarget(Long.MAX_VALUE, Long.MAX_VALUE, -Float.MAX_VALUE))
    }

    @Test fun veryLargeStartPositionsRetainMillisecondPrecision() {
        val start = Long.MAX_VALUE - 200_000L
        assertEquals(start + 60_000L, videoSwipeSeekTarget(start, Long.MAX_VALUE, .5f))
        assertEquals(start - 60_000L, videoSwipeSeekTarget(start, Long.MAX_VALUE, -.5f))
    }

    @Test fun smallMovementsRoundToTheNearestMillisecond() {
        assertEquals(101L, videoSwipeSeekTarget(100L, 1_000L, .0006f))
        assertEquals(99L, videoSwipeSeekTarget(100L, 1_000L, -.0006f))
    }
}
