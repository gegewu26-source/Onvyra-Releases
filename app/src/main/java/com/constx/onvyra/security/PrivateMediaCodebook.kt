package com.constx.onvyra.security

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Contains only one-way verifiers. The secret which generates codes never ships with the app. */
internal class PrivateMediaCodebook private constructor(
    val anchorMillis: Long,
    val periodMillis: Long,
    private val salt: String,
    private val hashes: List<ByteArray>,
) {
    fun periodAt(now: Long): Int? {
        if (now < anchorMillis) return null
        val period = (now - anchorMillis) / periodMillis
        return period.takeIf { it < hashes.size }?.toInt()
    }

    fun expiresAt(now: Long): Long? = periodAt(now)?.let { anchorMillis + (it + 1L) * periodMillis }

    fun matches(rawCode: String, now: Long): Boolean {
        val index = periodAt(now) ?: return false
        val code = normalize(rawCode) ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "onvyra-private-media-v1\n$index\n$salt\n$code".toByteArray(Charsets.UTF_8),
        )
        return MessageDigest.isEqual(hashes[index], digest)
    }

    companion object {
        const val PERIOD_MILLIS = 15L * 24 * 60 * 60 * 1000
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun normalize(raw: String): String? {
            if (raw.length > 64) return null
            val code = raw.filterNot { it == '-' || it == ' ' || it == '\n' || it == '\r' || it == '\t' }
                .uppercase(Locale.ROOT)
            return code.takeIf { it.length == 16 && it.all { char -> char in ALPHABET } }
        }

        fun parse(text: String): PrivateMediaCodebook {
            require(text.length <= 64 * 1024)
            val root = Json.parseToJsonElement(text) as JsonObject
            require(root["version"]?.jsonPrimitive?.long == 1L)
            val anchor = root.getValue("anchor_ms").jsonPrimitive.long
            val period = root.getValue("period_ms").jsonPrimitive.long
            require(anchor > 0 && anchor < Long.MAX_VALUE - PERIOD_MILLIS * 512)
            require(period == PERIOD_MILLIS)
            val salt = (root.getValue("salt") as JsonPrimitive).content
            require(salt.length in 32..64 && salt.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
            val hashes = root.getValue("hashes").jsonArray.map {
                Base64.getUrlDecoder().decode(it.jsonPrimitive.content).also { bytes -> require(bytes.size == 32) }
            }
            require(hashes.size in 1..512)
            return PrivateMediaCodebook(anchor, period, salt, hashes)
        }
    }
}

/** Uses elapsed time within a process and an encrypted high-water mark across restarts. */
internal class PrivateMediaClock(initialHighWater: Long) {
    var highWaterMillis: Long = initialHighWater
        private set
    private var baselineWall = 0L
    private var baselineElapsed = 0L

    @Synchronized
    fun read(wallMillis: Long, elapsedMillis: Long): Long? {
        if (wallMillis < highWaterMillis - 120_000L) return null
        val projected = if (baselineWall > 0 && elapsedMillis >= baselineElapsed) {
            baselineWall + elapsedMillis - baselineElapsed
        } else wallMillis
        if (wallMillis < projected - 120_000L) return null
        val now = maxOf(wallMillis, projected, highWaterMillis)
        baselineWall = now
        baselineElapsed = elapsedMillis
        highWaterMillis = now
        return now
    }
}
