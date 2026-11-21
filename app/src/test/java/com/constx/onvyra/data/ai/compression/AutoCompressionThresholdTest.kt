package com.constx.onvyra.data.ai.compression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoCompressionThresholdTest {
    @Test fun `empty and zero preserve automatic budgeting`() {
        for (input in listOf("", "  ", "0", "000", "0K", "0.000K")) {
            assertEquals(input, 0, parseAutoCompressionThresholdInput(input))
        }
    }

    @Test fun `custom thresholds accept whole tokens and exact decimal thousands`() {
        val values = mapOf(
            "1" to 1,
            "258000" to 258_000,
            "258K" to 258_000,
            " 512k " to 512_000,
            "1.5K" to 1_500,
            "1.5000K" to 1_500,
            "0.001K" to 1,
            "2147483647" to Int.MAX_VALUE,
            "2147483.647K" to Int.MAX_VALUE,
        )
        for ((input, expected) in values) assertEquals(input, expected, parseAutoCompressionThresholdInput(input))
    }

    @Test fun `invalid fractional negative and overflowing inputs are rejected`() {
        for (input in listOf(
            "-1", "+1", "1.5", "1.0001K", "0.0001K", "K", ".5K", "1.K",
            "1.2.3K", "1 K", "1,000", "1e3", "NaN", "1M", "１２３", "2147483648",
            "2147483.648K", "2147484K", "999999999999999999999999999K",
        )) assertNull(input, parseAutoCompressionThresholdInput(input))
    }

    @Test fun `stored thresholds format without losing token precision`() {
        assertEquals("", formatAutoCompressionThresholdInput(0))
        assertEquals("258K", formatAutoCompressionThresholdInput(258_000))
        assertEquals("512K", formatAutoCompressionThresholdInput(512_000))
        assertEquals("1500", formatAutoCompressionThresholdInput(1_500))
        for (tokens in listOf(0, 1, 999, 1_000, 1_500, 258_000, 512_000, Int.MAX_VALUE)) {
            assertEquals(tokens, parseAutoCompressionThresholdInput(formatAutoCompressionThresholdInput(tokens)))
        }
    }
}
