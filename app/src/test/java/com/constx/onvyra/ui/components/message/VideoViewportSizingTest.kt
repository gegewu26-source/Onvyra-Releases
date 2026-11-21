package com.constx.onvyra.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoViewportSizingTest {
    @Test fun cinemaAndPortraitRatiosComeFromTheActualPicture() {
        assertEquals(1080f / 412f, videoDisplayAspectRatio(1080, 412)!!, .0001f)
        assertEquals(9f / 16f, videoDisplayAspectRatio(1080, 1920)!!, .0001f)
        assertEquals(16f / 9f, videoDisplayAspectRatio(1920, 1080)!!, .0001f)
    }

    @Test fun nonSquarePixelsChangeDisplayWidthWithoutCroppingTheEncodedPicture() {
        assertEquals(4f / 3f, videoDisplayAspectRatio(720, 576, 16f / 15f)!!, .0001f)
        assertEquals(16f / 9f, videoDisplayAspectRatio(720, 576, 64f / 45f)!!, .0001f)
    }

    @Test fun unappliedQuarterTurnsInvertThePixelCorrectedRatio() {
        assertEquals(9f / 16f, videoDisplayAspectRatio(1920, 1080, 1f, 90)!!, .0001f)
        assertEquals(9f / 16f, videoDisplayAspectRatio(1920, 1080, 1f, -90)!!, .0001f)
        assertEquals(16f / 9f, videoDisplayAspectRatio(1920, 1080, 1f, 180)!!, .0001f)
        assertEquals(3f / 4f, videoDisplayAspectRatio(720, 576, 16f / 15f, 450)!!, .0001f)
    }

    @Test fun unknownOrInvalidDimensionsDoNotReplaceKnownGeometry() {
        assertNull(videoDisplayAspectRatio(0, 1080))
        assertNull(videoDisplayAspectRatio(1920, 0))
        assertNull(videoDisplayAspectRatio(-1, -1))
        assertNull(videoDisplayAspectRatio(Int.MAX_VALUE, 1, Float.MAX_VALUE))
    }

    @Test fun invalidPixelAspectFallsBackToSquarePixels() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach { pixelRatio ->
            assertEquals(16f / 9f, videoDisplayAspectRatio(1920, 1080, pixelRatio)!!, .0001f)
        }
    }
}
