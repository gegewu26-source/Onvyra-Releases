package com.constx.onvyra.data.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class OriginalImageDataTest {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun originalGifBytesArePreservedEvenWhenTheDataUriClaimsPng() {
        // Two complete GIF frames; saving this through a Bitmap would retain only the first.
        val encoded = "R0lGODlhAQABAIAAAAAAAP///yH5BAAAAAAALAAAAAABAAEAAAIBRAAh+QQAAAAAACwAAAAAAQABAAACAUQAOw=="
        val image = decodeOriginalImage("data:image/png;base64,$encoded")
        assertArrayEquals(Base64.decode(encoded), image.bytes)
        assertEquals("image/gif", image.mimeType)
        assertEquals("gif", image.extension)
    }

    @Test
    fun otherImageOutputExtensionsArePreserved() {
        assertEquals("jpg", imageFileExtension("image/jpeg"))
        assertEquals("webp", imageFileExtension("image/webp; charset=binary"))
    }
}
