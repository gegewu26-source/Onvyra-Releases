package me.rerere.androidcontrol.slider

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight slider-gap locator working on the raw (unscaled) window bitmap.
 * Ported in spirit from slidex's vision core (column edge-energy profile +
 * edge-mask template NCC) without any OpenCV dependency: only Bitmap pixels
 * and basic math. All coordinates are in the bitmap's pixel space.
 *
 * Column energy is the 75th percentile of per-row channel-max gradients, not
 * their sum: a coherent border produces the same strong gradient on every
 * row, while per-pixel photo noise spreads across the range and stays low in
 * the upper quartile. That single change is what keeps textured backgrounds
 * from drowning the gap.
 */
internal object SliderVision {

    data class Detection(
        /** Left edge of the puzzle gap, in bitmap pixels. */
        val gapX: Int,
        /** Confidence in 0..1; strategies that agree score higher. */
        val confidence: Double,
        /** Which strategy produced gapX: column_profile / template_edge / combined. */
        val method: String,
        /** Vertical center of the detected puzzle band, in bitmap pixels. */
        val bandCenterY: Int,
    )

    /** Luma at (x, y) from an ARGB pixel array. */
    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Channel-max gradient: a coloured piece against grey background still registers. */
    private fun maxChannelDiff(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }

    /**
     * Robust column edge energy: 75th percentile of the per-row horizontal
     * gradient inside [yFrom, yTo). Coherent borders score high on every
     * row; random texture scores high on few rows only.
     */
    private fun columnEnergy(
        pixels: IntArray, width: Int, x: Int, yFrom: Int, yTo: Int,
    ): Int {
        if (x < 1 || x >= width - 1) return 0
        val hist = IntArray(256)
        var n = 0
        for (y in yFrom until yTo) {
            val row = y * width
            hist[maxChannelDiff(pixels[row + x - 1], pixels[row + x + 1])]++
            n++
        }
        if (n == 0) return 0
        val target = n * 3 / 4
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }

    /** Median of a contiguous slice; the slice is copied, callers pass small ranges. */
    private fun median(values: IntArray, from: Int, to: Int): Int {
        if (to <= from) return 0
        val copy = values.copyOfRange(from, to)
        copy.sort()
        return copy[copy.size / 2]
    }

    /**
     * Strategy A — column edge-energy profile (slidex column_profile):
     * skip the left start zone where the puzzle piece rests, then find the
     * first column whose coherent edge energy clearly dominates the ROI
     * median. The gap's left border is the drag target's zero point.
     */
    fun columnProfile(
        pixels: IntArray, width: Int, height: Int,
        roiLeft: Int, roiTop: Int, roiRight: Int, roiBottom: Int,
    ): Int? {
        val w = roiRight - roiLeft
        val h = roiBottom - roiTop
        if (w < 24 || h < 24) return null
        val skip = (w * 0.20f).toInt().coerceAtLeast(8)
        val energies = IntArray(w)
        for (x in skip until w - 4) {
            energies[x] = columnEnergy(pixels, width, roiLeft + x, roiTop + 1, roiTop + h - 1)
        }
        // 3-column smoothing: gap borders form a wide ridge, noise spikes do not.
        val smoothed = IntArray(w)
        for (x in skip + 1 until w - 5) {
            smoothed[x] = (energies[x - 1] + energies[x] + energies[x + 1]) / 3
        }
        val peak = (skip until w - 5).maxOfOrNull { smoothed[it] } ?: return null
        if (peak < 40) return null
        val noise = median(smoothed, skip, w - 5)
        val threshold = max((peak * 0.55f).toInt(), (noise * 1.35f).toInt())
        for (x in skip + 1 until w - 6) {
            val e = smoothed[x]
            if (e < threshold) continue
            // First qualifying local ridge — the gap's left border.
            if (e >= smoothed[x - 1] && e >= smoothed[x + 1]) return roiLeft + x
        }
        return null
    }

