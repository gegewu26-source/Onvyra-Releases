package com.constx.onvyra.security

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All codes and hashes below are fixed, synthetic fixtures, unrelated to the owner's key. */
class PrivateMediaCodebookTest {
    private val anchor = 1_793_980_800_000L
    private val period = 1_296_000_000L
    private val fakeSalt = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val fakeCodes = listOf("ABCDEFGHJKLMNPQR", "STUVWXYZ23456789", "23456789ABCDEFGH")
    // Independently computed using Python hashlib.sha256 and urlsafe_b64encode.
    private val fakeHashes = listOf(
        "3KVuY1cv8t54oIKWi5zqfKc3J_t9HBo0CwH4cnhg250",
        "CZStvq6qszlGb9LNR4OPVkqCkSllI62EuKLuSbb3-DY",
        "vhfAGtsGcnZEn2G72BidBwuN83MjXrRtbKwF7A9edPI",
    )

    private fun manifest(
        version: Long = 1L,
        anchorMillis: Long = anchor,
        periodMillis: Long = period,
        salt: String = fakeSalt,
        hashes: List<String> = fakeHashes,
    ): String = buildJsonObject {
        put("version", version)
        put("anchor_ms", anchorMillis)
        put("period_ms", periodMillis)
        put("salt", salt)
        put("hashes", JsonArray(hashes.map(::JsonPrimitive)))
    }.toString()

    private fun codebook() = PrivateMediaCodebook.parse(manifest())

    private fun assertRejected(text: String) {
        assertTrue("Invalid codebook must be rejected", runCatching { PrivateMediaCodebook.parse(text) }.isFailure)
    }

    @Test fun normalizationAcceptsLowercaseGroupingAndOrdinaryWhitespace() {
        assertEquals(fakeCodes[0], PrivateMediaCodebook.normalize(" abcd-efgh-jklm-npqr\t\r\n"))
        assertTrue(codebook().matches("abcd-efgh-jklm-npqr", anchor))
    }

    @Test fun normalizationRequiresExactlySixteenUnambiguousCharacters() {
        listOf("", "A".repeat(15), "A".repeat(17), "A".repeat(65)).forEach {
            assertNull(PrivateMediaCodebook.normalize(it))
        }
        listOf('I', 'O', '0', '1', '/', '_', 'Ａ', '中').forEach { invalid ->
            assertNull(PrivateMediaCodebook.normalize(invalid + "A".repeat(15)))
        }
        assertEquals("23456789ABCDEFGH", PrivateMediaCodebook.normalize("23456789ABCDEFGH"))
    }

    @Test fun eachCurrentPeriodAcceptsOnlyItsOwnCode() {
        val book = codebook()
        fakeCodes.forEachIndexed { index, code ->
            val now = anchor + index * period + 123L
            assertTrue(book.matches(code, now))
            fakeCodes.forEachIndexed { otherIndex, other ->
                if (otherIndex != index) assertFalse(book.matches(other, now))
            }
        }
    }

    @Test fun exactFifteenDayBoundaryRejectsPreviousAndAcceptsNextPeriod() {
        val book = codebook()
        assertTrue(book.matches(fakeCodes[0], anchor + period - 1L))
        assertFalse(book.matches(fakeCodes[1], anchor + period - 1L))
        assertFalse(book.matches(fakeCodes[0], anchor + period))
        assertTrue(book.matches(fakeCodes[1], anchor + period))
        assertEquals(anchor + period, book.expiresAt(anchor))
        assertEquals(anchor + 2L * period, book.expiresAt(anchor + period))
    }

    @Test fun beforeAnchorAndAfterTheLastKnownPeriodAlwaysStayLocked() {
        val book = codebook()
        assertNull(book.periodAt(anchor - 1L))
        assertNull(book.expiresAt(anchor - 1L))
        assertFalse(book.matches(fakeCodes[0], anchor - 1L))
        assertEquals(2, book.periodAt(anchor + 3L * period - 1L))
        assertNull(book.periodAt(anchor + 3L * period))
        assertNull(book.expiresAt(anchor + 3L * period))
        assertFalse(book.matches(fakeCodes.last(), anchor + 3L * period))
    }

