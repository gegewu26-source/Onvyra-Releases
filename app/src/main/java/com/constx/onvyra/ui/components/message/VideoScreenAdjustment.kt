package com.constx.onvyra.ui.components.message

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Window
import kotlin.math.roundToInt

internal data class VideoScreenAdjustmentPreview(
    val brightness: Boolean,
    val initialFraction: Float,
    val fraction: Float,
    val minimumVolume: Int = 0,
    val maximumVolume: Int = 0,
)

/** Brightness belongs to the fullscreen window; volume is the user's media stream. */
internal class VideoScreenAdjustment(private val context: Context, private val window: Window) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousWindowBrightness = window.attributes.screenBrightness

    fun begin(brightness: Boolean): VideoScreenAdjustmentPreview? {
        if (brightness) {
            val current = window.attributes.screenBrightness.takeIf { it.isFinite() && it >= 0f }
                ?: systemBrightnessFraction()
            val fraction = current.coerceIn(.02f, 1f)
            return VideoScreenAdjustmentPreview(true, fraction, fraction)
        }
        if (audio.isVolumeFixed) return null
        val minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maximum <= minimum) return null
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(minimum, maximum)
        val fraction = (current - minimum).toFloat() / (maximum - minimum)
        return VideoScreenAdjustmentPreview(false, fraction, fraction, minimum, maximum)
    }

    fun update(preview: VideoScreenAdjustmentPreview, upwardFraction: Float): VideoScreenAdjustmentPreview {
        val delta = upwardFraction.takeIf(Float::isFinite) ?: 0f
        val requested = (preview.initialFraction + delta).coerceIn(if (preview.brightness) .02f else 0f, 1f)
        return if (preview.brightness) {
            window.attributes = window.attributes.apply { screenBrightness = requested }
            preview.copy(fraction = requested)
        } else {
            val range = preview.maximumVolume - preview.minimumVolume
            val target = (preview.minimumVolume + requested * range).roundToInt()
                .coerceIn(preview.minimumVolume, preview.maximumVolume)
            // Flags=0 avoids showing a second, system volume popup over the player.
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
            preview.copy(fraction = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) - preview.minimumVolume).toFloat() / range)
        }
    }

    fun restoreWindowBrightness() {
        window.attributes = window.attributes.apply { screenBrightness = previousWindowBrightness }
    }

    private fun systemBrightnessFraction(): Float {
        val resolver = context.contentResolver
        val normalized = runCatching { Settings.System.getFloat(resolver, "screen_brightness_float") }.getOrNull()
        if (normalized != null && normalized.isFinite() && normalized in 0f..1f) return normalized
        val raw = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        val maximum = runCatching {
            val id = context.resources.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            if (id == 0) null else context.resources.getInteger(id)
        }.getOrNull()
        return videoSystemBrightnessFraction(raw, Build.MANUFACTURER, Build.MODEL, maximum)
    }
}
