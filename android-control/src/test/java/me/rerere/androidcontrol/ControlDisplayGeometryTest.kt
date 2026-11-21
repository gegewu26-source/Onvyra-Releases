package me.rerere.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class ControlDisplayGeometryTest {
    @Test fun nativeFloatingProfileUsesSixteenByNineAndKeepsDensity() {
        assertEquals(ControlDisplayGeometry(1080, 1920, 420), floatingDisplayGeometry(1080, 2340, 420))
        assertEquals(ControlDisplayGeometry(1920, 1080, 420), floatingDisplayGeometry(2340, 1080, 420))
        assertEquals(ControlDisplayGeometry(1272, 2261, 480), floatingDisplayGeometry(1272, 2800, 480))
    }

    @Test fun phoneResolutionAndDensityRemainExactlyTheSameAsTheMainDisplay() {
        assertEquals(ControlDisplayGeometry(1272, 2800, 480), phoneDisplayGeometry(1272, 2800, 480))
        assertEquals(ControlDisplayGeometry(1272, 2800, 560), phoneDisplayGeometry(1272, 2800, 560))
        assertEquals(ControlDisplayGeometry(1440, 3200, 560), phoneDisplayGeometry(1440, 3200, 560))
        assertEquals(ControlDisplayGeometry(2800, 1272, 480), phoneDisplayGeometry(2800, 1272, 480))
    }

    @Test fun oversizedProfilesUseOneScaleForPixelsAndDensity() {
        val native = ControlDisplayGeometry(2160, 4800, 720)
        val actual = phoneDisplayGeometry(native.width, native.height, native.dpi)
        assertTrue(actual.width in 120..4096 && actual.height in 120..4096 && actual.dpi in 72..640)
        assertEquals(4096, actual.height)
        // The app must still see the same logical phone viewport, within integer rounding.
        assertEquals(native.width * 160.0 / native.dpi, actual.width * 160.0 / actual.dpi, 1.0)
        assertEquals(native.height * 160.0 / native.dpi, actual.height * 160.0 / actual.dpi, 1.0)
        assertEquals(ControlDisplayGeometry(4096, 2048, 640), phoneDisplayGeometry(8192, 4096, 1280))
    }

    @Test fun completeLowLevelProfilesAreKeptWithoutIndependentClamping() {
        assertEquals(ControlDisplayGeometry(720, 1280, 240), explicitDisplayGeometry(720, 1280, 240))
        assertEquals(ControlDisplayGeometry(320, 640, 160), explicitDisplayGeometry(320, 640, 160))
        assertThrows(IllegalArgumentException::class.java) { explicitDisplayGeometry(99, 1280, 240) }
        assertThrows(IllegalArgumentException::class.java) { explicitDisplayGeometry(720, 5000, 240) }
        assertThrows(IllegalArgumentException::class.java) { explicitDisplayGeometry(720, 1280, 900) }
    }

    @Test fun invalidNativeMetricsCannotSilentlyChangeTheViewport() {
        assertThrows(IllegalArgumentException::class.java) { phoneDisplayGeometry(0, 1280, 240) }
        assertThrows(IllegalArgumentException::class.java) { phoneDisplayGeometry(720, 0, 240) }
        assertThrows(IllegalArgumentException::class.java) { phoneDisplayGeometry(720, 1280, 0) }
        assertThrows(IllegalArgumentException::class.java) { phoneDisplayGeometry(120, 100000, 640) }
    }
}
