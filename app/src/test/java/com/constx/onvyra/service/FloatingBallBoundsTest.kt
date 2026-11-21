package com.constx.onvyra.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBallBoundsTest {
    @Test
    fun `drag cannot leave the usable display area`() {
        val bounds = FloatingBallBounds(width = 1080, height = 2200, ballSize = 156)
        assertEquals(0, bounds.clampX(-100))
        assertEquals(924, bounds.clampX(5000))
        assertEquals(0, bounds.clampY(-100))
        assertEquals(2044, bounds.clampY(5000))
    }

    @Test
    fun `idle docking chooses the nearest horizontal edge`() {
        val bounds = FloatingBallBounds(width = 1080, height = 2200, ballSize = 156)
        assertEquals(0, bounds.nearestEdgeX(462))
        assertEquals(924, bounds.nearestEdgeX(463))
        assertEquals(0, bounds.nearestEdgeX(-200))
        assertEquals(924, bounds.nearestEdgeX(4000))
    }

    @Test
    fun `tucked circle exposes only the requested strip on either edge`() {
        val bounds = FloatingBallBounds(width = 1080, height = 2200, ballSize = 156)
        val left = bounds.tuckedX(onRight = false, visibleSize = 36)
        val right = bounds.tuckedX(onRight = true, visibleSize = 36)
        assertEquals(-120, left)
        assertEquals(0, bounds.visibleX(left))
        assertEquals(36, bounds.visibleWidth(left))
        assertEquals(1044, right)
        assertEquals(1044, bounds.visibleX(right))
        assertEquals(36, bounds.visibleWidth(right))
        assertEquals(0, bounds.clampX(left))
        assertEquals(924, bounds.clampX(right))
    }

    @Test
    fun `sliding circle keeps its diameter until crossing the edge`() {
        val bounds = FloatingBallBounds(width = 1080, height = 2200, ballSize = 156)
        assertEquals(156, bounds.visibleWidth(300))
        assertEquals(156, bounds.visibleWidth(0))
        assertEquals(156, bounds.visibleWidth(924))
        assertEquals(100, bounds.visibleWidth(-56))
        assertEquals(100, bounds.visibleWidth(980))
    }

    @Test
    fun `small windows and rotation leave a valid position`() {
        val small = FloatingBallBounds(width = 40, height = 40, ballSize = 52)
        assertEquals(0, small.clampX(100))
        assertEquals(0, small.clampY(-100))
        assertEquals(0, small.nearestEdgeX(100))
        assertEquals(40, small.visibleWidth(0))
        assertEquals(12, small.visibleWidth(small.tuckedX(false, 12)))
        assertEquals(12, small.visibleWidth(small.tuckedX(true, 12)))
        val rotated = FloatingBallBounds(width = 2200, height = 1080, ballSize = 156)
        assertEquals(924, rotated.clampY(2044))
        assertEquals(2044, rotated.nearestEdgeX(1800))
    }
}
