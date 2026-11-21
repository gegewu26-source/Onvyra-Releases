package me.rerere.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class ControlSliderCoordinatesTest {
    @Test fun uploadedImageRegionsScaleWithoutSubtractingWindowOrigin() {
        assertArrayEquals(intArrayOf(100, 200, 700, 1000),
            sliderImageRectangle(listOf(50, 100, 350, 500), 400, 800, 800, 1600))
        assertArrayEquals(intArrayOf(0, 0, 800, 1600),
            sliderImageRectangle(listOf(0, 0, 400, 800), 400, 800, 800, 1600))
    }

    @Test fun backwardsDragsAndNonIntegerScalingRetainDirection() {
        assertEquals(300, sliderScaledDistance(150, 400, 800))
        assertEquals(-300, sliderScaledDistance(-150, 400, 800))
        assertEquals(333, sliderScaledDistance(100, 300, 1000))
    }

    @Test fun malformedRegionsAndZeroDistanceAreRejectedBeforeAnyInput() {
        for (rect in listOf(listOf(-1, 0, 100, 100), listOf(10, 0, 10, 100),
            listOf(0, 0, 401, 800), listOf(0, 0, 400))) {
            assertThrows(IllegalArgumentException::class.java) { sliderImageRectangle(rect, 400, 800, 800, 1600) }
        }
        assertThrows(IllegalArgumentException::class.java) { sliderScaledDistance(0, 400, 800) }
    }
}
