package com.constx.onvyra.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoBrightnessScaleTest {
    @Test fun standardPanelUsesItsDeclaredRange() {
        assertEquals(.5f, fraction(127, maximum = 254), .0001f)
    }

    @Test fun measuredOnePlusUsesTwelveBitRangeDespiteLegacyResource() {
        assertEquals(1000f / 4095, fraction(1000, "OnePlus", "PLC110"), .0001f)
        assertEquals(1f, fraction(4095, "OnePlus", "PLC110"), .0001f)
    }

    @Test fun measuredPanelKeepsTheSameScaleBelow255() {
        assertEquals(255f / 4095, fraction(255, "OnePlus", "PLC110"), .0001f)
    }

    @Test fun calibrationDoesNotApplyToOtherOnePlusModels() {
        assertEquals(.5f, fraction(1000, "OnePlus", "other"), .0001f)
        assertEquals(127f / 255, fraction(127, "OnePlus", "other"), .0001f)
    }

    @Test fun vendorDeclaredExtendedRangeIsRespected() {
        assertEquals(.5f, fraction(1023, maximum = 2046), .0001f)
    }

    @Test fun invalidAndUnknownRangesFallBackWithoutForcingMaximum() {
        assertEquals(.5f, fraction(null), .0001f)
        assertEquals(.5f, fraction(-1), .0001f)
        assertEquals(.5f, fraction(4095), .0001f)
        assertEquals(127f / 255, fraction(127, maximum = 0), .0001f)
    }

    private fun fraction(raw: Int?, manufacturer: String = "generic", model: String = "generic", maximum: Int? = 255) =
        videoSystemBrightnessFraction(raw, manufacturer, model, maximum)
}
