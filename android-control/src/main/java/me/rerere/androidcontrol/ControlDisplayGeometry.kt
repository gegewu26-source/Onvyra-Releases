package me.rerere.androidcontrol

import kotlin.math.roundToInt

internal data class ControlDisplayGeometry(val width: Int, val height: Int, val dpi: Int)

/** Keep both physical size and density aligned with the active phone display. */
internal fun phoneDisplayGeometry(width: Int, height: Int, dpi: Int): ControlDisplayGeometry {
    require(width > 0 && height > 0 && dpi > 0) { "invalid_phone_display_metrics" }
    // Scale all three together only when the helper cannot represent the native profile.
    val scale = minOf(1.0, 4096.0 / width, 4096.0 / height, 640.0 / dpi)
    return explicitDisplayGeometry(
        (width * scale).roundToInt(),
        (height * scale).roundToInt(),
        (dpi * scale).roundToInt(),
    )
}

/** Low-level callers may explicitly request a complete profile; never clamp its axes independently. */
internal fun explicitDisplayGeometry(width: Int, height: Int, dpi: Int): ControlDisplayGeometry {
    require(width in 120..4096 && height in 120..4096 && dpi in 72..640) { "invalid_display_geometry" }
    return ControlDisplayGeometry(width, height, dpi)
}

/** Native-style small windows use 9:16, keeping the phone's short-edge pixels and density. */
internal fun floatingDisplayGeometry(width: Int, height: Int, dpi: Int): ControlDisplayGeometry {
    require(width > 0 && height > 0 && dpi > 0) { "invalid_phone_display_metrics" }
    val short = minOf(width, height)
    val long = (short * 16.0 / 9.0).roundToInt()
    return if (width > height) phoneDisplayGeometry(long, short, dpi)
        else phoneDisplayGeometry(short, long, dpi)
}
