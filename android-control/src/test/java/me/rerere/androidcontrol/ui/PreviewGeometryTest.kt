package me.rerere.androidcontrol.ui

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class PreviewGeometryTest {
    @Test fun bottomHandleHasItsOwnStripWithoutChangingTheRenderedPictureRatio() {
        val portrait = fitPreviewWindowSize(548, 1080, 1920, 930, 1950, 100, 24)
        assertEquals(548, portrait.first)
        assertTrue(abs(portrait.second - 24 - portrait.first * 1920.0 / 1080) <= 1.0)
        val landscape = fitPreviewWindowSize(753, 1920, 1080, 930, 1950, 100, 24)
        assertEquals(753 to 448, landscape)
        // Height limits include the handle instead of cropping the source app's bottom row.
        val limited = fitPreviewWindowSize(300, 720, 1280, 320, 424, 100, 24)
        assertEquals(225 to 424, limited)
        assertEquals(1 to 15, fitPreviewWindowSize(200, 720, 1280, 200, 15, 100, 24))
    }

    @Test fun resizingKeepsTheTopAndHorizontalCenterWhileTheBottomFollowsTheFinger() {
        val grown = resizePreviewFromBottom(200, 300, 200, 424, 50f,
            720, 1440, 0, 0, 1000, 1600, 100, 24)
        assertEquals(PreviewResizeGeometry(188, 300, 225, 474), grown)
        assertEquals(300.0, grown.x + grown.width / 2.0, 0.5)
        assertEquals(300, grown.y)
        assertEquals(50, grown.y + grown.height - (300 + 424))
        assertTrue(grown.x < 200)
        assertTrue(grown.x + grown.width > 400 && grown.y + grown.height > 724)
    }

    @Test fun topAnchoredShrinkReversesGrowthWithoutPinningTheLeftEdge() {
        val shrunk = resizePreviewFromBottom(188, 300, 225, 474, -50f,
            720, 1440, 0, 0, 1000, 1600, 100, 24)
        assertEquals(PreviewResizeGeometry(201, 300, 200, 424), shrunk)
    }

    @Test fun centeredResizeClampsAtScreenInsetsAndPreservesTheWholePicture() {
        val resized = resizePreviewFromBottom(20, 40, 200, 424, 1000f,
            720, 1440, 20, 40, 820, 1440, 100, 24)
        assertTrue(resized.x >= 20 && resized.y >= 40)
        assertTrue(resized.x + resized.width <= 820 && resized.y + resized.height <= 1440)
        assertTrue(abs(resized.height - 24 - resized.width * 2.0) <= 1.0)
        assertEquals(40, resized.y)
    }

    @Test fun reachingTheBottomLimitNeverPushesTheTopEdgeUpward() {
        val resized = resizePreviewFromBottom(200, 900, 200, 424, 1000f,
            720, 1440, 0, 40, 1000, 1440, 100, 24)
        assertEquals(900, resized.y)
        assertEquals(540, resized.height)
        assertEquals(1440, resized.y + resized.height)
        assertEquals(300.0, resized.x + resized.width / 2.0, .5)
    }

    @Test fun zeroMovementKeepsTheStartingOddSizedFrameInPlace() {
        val unchanged = resizePreviewFromBottom(201, 301, 201, 426, 0f,
            720, 1440, 0, 0, 1000, 1800, 100, 24)
        assertEquals(PreviewResizeGeometry(201, 301, 201, 426), unchanged)
    }

    @Test fun handleStripAndCenteredResizeKeepPortraitAndLandscapeContentProportional() {
        for ((sourceWidth, sourceHeight) in listOf(720 to 1280, 1920 to 1080, 1440 to 320)) {
            val initial = fitPreviewWindowSize(400, sourceWidth, sourceHeight, 1000, 1600, 100, 24)
            for (delta in listOf(-500f, -30f, 0f, 40f, 1000f)) {
                val resized = resizePreviewFromBottom(250, 300, initial.first, initial.second, delta,
                    sourceWidth, sourceHeight, 0, 0, 1000, 1600, 100, 24)
                assertTrue(abs(resized.height - 24 - resized.width * sourceHeight.toDouble() / sourceWidth) <= 1.0)
                assertTrue(resized.x >= 0 && resized.y >= 0)
                assertTrue(resized.x + resized.width <= 1000 && resized.y + resized.height <= 1600)
            }
        }
    }

    @Test fun centeredResizeRejectsInvalidTouchCoordinatesAndEmptyBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            resizePreviewFromBottom(0, 0, 200, 400, Float.NaN, 720, 1280, 0, 0, 1000, 1600)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resizePreviewFromBottom(0, 0, 200, 400, 1f, 720, 1280, 0, 0, 0, 1600)
        }
    }

    @Test fun compactTallPhonePreviewUsesAtMostHalfOfTheAvailableHeight() {
        val (width, height) = compactPreviewSize(588, 1272, 2800, 1272, 2505, 350)
        assertTrue(height <= 1252)
        assertTrue(width < 588)
        assertTrue(abs(height - width * 2800.0 / 1272) <= 1.0)
        val small = compactPreviewSize(168, 720, 1280, 320, 568, 100)
        assertTrue(small.second <= 284)
        assertTrue(abs(small.second - small.first * 1280.0 / 720) <= 1.0)
    }

    @Test fun restoredGiantPortraitWidthIsBoundedWhileThisSessionCanStillBeEnlarged() {
        val restored = compactPreviewSize(1200, 1272, 2800, 1272, 2505, 350)
        assertTrue(restored.second <= 1252)
        assertTrue(abs(restored.second - restored.first * 2800.0 / 1272) <= 1.0)
        val explicitlyDragged = fitPreviewSize(1000, 1272, 2800, 1272, 2505, 350)
        assertTrue(explicitlyDragged.second > restored.second)
        val small = compactPreviewSize(220, 1272, 2800, 1272, 2505, 100)
        assertEquals(220, small.first)
    }

    @Test fun aHeightLimitedPortraitResizeReducesWidthInsteadOfCreatingBlackBands() {
        // A 300px-wide 720:1280 frame cannot retain that width in only 400px of height.
        assertEquals(225 to 400, fitPreviewSize(300, 720, 1280, 320, 400, 100))
        assertEquals(320 to 180, fitPreviewSize(1000, 1920, 1080, 320, 250, 100))
    }

    @Test fun nativeReferencePortraitAndLandscapeKeepTheSameWholePictureRatios() {
        val portrait = compactPreviewSize((930 * .59).roundToInt(), 1080, 1920, 930, 1950, 100)
        assertTrue(portrait.first in 547..549)
        assertTrue(portrait.second in 972..975)
        assertTrue(abs(portrait.first.toDouble() / portrait.second - 9.0 / 16) < .001)
        val landscape = compactPreviewSize((930 * .81).roundToInt(), 1920, 1080, 930, 1950, 100)
        assertEquals(753 to 424, landscape)
        assertTrue(abs(landscape.first.toDouble() / landscape.second - 16.0 / 9) < .002)
    }

    @Test fun edgeDragRequiresHalfTheWindowOutsideAndKeepsTheRemainingHalfReachable() {
        val left = 20; val right = 950; val width = 548
        assertNull(previewDockSide(20, width, left, right))
        assertNull(previewDockSide(-253, width, left, right))
        assertEquals(PreviewDockSide.LEFT, previewDockSide(-254, width, left, right))
        assertNull(previewDockSide(674, width, left, right))
        assertEquals(PreviewDockSide.RIGHT, previewDockSide(675, width, left, right))
        assertEquals(-254, previewDragX(Int.MIN_VALUE, width, left, right))
        assertEquals(675, previewDragX(Int.MAX_VALUE, width, left, right))
        assertEquals(353, previewDragX(353, width, left, right))
        // An odd-sized window still has at least half of its pixels reachable.
        assertEquals(-253, previewDragX(-1000, 547, left, right))
        assertEquals(676, previewDragX(1000, 547, left, right))
    }

    @Test fun aDockedIconMovesExactlyHalfOutsideAtEitherEdge() {
        assertEquals(20, previewIconX(PreviewDockSide.LEFT, 56, 20, 950, false))
        assertEquals(-8, previewIconX(PreviewDockSide.LEFT, 56, 20, 950, true))
        assertEquals(894, previewIconX(PreviewDockSide.RIGHT, 56, 20, 950, false))
        assertEquals(922, previewIconX(PreviewDockSide.RIGHT, 56, 20, 950, true))
    }

    @Test fun partialOutsidePlacementSurvivesSavingAndNewWindowGeometryUntilHalfThreshold() {
        val left = 20; val right = 950; val width = 548
        for (outside in listOf(.25f, .49f)) for (side in PreviewDockSide.entries) {
            val signed = if (side == PreviewDockSide.LEFT) -outside else outside
            val x = previewOutsideX(signed, width, left, right)
            assertEquals("Parking a quarter or 49 percent outside must not snap back", x,
                previewDragX(x, width, left, right))
            assertNull("Below half must remain an expanded window", previewDockSide(x, width, left, right))
            val saved = requireNotNull(previewOutsideFraction(x, width, left, right))
            assertEquals(x, previewOutsideX(saved, width, left, right))
            val resized = previewOutsideX(saved, 400, 0, 800)
            assertEquals(saved, requireNotNull(previewOutsideFraction(resized, 400, 0, 800)), .003f)
            assertNull(previewDockSide(resized, 400, 0, 800))
        }
        assertEquals(PreviewDockSide.LEFT, previewDockSide(previewDragX(left - (width * .51).roundToInt(), width, left, right), width, left, right))
        assertEquals(PreviewDockSide.RIGHT, previewDockSide(previewDragX(right - width + (width * .51).roundToInt(), width, left, right), width, left, right))
        // A window as wide as its usable screen still remembers its partial position.
        assertEquals(-200, previewOutsideX(-.25f, 800, 0, 800))
        assertEquals(.25f, requireNotNull(previewOutsideFraction(200, 800, 0, 800)), .0001f)
        assertNull(previewOutsideFraction(100, 400, 0, 800))
    }

    @Test fun minimumSizeYieldsToTheUsableScreenInsteadOfThrowingOrOverflowing() {
        val small = fitPreviewSize(168, 720, 1280, 60, 40, 100)
        assertTrue(small.first in 1..60)
        assertTrue(small.second in 1..40)
        assertTrue(abs(small.second - small.first * 1280.0 / 720) <= 1.0)
        assertEquals(9999 to 9999,
            fitPreviewSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 10_000, 9999, 100))
        assertEquals(Int.MAX_VALUE to 1,
            fitPreviewSize(Int.MAX_VALUE, Int.MAX_VALUE, 1, Int.MAX_VALUE, 400, 100))
    }

    @Test fun portraitLandscapeAndRotationBoundsKeepOneScaleWithoutCropping() {
        val displaySizes = listOf(720 to 1280, 1080 to 2400, 1920 to 1080, 1440 to 320)
        val usableAreas = listOf(320 to 568, 568 to 270, 1280 to 720)
        for ((displayWidth, displayHeight) in displaySizes) for ((maxWidth, maxHeight) in usableAreas) {
            for (requested in listOf(1, 100, 168, 400, Int.MAX_VALUE)) {
                val (width, height) = fitPreviewSize(requested, displayWidth, displayHeight, maxWidth, maxHeight, 100)
                assertTrue(width in 1..maxWidth)
                assertTrue(height in 1..maxHeight)
                assertTrue("$displayWidth:$displayHeight in $maxWidth:$maxHeight produced $width:$height",
                    abs(height - width * displayHeight.toDouble() / displayWidth) <= 1.0)
            }
        }
    }

    @Test fun freePositionFractionsPreserveInteriorDropsAndClampOnlyAtRealBounds() {
        assertEquals(.25f, previewPositionFraction(150, 50, 400), .0001f)
        assertEquals(.75f, previewPositionFraction(350, 50, 400), .0001f)
        assertEquals(0f, previewPositionFraction(-30, 50, 400), 0f)
        assertEquals(1f, previewPositionFraction(600, 50, 400), 0f)
        assertEquals(0f, previewPositionFraction(150, 50, 0), 0f)
        assertEquals(0f, previewPositionFraction(150, 50, -1), 0f)
        for (position in listOf(51, 113, 217, 381)) {
            val fraction = previewPositionFraction(position, 50, 333)
            val restored = 50 + (333 * fraction).roundToInt()
            assertTrue(abs(position - restored) <= 1)
            assertTrue("An interior placement cannot become left/right docking", fraction > 0f && fraction < 1f)
        }
    }

    @Test fun invalidDisplayOrUsableDimensionsAreRejectedBeforeLayout() {
        for (args in listOf(intArrayOf(0, 1280, 320, 568), intArrayOf(720, 0, 320, 568),
            intArrayOf(720, 1280, 0, 568), intArrayOf(720, 1280, 320, 0))) {
            assertThrows(IllegalArgumentException::class.java) {
                fitPreviewSize(168, args[0], args[1], args[2], args[3], 100)
            }
        }
    }
}
