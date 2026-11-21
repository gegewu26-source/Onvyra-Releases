package me.rerere.androidcontrol.captcha

/**
 * Geometry for a captcha crop request.
 *
 * The agent works in display pixels or in the pixels of the screenshot it was
 * given, but the recogniser must see the raw window bitmap. These helpers keep
 * the two conversions explicit so a crop can never be silently off by the
 * window origin (the same class of bug that used to move slider targets).
 */
internal object CaptchaRegion {

    /** Window-local rectangle using ddddocr/MS-style inclusive-exclusive edges. */
    fun windowRect(
        rect: List<Int>, windowWidth: Int, windowHeight: Int,
    ): IntArray {
        require(rect.size == 4) { "invalid_captcha_region" }
        val left = rect[0].coerceIn(0, windowWidth - 1)
        val top = rect[1].coerceIn(0, windowHeight - 1)
        val right = rect[2].coerceIn(left + 1, windowWidth)
        val bottom = rect[3].coerceIn(top + 1, windowHeight)
        require(right - left >= 8 && bottom - top >= 8) { "captcha_region_too_small" }
        return intArrayOf(left, top, right, bottom)
    }

    /**
     * Converts display pixels to window-local pixels by subtracting the window
     * origin. Negative or out-of-window results are rejected rather than clamped,
     * because clamping would read the wrong part of the screen.
     */
    fun displayToWindow(rect: List<Int>, windowLeft: Int, windowTop: Int,
        windowWidth: Int, windowHeight: Int): IntArray {
        require(rect.size == 4) { "invalid_captcha_region" }
        val local = intArrayOf(rect[0] - windowLeft, rect[1] - windowTop, rect[2] - windowLeft, rect[3] - windowTop)
        require(local[0] >= 0 && local[1] >= 0 && local[2] <= windowWidth && local[3] <= windowHeight &&
            local[2] > local[0] && local[3] > local[1]) { "captcha_region_outside_window" }
        return windowRect(local.toList(), windowWidth, windowHeight)
    }

    /** Scales a rectangle expressed in screenshot pixels onto the raw window bitmap. */
    fun imageToWindow(rect: List<Int>, imageWidth: Int, imageHeight: Int,
        windowWidth: Int, windowHeight: Int): IntArray {
        require(rect.size == 4 && imageWidth > 0 && imageHeight > 0) { "invalid_captcha_region" }
        require(rect[0] >= 0 && rect[1] >= 0 && rect[2] <= imageWidth && rect[3] <= imageHeight &&
            rect[2] > rect[0] && rect[3] > rect[1]) { "captcha_region_outside_image" }
        val scaled = intArrayOf(
            (rect[0].toLong() * windowWidth / imageWidth).toInt(),
            (rect[1].toLong() * windowHeight / imageHeight).toInt(),
            (rect[2].toLong() * windowWidth / imageWidth).toInt(),
            (rect[3].toLong() * windowHeight / imageHeight).toInt(),
        )
        return windowRect(scaled.toList(), windowWidth, windowHeight)
    }

    /**
     * A captcha field is wide and short; this rejects a whole-screen crop that
     * would make the recogniser read every label on the page instead of the code.
     */
    fun looksLikeCaptchaField(width: Int, height: Int): Boolean {
        if (width < 16 || height < 8) return false
        val ratio = width.toDouble() / height
        return ratio in 1.2..8.0 && height <= width
    }
}