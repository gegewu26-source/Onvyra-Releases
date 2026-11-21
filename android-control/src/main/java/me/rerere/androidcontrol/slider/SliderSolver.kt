package me.rerere.androidcontrol.slider

import android.graphics.Bitmap

/**
 * Full slider solve pipeline: raw-bitmap gap detection -> display-space
 * coordinate conversion -> human-like trajectory generation. Used by the
 * solve_slider control tool; pure Kotlin, no third-party dependency.
 */
internal object SliderSolver {

    data class Result(
        val ok: Boolean,
        val stroke: SliderTrajectory.Stroke?,
        val gapX: Int?,
        val handleX: Int?,
        val handleY: Int?,
        val distancePx: Int?,
        val method: String?,
        val confidence: Double?,
        val error: String? = null,
    )

    /** A known handle and displacement do not require a screenshot or image recognition. */
    fun solveExplicit(
        handleX: Int,
        handleY: Int,
        distancePx: Int,
        displayBounds: IntArray,
        seed: Long = System.currentTimeMillis(),
    ): Result {
        fun rejected(reason: String) = Result(false, null, null, handleX, handleY,
            distancePx, "explicit_distance", null, reason)
        if (!validBounds(displayBounds)) return rejected("invalid_display_bounds")
        if (handleX !in displayBounds[0] until displayBounds[2] ||
            handleY !in displayBounds[1] until displayBounds[3]) return rejected("handle_outside_display_bounds")
        if (distancePx == 0) return rejected("distance_must_be_nonzero")
        val endX = handleX.toLong() + distancePx
        if (endX < displayBounds[0] || endX >= displayBounds[2]) return rejected("target_outside_display_bounds")
        val stroke = SliderTrajectory.build(handleX, handleY, endX.toInt(), handleY,
            displayBounds, kotlin.random.Random(seed))
        return Result(true, stroke, null, handleX, handleY, distancePx, "explicit_distance", 1.0)
    }

    private fun validBounds(bounds: IntArray): Boolean = bounds.size == 4 &&
        bounds[2] > bounds[0] && bounds[3] > bounds[1]

    /**
     * Core solver on a raw pixel array (ARGB, row-major [width] stride) so the
     * pipeline is unit-testable on plain JVM without a Bitmap.
     */
    fun solvePixels(
        pixels: IntArray, width: Int, height: Int,
        windowLeft: Int,
        windowTop: Int,
        displayBounds: IntArray,
        roi: IntArray?,
        track: IntArray?,
        handleHint: Pair<Int, Int>?,
        distanceHint: Int?,
        pieceWidthHint: Int? = null,
        seed: Long = System.currentTimeMillis(),
        pieceStartXHint: Int? = null,
    ): Result {
        fun rejected(reason: String) = Result(false, null, null, null, null, null, null, null, reason)
        if (!validBounds(displayBounds)) return rejected("invalid_display_bounds")
        if (width < 24 || height < 24 || pixels.size.toLong() != width.toLong() * height) {
            return rejected("invalid_slider_image")
        }
        if (roi != null && (roi.size != 4 || roi[0] < 0 || roi[1] < 0 ||
                roi[2] > width || roi[3] > height || roi[2] - roi[0] < 24 || roi[3] - roi[1] < 24)) {
            return rejected("invalid_slider_roi")
        }
        if (track != null && (track.size != 4 || track[0] < 0 || track[1] < 0 ||
                track[2] > width || track[3] > height || track[2] - track[0] < 16 || track[3] - track[1] < 8)) {
            return rejected("invalid_slider_track")
        }
        if (pieceStartXHint != null && pieceStartXHint !in 0 until width) return rejected("piece_origin_outside_image")
        val roiRect = roi?.takeIf { it.size == 4 }
            ?: intArrayOf(0, 0, width, (height * 0.7f).toInt())
        val detection = if (distanceHint == null) SliderVision.detect(pixels, width, height,
            roiRect[0], roiRect[1], roiRect[2], roiRect[3], pieceWidthHint) else null
        if (detection != null && detection.confidence < .65) return rejected("ambiguous_gap_supply_distance_or_roi")

        var handleX: Int? = null
        var handleY: Int? = null
        if (handleHint != null) {
            handleX = handleHint.first - windowLeft
            handleY = handleHint.second - windowTop
        } else {
            // A challenge can be centered in a tall activity. Its track belongs
            // below the puzzle, not necessarily in the activity's bottom 25%.
            // Without a supplied ROI, search for an actual unambiguous knob.
            val trackRect = track ?: if (roi != null) intArrayOf(
                roi[0], roi[3], roi[2], minOf(height, roi[3] + maxOf(roi[3] - roi[1], height / 3)),
            ) else intArrayOf(0, 0, width, height)
            if (trackRect[3] - trackRect[1] < 8) return rejected("handle_not_found")
            SliderVision.detectHandle(pixels, width, height, trackRect[0], trackRect[1], trackRect[2], trackRect[3])?.let {
                handleX = it.first; handleY = it.second
            }
        }
        if (handleX == null || handleY == null) {
            return Result(false, null, null, null, null, null, null, null, "handle_not_found")
        }

        val pieceLeft = pieceStartXHint ?: if (distanceHint == null) SliderVision.detectRestingPieceLeft(
            pixels, width, height, roiRect[0], roiRect[1], roiRect[2], roiRect[3]) else null
        // The knob's anchor and the puzzle piece's left edge are different
        // points. Their displacement is equal, but their absolute X is not.
        val distance = distanceHint ?: detection?.let { gap -> pieceLeft?.let { gap.gapX - it } }
        if (distance == null) {
            return rejected(if (detection == null) "gap_not_found_supply_distance_or_roi"
                else "piece_origin_not_found_supply_distance")
        }

        // Image space -> display space: bitmap pixels are window pixels; the
        // window's top-left maps the origin onto the virtual display.
        val displayHandleX = handleX + windowLeft
        val displayHandleY = handleY + windowTop
        val explicit = solveExplicit(displayHandleX, displayHandleY, distance, displayBounds, seed)
        if (!explicit.ok) return explicit
        return Result(
            ok = true,
            stroke = explicit.stroke,
            gapX = detection?.gapX?.plus(windowLeft),
            handleX = displayHandleX,
            handleY = displayHandleY,
            distancePx = distance,
            method = if (distanceHint != null) "explicit_distance" else detection?.method ?: "unknown",
            confidence = detection?.confidence ?: 1.0,
        )
    }

    /** Bitmap convenience overload used by the solve_slider control tool. */
    fun solve(
        bitmap: Bitmap,
        windowLeft: Int,
        windowTop: Int,
        displayBounds: IntArray,
        roi: IntArray?,
        track: IntArray?,
        handleHint: Pair<Int, Int>?,
        distanceHint: Int?,
        pieceWidthHint: Int? = null,
        seed: Long = System.currentTimeMillis(),
        pieceStartXHint: Int? = null,
    ): Result {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return solvePixels(
            pixels, bitmap.width, bitmap.height, windowLeft, windowTop, displayBounds,
            roi, track, handleHint, distanceHint, pieceWidthHint, seed, pieceStartXHint,
        )
    }
}
