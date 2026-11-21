package me.rerere.androidcontrol.captcha

import kotlin.math.roundToInt

/**
 * ddddocr's character-captcha decoding rules, ported as pure functions so the
 * exact behaviour can be unit-tested on the JVM without ONNX or Android.
 *
 * The bundled model's softmax has one row per charset entry and index 0 is the
 * CTC blank. Recognition therefore has to stay index-aligned with the charset
 * asset: a charset stored as a plain string would shift every index by one
 * because entry 0 is the empty string.
 */
internal object CaptchaCharset {

    /** Charset entry that CTC treats as "no character here". */
    const val BLANK_INDEX = 0

    /**
     * Collapse per-timestep argmax indices into text.
     *
     * Mirrors ddddocr `_ctc_decode_indices`: skip a repeat of the immediately
     * previous index (including repeated blanks), then drop blanks.
     */
    fun decode(indices: IntArray, charset: List<String>): String {
        if (indices.isEmpty()) return ""
        val out = StringBuilder()
        var previous = Int.MIN_VALUE
        for (index in indices) {
            if (index == previous) continue
            previous = index
            if (index == BLANK_INDEX) continue
            if (index in charset.indices) out.append(charset[index])
        }
        return out.toString()
    }

    /**
     * Restrict argmax to a character whitelist such as "0123456789".
     *
     * ddddocr exposes this as charset_range. Restricting cannot invent a
     * character the model did not favour, so an empty/blank result stays empty
     * rather than being replaced by the whitelist's first entry.
     */
    fun decodeRestricted(
        scores: Array<FloatArray>, charset: List<String>, allowed: Set<Char>?,
    ): String {
        if (allowed == null) {
            val indices = IntArray(scores.size)
            for (step in scores.indices) indices[step] = argMax(scores[step], 0, scores[step].size)
            return decode(indices, charset)
        }
        val allowedIndices = IntArray(charset.size + 1)
        allowedIndices[0] = BLANK_INDEX
        var count = 1
        for (index in 1 until charset.size) {
            val value = charset[index]
            if (value.length == 1 && value[0] in allowed) allowedIndices[count++] = index
        }
        if (count == 1) return "" // whitelist matched nothing in this charset
        val indices = IntArray(scores.size)
        for (step in scores.indices) {
            val row = scores[step]
            var bestSlot = 0
            var bestScore = row[allowedIndices[0]]
            for (slot in 1 until count) {
                val score = row[allowedIndices[slot]]
                if (score > bestScore) { bestScore = score; bestSlot = slot }
            }
            indices[step] = allowedIndices[bestSlot]
        }
        return decode(indices, charset)
    }

    private fun argMax(values: FloatArray, from: Int, to: Int): Int {
        var best = from
        for (index in from + 1 until to) if (values[index] > values[best]) best = index
        return best
    }

    /**
     * ddddocr resizes to a fixed height of 64 and keeps the width proportional,
     * then converts to 8-bit greyscale. Returns the target width for a capture
     * of [width]x[height] pixels; at least one pixel wide.
     */
    fun recognitionWidth(width: Int, height: Int, targetHeight: Int = 64): Int {
        require(width > 0 && height > 0 && targetHeight > 0)
        return (width.toDouble() * targetHeight / height).roundToInt().coerceAtLeast(1)
    }

    /**
     * Typical deployed character captchas are ASCII. A whitelist may only be
     * applied when the caller explicitly asked for one; nothing here guesses.
     */
    fun whitelist(value: String?): Set<Char>? {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty()) null else trimmed.toSet()
    }
}