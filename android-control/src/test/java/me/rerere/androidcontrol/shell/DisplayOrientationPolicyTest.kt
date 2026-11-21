package me.rerere.androidcontrol.shell

import org.junit.Assert.*
import org.junit.Test

class DisplayOrientationPolicyTest {
    @Test fun manifestFixedLandscapeAndPortraitDriveThePhoneVirtualDisplay() {
        assertEquals(1, requestedDisplayRotation(0, 1080, 2340, 0))
        assertEquals(3, requestedDisplayRotation(8, 1080, 2340, 0))
        assertEquals(0, requestedDisplayRotation(1, 1080, 2340, 1))
        assertEquals(2, requestedDisplayRotation(9, 1080, 2340, 1))
    }

    @Test fun sensorAxisRequestsKeepAnAlreadyCorrectReverseOrientation() {
        assertEquals(3, requestedDisplayRotation(6, 1080, 2340, 3))
        assertEquals(1, requestedDisplayRotation(11, 1080, 2340, 0))
        assertEquals(2, requestedDisplayRotation(7, 1080, 2340, 2))
        assertEquals(0, requestedDisplayRotation(12, 1080, 2340, 3))
    }

    @Test fun runtimeDumpReadsOnlyTheOwnedTaskAndTheActualRequestedField() {
        val dump = """
            Display #0
              * Hist #0: ActivityRecord{aaa u0 com.game/.Main t12}
                requestedOrientation=SCREEN_ORIENTATION_PORTRAIT
            Display #8
              * Hist #0: ActivityRecord{bbb u0 com.game/.Main t44}
                overrideOrientation=SCREEN_ORIENTATION_PORTRAIT
                requestedOrientation=SCREEN_ORIENTATION_REVERSE_LANDSCAPE
              * Hist #1: ActivityRecord{ccc u0 com.game/.Older t44}
                requestedOrientation=SCREEN_ORIENTATION_PORTRAIT
        """.trimIndent()
        assertEquals(8, requestedOrientationFromActivityDump(dump, 44, "com.game/.Main"))
        assertEquals(1, requestedOrientationFromActivityDump(dump, 12, "com.game/.Main"))
        assertNull(requestedOrientationFromActivityDump(dump, 45, "com.game/.Main"))
        assertNull(requestedOrientationFromActivityDump(dump, 44, "com.other/.Main"))
        assertNull(requestedOrientationFromActivityDump("requestedOrientation=LANDSCAPE", 44, "com.game/.Main"))
    }

    @Test fun runtimeCanReleaseAFixedManifestRequestAndUnknownDumpDoesNotInventOne() {
        fun fixture(value: String) = "  * Hist #0: ActivityRecord{bbb u0 com.game/.Main t44}\n    requestedOrientation=$value"
        assertEquals(-1, requestedOrientationFromActivityDump(fixture("SCREEN_ORIENTATION_UNSPECIFIED"), 44, "com.game/.Main"))
        assertEquals(9, requestedOrientationFromActivityDump(fixture("9"), 44, "com.game/.Main"))
        assertNull(requestedOrientationFromActivityDump(fixture("BAD_VALUE"), 44, "com.game/.Main"))
    }

    @Test fun unknownOrFreeRequestsDoNotInventAnOrientationAndNaturalLandscapeIsSupported() {
        for (request in listOf(-1, 2, 3, 4, 5, 10, 13, 14)) {
            assertNull(requestedDisplayRotation(request, 1080, 2340, 1))
        }
        assertEquals(0, requestedDisplayRotation(0, 2340, 1080, 0))
        assertEquals(2, requestedDisplayRotation(8, 2340, 1080, 0))
        assertEquals(1, requestedDisplayRotation(1, 2340, 1080, 0))
        assertEquals(3, requestedDisplayRotation(9, 2340, 1080, 0))
    }
}
