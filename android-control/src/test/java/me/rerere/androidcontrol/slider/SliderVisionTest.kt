package me.rerere.androidcontrol.slider

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SliderVisionTest {
    /** Synthesize a slider image: noisy background, resting piece, gap. */
    private fun syntheticSlider(width: Int, height: Int, gapX: Int, pieceW: Int, seed: Int = 5): Pair<IntArray, Pair<Int, Int>> {
        val rnd = Random(seed)
        val pixels = IntArray(width * height)
        // Real slider backgrounds (photos/cartoons) have low per-pixel noise
        // (~+/-10 luma); the earlier +/-80 amplitude made column noise energy
        // rival the piece borders and no detector could work on it.
        for (i in pixels.indices) {
            val v = 128 + rnd.nextInt(20) - 10
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or (v + 20)
        }
        // Resting piece near the left at the puzzle band vertical center,
        // aligned with the resting knob (real sliders draw them same-x).
        val bandTop = height / 4
        val bandBottom = height * 3 / 4
        val pieceStart = 20
        // Gap: darker recessed rectangle at gapX.
        for (y in bandTop until bandBottom) for (x in gapX until gapX + pieceW) {
            pixels[y * width + x] = (0xFF shl 24) or (0x08 shl 16) or (0x0A shl 8) or 0x16
        }
        // Piece: bright rectangle starting at pieceStart (resting position).
        for (y in bandTop until bandBottom) for (x in pieceStart until pieceStart + pieceW) {
            pixels[y * width + x] = (0xFF shl 24) or (0x6E shl 16) or (0xAA shl 8) or 0xFF
        }
        return pixels to (bandTop to bandBottom)
    }

    @Test fun columnProfileLocatesGapLeftEdge() {
        for (gapX in listOf(180, 300, 480)) {
            val (pixels, band) = syntheticSlider(720, 400, gapX, 60)
            val hit = SliderVision.columnProfile(pixels, 720, 400, 0, band.first, 720, band.second)
            assertNotNull("gap at $gapX detected", hit)
            assertTrue("detected $hit vs $gapX", kotlin.math.abs(hit!! - gapX) <= 2)
        }
    }

    @Test fun templateEdgeLocatesGap() {
        for (gapX in listOf(200, 340, 500)) {
            val (pixels, band) = syntheticSlider(720, 400, gapX, 60)
            val hit = SliderVision.templateEdge(pixels, 720, 400, 0, band.first, 720, band.second, pieceWidth = 60)
            assertNotNull("gap at $gapX detected", hit)
            assertTrue("detected $hit vs $gapX", kotlin.math.abs(hit!! - gapX) <= 4)
        }
    }

    @Test fun combinedDetectAgreesWithinTwoPixels() {
        for (gapX in listOf(220, 380, 560)) {
            val (pixels, band) = syntheticSlider(720, 400, gapX, 60)
            val d = SliderVision.detect(pixels, 720, 400, 0, band.first, 720, band.second, pieceWidthHint = 60)
            assertNotNull(d)
            assertTrue("gapX ${d!!.gapX} vs $gapX", kotlin.math.abs(d.gapX - gapX) <= 2)
            assertTrue("confidence reported", d.confidence > 0.5)
        }
    }

    @Test fun detectHandleFindsHighContrastKnob() {
        // Track band at the bottom with a bright knob on a dark track.
        val width = 720; val height = 400
        val pixels = IntArray(width * height) { (0xFF shl 24) or (0x2D shl 16) or (0x37 shl 8) or 0x60 }
        val trackTop = 360; val trackBottom = 392
        for (y in 348 until 396) for (x in 20 until 72) {
            pixels[y * width + x] = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
        }
        val handle = SliderVision.detectHandle(pixels, width, height, 0, trackTop, width, trackBottom)
        assertNotNull(handle)
        // The knob spans x=20..72; the CENTER is the drag anchor.
        assertTrue("handle x ${handle!!.first} near knob center 46", handle.first in 36..56)
        assertTrue("handle y near band center", kotlin.math.abs(handle.second - (trackTop + trackBottom) / 2) <= 2)
    }

    @Test fun solverEndsInsideGapFromSyntheticImage() {
        val gapX = 380; val pieceW = 60
        val (pixels, band) = syntheticSlider(720, 400, gapX, pieceW)
        // Track band with a resting knob at x=44.
        val width = 720; val height = 400
        for (y in 360 until 392) for (x in 20 until 72) {
            if (pixels[y * width + x] and 0xFF000000.toInt() != 0) pixels[y * width + x] = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
        }
        val result = SliderSolver.solvePixels(
            pixels, width, height,
            windowLeft = 0, windowTop = 0,
            displayBounds = intArrayOf(0, 0, 720, 400),
            roi = intArrayOf(0, band.first, width, band.second),
            track = intArrayOf(0, 352, width, 400),
            handleHint = null, distanceHint = null,
            pieceWidthHint = pieceW,
            seed = 11,
        )
        assertTrue("solver ok, error=${result.error}", result.ok)
        assertNotNull(result.stroke)
        val distance = result.distancePx ?: -1
        // The piece starts at x=20, independently of the knob center x=46.
        // A center-to-left subtraction used to undershoot by about 26px.
        assertTrue("piece's left edge lands on gap, distance=$distance",
            kotlin.math.abs(20 + distance - gapX) <= 3)
        val stroke = result.stroke!!
        assertEquals(result.handleX!! + distance, stroke.xs[stroke.size - 1])
        assertTrue("knob anchor retains its offset from the piece", stroke.xs.last() > gapX + 15)
    }

    @Test fun explicitDistanceOverridesDetection() {
        val (pixels, band) = syntheticSlider(720, 400, 300, 60)
        val result = SliderSolver.solvePixels(
            pixels, 720, 400,
            windowLeft = 0, windowTop = 0,
            displayBounds = intArrayOf(0, 0, 720, 400),
            roi = intArrayOf(0, band.first, 720, band.second),
            track = null,
            handleHint = 44 to 380,
            distanceHint = 250,
            seed = 3,
        )
        assertTrue(result.ok)
        assertEquals(250, result.distancePx)
        assertEquals("explicit_distance", result.method)
        val stroke = result.stroke!!
        assertEquals(294, stroke.xs[stroke.size - 1])
    }

    @Test fun outOfBoundsTargetIsRejected() {
        val (pixels, band) = syntheticSlider(720, 400, 300, 60)
        val result = SliderSolver.solvePixels(
            pixels, 720, 400,
            windowLeft = 0, windowTop = 0,
            displayBounds = intArrayOf(0, 0, 300, 400),
            roi = null, track = null,
            handleHint = 280 to 380,
            distanceHint = 250,
            seed = 3,
        )
        assertFalse(result.ok)
        assertEquals("target_outside_display_bounds", result.error)
    }

    @Test fun blankTrackDoesNotInventAHandle() {
        val pixels = IntArray(720 * 400) { 0xff808080.toInt() }
        assertNull(SliderVision.detectHandle(pixels, 720, 400, 0, 300, 720, 400))
    }

    @Test fun centeredChallengeFindsTrackBelowRoiInsteadOfWindowBottom() {
        val width = 720; val height = 1280
        val pixels = IntArray(width * height) { 0xff182034.toInt() }
        fun rect(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
        }
        rect(80, 300, 640, 480, 0xff808094.toInt())
        rect(100, 300, 160, 480, 0xff6eaaff.toInt())
        rect(400, 300, 460, 480, 0xff080a16.toInt())
        rect(100, 540, 152, 592, 0xffffffff.toInt())
        val result = SliderSolver.solvePixels(pixels, width, height,
            windowLeft = 0, windowTop = 0, displayBounds = intArrayOf(0, 0, width, height),
            roi = intArrayOf(80, 300, 640, 480), track = null, handleHint = null,
            distanceHint = null, pieceWidthHint = 60, seed = 3)
        assertTrue("error=${result.error}", result.ok)
        assertTrue("actual centered knob", result.handleY!! in 563..568)
        assertTrue(kotlin.math.abs(100 + result.distancePx!! - 400) <= 3)
    }

    @Test fun noRoiSearchCanFindAnUnambiguousKnobAwayFromTheBottom() {
        val width = 720; val height = 1280
        val pixels = IntArray(width * height) { 0xff182034.toInt() }
        for (y in 540 until 592) for (x in 100 until 152) pixels[y * width + x] = 0xffffffff.toInt()
        val result = SliderSolver.solvePixels(pixels, width, height,
            windowLeft = 0, windowTop = 0, displayBounds = intArrayOf(0, 0, width, height),
            roi = null, track = null, handleHint = null, distanceHint = 100, seed = 3)
        assertTrue("error=${result.error}", result.ok)
        assertTrue(result.handleY!! in 563..568)
        assertEquals(126, result.handleX)
    }

    @Test fun handleDetectionRejectsLongLinesAndTallImageBorders() {
        val width = 720; val height = 600
        val pixels = IntArray(width * height) { 0xff182034.toInt() }
        for (y in 100 until 106) for (x in 20 until 220) pixels[y * width + x] = 0xffffffff.toInt()
        for (y in 180 until 480) for (x in 20 until 60) pixels[y * width + x] = 0xffffffff.toInt()
        assertNull(SliderVision.detectHandle(pixels, width, height, 0, 0, width, height))
    }

    @Test fun competingKnobShapesNeedAnExplicitTrackOrHandle() {
        val width = 720; val height = 600
        val pixels = IntArray(width * height) { 0xff182034.toInt() }
        for (top in listOf(100, 400)) for (y in top until top + 52) for (x in 20 until 72) {
            pixels[y * width + x] = 0xffffffff.toInt()
        }
        assertNull(SliderVision.detectHandle(pixels, width, height, 0, 0, width, height))
        assertNotNull(SliderVision.detectHandle(pixels, width, height, 0, 380, width, 470))
    }

    @Test fun insetWindowMovesPieceByTheSameDistance() {
        val (pixels, band) = syntheticSlider(720, 400, 380, 60)
        val result = SliderSolver.solvePixels(pixels, 720, 400,
            windowLeft = 40, windowTop = 90, displayBounds = intArrayOf(0, 0, 900, 700),
            roi = intArrayOf(0, band.first, 720, band.second), track = null,
            handleHint = 86 to 470, distanceHint = null, pieceWidthHint = 60, seed = 6)
        assertTrue("error=${result.error}", result.ok)
        assertEquals(86, result.stroke!!.xs.first())
        assertEquals(470, result.stroke.ys.first())
        assertTrue(kotlin.math.abs(20 + result.distancePx!! - 380) <= 3)
        assertTrue(kotlin.math.abs(result.stroke.xs.last() - (86 + 360)) <= 3)
    }

    @Test fun malformedImagesAndRegionsAreRejected() {
        fun solve(pixels: IntArray, width: Int, height: Int, roi: IntArray? = null) = SliderSolver.solvePixels(
            pixels, width, height, 0, 0, intArrayOf(0, 0, 720, 400), roi, null,
            44 to 380, null, seed = 1)
        assertEquals("invalid_slider_image", solve(IntArray(1), 720, 400).error)
        assertEquals("invalid_slider_image", solve(IntArray(4), 2, 2).error)
        assertEquals("invalid_slider_roi", solve(IntArray(720 * 400), 720, 400, intArrayOf(-90, 10, 600, 300)).error)
    }

    @Test fun knownPieceOriginCanBeSuppliedWhenItsEdgeIsNotVisible() {
        val (pixels, band) = syntheticSlider(720, 400, 380, 60)
        for (y in band.first until band.second) for (x in 20 until 80) pixels[y * 720 + x] = 0xff808094.toInt()
        val result = SliderSolver.solvePixels(pixels, 720, 400,
            windowLeft = 0, windowTop = 0, displayBounds = intArrayOf(0, 0, 720, 400),
            roi = intArrayOf(0, band.first, 720, band.second), track = null,
            handleHint = 46 to 376, distanceHint = null, pieceWidthHint = 60,
            seed = 6, pieceStartXHint = 20)
        assertTrue("error=${result.error}", result.ok)
        assertTrue(kotlin.math.abs(20 + result.distancePx!! - 380) <= 3)
    }

    @Test fun explicitGestureWorksWithoutBitmapInBothDirections() {
        val bounds = intArrayOf(0, 0, 720, 400)
        for (distance in listOf(250, -250)) {
            val result = SliderSolver.solveExplicit(350, 280, distance, bounds, seed = 4)
            assertTrue("error=${result.error}", result.ok)
            assertEquals("explicit_distance", result.method)
            assertEquals(350, result.stroke!!.xs.first())
            assertEquals(350 + distance, result.stroke.xs.last())
            assertEquals(280, result.stroke.ys.last())
        }
    }

    @Test fun explicitGestureRejectsInvalidStartEndAndDistance() {
        val bounds = intArrayOf(0, 0, 720, 400)
        assertEquals("distance_must_be_nonzero", SliderSolver.solveExplicit(350, 280, 0, bounds).error)
        assertEquals("handle_outside_display_bounds", SliderSolver.solveExplicit(-1, 280, 10, bounds).error)
        assertEquals("handle_outside_display_bounds", SliderSolver.solveExplicit(350, 400, 10, bounds).error)
        assertEquals("target_outside_display_bounds", SliderSolver.solveExplicit(10, 280, -11, bounds).error)
        assertEquals("target_outside_display_bounds", SliderSolver.solveExplicit(350, 280, Int.MAX_VALUE, bounds).error)
        assertEquals("invalid_display_bounds", SliderSolver.solveExplicit(350, 280, 10, intArrayOf(720, 400, 0, 0)).error)
    }
}
