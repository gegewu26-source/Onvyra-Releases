package com.constx.onvyra.ui.components.message

/** OEM settings can use hardware units even though Window brightness always uses 0..1. */
internal fun videoSystemBrightnessFraction(
    raw: Int?,
    manufacturer: String,
    model: String,
    resourceMaximum: Int?,
): Float {
    // Verified on PLC110: normal maximum is 4095, while the legacy framework integer says 255.
    // Limit this calibration to the measured model, including its low (<255) brightness values.
    val maximum = if (manufacturer.equals("OnePlus", ignoreCase = true) && model == "PLC110") {
        4095
    } else {
        resourceMaximum?.takeIf { it > 0 } ?: 255
    }
    // Never guess the scale of an unknown extended-range panel by clamping it to full brightness.
    return if (raw != null && raw in 0..maximum) raw.toFloat() / maximum else .5f
}
