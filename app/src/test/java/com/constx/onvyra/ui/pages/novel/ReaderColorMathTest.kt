package com.constx.onvyra.ui.pages.novel

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReaderColorMathTest {
    @Test fun wheelCenterAndOutsideDragStayWithinTheSelectedDisc() {
        val center = ReaderColorMath.fromPoint(100f, 100f, 200f, 200f, 1f)
        assertEquals(0xFFFFFFFF, ReaderColorMath.toColor(center))
        val farRight = ReaderColorMath.fromPoint(400f, 100f, 200f, 200f, 1f)
        assertEquals(1f, farRight.saturation, .0001f)
        assertEquals(0xFFFF0000, ReaderColorMath.toColor(farRight))
        val lowerEdge = ReaderColorMath.fromPoint(100f, 200f, 200f, 200f, 1f)
        assertEquals(90f, lowerEdge.hue, .0001f)
    }

    @Test fun customColorsRoundTripAndAlwaysUseOpaqueReadableText() {
        for (color in listOf(0xFF001122L, 0xFFEE76A5L, 0xFFE6DFC8L, 0xFF32C6F5L)) {
            assertEquals(color, ReaderColorMath.toColor(ReaderColorMath.fromColor(color)))
        }
        assertEquals(0xFFFFFFFF, ReaderColorMath.textColor(0xFF000000))
        assertEquals(0xFF000000, ReaderColorMath.textColor(0xFFFFFFFF))
        assertEquals(0xFFFF0000, NovelReaderSettings(customBackgroundColor = 0x00FF0000).readerBackgroundColor())
        for (shade in 0..255 step 5) {
            val background = 0xFF000000L or (shade.toLong() shl 16) or (shade.toLong() shl 8) or shade.toLong()
            val back = ReaderColorMath.luminance(background)
            val text = ReaderColorMath.luminance(ReaderColorMath.textColor(background))
            val contrast = (maxOf(back, text) + .05) / (minOf(back, text) + .05)
            assertTrue("Text must remain readable across custom background brightness", contrast >= 4.5)
        }
    }

    @Test fun oldNightSettingsStillDecodeAndDefaultCustomColorIsAbsent() {
        val old = Json.decodeFromString<NovelReaderSettings>("""{"theme":"NIGHT"}""")
        assertNull(old.customBackgroundColor)
        assertEquals(NovelReaderTheme.NIGHT.backgroundColorHex, old.readerBackgroundColor())
        assertTrue(old.isReaderDark())
        val custom = old.copy(customBackgroundColor = 0xFFE6DFC8)
        assertEquals(0xFFE6DFC8, custom.readerBackgroundColor())
        assertEquals(0xFF000000, custom.readerTextColor())
        assertFalse(custom.isReaderDark())
    }
}