    @Test fun oneWrongCharacterIsRejected() {
        assertFalse(codebook().matches("BBCDEFGHJKLMNPQR", anchor))
        assertFalse(codebook().matches("ABCDEFGHJKLMNPQS", anchor))
    }

    @Test fun digestMatchesTheIndependentProtocolFixture() {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "onvyra-private-media-v1\n0\n$fakeSalt\n${fakeCodes[0]}".toByteArray(Charsets.UTF_8),
        )
        assertTrue(MessageDigest.isEqual(Base64.getUrlDecoder().decode(fakeHashes[0]), digest))
        val tampered = digest.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(MessageDigest.isEqual(digest, tampered))
    }

    @Test fun changingTheSaltOrSwappingHashPeriodsCannotValidateACode() {
        val differentSalt = PrivateMediaCodebook.parse(manifest(salt = "B".repeat(43)))
        assertFalse(differentSalt.matches(fakeCodes[0], anchor))
        val swapped = PrivateMediaCodebook.parse(manifest(hashes = fakeHashes.reversed()))
        assertFalse(swapped.matches(fakeCodes[0], anchor))
    }

    @Test fun malformedJsonAndMissingRequiredFieldsAreRejected() {
        listOf("", "{", "[]", "null", " ".repeat(65_537)).forEach(::assertRejected)
        val fields = Json.parseToJsonElement(manifest()) as JsonObject
        listOf("version", "anchor_ms", "period_ms", "salt", "hashes").forEach { missing ->
            assertRejected(JsonObject(fields.filterKeys { it != missing }).toString())
        }
    }

    @Test fun unsupportedVersionPeriodAndInvalidAnchorAreRejected() {
        assertRejected(manifest(version = 2L))
        assertRejected(manifest(periodMillis = period - 1L))
        assertRejected(manifest(periodMillis = 0L))
        assertRejected(manifest(anchorMillis = 0L))
        assertRejected(manifest(anchorMillis = -1L))
        assertRejected(manifest(anchorMillis = Long.MAX_VALUE))
    }

    @Test fun corruptSaltHashAndTableSizeAreRejected() {
        assertRejected(manifest(salt = "short"))
        assertRejected(manifest(salt = "/".repeat(43)))
        assertRejected(manifest(hashes = emptyList()))
        assertRejected(manifest(hashes = List(513) { fakeHashes.first() }))
        assertRejected(manifest(hashes = listOf("not%base64")))
        assertRejected(manifest(hashes = listOf(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31)))))
    }

    @Test fun smallClockCorrectionCannotExtendAnExpiringGrant() {
        val start = anchor + period - 1_000L
        val clock = PrivateMediaClock(start)
        assertEquals(start, clock.read(start, 0L))
        val protectedNow = clock.read(start - 50_000L, 2_000L)
        assertEquals(start + 2_000L, protectedNow)
        assertFalse(codebook().matches(fakeCodes[0], requireNotNull(protectedNow)))
        assertTrue(codebook().matches(fakeCodes[1], protectedNow))
    }

    @Test fun obviousClockRollbackIsDeniedWithoutLoweringTheWatermark() {
        val start = anchor + 500_000L
        val clock = PrivateMediaClock(start)
        assertEquals(start, clock.read(start, 100L))
        assertNull(clock.read(start - 120_001L, 101L))
        assertEquals(start, clock.highWaterMillis)
        assertEquals(start + 1L, clock.read(start + 1L, 101L))
    }

    @Test fun elapsedTimeAdvancesTheClockWhenWallTimeIsTemporarilyBehind() {
        val clock = PrivateMediaClock(anchor)
        assertEquals(anchor, clock.read(anchor, 0L))
        assertEquals(anchor + 60_000L, clock.read(anchor, 60_000L))
        assertEquals(anchor + 120_000L, clock.read(anchor, 120_000L))
        assertNull(clock.read(anchor, 120_001L))
    }

    @Test fun persistedWatermarkSurvivesANewClockInstance() {
        val first = PrivateMediaClock(anchor)
        val watermark = requireNotNull(first.read(anchor + 90_000L, 20_000L))
        val restarted = PrivateMediaClock(watermark)
        assertEquals(watermark, restarted.read(anchor + 10_000L, 1L))
        assertEquals(watermark, restarted.highWaterMillis)
        assertNull(PrivateMediaClock(watermark).read(anchor - 30_001L, 1L))
    }
}