    /**
     * Strategy B — template edge NCC (slidex template_edge): the resting
     * piece's edge profile is slid across the same band; the matched offset
     * is shifted by the piece's left border inside the template so the
     * result is the gap's LEFT border, not the template-zone start.
     */
    fun templateEdge(
        pixels: IntArray, width: Int, height: Int,
        roiLeft: Int, roiTop: Int, roiRight: Int, roiBottom: Int,
        pieceWidth: Int,
    ): Int? {
        val w = roiRight - roiLeft
        val h = roiBottom - roiTop
        if (w < 48 || h < 24 || pieceWidth !in 8..(w / 2)) return null
        val energies = IntArray(w)
        for (x in 1 until w - 1) {
            energies[x] = columnEnergy(pixels, width, roiLeft + x, roiTop, roiTop + h)
        }
        val templateLen = pieceWidth
        if (templateLen < 8 || templateLen >= w - 8) return null
        // Piece left border = first column in the template zone that dominates
        // its local noise. The piece zone shows two spikes (both borders).
        val noise = median(energies, 0, templateLen)
        val pieceThreshold = max(noise * 2 + 25, 45)
        val pieceLeftInTemplate = (0 until templateLen).firstOrNull { energies[it] >= pieceThreshold }
            ?: return null
        val template = energies.copyOfRange(0, templateLen)
        val templateMean = template.average()
        var tNorm = 0.0
        for (v in template) { val d = v - templateMean; tNorm += d * d }
        if (tNorm <= 0.0) return null
        val skip = (w * 0.20f).toInt().coerceAtLeast(templateLen + 4)
        if (skip >= w - templateLen) return null
        var bestScore = -1.0
        var bestOffset = -1
        for (offset in skip until w - templateLen) {
            val score = ncc(energies, template, templateMean, tNorm, offset) ?: continue
            if (score > bestScore) { bestScore = score; bestOffset = offset }
        }
        if (bestOffset < 0 || bestScore < 0.5) return null
        // Two-spike ambiguity: left-to-left and right-to-right alignments score
        // alike. The smallest near-best offset is the left-border alignment.
        var leftmost = -1
        for (offset in skip until w - templateLen) {
            val score = ncc(energies, template, templateMean, tNorm, offset) ?: continue
            if (score >= bestScore * 0.98) { leftmost = offset; break }
        }
        val chosen = if (leftmost >= 0) leftmost else bestOffset
        return roiLeft + chosen + pieceLeftInTemplate
    }

    private fun ncc(
        energies: IntArray, template: IntArray, templateMean: Double, tNorm: Double, offset: Int,
    ): Double? {
        val len = template.size
        if (offset < 0 || offset + len >= energies.size) return null
        var mean = 0.0
        for (i in 0 until len) mean += energies[offset + i]
        mean /= len
        var num = 0.0
        var eNorm = 0.0
        for (i in 0 until len) {
            val dt = template[i] - templateMean
            val de = energies[offset + i] - mean
            num += dt * de; eNorm += de * de
        }
        if (eNorm <= 0.0) return null
        return num / sqrt(tNorm * eNorm)
    }

    /**
     * Combined entry: run both strategies on the given ROI; when they agree
     * within tolerance, boost confidence and report `combined`.
     */
    fun detect(
        pixels: IntArray, width: Int, height: Int,
        roiLeft: Int, roiTop: Int, roiRight: Int, roiBottom: Int,
        pieceWidthHint: Int? = null,
    ): Detection? {
        if (width < 24 || height < 24 || pixels.size.toLong() != width.toLong() * height) return null
        val l = roiLeft.coerceIn(0, width - 8)
        val t = roiTop.coerceIn(0, height - 8)
        val r = roiRight.coerceIn(l + 8, width)
        val b = roiBottom.coerceIn(t + 8, height)
        val profile = columnProfile(pixels, width, height, l, t, r, b)
        val pieceW = pieceWidthHint ?: (r - l) * 0.10f
        val template = templateEdge(pixels, width, height, l, t, r, b, pieceW.toInt().coerceAtLeast(16))
        val bandCenterY = t + (b - t) / 2
        return when {
            profile != null && template != null && abs(profile - template) <= 12 ->
                Detection((profile + template) / 2, 0.95, "combined", bandCenterY)
            profile != null && template != null ->
                Detection(profile, 0.6, "column_profile_ambiguous", bandCenterY)
            profile != null -> Detection(profile, 0.72, "column_profile", bandCenterY)
            template != null -> Detection(template, 0.65, "template_edge", bandCenterY)
            else -> null
        }
    }

