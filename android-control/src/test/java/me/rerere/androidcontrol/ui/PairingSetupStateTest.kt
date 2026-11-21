package me.rerere.androidcontrol.ui

import org.junit.Assert.*
import org.junit.Test

class PairingSetupStateTest {
    @Test fun sixAsciiDigitsKeepLeadingZeroes() {
        assertEquals("012345",pairingCode(" 012345\n"))
    }
    @Test fun groupedAndUnicodeDigitsAreNormalizedForManualNotificationInput() {
        assertEquals("012345", pairingCode(" 012-345\n"))
        assertEquals("123456", pairingCode("１２３ ４５６"))
    }
    @Test fun malformedCodesAreRejected() {
        listOf(null,"12345","1234567","12 345","abcdef","123.456").forEach { assertNull(pairingCode(it)) }
    }
}
