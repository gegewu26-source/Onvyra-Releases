// Adapted from Mika (wangzan101/Mika), revision 688f59d5a18d99d0231504598da4059f3feb226e. Apache-2.0.
package me.rerere.androidcontrol.adb

import android.content.Context
import android.net.ssl.SSLSockets
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import me.rerere.androidcontrol.adb.AdbIdentity
import me.rerere.androidcontrol.adb.AdbKeyStore
import me.rerere.androidcontrol.adb.AdbTls
import me.rerere.androidcontrol.adb.KeyingMaterialExporter
import me.rerere.androidcontrol.adb.WrappedKeyFile
import java.io.File
import java.net.Socket
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.net.ssl.SSLException

/** Android 上的 adb 身份与 TLS:钥匙用 Keystore 里的 AES 密钥包装后放在 App 私有目录;导出密钥走平台 TLS。 */
object AndroidAdb {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "onvyra_android_control_adb_wrap"

    // Match Android's native ADB exporter: no application context.
    val exporter = KeyingMaterialExporter { socket, label, length ->
        if (!SSLSockets.isSupportedSocket(socket)) throw SSLException("TLS exporter requires a platform socket")
        SSLSockets.exportKeyingMaterial(socket, label, null, length) ?: throw SSLException("keying material export failed")
    }

    fun keyStore(context: Context): AdbKeyStore =
        WrappedKeyFile(File(context.applicationContext.noBackupFilesDir, "adb/adbkey.bin")) { wrappingKey() }

    fun identity(context: Context): AdbIdentity = AdbIdentity.loadOrCreate(keyStore(context), "onvyra@${Build.MODEL}")

    @JvmOverloads
    fun tls(identity: AdbIdentity, onSocket: (Socket) -> Unit = {}) = AdbTls(identity, exporter, onSocket = onSocket)

    /** 删除钥匙(「解除配对」用);设备那边的授权记录要由用户在系统设置里撤销。 */
    fun forget(context: Context) {
        keyStore(context).clear()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(WRAP_ALIAS) }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
