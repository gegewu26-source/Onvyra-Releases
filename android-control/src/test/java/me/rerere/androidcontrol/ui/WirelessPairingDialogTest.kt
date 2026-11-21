package me.rerere.androidcontrol.ui

import org.junit.Assert.*
import org.junit.Test

class WirelessPairingDialogTest {
    private fun nodes(vararg texts: String) = texts.map { WirelessPairingText(it) }

    @Test fun readsChinesePairingDialogAndPreservesLeadingZeroes() {
        val result = WirelessPairingDialog.read(nodes("使用配对码配对设备", "012 345", "192.168.1.8:37123"), setOf(37123))
        assertNotNull(result)
        assertEquals("012345", result!!.code)
        assertEquals(37123, result.port)
        assertFalse(result.toString().contains(result.code))
    }

    @Test fun readsLabelAndCodeFromOneNodeOrAdjacentThreeDigitNodes() {
        assertEquals("012345", WirelessPairingDialog.read(
            nodes("使用配对码配对设备：012 345", "192.168.1.8:37123"), setOf(37123))?.code)
        assertEquals("012345", WirelessPairingDialog.read(
            nodes("配对码", "012", "345", "192.168.1.8:37123"), setOf(37123))?.code)
    }

    @Test fun normalizesUnicodeDecimalDigitsWithoutAcceptingAddressDigits() {
        assertEquals("123456", WirelessPairingDialog.read(
            nodes("Wi-Fi pairing code", "１２３ ４５６", "192.168.1.8:37123"), setOf(37123))?.code)
        assertNull(WirelessPairingDialog.read(nodes("配对码", "192.168.1.8:37123"), setOf(37123)))
    }

    @Test fun readsSixSeparateDigitNodes() {
        val result = WirelessPairingDialog.read(nodes("WLAN 配对码", "0", "1", "2", "3", "4", "5",
            "192.168.1.8:37123"), setOf(37123))
        assertEquals("012345", result?.code)
        assertEquals(37123, result?.port)
    }

    @Test fun pairingEndpointWinsOverVisibleBackgroundConnectionEndpoint() {
        val result = WirelessPairingDialog.read(nodes("无线调试", "192.168.1.8:35495", "与设备配对",
            "WLAN 配对码", "123456", "IP 地址和端口", "192.168.1.8:33883"), setOf(33883))
        assertEquals("123456", result?.code)
        assertEquals(33883, result?.port)
        // Still reject genuinely ambiguous pairing endpoints and stale dialog ports.
        assertNull(WirelessPairingDialog.read(nodes("配对码", "123456", "192.168.1.8:33883",
            "192.168.1.8:35495"), setOf(33883, 35495)))
        assertNull(WirelessPairingDialog.read(nodes("配对码", "123456", "192.168.1.8:33883"), setOf(35495)))
    }

    @Test fun readsEnglishAndIpv6AndUsesOnlyCurrentLocalDiscoveredPort() {
        val result = WirelessPairingDialog.read(nodes("Wi-Fi pairing code", "654321", "[fe80::1234%wlan0]:42123"), setOf(42123, 43123))
        assertEquals(42123, result?.port)
        assertNull(WirelessPairingDialog.read(nodes("Wi-Fi pairing code", "654321", "[fe80::1234%wlan0]:42123"), setOf(43123)))
    }

    @Test fun neverReadsAnUnrelatedSixDigitOtpWithoutPairingDialogEvidence() {
        assertNull(WirelessPairingDialog.read(nodes("Verification code", "123456", "192.168.1.8:37123"), setOf(37123)))
        assertNull(WirelessPairingDialog.read(nodes("Wi-Fi pairing code", "123456"), emptySet()))
        assertNull(WirelessPairingDialog.read(nodes("Wi-Fi pairing code", "1234567", "192.168.1.8:37123"), setOf(37123)))
    }

    @Test fun rejectsAmbiguousCodesPortsAndInvalidEndpoints() {
        assertNull(WirelessPairingDialog.read(nodes("配对码", "123456", "654321", "192.168.1.8:37123"), setOf(37123)))
        assertNull(WirelessPairingDialog.read(nodes("配对码", "123456", "192.168.1.8:37123", "192.168.1.8:37124"), setOf(37123, 37124)))
        assertNull(WirelessPairingDialog.read(nodes("配对码", "123456"), setOf(37123, 37124)))
        // A malformed address is not a valid way to select a port; no unique fallback exists.
        assertNull(WirelessPairingDialog.read(nodes("配对码", "123456", "999.1.1.1:37123"), setOf(37123, 37124)))
    }

