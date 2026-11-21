package me.rerere.androidcontrol.shell

import org.junit.Assert.*
import org.junit.Test

class ShellInputFallbackTest {
    @Test fun tapAlwaysIncludesItsPositiveDisplayId() {
        assertEquals(listOf("touchscreen", "-d", "7", "tap", "12", "34"), plan(7, intArrayOf(12,12), intArrayOf(34,34), longArrayOf(0,50)))
    }
    @Test fun heldPressUsesAStationarySwipeWithTheOriginalDuration() {
        assertEquals(listOf("touchscreen", "-d", "9", "swipe", "12", "34", "12", "34", "5000"),
            plan(9,intArrayOf(12,12),intArrayOf(34,34),longArrayOf(0,5000)))
    }
    @Test fun straightSwipeCanRetainItsEndpointsAndDuration() {
        assertNotNull(plan(3,intArrayOf(0,50,100),intArrayOf(20,70,120),longArrayOf(0,500,1000)))
    }
    @Test fun curvedAndNonuniformStrokesAreNotSilentlyReplaced() {
        assertNull(plan(3,intArrayOf(0,50,100),intArrayOf(20,200,120),longArrayOf(0,500,1000)))
        assertNull(plan(3,intArrayOf(0,50,100),intArrayOf(20,70,120),longArrayOf(0,100,1000)))
    }
    @Test fun mainAndInvalidDisplaysNeverProduceACommand() {
        listOf(0,-1).forEach { assertNull(plan(it,intArrayOf(1,1),intArrayOf(2,2),longArrayOf(0,50))) }
    }
    @Test fun malformedAndExcessiveStrokesNeverProduceACommand() {
        assertNull(plan(3,intArrayOf(1),intArrayOf(2,2),longArrayOf(0,50)))
        assertNull(plan(3,intArrayOf(1,1),intArrayOf(2,2),longArrayOf(0,10001)))
        assertNull(plan(3,intArrayOf(1,1),intArrayOf(2,2),longArrayOf(0,-1)))
    }
    private fun plan(display: Int, xs: IntArray, ys: IntArray, times: LongArray) = shellStrokeFallbackArguments(display,xs,ys,times)
}
