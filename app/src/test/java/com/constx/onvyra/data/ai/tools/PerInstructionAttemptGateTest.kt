package com.constx.onvyra.data.ai.tools

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PerInstructionAttemptGateTest {
    @Test fun `a new explicit input gets a budget but repeated or older requests do not`() {
        val gate = PerInstructionAttemptGate()
        assertTrue(gate.claim(0)); assertFalse(gate.claim(0))
        assertTrue(gate.claim(1)); assertFalse(gate.claim(1)); assertFalse(gate.claim(0))
        assertTrue(gate.claim(10)); assertFalse(gate.claim(9))
    }

    @Test fun `concurrent tool choices cannot spend the same instruction twice`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val gate = PerInstructionAttemptGate()
            val tasks = List(20) { executor.submit<Boolean> { gate.claim(3) } }
            assertEquals(1, tasks.count { it.get(2, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
    }
}
