package com.constx.onvyra.utils

import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

class SanitizeCrashReportTest {
    @Test fun messagesAndCredentialsDoNotSurviveInEitherStoredTextOrPlatformThrowable() {
        val original = IllegalStateException("https://upstream.example/?api_key=TOP_SECRET", IllegalArgumentException("Bearer TOP_SECRET"))
        original.addSuppressed(IllegalStateException("Cookie: TOP_SECRET"))
        original.stackTrace = arrayOf(StackTraceElement("com.example.Parser", "read", "Parser.kt", 42))
        val sanitized = sanitizeCrashReport(original)
        listOf(sanitized.text, sanitized.throwable.stackTraceToString()).forEach { text ->
            assertFalse(text.contains("TOP_SECRET"))
            assertFalse(text.contains("https://"))
            assertTrue(text.contains("java.lang.IllegalStateException"))
            assertTrue(text.contains("java.lang.IllegalArgumentException"))
            assertTrue(text.contains("com.example.Parser.read(Parser.kt:42)"))
        }
        assertEquals("https://upstream.example/?api_key=TOP_SECRET", original.message)
        assertNotSame(original, sanitized.throwable)
        assertNotSame(original.cause, sanitized.throwable.cause)
        assertNotSame(original.suppressed.single(), sanitized.throwable.suppressed.single())
    }

    @Test fun cyclesAndLargeSuppressedGraphsAreBoundedWithoutHoldingOriginalReferences() {
        val one = RuntimeException("first secret")
        val two = RuntimeException("second secret")
        one.initCause(two)
        two.initCause(one)
        repeat(100) { one.addSuppressed(RuntimeException("suppressed secret $it")) }
        val sanitized = sanitizeCrashReport(one)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        fun walk(current: Throwable) {
            assertTrue("Sanitized graph must not contain cycles", seen.add(current))
            assertNotSame(one, current)
            assertNotSame(two, current)
            current.cause?.let(::walk)
            current.suppressed.forEach(::walk)
        }
        walk(sanitized.throwable)
        assertTrue(seen.size <= 24)
        assertTrue(sanitized.text.length <= 8_000)
        assertFalse(sanitized.text.contains("secret"))
    }

    @Test fun unsafeStackFrameNamesAndPathsAreNotUsedAsArbitraryLogText() {
        val original = RuntimeException("secret")
        original.stackTrace = arrayOf(
            StackTraceElement("invalid\nTOKEN_SECRET", "bad\nTOKEN_SECRET", "https://host/?key=TOKEN_SECRET", 2),
            StackTraceElement("com.example.Safe", "run", "SourceFile", 7),
        )
        val safe = sanitizeCrashReport(original)
        assertFalse(safe.text.contains("TOKEN_SECRET"))
        assertFalse(safe.text.contains("https://"))
        assertTrue(safe.text.contains("com.example.Safe.run(SourceFile:7)"))
    }

    @Test fun frameCountAndLengthsAreBounded() {
        val original = RuntimeException("secret")
        original.stackTrace = Array(5_000) { StackTraceElement("com.example.Safe", "run", "Safe.kt", it) }
        val safe = sanitizeCrashReport(original)
        assertEquals(24, safe.throwable.stackTrace.size)
        assertTrue(safe.text.length <= 8_000)
    }
}
