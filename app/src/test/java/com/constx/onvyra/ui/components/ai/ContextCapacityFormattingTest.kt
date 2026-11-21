package com.constx.onvyra.ui.components.ai

import org.junit.Assert.*
import org.junit.Test

class ContextCapacityFormattingTest {
    @Test fun everySingleTokenChangeRemainsVisibleNearOneHundredThousand() {
        val first = ContextCapacityState(99_400L, 512_000L)
        val next = first.copy(usedTokens = 99_401L)
        assertTrue(first.capacitySummary().contains("99,400/512,000"))
        assertTrue(next.capacitySummary().contains("99,401/512,000"))
        assertNotEquals(first.capacitySummary(), next.capacitySummary())
        assertEquals("99,419", formatExactContextTokens(99_419L))
    }

    @Test fun smallContextGrowthHasVisibleHundredthPercentAndUnknownIsNotZero() {
        assertTrue(ContextCapacityState(98_851L, 512_000L).capacitySummary().contains("19.31%"))
        assertTrue(ContextCapacityState(99_419L, 512_000L).capacitySummary().contains("19.42%"))
        assertTrue(ContextCapacityState(null, 512_000L).capacitySummary().contains("暂无数据"))
        assertTrue(ContextCapacityState(99_419L, 512_000L, usageEstimated = true).capacitySummary().startsWith("≈"))
    }

    @Test fun awaitingReportLabelsReferenceRatherThanPresentingItAsCurrentMeasurement() {
        val pending = ContextCapacityState(98_851L, 512_000L, awaitingUsage = true)
        assertTrue(pending.capacitySummary().startsWith("上次确认 98,851"))
        assertFalse(pending.capacitySummary().contains("≈"))
        assertTrue(pending.copy(usedTokens = null).capacitySummary().contains("等待用量回报"))
    }
}
