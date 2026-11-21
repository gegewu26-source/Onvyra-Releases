package com.constx.onvyra.ui.pages.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StatsTokenMetricsTest {
    @Test
    fun `rate uses cached input as a subset not an extra input category`() {
        val rate = calculateCacheHitRate(inputTokens = 1000, cachedTokens = 800)
        assertEquals(0.8, rate.fraction!!, 0.000001)
        assertEquals("80.0%", formatCacheHitRate(rate, Locale.US))
    }

    @Test
    fun `lifetime totals weight requests by tokens instead of averaging their percentages`() {
        // A 100%-cached 100-token request and an uncached 900-token request give 10%, not 50%.
        assertEquals(0.1, calculateCacheHitRate(100 + 900L, 100 + 0L).fraction!!, 0.000001)
    }

    @Test
    fun `positive input with zero hits is zero and no input is unavailable`() {
        assertEquals("0.0%", formatCacheHitRate(calculateCacheHitRate(200, 0), Locale.US))
        val empty = calculateCacheHitRate(0, 0)
        assertNull(empty.fraction)
        assertEquals(CacheHitRateUnavailable.NO_INPUT, empty.unavailable)
        assertEquals("—", formatCacheHitRate(empty, Locale.US))
    }

    @Test
    fun `inconsistent counts are unavailable instead of clamped to a plausible percentage`() {
        for ((input, cached) in listOf(0L to 1L, 100L to 101L, -1L to 0L, 100L to -1L)) {
            val rate = calculateCacheHitRate(input, cached)
            assertNull(rate.fraction)
            assertEquals(CacheHitRateUnavailable.INCONSISTENT_COUNTS, rate.unavailable)
            assertEquals("—", formatCacheHitRate(rate, Locale.US))
        }
    }

    @Test
    fun `large lifetime counters do not overflow before division`() {
        assertEquals(0.5, calculateCacheHitRate(8_000_000_000, 4_000_000_000).fraction!!, 0.000001)
        assertEquals("100.0%", formatCacheHitRate(calculateCacheHitRate(Long.MAX_VALUE, Long.MAX_VALUE), Locale.US))
    }

    @Test
    fun `formatting respects locale and rejects nonfinite or out of range fractions`() {
        val localized = formatCacheHitRate(CacheHitRate(0.8), Locale.FRANCE)
        assertTrue(localized.startsWith("80,0"))
        assertTrue(localized.endsWith("%"))
        for (fraction in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertEquals("—", formatCacheHitRate(CacheHitRate(fraction), Locale.US))
        }
    }
}