    /** Locate the resting piece's LEFT edge, independently of the track knob. */
    fun detectRestingPieceLeft(
        pixels: IntArray, width: Int, height: Int,
        roiLeft: Int, roiTop: Int, roiRight: Int, roiBottom: Int,
    ): Int? {
        if (width < 24 || height < 24 || pixels.size.toLong() != width.toLong() * height) return null
        val l = roiLeft.coerceIn(0, width - 24)
        val t = roiTop.coerceIn(0, height - 24)
        val r = roiRight.coerceIn(l + 24, width)
        val b = roiBottom.coerceIn(t + 24, height)
        // The gap detector reserves this same left 20% zone for the resting piece.
        val zoneWidth = ((r - l) * .20f).toInt().coerceAtLeast(8)
        val energies = IntArray(zoneWidth) { x -> columnEnergy(pixels, width, l + x, t + 1, b - 1) }
        val noise = median(energies, 0, energies.size)
        val threshold = max(45, noise * 2 + 25)
        for (x in 2 until energies.lastIndex) {
            if (energies[x] >= threshold && energies[x] >= energies[x - 1] && energies[x] >= energies[x + 1]) {
                return l + x
            }
        }
        return null
    }

    /** Bitmap convenience overload for the control tool path. */
    fun detect(
        bitmap: Bitmap,
        roiLeft: Int, roiTop: Int, roiRight: Int, roiBottom: Int,
        pieceWidthHint: Int? = null,
    ): Detection? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return detect(pixels, bitmap.width, bitmap.height, roiLeft, roiTop, roiRight, roiBottom, pieceWidthHint)
    }

    /**
     * Locate a compact high-contrast knob across multiple rows. Single text
     * strokes, long track lines and tall image borders are not knob evidence.
     * Similar competing candidates require a tighter track or a handle hint.
     */
    fun detectHandle(
        pixels: IntArray, width: Int, height: Int,
        trackLeft: Int, trackTop: Int, trackRight: Int, trackBottom: Int,
    ): Pair<Int, Int>? {
        if (width < 16 || height < 8 || pixels.size.toLong() != width.toLong() * height) return null
        val l = trackLeft.coerceIn(0, width - 4)
        val t = trackTop.coerceIn(0, height - 4)
        val r = trackRight.coerceIn(l + 4, width)
        val b = trackBottom.coerceIn(t + 4, height)
        if (r - l < 16 || b - t < 8) return null
        data class RunGroup(var centerX: Int, var firstY: Int, var lastY: Int, var width: Int, var rows: Int)
        val groups = ArrayList<RunGroup>()
        val maxKnobWidth = minOf((r - l) * .35f, (b - t) * 3f).toInt().coerceAtLeast(8)
        val minKnobWidth = maxOf(12, (r - l) / 40)
        fun addRun(centerX: Int, rowY: Int, runWidth: Int) {
            if (runWidth !in minKnobWidth..maxKnobWidth) return
            val group = groups.lastOrNull {
                rowY > it.lastY && rowY - it.lastY <= maxOf(6, it.width / 3) &&
                    abs(centerX - it.centerX) <= maxOf(3, it.width / 12) &&
                    abs(runWidth - it.width) <= maxOf(4, it.width / 5)
            }
            if (group == null) groups.add(RunGroup(centerX, rowY, rowY, runWidth, 1))
            else { group.lastY = rowY; group.width = maxOf(group.width, runWidth); group.rows++ }
        }
        var y = t + 2
        while (y < b - 2) {
            val row = IntArray(r - l) { luma(pixels[y * width + l + it]) }
            val sorted = row.sortedArray()
            val baseline = sorted[sorted.size / 2]
            var runStart = -1
            var runLen = 0
            for (i in row.indices) {
                if (abs(row[i] - baseline) >= 40) {
                    if (runStart < 0) runStart = i
                    runLen++
                } else {
                    addRun(l + runStart + runLen / 2, y, runLen)
                    runStart = -1
                    runLen = 0
                }
            }
            addRun(l + runStart + runLen / 2, y, runLen)
            y += 3
        }
        fun RunGroup.span() = lastY - firstY + 3
        fun RunGroup.score() = minOf(width, span()).toLong().let { it * it }
        val candidates = groups.filter {
            val span = it.span()
            span >= maxOf(8, (it.width * .4f).toInt()) && span <= it.width * 1.8f && it.rows * 3 >= span * .45f
        }.sortedByDescending { it.score() }
        val best = candidates.firstOrNull() ?: return null
        if (candidates.drop(1).any { it.score() >= best.score() * .35 }) return null
        return best.centerX to (best.firstY + best.lastY) / 2
    }
}
