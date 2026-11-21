package com.constx.onvyra.data.files

internal data class GifAnimationInfo(val width: Int, val height: Int, val frames: Int, val delaysMs: List<Int>)
internal class GifAnimationException(message: String) : IllegalArgumentException(message)

/** Parse and decode each GIF frame; headers or differently packed identical frames are insufficient. */
internal object GifAnimationVerifier {
    fun verify(bytes: ByteArray): GifAnimationInfo {
        val input = Cursor(bytes)
        require(input.text(6) in setOf("GIF87a", "GIF89a")) { "接口返回的内容不是GIF动画。" }
        val width = input.word(); val height = input.word()
        require(width in 1..2048 && height in 1..2048) { "GIF尺寸超出可验证范围。" }
        val packed = input.byte(); val background = input.byte(); input.byte()
        val global = if (packed and 0x80 != 0) input.palette(1 shl ((packed and 7) + 1)) else null
        val canvas = IntArray(width * height) { global?.getOrNull(background) ?: 0 }
        var firstFrame: IntArray? = null
        var changed = false
        var frames = 0
        var decodedPixels = 0L
        var disposal = 0
        var delay = 0
        var transparent = -1
        var previousDisposal = 0
        var previousRect = intArrayOf(0, 0, 0, 0)
        var restore: IntArray? = null
        val delays = mutableListOf<Int>()
        var finished = false
        while (!finished) {
            when (input.byte()) {
                0x3b -> finished = true
                0x21 -> when (input.byte()) {
                    0xf9 -> {
                        require(input.byte() == 4)
                        val control = input.byte()
                        disposal = control ushr 2 and 7
                        delay = input.word() * 10
                        val index = input.byte(); transparent = if (control and 1 != 0) index else -1
                        require(input.byte() == 0)
                    }
                    else -> input.blocks()
                }
                0x2c -> {
                    require(frames < 16) { "GIF超过16帧，未保存未验证的动画。" }
                    if (previousDisposal == 2) {
                        val (left, top, w, h) = previousRect
                        val fill = global?.getOrNull(background) ?: 0
                        for (row in top until top + h) java.util.Arrays.fill(canvas, row * width + left, row * width + left + w, fill)
                    } else if (previousDisposal == 3 && restore != null) restore.copyInto(canvas)
                    val left = input.word(); val top = input.word()
                    val w = input.word(); val h = input.word(); val descriptor = input.byte()
                    require(w > 0 && h > 0 && left + w <= width && top + h <= height) { "GIF画面范围无效。" }
                    decodedPixels += w.toLong() * h
                    require(decodedPixels <= 32L * 1024 * 1024) { "GIF解码体积过大。" }
                    val palette = if (descriptor and 0x80 != 0) input.palette(1 shl ((descriptor and 7) + 1)) else global
                    require(palette != null) { "GIF缺少颜色表。" }
                    val minimum = input.byte()
                    val indices = decodeLzw(input.blocks(), minimum, w * h)
                    restore = if (disposal == 3) canvas.copyOf() else null
                    val rows = if (descriptor and 0x40 == 0) (0 until h).toList() else buildList {
                        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) for (row in start until h step step) add(row)
                    }
                    var source = 0
                    for (row in rows) repeat(w) { column ->
                        val index = indices[source++].toInt() and 255
                        if (index != transparent) {
                            require(index < palette.size)
                            canvas[(top + row) * width + left + column] = palette[index]
                        }
                    }
                    if (firstFrame == null) firstFrame = canvas.copyOf()
                    else if (!canvas.contentEquals(firstFrame)) changed = true
                    frames++; delays += delay
                    previousDisposal = disposal; previousRect = intArrayOf(left, top, w, h)
                    disposal = 0; delay = 0; transparent = -1
                }
                else -> throw IllegalArgumentException("GIF结构损坏或数据不完整。")
            }
        }
        require(frames >= 2 && changed) { "接口返回的是静态图或重复画面，未生成有效的GIF动作。" }
        return GifAnimationInfo(width, height, frames, delays)
    }

    private fun decodeLzw(data: ByteArray, minimum: Int, expected: Int): ByteArray {
        require(minimum in 2..8) { "GIF压缩参数无效。" }
        val clear = 1 shl minimum
        val end = clear + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096) { if (it < clear) it else 0 }
        val stack = IntArray(4097)
        val result = ByteArray(expected)
        var bit = 0
        var codeSize = minimum + 1
        var available = end + 1
        var previous = -1
        var first = 0
        var output = 0
        var sawEnd = false
        while (bit + codeSize <= data.size * 8) {
            var code = 0
            repeat(codeSize) { shift -> code = code or (((data[(bit + shift) ushr 3].toInt() ushr ((bit + shift) and 7)) and 1) shl shift) }
            bit += codeSize
            if (code == clear) { codeSize = minimum + 1; available = end + 1; previous = -1; continue }
            if (code == end) { sawEnd = true; break }
            require(code <= available && code < 4096) { "GIF压缩数据损坏。" }
            val current = code
            var top = 0
            if (code == available) {
                require(previous >= 0)
                stack[top++] = first
                code = previous
            }
            var depth = 0
            while (code >= clear) {
                require(code < available && ++depth <= 4096)
                stack[top++] = suffix[code]
                code = prefix[code]
            }
            first = suffix[code]
            stack[top++] = first
            while (top > 0) { require(output < expected); result[output++] = stack[--top].toByte() }
            if (previous >= 0 && available < 4096) {
                prefix[available] = previous; suffix[available] = first; available++
                if (available == 1 shl codeSize && codeSize < 12) codeSize++
            }
            previous = current
        }
        require(output == expected && sawEnd) { "GIF帧数据不完整。" }
        return result
    }

    private class Cursor(private val bytes: ByteArray) {
        private var offset = 0
        fun byte(): Int { require(offset < bytes.size) { "GIF数据不完整。" }; return bytes[offset++].toInt() and 255 }
        fun word(): Int = byte() or (byte() shl 8)
        fun text(length: Int) = ByteArray(length) { byte().toByte() }.toString(Charsets.US_ASCII)
        fun palette(count: Int) = IntArray(count) { 0xff000000.toInt() or (byte() shl 16) or (byte() shl 8) or byte() }
        fun blocks(): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val length = byte()
                if (length == 0) break
                require(offset + length <= bytes.size)
                output.write(bytes, offset, length); offset += length
            }
            return output.toByteArray()
        }
    }
}
