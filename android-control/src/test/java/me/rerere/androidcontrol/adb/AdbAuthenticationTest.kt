package me.rerere.androidcontrol.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import javax.crypto.AEADBadTagException

class AdbAuthenticationTest {
    @Test fun pairingCertificateUsesTheOnvyraIdentityWithoutChangingTheAdbKey() {
        val identity = AdbIdentity.generate("onvyra@Fixture")
        val certificate = identity.certificate
        assertEquals("CN=Onvyra", certificate.subjectX500Principal.name)
        assertEquals("CN=Onvyra", certificate.issuerX500Principal.name)
        assertArrayEquals(identity.publicKey.encoded, certificate.publicKey.encoded)
        certificate.verify(identity.publicKey)
        assertTrue(identity.adbPublicKey.endsWith(" onvyra@Fixture"))
    }

    @Test fun matchedPairingPasswordsExchangeAuthenticatedPeerInformation() {
        val client = PairingAuth.client("123456-tls-exporter".toByteArray(), ByteArray(64) { it.toByte() })
        val server = PairingAuth.server("123456-tls-exporter".toByteArray(), ByteArray(64) { (it + 64).toByte() })
        client.initCipher(server.message)
        server.initCipher(client.message)
        val key = "public-adb-key".toByteArray()
        val guid = "device-guid".toByteArray()
        assertArrayEquals(key, server.decrypt(client.encrypt(key)))
        assertArrayEquals(guid, client.decrypt(server.encrypt(guid)))
    }

    @Test fun wrongPairingCodeCannotDecryptThePeerIdentity() {
        val client = PairingAuth.client("123456-exporter".toByteArray(), ByteArray(64) { it.toByte() })
        val server = PairingAuth.server("123457-exporter".toByteArray(), ByteArray(64) { (it + 1).toByte() })
        client.initCipher(server.message)
        server.initCipher(client.message)
        assertThrows(AEADBadTagException::class.java) { server.decrypt(client.encrypt(byteArrayOf(1, 2, 3))) }
    }

    @Test fun replayedEncryptedPeerPacketFailsAuthentication() {
        val sender = PairingCipher(ByteArray(64) { it.toByte() })
        val receiver = PairingCipher(ByteArray(64) { it.toByte() })
        val packet = sender.encrypt(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), receiver.decrypt(packet))
        assertThrows(AEADBadTagException::class.java) { receiver.decrypt(packet) }
    }

    @Test fun malformedKeyExchangePointIsRejected() {
        val client = PairingAuth.client("password".toByteArray(), ByteArray(64) { it.toByte() })
        assertThrows(IllegalArgumentException::class.java) { client.initCipher(ByteArray(31)) }
    }

    @Test fun adbPacketRoundTripKeepsBinaryPayloadAndStreamIds() {
        val payload = byteArrayOf(0, -1, 127, -128)
        val decoded = AdbMessage.read(input(AdbMessage(AdbMessage.WRTE, 13, 42, payload).encode()), verifyChecksum = true)!!
        assertEquals(AdbMessage.WRTE, decoded.command)
        assertEquals(13, decoded.arg0)
        assertEquals(42, decoded.arg1)
        assertArrayEquals(payload, decoded.data)
    }

    @Test fun adbPacketWithCorruptedMagicCannotBeParsed() {
        val encoded = AdbMessage(AdbMessage.CNXN, 1, 2).encode()
        encoded[20] = (encoded[20].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { AdbMessage.read(input(encoded)) }
    }

    @Test fun peerCannotAllocateAnUnboundedPacket() {
        val encoded = AdbMessage(AdbMessage.WRTE, 1, 2, ByteArray(100)).encode()
        assertThrows(IOException::class.java) { AdbMessage.read(input(encoded), maxData = 16) }
    }

    @Test fun pairingPacketRejectsWrongProtocolVersionAndType() {
        val packet = ByteArrayOutputStream().also { AdbPairing.writePacket(it, AdbPairing.TYPE_SPAKE2, ByteArray(32)) }.toByteArray()
        assertThrows(AdbPairingException::class.java) { AdbPairing.readPacket(input(packet), AdbPairing.TYPE_PEER_INFO) }
        packet[0] = 2
        assertThrows(AdbPairingException::class.java) { AdbPairing.readPacket(input(packet), AdbPairing.TYPE_SPAKE2) }
    }

    private fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))
}
