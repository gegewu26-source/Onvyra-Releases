package com.constx.onvyra.data.files

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class GalleryExportResult(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val relativePath: String,
    val savedAtMillis: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
)

/** Only legacy Android requires storage permission for exporting app-owned media. */
class GalleryStoragePermissionRequiredException : SecurityException(
    "Storage permission is required to save media on Android 9 and earlier",
) {
    val permission: String = Manifest.permission.WRITE_EXTERNAL_STORAGE
}

/** Call on an IO thread. Does not need an Activity and never reports an unfinished write as saved. */
class GalleryMediaExporter(context: Context) {
    private val context = context.applicationContext

    fun export(file: File, mimeType: String? = null, displayName: String? = null): GalleryExportResult {
        require(file.isFile && file.canRead()) { "Media file is missing or unreadable" }
        val media = describeGalleryMedia(file, mimeType, displayName)
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportScoped(file, media)
            else exportLegacy(file, media)
        val dimensions = if (!media.isVideo) runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                BitmapFactory.decodeFile(file.absolutePath, it)
            }
        }.getOrNull() else null
        return result.copy(savedAtMillis = System.currentTimeMillis(),
            width = dimensions?.outWidth?.takeIf { it > 0 }, height = dimensions?.outHeight?.takeIf { it > 0 })
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exportScoped(file: File, media: GalleryMediaDescription): GalleryExportResult {
        val resolver = context.contentResolver
        val collection = if (media.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, media.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, media.relativePath + "/")
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = writeGalleryMediaTransaction(
            expectedBytes = media.sizeBytes,
            create = { resolver.insert(collection, values) ?: throw IOException("Unable to create gallery media") },
            write = { target ->
                val output = resolver.openOutputStream(target, "w") ?: throw IOException("Unable to open gallery output")
                output.use { destination -> file.inputStream().use { copyGalleryMediaBytes(it, destination) } }
            },
            readSize = { target ->
                resolver.openFileDescriptor(target, "r")?.use { it.statSize }?.takeIf { it >= 0 }
                    ?: resolver.query(target, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
                    }
            },
            publish = { target ->
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(target, published, null, null) != 1) throw IOException("Unable to publish gallery media")
            },
            delete = { if (resolver.delete(it, null, null) != 1) throw IOException("Unable to remove unfinished gallery media") },
        )
        val actualName = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: media.displayName
        return media.copy(displayName = actualName).result(uri)
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(file: File, media: GalleryMediaDescription): GalleryExportResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw GalleryStoragePermissionRequiredException()
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(
            if (media.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        ), "Onvyra")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Unable to create gallery directory")
        // A repeat export must never overwrite an existing user file.
        var target = File(directory, media.displayName)
        while (!target.createNewFile()) {
            target = File(directory, media.displayName.substringBeforeLast('.') + "_" + UUID.randomUUID().toString().take(8) + "." + media.displayName.substringAfterLast('.'))
        }
        try {
            target.outputStream().use { output ->
                val copied = file.inputStream().use { copyGalleryMediaBytes(it, output) }
                if (copied != media.sizeBytes) throw IOException("Media source changed while saving")
            }
            if (target.length() != media.sizeBytes) throw IOException("Gallery media size does not match")
            val scanned = scanLegacyFile(target, media.mimeType)
            return media.copy(displayName = target.name).result(scanned)
        } catch (failure: Throwable) {
            if (target.exists() && !target.delete()) failure.addSuppressed(IOException("Unable to remove unfinished gallery file"))
            throw failure
        }
    }

    private fun scanLegacyFile(file: File, mimeType: String): Uri {
        val finished = CountDownLatch(1)
        val result = AtomicReference<Uri?>()
        val abandoned = AtomicBoolean(false)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType)) { _, uri ->
            result.set(uri)
            finished.countDown()
            if (abandoned.get() && uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
        }
        try {
            if (!finished.await(15, TimeUnit.SECONDS)) throw IOException("Gallery indexing did not finish")
            return result.get() ?: throw IOException("Unable to index gallery media")
        } catch (failure: Throwable) {
            abandoned.set(true)
            result.get()?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            throw failure
        }
    }
}

internal data class GalleryMediaDescription(val displayName: String, val mimeType: String, val sizeBytes: Long) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val relativePath: String get() = (if (isVideo) "Movies" else "Pictures") + "/Onvyra"
    fun result(uri: Uri) = GalleryExportResult(uri, displayName, mimeType, sizeBytes, relativePath)
}