    @Test fun knownSystemFieldSupportsOtherLocalesAndDuplicateLabelsAreHarmless() {
        val result = WirelessPairingDialog.read(listOf(WirelessPairingText("123456", "com.android.settings:id/pairing_code"),
            WirelessPairingText("123456")), setOf(37123))
        assertEquals("123456", result?.code)
        assertEquals(37123, result?.port)
    }

    @Test fun codeIsNotConsumedUntilSubmissionWasAccepted() {
        val attempts = WirelessPairingAutoAttempts()
        attempts.start(1)
        assertTrue(attempts.canSubmit(1, "123456"))
        // A failed rendezvous has not called submitted: the next observation may retry delivery.
        assertTrue(attempts.canSubmit(1, "123456"))
        attempts.submitted(1, "123456")
        assertFalse(attempts.canSubmit(1, "123456"))
        assertTrue(attempts.canSubmit(1, "654321"))
    }

    @Test fun stopOrNewSessionRejectsLateReadsAndDoesNotInheritTriedCodes() {
        val attempts = WirelessPairingAutoAttempts()
        attempts.start(1)
        attempts.submitted(1, "123456")
        attempts.close()
        assertFalse(attempts.canSubmit(1, "654321"))
        attempts.start(2)
        assertFalse(attempts.canSubmit(1, "654321"))
        assertTrue(attempts.canSubmit(2, "123456"))
        attempts.submitted(1, "123456")
        assertTrue(attempts.canSubmit(2, "123456"))
    }

    @Test fun oneSessionHasABoundedNumberOfAutomaticAttempts() {
        val attempts = WirelessPairingAutoAttempts()
        attempts.start(1)
        repeat(32) { index -> attempts.submitted(1, index.toString().padStart(6, '0')) }
        assertFalse(attempts.canSubmit(1, "999999"))
    }

    @Test fun reopeningTheDialogRequiresFreshLocalDiscoveryAndThenRecovers() {
        val reopened = nodes("使用配对码配对设备", "098765", "192.168.1.8:42123")
        assertNull(WirelessPairingDialog.read(reopened, setOf(37123)))
        assertNull(WirelessPairingDialog.read(reopened, emptySet()))
        val fresh = WirelessPairingDialog.read(reopened, setOf(42123))
        assertNotNull(fresh)
        assertEquals(42123, fresh!!.port)
        assertEquals("098765", fresh.code)
    }

    @Test fun unchangedDialogBecomesReadyAfterAShortStableReadInsteadOfTwoSlowPollingIntervals() {
        val stability = WirelessPairingCodeStability()
        val current = WirelessPairingReading("012345", 37123)
        assertFalse(stability.observe(current, 1_000))
        assertFalse(stability.observe(current, 1_079))
        assertTrue(stability.observe(current, 1_080))
        assertFalse(stability.observe(WirelessPairingReading("654321", 37123), 1_100))
        assertTrue(stability.observe(WirelessPairingReading("654321", 37123), 1_180))
    }

    @Test fun changedPortMissingDialogAndClockResetRequireANewStableObservation() {
        val stability = WirelessPairingCodeStability()
        assertFalse(stability.observe(WirelessPairingReading("012345", 37123), 1_000))
        assertFalse(stability.observe(WirelessPairingReading("012345", 42123), 1_100))
        assertFalse(stability.observe(null, 1_200))
        assertFalse(stability.observe(WirelessPairingReading("012345", 42123), 1_300))
        assertFalse(stability.observe(WirelessPairingReading("012345", 42123), 1_000))
        assertTrue(stability.observe(WirelessPairingReading("012345", 42123), 1_080))
    }

    @Test fun aCurrentDialogCanPairImmediatelyUsingItsRecentlyVerifiedLocalEndpoint() {
        assertEquals(37123, verifiedAutomaticPairingPort("012345",37123,
            WirelessPairingReading("012345",37123),setOf(37123),250))
        assertEquals(37123, verifiedAutomaticPairingPort("012345",37123,
            WirelessPairingReading("012345",37123),setOf(37123),5_000))
    }

    @Test fun aFastSubmissionCannotReuseAnExpiredEndpointOrAChangedDialog() {
        val current = WirelessPairingReading("012345",37123)
        assertNull(verifiedAutomaticPairingPort("012345",null,current,setOf(37123),250))
        assertNull(verifiedAutomaticPairingPort("012345",37123,null,setOf(37123),250))
        assertNull(verifiedAutomaticPairingPort("654321",37123,current,setOf(37123),250))
        assertNull(verifiedAutomaticPairingPort("012345",42123,current,setOf(37123,42123),250))
        assertNull(verifiedAutomaticPairingPort("012345",37123,current,setOf(42123),250))
        assertNull(verifiedAutomaticPairingPort("012345",37123,current,setOf(37123),5_001))
        assertNull(verifiedAutomaticPairingPort("012345",37123,current,setOf(37123),-1))
    }
}
