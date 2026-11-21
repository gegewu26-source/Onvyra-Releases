package com.constx.onvyra.ui.pages.novel

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** This test deliberately has no direct references to target-app implementation classes. */
class ReaderOptimizedRuntimeTest {
    @Test fun installedApkExecutesJavascriptAndContainsRuntimeLinkageFailures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = Class.forName(
            "com.constx.onvyra.ui.pages.novel.ReaderRuntimeCompatibilityProbe", true, context.classLoader)
        val result = probe.getMethod("verify").invoke(null)
        assertEquals("reader-js-optimized-ok", result)
    }
}
