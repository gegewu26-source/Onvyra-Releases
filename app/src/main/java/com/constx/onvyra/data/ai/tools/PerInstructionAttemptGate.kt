package com.constx.onvyra.data.ai.tools

/** Each explicit input may start one attempt; late callbacks cannot reopen old budgets. */
internal class PerInstructionAttemptGate {
    private var highestClaimed = Long.MIN_VALUE

    @Synchronized
    fun claim(revision: Long): Boolean {
        if (revision <= highestClaimed) return false
        highestClaimed = revision
        return true
    }
}
