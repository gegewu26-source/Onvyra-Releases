// Adapted from Mika (wangzan101/Mika), revision 688f59d5a18d99d0231504598da4059f3feb226e. Apache-2.0.
package me.rerere.androidcontrol.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.net.SocketTimeoutException
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** adbd 通过 mDNS 公布的服务类型。 */
object AdbServiceTypes {
    /** 「使用配对码配对」对话框开着时才公布。 */
    const val PAIRING = "_adb-tls-pairing._tcp"
    /** 无线调试开着时公布;端口每次开关无线调试都会变。 */
    const val CONNECT = "_adb-tls-connect._tcp"
}

/** 在本机网络上找 adbd 公布的服务端口;找不到(超时)返回 null。 */
/**
 * 找本机 adbd 公布的服务端口。同一服务可能查到好几条(关了又开无线调试后,旧的那条可能还留在 mDNS 里,新的改名带上序号),
 * 全部交出,先查到的在前,由调用方挨个试。
 */
fun interface AdbServiceFinder {
    suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int>
}

class AdbPairingException(val reason: Reason, message: String, cause: Throwable? = null,
                          val stage: Stage? = null) : IOException(message, cause) {
    enum class Reason { CONNECT_FAILED, TLS_FAILED, WRONG_CODE, PROTOCOL, TIMEOUT, CLOSED, EXPORT_FAILED }
    enum class Stage { CONNECT, TLS, EXPORT, SPAKE2, PEER_INFO }
}

/** 配对成功时设备回报的身份(通常是 type=1 的设备 GUID)。 */
class AdbPeerInfo(val type: Int, val data: String)

/**
 * 无线调试的配对码配对(客户端)。流程:
 * TLS 1.3 → 导出 64 字节密钥("adb-label\0")→ 口令 = 配对码 + 导出密钥 → 交换 SPAKE2 消息 →
 * 用协商出的 AES-GCM 互换 8192 字节的 PeerInfo(我们发 adb 公钥,设备回 GUID)。
 * 每个包前有 6 字节头:版本(1)、类型(0=SPAKE2,1=PEER_INFO)、负载长度(大端 uint32)。
 */
class AdbPairing(private val tls: AdbTls) {
    fun pair(host: String, port: Int, code: String, timeoutMs: Int = 15_000): AdbPeerInfo {
        val socket = try {
            tls.connect(host, port, timeoutMs)
        } catch (e: SocketTimeoutException) {
            throw AdbPairingException(AdbPairingException.Reason.TIMEOUT, "pairing connection timed out", e,
                if (e is AdbTlsHandshakeTimeoutException) AdbPairingException.Stage.TLS else AdbPairingException.Stage.CONNECT)
        } catch (e: SSLException) {
            throw AdbPairingException(AdbPairingException.Reason.TLS_FAILED, "TLS handshake failed", e, AdbPairingException.Stage.TLS)
        } catch (e: IOException) {
            throw AdbPairingException(AdbPairingException.Reason.CONNECT_FAILED, "cannot connect to pairing service", e, AdbPairingException.Stage.CONNECT)
        }
        socket.use { return exchange(it, code) }
    }

    private fun exchange(socket: SSLSocket, code: String): AdbPeerInfo {
        val exported = try { tls.exportKeyingMaterial(socket, EXPORT_LABEL, EXPORT_LENGTH) }
        catch (e: Exception) {
            throw AdbPairingException(AdbPairingException.Reason.EXPORT_FAILED, "TLS exporter failed", e, AdbPairingException.Stage.EXPORT)
        }
        val auth = PairingAuth.client(code.toByteArray(Charsets.UTF_8) + exported)
        val input = DataInputStream(socket.inputStream)
        val output = socket.outputStream

        val theirMsg = pairingIo(AdbPairingException.Stage.SPAKE2) {
            writePacket(output, TYPE_SPAKE2, auth.message)
            readPacket(input, TYPE_SPAKE2) ?: throw EOFException("connection closed before key exchange")
        }
        try {
            auth.initCipher(theirMsg)
        } catch (e: IllegalArgumentException) {
            throw protocol("bad key exchange message: ${e.message}")
        }

        val info = ByteArray(PEER_INFO_SIZE)
        info[0] = PEER_RSA_PUB_KEY
        val key = tls.identity.adbPublicKey.toByteArray(Charsets.UTF_8)
        check(key.size < PEER_INFO_SIZE - 1)
        System.arraycopy(key, 0, info, 1, key.size)
        val sealed = pairingIo(AdbPairingException.Stage.PEER_INFO) {
            writePacket(output, TYPE_PEER_INFO, auth.encrypt(info))
            readPacket(input, TYPE_PEER_INFO) ?: throw EOFException("connection closed during peer authentication")
        }
        val plain = try {
            auth.decrypt(sealed)
        } catch (e: AEADBadTagException) {
            throw AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "peer authentication failed", e, AdbPairingException.Stage.PEER_INFO)
        }
        if (plain.size != PEER_INFO_SIZE) throw protocol("peer info has ${plain.size} bytes")
        var end = 1
        while (end < plain.size && plain[end] != 0.toByte()) end++
        return AdbPeerInfo(plain[0].toInt() and 0xff, String(plain, 1, end - 1, Charsets.UTF_8))
    }

    private fun protocol(msg: String) = AdbPairingException(AdbPairingException.Reason.PROTOCOL, msg)

    companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_LENGTH = 64
        const val PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_DEVICE_GUID: Byte = 1

        fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
            val header = ByteBuffer.allocate(6).put(VERSION).put(type).putInt(payload.size).array()
            out.write(header + payload)
            out.flush()
        }

        /** 读一个包并核对类型;对方在包头之前断开返回 null。 */
        fun readPacket(input: DataInputStream, expectedType: Byte): ByteArray? {
            val header = ByteArray(6)
            try {
                input.readFully(header)
            } catch (e: EOFException) {
                return null
            }
            val bb = ByteBuffer.wrap(header)
            val version = bb.get()
            val type = bb.get()
            val len = bb.getInt()
            if (version != VERSION) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unsupported pairing version $version")
            if (type != expectedType) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "unexpected packet type $type")
            if (len <= 0 || len > MAX_PAYLOAD) throw AdbPairingException(AdbPairingException.Reason.PROTOCOL, "bad payload size $len")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}

/** Transport failure is not evidence that the six-digit password was wrong. */
internal fun pairingIoFailure(stage: AdbPairingException.Stage, error: IOException): AdbPairingException {
    if (error is AdbPairingException) return error
    val reason = when (error) {
        is SocketTimeoutException -> AdbPairingException.Reason.TIMEOUT
        is SSLException -> AdbPairingException.Reason.TLS_FAILED
        else -> AdbPairingException.Reason.CLOSED
    }
    return AdbPairingException(reason, "pairing transport failed", error, stage)
}

private inline fun <T> pairingIo(stage: AdbPairingException.Stage, action: () -> T): T =
    try { action() } catch (failure: IOException) { throw pairingIoFailure(stage, failure) }
