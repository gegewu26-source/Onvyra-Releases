package me.rerere.androidcontrol.shell

import java.io.IOException
import java.io.InputStream

internal object RootShellBootstrap {
    const val READY = "ANDROID_CONTROL_UID2000_READY"
    const val WRONG_UID_EXIT = 93

    /** Magisk su options precede its positional UID; never launch this helper with UID0. */
    fun arguments(apk: String, packageName: String, appUid: Int, certificatePin: String): List<String> {
        require(appUid >= 10_000) { "An application UID is required" }
        val command = "if [ \"\$(/system/bin/id -u)\" != \"2000\" ]; then exit $WRONG_UID_EXIT; fi; " +
            "printf '%s\\n' '$READY'; " + HelperHandoff.command(apk, packageName, appUid, certificatePin)
        // Modern Magisk forwards unknown -c and everything after it to sh. Put the
        // target UID before -c, otherwise the trailing 2000 becomes sh's $0 and su stays root.
        return listOf("su", "-g", "2000", "--", "2000", "-c", command)
    }

    /** Do not let an unexpected su banner allocate an unbounded line before authorization. */
    fun awaitReady(input: InputStream): Boolean {
        val line = StringBuilder()
        repeat(4_096) {
            when (val next = input.read()) {
                -1 -> return false
                10 -> {
                    if (line.toString().trimEnd('\r') == READY) return true
                    line.setLength(0)
                }
                else -> line.append(next.toChar())
            }
        }
        return false
    }

    /** Kill first: closing a pipe while another thread reads it can otherwise block cancellation. */
    fun closeProcess(process: java.lang.Process) {
        runCatching { process.destroyForcibly() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}

internal class RootShellStartException(val reason: Reason, cause: Throwable? = null) : IOException("Root helper startup failed", cause) {
    enum class Reason { UNAVAILABLE, DENIED, WRONG_UID, AUTHORIZATION_TIMEOUT, HANDOFF_TIMEOUT }
}
