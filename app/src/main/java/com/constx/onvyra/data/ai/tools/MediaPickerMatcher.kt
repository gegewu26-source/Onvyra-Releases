package com.constx.onvyra.data.ai.tools

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pixel data is used locally and is never included in a model tool result. */
internal data class PickerRaster(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) }
    fun pixel(x: Int, y: Int) = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

internal data class PickerRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun validFor(image: PickerRaster) = width >= 36 && height >= 36 && left >= 0 && top >= 0 &&
        right <= image.width && bottom <= image.height && width.toDouble() / height in .55..1.8
    fun overlap(other: PickerRect): Double {
        val common = max(0, min(right, other.right) - max(left, other.left)).toLong() *
            max(0, min(bottom, other.bottom) - max(top, other.top))
        val union = width.toLong() * height + other.width.toLong() * other.height - common
        return if (union > 0) common.toDouble() / union else 0.0
    }
}

internal data class PickerMatchCandidate(val rect: PickerRect, val score: Double, val source: String)
internal data class PickerMatchResult(val status: String, val candidates: List<PickerMatchCandidate>, val evaluated: Int)
internal data class PickerSelector(val rect: PickerRect, val checked: Boolean?)

internal fun selectorForMatchedThumbnail(tile: PickerRect, selectors: List<PickerSelector>): PickerSelector? = selectors
    .filter { candidate ->
        val r = candidate.rect
        r.width > 0 && r.height > 0 && r.width <= tile.width * .55 && r.height <= tile.height * .55 &&
            r.centerX in tile.left until tile.right && r.centerY in tile.top until tile.bottom &&
            r.centerX > tile.left + tile.width * .45
    }.minByOrNull { it.rect.width * it.rect.height }

internal fun isVisibleMediaPicker(labels: String): Boolean =
    Regex("相册|最近|图库|所有照片|选择照片|选择图片|照片和视频|\\balbums?\\b|\\brecent\\b|\\bgallery\\b|photo picker", RegexOption.IGNORE_CASE)
        .containsMatchIn(labels) ||
        Regex("照片|图片|\\bphotos?\\b|\\bimages?\\b", RegexOption.IGNORE_CASE).containsMatchIn(labels) &&
        Regex("下一步|完成|选择|\\bnext\\b|\\bdone\\b|\\bselect\\b", RegexOption.IGNORE_CASE).containsMatchIn(labels)

/** A conservative exact-appearance match, not a semantic guess about which photo looks plausible. */
internal fun matchSavedMediaThumbnail(reference: PickerRaster, screen: PickerRaster,
    nativeRects: List<PickerRect> = emptyList()): PickerMatchResult {
    val samples = 14
    val referenceFeatures = mutableMapOf<Long, IntArray>()
    fun features(aspect: Double): IntArray = referenceFeatures.getOrPut(aspect.toBits()) {
        val cropWidth = min(reference.width.toDouble(), reference.height * aspect)
        val cropHeight = min(reference.height.toDouble(), reference.width / aspect)
        val startX = (reference.width - cropWidth) / 2
        val startY = (reference.height - cropHeight) / 2
        IntArray(samples * samples) { index ->
            val u = .15 + .7 * (index % samples + .5) / samples
            val v = .15 + .7 * (index / samples + .5) / samples
            reference.pixel((startX + u * cropWidth).toInt(), (startY + v * cropHeight).toInt())
        }
    }
    val basic = features(1.0)
    val values = basic.map { c -> (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 3.0 }
    val average = values.average()
    val variation = sqrt(values.sumOf { (it - average) * (it - average) } / values.size)
    if (variation < 9) return PickerMatchResult("not_distinctive", emptyList(), 0)
    var evaluated = 0
    val ranked = ArrayList<PickerMatchCandidate>()
    fun evaluate(rect: PickerRect, source: String) {
        if (!rect.validFor(screen) || rect.width > screen.width * .62 || rect.height > screen.height * .5) return
        evaluated++
        val target = features(rect.width.toDouble() / rect.height)
        var difference = 0L
        for (index in target.indices) {
            val u = .15 + .7 * (index % samples + .5) / samples
            val v = .15 + .7 * (index / samples + .5) / samples
            val a = target[index]
            // Round the tile-local offset before adding its origin. Floating-point additions
            // at a nonzero origin can otherwise shift an exact thumbnail sample by one pixel.
            val b = screen.pixel(rect.left + (u * rect.width).toInt(), rect.top + (v * rect.height).toInt())
            difference += abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
                abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
        }
        val score = 1.0 - difference.toDouble() / (target.size * 3 * 255)
        if (score >= .79) ranked += PickerMatchCandidate(rect, score, source)
    }
    nativeRects.distinct().take(128).forEach { evaluate(it, "visible_image_node") }
    fun distinctBest(): List<PickerMatchCandidate> {
        val result = ArrayList<PickerMatchCandidate>()
        for (candidate in ranked.sortedByDescending { it.score }) {
            if (result.none { it.rect.overlap(candidate.rect) > .45 }) result += candidate
            if (result.size == 5) break
        }
        return result
    }
    val native = distinctBest()
    if (native.firstOrNull()?.score?.let { it >= .98 } == true &&
        (native.size == 1 || native.first().score - native[1].score >= .06)) {
        return PickerMatchResult("found", native, evaluated)
    }
    // Native pickers often omit photo labels. Search only a bounded, regular 2-5-column grid;
    // no gallery/database read or model vision call is made for other thumbnails.
    for (columns in 2..5) for (margin in listOf(0, 6)) for (gap in listOf(0, 3)) {
        val size = (screen.width - margin * 2 - gap * (columns - 1)) / columns
        if (size < 44) continue
        val step = max(3, size / 24)
        var top = (screen.height * .055).toInt()
        while (top + size <= screen.height * .96 && evaluated < 14_000) {
            for (column in 0 until columns) {
                val left = margin + column * (size + gap)
                evaluate(PickerRect(left, top, left + size, top + size), "visible_grid")
            }
            top += step
        }
    }
    val coarse = distinctBest().take(5)
    for (candidate in coarse) {
        val reach = max(3, candidate.rect.width / 24)
        for (dy in -reach..reach) for (dx in -5..5) {
            val r = candidate.rect
            evaluate(PickerRect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy), candidate.source)
        }
    }
    val best = distinctBest()
    val first = best.firstOrNull()
    val status = when {
        first == null || first.score < .92 -> "not_found"
        best.size > 1 && first.score - best[1].score < .045 -> "ambiguous"
        else -> "found"
    }
    return PickerMatchResult(status, best, evaluated)
}

internal fun pickerImageRect(bounds: List<Int>, origin: List<Int>, displayWidth: Int, displayHeight: Int,
    imageWidth: Int, imageHeight: Int): PickerRect? {
    if (bounds.size != 4 || origin.size != 2 || displayWidth <= 0 || displayHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    fun x(v: Int) = ((v.toLong() - origin[0]) * imageWidth / displayWidth).toInt()
    fun y(v: Int) = ((v.toLong() - origin[1]) * imageHeight / displayHeight).toInt()
    return PickerRect(x(bounds[0]), y(bounds[1]), x(bounds[2]), y(bounds[3]))
}
