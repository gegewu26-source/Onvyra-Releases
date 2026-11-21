package com.constx.onvyra.data.files

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream
import org.junit.Assert.*
import org.junit.Test

class AnimatedGifEncoderTest {
    @Test fun `independent decoder reads every changing frame and its delay`() {
        val frames = listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt())
            .map { color -> GifPixels(32, 24, IntArray(32 * 24) { color }) }
        val bytes = AnimatedGifEncoder.encode(frames, 125)
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            reader.input = stream
            assertEquals(4, reader.getNumImages(true))
            frames.forEachIndexed { index, frame ->
                val decoded = reader.read(index)
                assertEquals(32, decoded.width); assertEquals(24, decoded.height)
                assertEquals(frame.argb.first(), decoded.getRGB(15, 10))
                val tree = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                assertEquals("13", control.getAttribute("delayTime"))
            }
        }
        reader.dispose()
    }

    @Test fun `dictionary reset boundaries preserve all decoded pixels`() {
        val width = 96
        val colors = intArrayOf(0xff000000.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
        val frames = List(8) { frame -> GifPixels(width, 32, IntArray(width * 32) { colors[(it + frame) % 4] }) }
        val bytes = AnimatedGifEncoder.encode(frames, 200)
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            reader.input = stream
            assertEquals(8, reader.getNumImages(true))
            frames.forEachIndexed { index, expected ->
                val decoded = reader.read(index)
                repeat(expected.argb.size) { pixel ->
                    assertEquals(expected.argb[pixel], decoded.getRGB(pixel % width, pixel / width))
                }
            }
        }
        reader.dispose()
    }

    @Test fun `duplicate frames and excessive memory requests cannot masquerade as animation`() {
        val frame = GifPixels(2, 2, IntArray(4) { 0xff000000.toInt() })
        assertThrows(IllegalArgumentException::class.java) { AnimatedGifEncoder.encode(listOf(frame, frame.copy()), 100) }
        assertThrows(IllegalArgumentException::class.java) { AnimatedGifEncoder.encode(List(17) { frame }, 100) }
        assertThrows(IllegalArgumentException::class.java) { AnimatedGifEncoder.encode(listOf(frame), 100) }
        assertThrows(IllegalArgumentException::class.java) { AnimatedGifEncoder.encode(listOf(frame, frame), 10) }
    }
}