private val galleryExtensions = mapOf(
    "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
    "image/avif" to "avif", "image/heic" to "heic", "image/heif" to "heif", "image/bmp" to "bmp",
    "image/tiff" to "tiff", "image/svg+xml" to "svg", "video/mp4" to "mp4", "video/webm" to "webm",
    "video/quicktime" to "mov", "video/3gpp" to "3gp", "video/mpeg" to "mpeg", "video/x-matroska" to "mkv",
)

internal fun describeGalleryMedia(file: File, declaredMime: String?, requestedName: String?): GalleryMediaDescription {
    val size = file.length()
    require(size > 0) { "Media file is empty" }
    val header = file.inputStream().use { input -> ByteArray(64).let { bytes -> bytes.copyOf(input.read(bytes).coerceAtLeast(0)) } }
    fun bytes(vararg prefix: Int) = header.size >= prefix.size && prefix.indices.all { header[it].toInt().and(255) == prefix[it] }
    fun text(from: Int, until: Int) = if (header.size >= until) header.copyOfRange(from, until).toString(Charsets.US_ASCII) else ""
    val hint = declaredMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    val sniffed = when {
        bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        bytes(0xff, 0xd8, 0xff) -> "image/jpeg"
        text(0, 6) in setOf("GIF87a", "GIF89a") -> "image/gif"
        text(0, 4) == "RIFF" && text(8, 12) == "WEBP" -> "image/webp"
        bytes(0x42, 0x4d) -> "image/bmp"
        bytes(0x49, 0x49, 0x2a, 0) || bytes(0x4d, 0x4d, 0, 0x2a) -> "image/tiff"
        bytes(0x25, 0x50, 0x44, 0x46) -> "application/pdf"
        bytes(0x50, 0x4b, 0x03, 0x04) || bytes(0x50, 0x4b, 0x05, 0x06) -> "application/zip"
        text(4, 8) == "ftyp" -> when (text(8, 12)) {
            "avif", "avis" -> "image/avif"
            "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs" -> "image/heic"
            "mif1", "msf1", "heif" -> "image/heif"
            "qt  " -> "video/quicktime"
            "isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "M4V ", "MSNV", "dash" -> "video/mp4"
            else -> if (text(8, 12).startsWith("3g")) "video/3gpp" else null
        }
        bytes(0x1a, 0x45, 0xdf, 0xa3) -> if (hint == "video/x-matroska" || file.extension.equals("mkv", true)) "video/x-matroska" else "video/webm"
        bytes(0, 0, 1, 0xba) || bytes(0, 0, 1, 0xb3) -> "video/mpeg"
        else -> null
    }
    val extensionHint = file.extension.lowercase(Locale.ROOT)
    val mime = sniffed ?: hint?.takeIf { it in galleryExtensions }
        ?: galleryExtensions.entries.firstOrNull { (_, extension) -> extension == extensionHint }?.key
        ?: when (extensionHint) { "jpeg" -> "image/jpeg"; "tif" -> "image/tiff"; else -> null }
        ?: throw IllegalArgumentException("Unsupported gallery media format")
    val extension = galleryExtensions[mime] ?: throw IllegalArgumentException("Unsupported gallery media format")
    val sourceName = requestedName?.takeIf { it.isNotBlank() }
        ?: "Onvyra_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    val baseName = sourceName.substringAfterLast('/').substringAfterLast('\\')
        .substringBeforeLast('.', sourceName.substringAfterLast('/').substringAfterLast('\\'))
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.').take(120).ifEmpty { "Onvyra" }
    return GalleryMediaDescription("$baseName.$extension", mime, size)
}

/** IO-only transaction. Pending media is published only after both byte count and stored size match. */
internal fun <T> writeGalleryMediaTransaction(
    expectedBytes: Long,
    create: () -> T,
    write: (T) -> Long,
    readSize: (T) -> Long?,
    publish: (T) -> Unit,
    delete: (T) -> Unit,
): T {
    require(expectedBytes > 0) { "Media file is empty" }
    val item = create()
    try {
        if (write(item) != expectedBytes) throw IOException("Media source changed while saving")
        if (readSize(item) != expectedBytes) throw IOException("Gallery media size does not match")
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Gallery save was interrupted")
        publish(item)
        return item
    } catch (failure: Throwable) {
        try { delete(item) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}

internal fun copyGalleryMediaBytes(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var size = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Gallery save was interrupted")
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        size += read
    }
    output.flush()
    return size
}
