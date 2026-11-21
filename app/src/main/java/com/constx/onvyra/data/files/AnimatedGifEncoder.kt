package com.constx.onvyra.data.files

import java.io.ByteArrayOutputStream

internal data class GifPixels(val width: Int, val height: Int, val argb: IntArray)

/** Bounded RGB332 GIF89a writer. Emits real independent frames with an infinite loop. */
internal object AnimatedGifEncoder {
    fun encode(frames: List<GifPixels>, frameDurationMs: Int): ByteArray {
        require(frames.size in 2..16) { "GIF需要2至16个真实画面。" }
        val width = frames.first().width
        val height = frames.first().height
        require(width in 1..512 && height in 1..512) { "GIF每帧最大为512像素。" }
        require(frameDurationMs in 20..2000) { "GIF帧间隔应为20至2000毫秒。" }
        require(frames.all { it.width == width && it.height == height && it.argb.size == width * height })
        val indexed = frames.map { frame -> ByteArray(frame.argb.size) { paletteIndex(frame.argb[it]).toByte() } }
        require(indexed.drop(1).any { !it.contentEquals(indexed.first()) }) {
            "模型返回的分镜没有可区分的动作变化，未生成静态重复帧GIF；请补充具体动作后重试。"
        }
        val output = ByteArrayOutputStream()
        fun word(value: Int) { output.write(value and 255); output.write(value ushr 8 and 255) }
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height)
        output.write(0xf7); output.write(0); output.write(0)
        repeat(256) { index ->
            output.write(((index ushr 5) and 7) * 255 / 7)
            output.write(((index ushr 2) and 7) * 255 / 7)
            output.write((index and 3) * 255 / 3)
        }
        output.write(byteArrayOf(0x21, 0xff.toByte(), 11))
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(3, 1, 0, 0, 0))
        indexed.forEach { pixels ->
            output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 8)) // restore background, opaque
            word(((frameDurationMs + 5) / 10).coerceAtLeast(2))
            output.write(byteArrayOf(0, 0, 0x2c))
            word(0); word(0); word(width); word(height); output.write(0)
            output.write(8)
            // Clear before the dictionary reaches 512 entries, keeping all codes at nine bits.
            // This avoids mutable global dictionaries and bounds both CPU and memory per frame.
            val compressed = ByteArrayOutputStream()
            var bits = 0
            var bitCount = 0
            fun code(value: Int) {
                bits = bits or (value shl bitCount)
                bitCount += 9
                while (bitCount >= 8) {
                    compressed.write(bits and 255)
                    bits = bits ushr 8
                    bitCount -= 8
                }
            }
            var offset = 0
            while (offset < pixels.size) {
                code(256)
                val end = minOf(offset + 200, pixels.size)
                while (offset < end) code(pixels[offset++].toInt() and 255)
            }
            code(257)
            if (bitCount > 0) compressed.write(bits and 255)
            val bytes = compressed.toByteArray()
            var position = 0
            while (position < bytes.size) {
                val length = minOf(255, bytes.size - position)
                output.write(length); output.write(bytes, position, length); position += length
            }
            output.write(0)
        }
        output.write(0x3b)
        return output.toByteArray()
    }

    private fun paletteIndex(argb: Int): Int {
        val alpha = argb ushr 24 and 255
        fun overWhite(shift: Int) = (((argb ushr shift and 255) * alpha + 255 * (255 - alpha)) / 255)
        val red = overWhite(16)
        val green = overWhite(8)
        val blue = overWhite(0)
        return (red and 0xe0) or ((green and 0xe0) ushr 3) or (blue ushr 6)
    }
}
