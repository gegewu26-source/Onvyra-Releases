package com.constx.onvyra.data.netdisk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Account material stays in noBackupFilesDir, encrypted with this installation's Keystore key. */
internal class NetDiskCredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "netdisk-credentials-v1.json"))
    private val alias = "onvyra.netdisk.credentials.v1"
    private var cache: MutableMap<String, String>? = null

    @Synchronized fun get(platform: NetDiskPlatform): String = load()[platform.name].orEmpty()

    @Synchronized fun put(platform: NetDiskPlatform, value: String) {
        val normalized = if (value.trim().startsWith('{')) try { Json.parseToJsonElement(value).jsonObject.toString() }
            catch (_: Exception) { throw IllegalArgumentException("登录信息 JSON 格式不正确。") } else value.trim()
        require(normalized.length <= 65_536 && normalized.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "凭据包含换行或长度超过限制，请粘贴完整的 Cookie / Token 值。"
        }
        // Saving fresh credentials is also the explicit recovery path after an
        // installation's Keystore entry was lost; no readable entries are discarded.
        val updated = try { load().toMutableMap() } catch (e: NetDiskException) {
            if (e.reason != "credential_unavailable") throw e
            mutableMapOf()
        }
        if (normalized.isBlank()) updated.remove(platform.name) else updated[platform.name] = normalized
        val plain = JsonObject(updated.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = JsonObject(mapOf("iv" to JsonPrimitive(Base64.encodeToString(cipher.iv, Base64.NO_WRAP)),
            "data" to JsonPrimitive(Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)))).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(encoded); file.finishWrite(stream); cache = updated }
        catch (e: Exception) { file.failWrite(stream); throw e }
        finally { plain.fill(0) }
    }

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        if (!file.baseFile.exists()) return mutableMapOf<String, String>().also { cache = it }
        try {
            val stored = Json.parseToJsonElement(file.readFully().toString(Charsets.UTF_8)).jsonObject
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,
                Base64.decode(stored.getValue("iv").jsonPrimitive.content, Base64.NO_WRAP)))
            val bytes = cipher.doFinal(Base64.decode(stored.getValue("data").jsonPrimitive.content, Base64.NO_WRAP))
            return try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.mapNotNull { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { k to it }
            }.toMap().toMutableMap().also { cache = it } } finally { bytes.fill(0) }
        } catch (_: Exception) {
            throw NetDiskException("credential_unavailable", "本机保存的网盘登录信息无法读取，请重新配置登录信息。")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
