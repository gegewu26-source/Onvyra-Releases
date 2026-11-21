package me.rerere.androidcontrol.ui

import org.junit.Assert.*
import org.junit.Test

class PreviewRotationTest {
    @Test fun producerCornersStayUprightThroughAllFourRotations() {
        // SurfaceFlinger projects logical content into a fixed native portrait buffer. Its
        // inverse maps the three buffer corners into the current logical viewport.
        val expected = listOf(
            listOf(0f to 0f, 1f to 0f, 0f to 1f),
            listOf(0f to 1f, 0f to 0f, 1f to 1f),
            listOf(1f to 1f, 0f to 1f, 1f to 0f),
            listOf(1f to 0f, 1f to 1f, 0f to 0f),
        )
        repeat(4) { rotation ->
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f).map { (x, y) ->
                nativeBufferPointInLogicalSpace(x, y, rotation)
            }
            assertEquals("rotation=$rotation", expected[rotation], corners)
            assertEquals(.5f to .5f, nativeBufferPointInLogicalSpace(.5f, .5f, rotation))
        }
    }

    @Test fun landscapeRightHalfIsValidAndPortraitBoundsAreNotReused() {
        val portrait = PreviewDisplayGeometry(1080, 2340, 0)
        val landscape = PreviewDisplayGeometry(2340, 1080, 1)
        assertFalse(portrait.contains(2000, 700))
        assertTrue(landscape.contains(2000, 700))
        assertFalse(landscape.contains(700, 2000))
        assertTrue(landscape.contains(2339, 1079))
        assertFalse(landscape.contains(2340, 1079))
        assertFalse(landscape.contains(-1, 0))
        // A 180-degree change also invalidates an in-progress gesture even though axes match.
        assertNotEquals(portrait, PreviewDisplayGeometry(1080, 2340, 2))
    }

    @Test fun rotationMatrixFitsLandscapeWithoutStretchingOrCropping() {
        val logical = PreviewDisplayGeometry(2340, 1080, 1)
        val actual = nativePreviewDestinationPoints(468, 216, logical)
        assertArrayEquals(floatArrayOf(0f, 216f, 0f, 0f, 468f, 216f), actual, .001f)
        // A constrained square preview must keep letterboxing and the same aspect ratio.
        val square = nativePreviewDestinationPoints(400, 400, logical)
        val fittedHeight = 400f * 1080f / 2340f
        val top = (400f - fittedHeight) / 2f
        assertArrayEquals(floatArrayOf(0f, top + fittedHeight, 0f, top, 400f, top + fittedHeight), square, .001f)
        val fit = fitPreviewSize(468, logical.width, logical.height, 1080, 2000)
        assertEquals(468 to 216, fit)
    }

    @Test fun reverseLandscapeAndAlreadyLandscapeNaturalBuffersBothFit() {
        assertArrayEquals(floatArrayOf(468f, 0f, 468f, 216f, 0f, 0f),
            nativePreviewDestinationPoints(468, 216, PreviewDisplayGeometry(2340, 1080, 3)), .001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 468f, 0f, 0f, 216f),
            nativePreviewDestinationPoints(468, 216, PreviewDisplayGeometry(2340, 1080, 0)), .001f)
        assertThrows(IllegalArgumentException::class.java) { PreviewDisplayGeometry(0, 1080, 0) }
    }
}
