package com.constx.onvyra.security

import java.security.MessageDigest
import java.util.Locale

/** Certificate pins are public. They are exact current-signer checks, not signing-history checks. */
internal fun hasTrustedSigningCertificate(expectedSha256: String, currentSigners: List<ByteArray>): Boolean =
    currentSigners.size == 1 && matchesSha256(expectedSha256, currentSigners.single())

internal fun matchesSha256(expectedSha256: String, bytes: ByteArray): Boolean {
    val normalized = expectedSha256.replace(":", "").lowercase(Locale.ROOT)
    if (normalized.length != 64 || normalized.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
    val expected = ByteArray(32) { index -> normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    return MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(bytes))
}
