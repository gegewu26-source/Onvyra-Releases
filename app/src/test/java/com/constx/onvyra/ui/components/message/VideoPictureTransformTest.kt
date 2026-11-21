package com.constx.onvyra.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPictureTransformTest {
    @Test fun aWideFilmCoversTheScreenWithoutDistortingItsRatio() {
        assertEquals(4f / 3f, videoCoverScale(640f, 240f, 640f, 320f), .0001f)
    }

    @Test fun aPortraitPictureCanCoverAWideViewport() {
        assertEquals(640f / 180f, videoCoverScale(180f, 320f, 640f, 320f), .0001f)
    }

    @Test fun anAlreadyCoveringPictureDoesNotNeedToBeShrunk() {
        assertEquals(1f, videoCoverScale(800f, 600f, 400f, 300f), 0f)
    }

    @Test fun matchingPictureAndViewportStartAtOne() {
        assertEquals(1f, videoCoverScale(640f, 360f, 640f, 360f), 0f)
    }

    @Test fun centeredPinchMagnifiesThePictureWithoutMovingItsCenter() {
        val result = gesture(zoom = 2f)
        assertEquals(VideoPictureTransform(2f, 0f, 0f), result)
    }

    @Test fun anOffCenterPinchKeepsTheTouchedPicturePointUnderTheFingers() {
        val result = gesture(zoom = 2f, focusX = 150f, focusY = 75f)
        assertEquals(150f, 100f + 50f * result.scale + result.offsetX, .0001f)
        assertEquals(75f, 100f - 25f * result.scale + result.offsetY, .0001f)
    }

    @Test fun panMovesAZoomedPictureWithinItsRealEdges() {
        val result = gesture(VideoPictureTransform(2f), panX = 45f, panY = -30f)
        assertEquals(VideoPictureTransform(2f, 45f, -30f), result)
    }

    @Test fun panningCannotExposeSpaceBeyondThePictureEdges() {
        val result = transformVideoPicture(VideoPictureTransform(3f), 200f, 100f, 200f, 200f,
            1f, 999f, -999f, 100f, 100f, 6f)
        assertEquals(VideoPictureTransform(3f, 200f, -50f), result)
    }

    @Test fun shrinkingToFitCentersDimensionsThatNoLongerOverflow() {
        val result = transformVideoPicture(VideoPictureTransform(2f, 200f, -80f), 640f, 240f, 640f, 320f,
            .1f, 20f, 30f, 500f, 200f, 6f)
        assertEquals(VideoPictureTransform(), result)
    }

    @Test fun reachingMaximumZoomPreservesTheFocusUsingTheAppliedScale() {
        val result = gesture(VideoPictureTransform(2f), zoom = 10f, focusX = 150f, maximum = 3f)
        assertEquals(3f, result.scale, 0f)
        assertEquals(150f, 100f + 25f * result.scale + result.offsetX, .0001f)
    }

    @Test fun aLayoutChangeRecentersAPictureThatNowFitsTheViewport() {
        val result = clampVideoPicture(VideoPictureTransform(2f, 200f, 100f), 400f, 200f, 800f, 400f, 6f)
        assertEquals(VideoPictureTransform(2f), result)
    }

    @Test fun unavailableOrInvalidDimensionsProduceASafeIdentity() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { width ->
            assertEquals(1f, videoCoverScale(width, 100f, 200f, 200f), 0f)
            assertEquals(VideoPictureTransform(), transformVideoPicture(VideoPictureTransform(2f, 20f, 30f),
                width, 100f, 200f, 200f, 2f, 5f, 5f, 100f, 100f, 6f))
        }
    }

    @Test fun invalidGestureValuesDoNotPoisonAnExistingPicture() {
        val current = VideoPictureTransform(2f, 20f, -10f)
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { zoom ->
            val result = gesture(current, zoom = zoom, panX = Float.NaN, panY = Float.NEGATIVE_INFINITY,
                focusX = Float.NaN, focusY = Float.POSITIVE_INFINITY)
            assertEquals(current, result)
        }
    }

    @Test fun invalidStoredTransformsAndScaleLimitsAreSanitized() {
        val result = gesture(VideoPictureTransform(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN),
            zoom = 2f, maximum = Float.NaN)
        assertEquals(VideoPictureTransform(), result)
        assertEquals(VideoPictureTransform(), gesture(zoom = 2f, maximum = .5f))
    }

    @Test fun veryLargeFiniteInputsStillProduceFiniteViewProperties() {
        val result = transformVideoPicture(VideoPictureTransform(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE),
            Float.MAX_VALUE, Float.MAX_VALUE, 200f, 200f, Float.MAX_VALUE,
            Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        assertTrue(result.scale.isFinite() && result.scale >= 1f)
        assertTrue(result.offsetX.isFinite() && result.offsetY.isFinite())
        assertTrue(videoCoverScale(Float.MIN_VALUE, Float.MIN_VALUE, Float.MAX_VALUE, Float.MAX_VALUE).isFinite())
    }

    private fun gesture(
        current: VideoPictureTransform = VideoPictureTransform(),
        zoom: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f,
        focusX: Float = 100f,
        focusY: Float = 100f,
        maximum: Float = 6f,
    ) = transformVideoPicture(current, 400f, 400f, 200f, 200f,
        zoom, panX, panY, focusX, focusY, maximum)
}
