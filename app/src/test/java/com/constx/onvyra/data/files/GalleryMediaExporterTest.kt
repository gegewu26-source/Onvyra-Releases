package com.constx.onvyra.data.files

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64

class GalleryMediaExporterTest {
    @Test fun aCompleteWriteIsPublishedOnlyAfterStoredByteCountIsVerified() {
        val events = mutableListOf<String>()
        val row = writeGalleryMediaTransaction(42, create = { events += "create-pending"; "row" },
            write = { events += "write-closed"; 42 }, readSize = { events += "verify-size"; 42 },
            publish = { events += "publish" }, delete = { events += "delete" })
        assertEquals("row", row)
        assertEquals(listOf("create-pending", "write-closed", "verify-size", "publish"), events)
    }

    @Test fun partialCopyRollsBackWithoutPublishing() {
        var published = false
        var deleted = false
        expectIOException {
            writeGalleryMediaTransaction(42, { "row" }, { 41 }, { 41 }, { published = true }, { deleted = true })
        }
        assertFalse(published)
        assertTrue(deleted)
    }

    @Test fun missingOrWrongStoredSizeRollsBackInsteadOfReportingSuccess() {
        for (stored in listOf(null, 0L, 41L)) {
            var deleted = false
            expectIOException {
                writeGalleryMediaTransaction(42, { "row" }, { 42 }, { stored }, { fail("must not publish") }, { deleted = true })
            }
            assertTrue(deleted)
        }
    }

    @Test fun streamOrPublishFailureCleansTheRowAndPreservesTheOriginalFailure() {
        for (failDuringWrite in listOf(true, false)) {
            val original = IOException("storage rejected the write")
            var deleted = false
            try {
                writeGalleryMediaTransaction(42, { "row" }, { if (failDuringWrite) throw original else 42 }, { 42 },
                    { throw original }, { deleted = true })
                fail("save should fail")
            } catch (failure: IOException) {
                assertSame(original, failure)
            }
            assertTrue(deleted)
        }
    }

    @Test fun cleanupFailureDoesNotHideTheActualStorageFailure() {
        val actual = IOException("write failed")
        val cleanup = IOException("delete failed")
        try {
            writeGalleryMediaTransaction(42, { "row" }, { throw actual }, { 42 }, {}, { throw cleanup })
            fail("save should fail")
        } catch (failure: IOException) {
            assertSame(actual, failure)
            assertArrayEquals(arrayOf(cleanup), failure.suppressed)
        }
    }

    @Test fun anInterruptedWriteIsNotPublishedAndItsPendingRowIsDeleted() {
        var deleted = false
        try {
            writeGalleryMediaTransaction(42, { "row" }, { throw InterruptedIOException("cancelled") }, { 42 },
                { fail("must not publish") }, { deleted = true })
            fail("save should fail")
        } catch (_: InterruptedIOException) {
            assertTrue(deleted)
        }
    }

    @Test fun originalAnimatedGifBytesAndRealMimeOverrideThePngLabelAndExtension() {
        val bytes = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAAAAAAALAAAAAABAAEAAAIBRAAh+QQAAAAAACwAAAAAAQABAAACAUQAOw==")
        withFile(".png", bytes) { file ->
            val media = describeGalleryMedia(file, "image/png", "../kitten.png")
            assertEquals("image/gif", media.mimeType)
            assertEquals("kitten.gif", media.displayName)
            assertEquals("Pictures/Onvyra", media.relativePath)
            assertFalse(media.isVideo)
            val output = ByteArrayOutputStream()
            assertEquals(bytes.size.toLong(), copyGalleryMediaBytes(ByteArrayInputStream(bytes), output))
            assertArrayEquals(bytes, output.toByteArray())
        }
    }

    @Test fun videoBytesUseTheVideoCollectionAndCorrectTheRequestedImageSuffix() {
        val bytes = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12)
        withFile(".tmp", bytes) { file ->
            val media = describeGalleryMedia(file, null, "teacup.png")
            assertEquals("video/mp4", media.mimeType)
            assertEquals("teacup.mp4", media.displayName)
            assertEquals("Movies/Onvyra", media.relativePath)
            assertTrue(media.isVideo)
        }
    }

    @Test fun emptyAndUnknownFilesAreRejectedBeforeCreatingAGalleryRecord() {
        for (bytes in listOf(byteArrayOf(), "not media".toByteArray())) {
            withFile(".tmp", bytes) { file ->
                try {
                    describeGalleryMedia(file, null, "test")
                    fail("invalid media should fail")
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun callerFileNamesCannotEscapeTheAlbumOrIntroduceControlCharacters() {
        withFile(".gif", "GIF89a-data".toByteArray()) { file ->
            val media = describeGalleryMedia(file, null, "C:\\other\\danger\nname.png")
            assertEquals("danger_name.gif", media.displayName)
            assertFalse(media.displayName.contains('/'))
            assertFalse(media.displayName.contains('\\'))
        }
    }

    @Test fun aPdfWithAnImageSuffixIsNotInsertedIntoThePhotoCollection() {
        withFile(".png", "%PDF-1.7 not an image".toByteArray()) { file ->
            try {
                describeGalleryMedia(file, "image/png", "landscape.png")
                fail("a document is not a gallery image")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun expectIOException(block: () -> Unit) {
        try { block(); fail("save should fail") } catch (_: IOException) { }
    }

    private fun withFile(extension: String, bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("gallery_export_test_", extension)
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }
}
