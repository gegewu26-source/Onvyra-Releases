package com.constx.onvyra.utils

import java.util.Collections
import java.util.IdentityHashMap

internal data class SanitizedCrashReport(val text: String, val throwable: Throwable)

/** No messages, thread names, user breadcrumbs or references to the original Throwable escape. */
internal fun sanitizeCrashReport(original: Throwable): SanitizedCrashReport {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var remaining = 16

    fun cleanName(name: String, fallback: String): String = name.takeIf {
        it.length in 1..200 && it.all { char -> char.isLetterOrDigit() && char.code < 128 || char in "_.$<>-" }
    } ?: fallback

    fun cleanFrame(frame: StackTraceElement): StackTraceElement {
        val fileName = frame.fileName?.let { file ->
            file.takeIf { it.length in 1..120 && (it.endsWith(".kt") || it.endsWith(".java") || it == "SourceFile") &&
                it.all { char -> char.isLetterOrDigit() && char.code < 128 || char in "_.$-" } }
        }
        return StackTraceElement(cleanName(frame.className, "unknown"), cleanName(frame.methodName, "unknown"),
            fileName, frame.lineNumber.takeIf { it in -2..10_000_000 } ?: -1)
    }

    fun copy(source: Throwable, depth: Int): Throwable {
        if (remaining <= 0 || depth >= 8 || !seen.add(source)) {
            return RuntimeException("[additional crash details omitted]").apply { stackTrace = emptyArray() }
        }
        remaining--
        val type = cleanName(source.javaClass.name, "Throwable")
        val safe = RuntimeException("Exception type: $type")
        safe.stackTrace = runCatching { source.stackTrace.take(24).map(::cleanFrame).toTypedArray() }
            .getOrDefault(emptyArray())
        runCatching { source.cause }.getOrNull()?.let { safe.initCause(copy(it, depth + 1)) }
        runCatching { source.suppressed.take(4) }.getOrDefault(emptyList()).forEach { suppressed ->
            if (remaining > 0) safe.addSuppressed(copy(suppressed, depth + 1))
        }
        return safe
    }

    val safe = copy(original, 0)
    return SanitizedCrashReport(safe.stackTraceToString().take(8_000), safe)
}
