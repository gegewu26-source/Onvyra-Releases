package com.constx.onvyra.data.netdisk.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Source URLs/headers are installation-private, never exposed by the UI task model. */
@Serializable
internal data class StoredNetDiskDownload(
    val task: NetDiskDownloadTask,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val generation: Long = 0,
)

internal class NetDiskDownloadStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "netdisk-download-ledger-v1"))
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StoredNetDiskDownload.serializer())
    private val alias = "onvyra.netdisk.downloads.v1"

    @Synchronized fun read(): List<StoredNetDiskDownload> {
        if (!file.baseFile.exists()) return emptyList()
        val bytes = file.readFully()
        require(bytes.size in 30..MAX_BYTES && bytes[0] == 1.toByte()) { "下载记录无法读取，请保留本机数据后重试。" }
        val iv = bytes.copyOfRange(1, 13)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes, 13, bytes.size - 13)
        return try { json.decodeFromString(serializer, plain.toString(Charsets.UTF_8)) }
            finally { plain.fill(0) }
    }

    @Synchronized fun write(entries: List<StoredNetDiskDownload>) {
        val plain = json.encodeToString(serializer, entries).toByteArray(Charsets.UTF_8)
        require(plain.size < MAX_BYTES - 64) { "下载记录过多，请先移除已结束的任务记录。" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            val stream = file.startWrite()
            try { stream.write(encrypted); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        } finally { plain.fill(0) }
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

    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}
