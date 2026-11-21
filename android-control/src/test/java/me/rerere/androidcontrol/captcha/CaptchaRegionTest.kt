package me.rerere.androidcontrol.captcha

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaRegionTest {

    @Test fun displayRectangleLosesTheWindowOrigin() {
        // Window sits at (40, 90) inside the display; a display-space crop of
        // the captcha must become window-local before it is cut out.
        val rect = CaptchaRegion.displayToWindow(listOf(140, 190, 340, 250), 40, 90, 720, 400)
        assertArrayEquals(intArrayOf(100, 100, 300, 160), rect)
    }

    @Test fun displayRectangleOutsideTheWindowIsRejectedNotClamped() {
        // Clamping here would silently read the wrong pixels.
        val error = runCatching {
            CaptchaRegion.displayToWindow(listOf(0, 0, 200, 50), 40, 90, 720, 400)
        }.exceptionOrNull()
        assertTrue(error?.message == "captcha_region_outside_window")
    }

    @Test fun screenshotPixelsScaleOntoTheRawWindowBitmap() {
        // The upload is downscaled to 1280 max side; the raw capture is larger.
        val rect = CaptchaRegion.imageToWindow(listOf(100, 50, 300, 100), 640, 320, 1280, 640)
        assertArrayEquals(intArrayOf(200, 100, 600, 200), rect)
    }

    @Test fun imageRectangleOutsideTheImageIsRejected() {
        val error = runCatching {
            CaptchaRegion.imageToWindow(listOf(-1, 0, 100, 50), 640, 320, 1280, 640)
        }.exceptionOrNull()
        assertTrue(error?.message == "captcha_region_outside_image")
    }

    @Test fun tinyRegionsAreRejectedAsNotACaptchaField() {
        val error = runCatching { CaptchaRegion.windowRect(listOf(10, 10, 14, 13), 720, 400) }
            .exceptionOrNull()
        assertTrue(error?.message == "captcha_region_too_small")
    }

    @Test fun fieldShapeAcceptsCaptchaSizedBoxesAndRejectsWholePages() {
        assertTrue(CaptchaRegion.looksLikeCaptchaField(120, 40))   // 3:1, typical
        assertTrue(CaptchaRegion.looksLikeCaptchaField(200, 60))
        assertFalse("a tall column is not a captcha", CaptchaRegion.looksLikeCaptchaField(80, 400))
        assertFalse("a square icon grid is not a captcha field", CaptchaRegion.looksLikeCaptchaField(300, 300))
        assertFalse("a whole page is not a captcha", CaptchaRegion.looksLikeCaptchaField(720, 1600))
    }

    @Test fun rectsAreClampedToTheWindowForTheWindowSpacePath() {
        assertArrayEquals(intArrayOf(0, 0, 720, 400),
            CaptchaRegion.windowRect(listOf(-20, -20, 900, 900), 720, 400))
    }
}