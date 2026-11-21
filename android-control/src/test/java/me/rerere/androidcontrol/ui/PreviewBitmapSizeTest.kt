package me.rerere.androidcontrol.ui

import org.junit.Assert.*
import org.junit.Test

class PreviewBitmapSizeTest {
    @Test fun preservesPortraitAndLandscapeWithoutCropping() {
        assertEquals(360 to 640, previewBitmapSize(720,1280,360))
        assertEquals(360 to 202, previewBitmapSize(1920,1080,360))
        assertEquals(90 to 160, previewBitmapSize(90,160,360))
    }
    @Test fun handlesTallBoundsAndUsesLongMathBeforeScaling() {
        assertEquals(360 to 864, previewBitmapSize(1000,2400,360))
        assertEquals(360 to 360, previewBitmapSize(Int.MAX_VALUE,Int.MAX_VALUE,360))
        assertEquals(1 to 1, previewBitmapSize(720,1,1))
    }
    @Test fun rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) { previewBitmapSize(0,1280,360) }
        assertThrows(IllegalArgumentException::class.java) { previewBitmapSize(720,-1,360) }
        assertThrows(IllegalArgumentException::class.java) { previewBitmapSize(720,1280,0) }
    }
}
