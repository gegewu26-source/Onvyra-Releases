package com.constx.onvyra.data.files

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class OriginalImageData(val bytes: ByteArray, val mimeType: String) {
    val extension: String get() = imageFileExtension(mimeType)
}

/** Store encoded image bytes unchanged: decoding an animated image as a Bitmap discards its other frames. */
@OptIn(ExperimentalEncodingApi::class)
internal fun decodeOriginalImage(dataUri: String): OriginalImageData {
    val bytes = Base64.decode(dataUri.substringAfter("base64,"))
    val declaredMime = dataUri.substringAfter("data:").substringBefore(';').lowercase()
    val isGif = bytes.size >= 6 && bytes.copyOfRange(0, 6).decodeToString() in setOf("GIF87a", "GIF89a")
    return OriginalImageData(bytes, if (isGif) "image/gif" else declaredMime)
}

internal fun imageFileExtension(mimeType: String): String = when (mimeType.substringBefore(';').lowercase()) {
    "image/gif" -> "gif"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/svg+xml" -> "svg"
    "image/avif" -> "avif"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/bmp" -> "bmp"
    "image/tiff" -> "tiff"
    else -> "png"
}
