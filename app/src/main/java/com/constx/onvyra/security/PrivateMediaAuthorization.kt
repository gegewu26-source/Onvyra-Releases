package com.constx.onvyra.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Grants are encrypted by this installation's Keystore key and excluded from backup/export. */
internal class PrivateMediaAuthorization private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val store = AtomicFile(File(appContext.noBackupFilesDir, "private-media-access.v1"))
    private val codebook = appContext.assets.open("security/private_media_access.json").bufferedReader().use {
        PrivateMediaCodebook.parse(it.readText())
    }
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var code: String? = null
    private var failures = 0
    private var retryAt = 0L
    private var lastPersisted = 0L
    private var storedHighWater = codebook.anchorMillis
    private val clock: PrivateMediaClock

    init {
        readRecord()
        clock = PrivateMediaClock(storedHighWater)
    }

    data class Result(val successful: Boolean, val message: String)

    @Synchronized
    fun hasAccess(): Boolean {
        if (codebook.periodAt(System.currentTimeMillis()) == null) return false
        val now = clock.read(System.currentTimeMillis(), SystemClock.elapsedRealtime()) ?: return false
        val accepted = code?.let { codebook.matches(it, now) } ?: false
        if (code != null && !accepted) {
            code = null
            persist(now)
        } else if (now - lastPersisted >= 300_000L && code != null) {
            persist(now)
        }
        return accepted
    }

    @Synchronized
    fun expiresAt(): Long? = if (hasAccess()) codebook.expiresAt(clock.highWaterMillis) else null

    @Synchronized
    fun unlock(rawCode: String): Result {
        AppIntegrity.requireTrusted()
        if (codebook.periodAt(System.currentTimeMillis()) == null)
            return Result(false, "设备时间异常或校验表已到期，请校准时间并更新应用。")
        val now = clock.read(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            ?: return Result(false, "设备时间异常，请校准时间后重试。")
        if (now < retryAt) {
            val seconds = ((retryAt - now + 999) / 1000).coerceAtLeast(1)
            return Result(false, "尝试过于频繁，请在 $seconds 秒后重试。")
        }
        AppIntegrity.requireFreshTrust(appContext)
        if (!codebook.matches(rawCode, now)) {
            failures = (failures + 1).coerceAtMost(100)
            if (failures >= 5) {
                val multiplier = 1L shl ((failures - 5) / 5).coerceAtMost(6)
                retryAt = now + (30_000L * multiplier).coerceAtMost(1_800_000L)
            }
            // Do not allow an invalid entry to discard an existing, valid authorization.
            if (!persist(now)) return Result(false, "本地安全存储不可用，请稍后重试。")
            return Result(false, "口令无效或已过期。")
        }
        val previous = code
        code = PrivateMediaCodebook.normalize(rawCode)
        failures = 0
        retryAt = 0L
        if (!persist(now)) {
            code = previous
            return Result(false, "本地安全存储不可用，未开启功能。")
        }
        return Result(true, "已开启，将在本期口令到期时自动关闭。")
    }

    @Synchronized
    fun lock(): Boolean {
        code = null
        val saved = persist(clock.highWaterMillis)
        if (!saved) {
            // Invalidate any previously encrypted grant even if a storage write failed.
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            runCatching { store.delete() }
        }
        return saved
    }

    private fun encryptionKey(create: Boolean): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Missing installation key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun readRecord() {
        runCatching {
            val encrypted = store.openRead().use { input ->
                val buffer = ByteArray(8_193)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                check(size in 29..8_192)
                buffer.copyOf(size)
            }
            check(encrypted[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(false), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
            cipher.updateAAD(AAD)
            val record = Json.parseToJsonElement(cipher.doFinal(encrypted, 13, encrypted.size - 13).toString(Charsets.UTF_8)) as JsonObject
            code = (record["code"] as? JsonPrimitive)?.contentOrNull?.let(PrivateMediaCodebook::normalize)
            failures = ((record["failures"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 100)
            retryAt = (record["retry_at"] as? JsonPrimitive)?.longOrNull ?: 0L
            storedHighWater = maxOf(codebook.anchorMillis, (record["high_water"] as? JsonPrimitive)?.longOrNull ?: 0L)
            lastPersisted = storedHighWater
        }.onFailure {
            // An absent, corrupt or transferred grant always starts locked. No secret is logged.
            code = null
            failures = 0
            retryAt = 0L
        }
    }

    private fun persist(now: Long): Boolean = runCatching {
        val record = buildJsonObject {
            code?.let { put("code", it) }
            put("failures", failures)
            put("retry_at", retryAt)
            put("high_water", maxOf(now, clock.highWaterMillis))
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(true))
        cipher.updateAAD(AAD)
        val data = byteArrayOf(1) + cipher.iv + cipher.doFinal(record)
        val output = store.startWrite()
        try {
            output.write(data)
            store.finishWrite(output)
        } catch (error: Exception) {
            store.failWrite(output)
            throw error
        }
        lastPersisted = now
        true
    }.getOrDefault(false)

    companion object {
        private const val KEY_ALIAS = "onvyra.private-media.access.v1"
        private val AAD = "com.constx.onvyra:private-media:v1".toByteArray(Charsets.UTF_8)
        @Volatile private var instance: PrivateMediaAuthorization? = null

        fun get(context: Context): PrivateMediaAuthorization = instance ?: synchronized(this) {
            instance ?: PrivateMediaAuthorization(context).also { instance = it }
        }

        fun initialize(context: Context) {
            // Initialization failure never grants access or stops the rest of the app starting.
            val appContext = context.applicationContext
            runCatching { get(appContext) }
            // Resolve the cached singleton at call time. Capturing a failed first load as null
            // would keep the backend locked even if a later dialog successfully unlocks it.
            PrivateMediaAccess.installVerifier(RecoverableAccessVerifier(
                load = { get(appContext) },
                isAllowed = { it.hasAccess() },
            ))
        }
    }
}
