package com.constx.onvyra.data.files

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream
import org.junit.Assert.*
import org.junit.Test

class GifAnimationVerifierTest {
    @Test fun `verifier decodes generated animation rather than trusting gif header`() {
        val bytes = AnimatedGifEncoder.encode(listOf(
            GifPixels(16, 16, IntArray(256) { 0xffff0000.toInt() }),
            GifPixels(16, 16, IntArray(256) { 0xff00ff00.toInt() }),
        ), 200)
        val info = GifAnimationVerifier.verify(bytes)
        assertEquals(2, info.frames); assertEquals(16, info.width)
        assertEquals(listOf(200, 200), info.delaysMs)
        assertThrows(IllegalArgumentException::class.java) { GifAnimationVerifier.verify(bytes.copyOf(40)) }
        assertThrows(IllegalArgumentException::class.java) { GifAnimationVerifier.verify("GIF89a".toByteArray()) }
    }

    @Test fun `independent imageio animations with compressed dictionaries and local palettes decode`() {
        val frames = List(4) { index -> BufferedImage(40, 32, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width)
                setRGB(x, y, if ((x + index * 4) % 20 < 8) 0xff00ff00.toInt() else 0xff000000.toInt())
        } }
        assertEquals(4, GifAnimationVerifier.verify(sequence(frames)).frames)
    }

    @Test fun `single frame or several identically displayed frames are rejected`() {
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
        assertThrows(IllegalArgumentException::class.java) { GifAnimationVerifier.verify(sequence(listOf(image))) }
        assertThrows(IllegalArgumentException::class.java) { GifAnimationVerifier.verify(sequence(listOf(image, image, image))) }
    }

    private fun sequence(images: List<BufferedImage>): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        MemoryCacheImageOutputStream(output).use { stream ->
            writer.output = stream; writer.prepareWriteSequence(null)
            images.forEach { writer.writeToSequence(IIOImage(it, null, null), null) }
            writer.endWriteSequence(); stream.flush()
        }
        writer.dispose()
        return output.toByteArray()
    }
}
