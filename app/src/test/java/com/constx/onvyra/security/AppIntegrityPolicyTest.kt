package com.constx.onvyra.security

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class AppIntegrityPolicyTest {
    private val certificate = "synthetic-test-certificate".toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun exactCurrentSignerIsAccepted() {
        assertTrue(hasTrustedSigningCertificate(digest(certificate), listOf(certificate)))
        assertTrue(hasTrustedSigningCertificate(digest(certificate).uppercase().chunked(2).joinToString(":"), listOf(certificate)))
    }

    @Test fun resigningAndUnexpectedAdditionalSignersAreRejected() {
        assertFalse(hasTrustedSigningCertificate(digest(certificate), listOf("different-certificate".toByteArray())))
        assertFalse(hasTrustedSigningCertificate(digest(certificate), emptyList()))
        assertFalse(hasTrustedSigningCertificate(digest(certificate), listOf(certificate, certificate)))
    }

    @Test fun missingOrMalformedPinsFailClosed() {
        listOf("", "0".repeat(63), "z".repeat(64), "x".repeat(65)).forEach {
            assertFalse(hasTrustedSigningCertificate(it, listOf(certificate)))
        }
    }

    @Test fun resourceModificationOrTruncationIsRejected() {
        val original = "public-test-asset\n".toByteArray()
        assertTrue(matchesSha256(digest(original), original))
        assertFalse(matchesSha256(digest(original), original + byteArrayOf(0)))
        assertFalse(matchesSha256(digest(original), original.copyOf(original.size - 1)))
    }
}
