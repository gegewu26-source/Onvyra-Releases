package me.rerere.ai.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

class ImageGenerationDataException(message: String) : IllegalArgumentException(message)

/** Normalize a provider image value without logging it or adding a second data-URI header. */
fun normalizeGeneratedImageResult(value: String, fallbackMime: String = "image/png", complete: Boolean = true): String? {
    val input = value.trim()
    if (input.isEmpty()) return null
    if (input.startsWith("https://", true) || input.startsWith("http://", true)) {
        val url = input.toHttpUrlOrNull()
            ?: throw ImageGenerationDataException("图片接口返回了无效的图片地址。")
        if (url.username.isNotEmpty() || url.password.isNotEmpty())
            throw ImageGenerationDataException("图片接口返回的图片地址包含不支持的认证信息。")
        return url.toString()
    }
    var mime = fallbackMime.substringBefore(';').lowercase()
    val data = if (input.startsWith("data:", true)) {
        val header = input.substringBefore(',', "")
        if (!header.startsWith("data:image/", true) || !header.endsWith(";base64", true))
            throw ImageGenerationDataException("图片接口返回了不支持的图片编码。")
        mime = header.substringAfter(':').substringBefore(';').lowercase()
        input.substringAfter(',', "")
    } else input
    val normalized = data.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/')
    if (normalized.isEmpty()) return null
    if (!mime.startsWith("image/") || normalized.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it !in "+/=" })
        throw ImageGenerationDataException("图片接口返回的图片数据格式无效。")
    var encoded = normalized
    if (complete) {
        val bytes = try { Base64.getDecoder().decode(normalized) }
        catch (_: IllegalArgumentException) { throw ImageGenerationDataException("图片接口返回的图片数据不完整或编码无效。") }
        if (bytes.isEmpty()) return null
        // Kotlin's default decoder used by file persistence requires ordinary
        // alphabet/padding even if a gateway returned URL-safe unpadded input.
        encoded = Base64.getEncoder().encodeToString(bytes)
        mime = when {
            bytes.size >= 6 && bytes.copyOfRange(0, 6).decodeToString() in setOf("GIF87a", "GIF89a") -> "image/gif"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            else -> mime
        }
    }
    return "data:$mime;base64,$encoded"
}
