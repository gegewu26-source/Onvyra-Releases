package com.constx.onvyra.data.ai.mcp

import io.ktor.client.plugins.sse.SSEConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class McpReconnectPolicyTest {
    @Test fun initialFailureGetsExactlyTwoRetriesThenStaysExhausted() {
        val budget = McpReconnectBudget()
        var connectionCalls = 1 // Initial attempt is not a retry.
        val delays = mutableListOf<Long>()
        while (true) {
            val retry = budget.next() ?: break
            connectionCalls++
            delays += retry.delayMs
        }
        assertEquals(3, connectionCalls)
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals(2, budget.retryCount)
        repeat(10) { assertNull(budget.next()) }
        assertTrue(budget.exhausted)
    }

    @Test fun successOrExplicitRetryGetsANewBudgetInsteadOfKeepingOldFailures() {
        val budget = McpReconnectBudget()
        budget.next()
        budget.next()
        assertNull(budget.next())
        budget.reset()
        assertFalse(budget.exhausted)
        assertEquals(McpReconnectAttempt(1, 1_000), budget.next())
        budget.reset()
        assertEquals(McpReconnectAttempt(1, 1_000), budget.next())
    }

    @Test fun cancellationDuringBackoffNeverRunsAnotherConnectionOrSpendsFurtherRetries() = runTest {
        val budget = McpReconnectBudget()
        var connectionCalls = 1
        val task = launch {
            while (true) {
                val retry = budget.next() ?: break
                delay(retry.delayMs)
                connectionCalls++
            }
        }
        runCurrent()
        assertEquals(1, budget.retryCount)
        advanceTimeBy(500)
        task.cancelAndJoin()
        advanceTimeBy(10_000)
        assertEquals(1, connectionCalls)
        assertEquals(1, budget.retryCount)
    }

    @Test fun automaticCatalogChecksNeverResetOrRestartAnOfflineServersBudget() {
        val budget = McpReconnectBudget()
        assertTrue(budget.allowsAutomaticCheck(reconnectScheduled = false, failed = false))
        assertFalse(budget.allowsAutomaticCheck(reconnectScheduled = true, failed = false))
        assertFalse(budget.allowsAutomaticCheck(reconnectScheduled = false, failed = true))
        budget.next()
        assertFalse(budget.allowsAutomaticCheck(reconnectScheduled = false, failed = false))
        budget.next()
        repeat(10) {
            assertFalse(budget.allowsAutomaticCheck(reconnectScheduled = false, failed = false))
            assertNull(budget.next())
        }
        assertEquals(2, budget.retryCount)
        budget.reset()
        assertTrue(budget.allowsAutomaticCheck(reconnectScheduled = false, failed = false))
    }

    @Test fun sdkAndKtorDoNotMultiplyTheRegistryRetryBudget() {
        assertEquals(0, mcpTransportReconnectionOptions().maxRetries)
        val sse = SSEConfig().apply { useRegistryReconnectBudget() }
        assertEquals(0, sse.maxReconnectionAttempts)
        assertEquals(2, MCP_MAX_RECONNECT_RETRIES)
    }
}
