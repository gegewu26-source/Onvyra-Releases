package com.constx.onvyra.data.update

import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.*

internal data class UpdateChange(val type: String, val items: List<String>)
internal data class UpdateDownload(val abi: String, val url: String, val sha256: String, val sizeBytes: Long)
internal data class AppUpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    val title: String,
    val summary: String,
    val changes: List<UpdateChange>,
    val downloads: List<UpdateDownload>,
) {
    fun downloadFor(abis: List<String>): UpdateDownload? =
        abis.firstNotNullOfOrNull { abi -> downloads.firstOrNull { it.abi == abi } }
            ?: downloads.firstOrNull { it.abi == "universal" }
}

/** Signed bytes are verified before JSON is interpreted. The signing private key stays offline. */
internal class AppUpdateManifestVerifier(
    publicKeyBase64: String,
    private val allowedOrigin: String,
    private val applicationId: String = "com.constx.onvyra",
) {
    private val publicKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
    }

    fun verify(envelopeBytes: ByteArray): AppUpdateRelease {
        require(envelopeBytes.size in 1..MAX_ENVELOPE_BYTES) { "Invalid update document size" }
        val envelope = Json.parseToJsonElement(envelopeBytes.toString(Charsets.UTF_8)).jsonObject
        val payload = Base64.getDecoder().decode(envelope.text("payload_base64", 180_000))
        val signature = Base64.getDecoder().decode(envelope.text("signature_base64", 2_048))
        require(payload.size in 1..MAX_PAYLOAD_BYTES && signature.size in 256..1_024) { "Invalid update signature payload" }
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey); update(payload); verify(signature)
        }) { "Update signature is not trusted" }
        val value = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        require(value["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported update document" }
        require(value.text("applicationId", 100) == applicationId) { "Update belongs to another application" }
        val code = value["versionCode"]?.jsonPrimitive?.longOrNull ?: error("Missing version code")
        require(code in 1..Int.MAX_VALUE.toLong()) { "Invalid version code" }
        val published = value.text("publishedAt", 40)
        Instant.parse(published)
        val changes = value["changes"]?.jsonArray ?: error("Missing release notes")
        require(changes.size in 1..8) { "Invalid release notes" }
        val groups = changes.map { element ->
            val group = element.jsonObject
            val type = group.text("type", 20)
            require(type in setOf("feature", "fix", "improvement", "notice")) { "Invalid change category" }
            val items = group["items"]?.jsonArray ?: error("Missing change items")
            require(items.size in 1..24) { "Invalid change count" }
            UpdateChange(type, items.map { it.jsonPrimitive.let { item ->
                require(item.isString); item.content.trim().also { text -> require(text.isNotEmpty() && text.length <= 600) }
            } })
        }
        val downloads = value["downloads"]?.jsonArray ?: error("Missing update download")
        require(downloads.size in 1..8) { "Invalid download count" }
        val packages = downloads.map { element ->
            val download = element.jsonObject
            val abi = download.text("abi", 30)
            require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal"))
            val url = download.text("url", 2_048)
            requireTrustedUpdateUrl(url, allowedOrigin)
            val sha = download.text("sha256", 64).lowercase()
            require(sha.matches(Regex("[0-9a-f]{64}"))) { "Invalid package checksum" }
            val size = download["sizeBytes"]?.jsonPrimitive?.longOrNull ?: error("Missing package size")
            require(size in 1..1_500_000_000L) { "Invalid package size" }
            UpdateDownload(abi, url, sha, size)
        }
        require(packages.distinctBy { it.abi }.size == packages.size) { "Duplicate package ABI" }
        return AppUpdateRelease(code, value.text("versionName", 80), published,
            value.text("title", 120), value.text("summary", 800, allowBlank = true), groups, packages)
    }

    companion object {
        const val MAX_ENVELOPE_BYTES = 256 * 1_024
        const val MAX_PAYLOAD_BYTES = 128 * 1_024
    }
}

internal fun requireTrustedUpdateUrl(value: String, allowedOrigin: String) {
    val url = URI(value)
    val origin = URI(allowedOrigin)
    require(url.scheme == "https" && url.host?.lowercase() == origin.host?.lowercase() &&
        url.port == origin.port && url.userInfo == null && url.rawQuery == null && url.fragment == null &&
        url.rawPath.startsWith("/onvyra-updates/releases/") && url.rawPath.endsWith(".apk") &&
        '%' !in url.rawPath && '\\' !in url.rawPath &&
        url.rawPath.split('/').none { it == "." || it == ".." }) { "Update download URL is not trusted" }
}

private fun JsonObject.text(key: String, max: Int, allowBlank: Boolean = false): String {
    val field = this[key] as? JsonPrimitive ?: error("Missing update field")
    require(field.isString)
    return field.content.trim().also { require(it.length <= max && (allowBlank || it.isNotEmpty())) }
}

internal const val UPDATE_REMINDER_DELAY_MS = 24 * 60 * 60_000L
internal fun shouldCheckAutomaticUpdate(lastAttempt: Long, failed: Boolean, now: Long): Boolean =
    lastAttempt == 0L || now - lastAttempt !in 0 until (if (failed) 5 * 60_000L else 15 * 60_000L)

internal fun shouldShowAutomaticUpdate(
    releaseCode: Long, installedCode: Long, ignoredCode: Long, deferredCode: Long, deferredUntil: Long, now: Long,
): Boolean = releaseCode > installedCode && releaseCode != ignoredCode &&
    (releaseCode != deferredCode || now >= deferredUntil)
