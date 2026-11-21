package me.rerere.androidcontrol.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal enum class PreviewMenuIcon { FULLSCREEN, MINIMIZE, CLOSE }

/** Fixed paths keep the three controls consistent across fonts, emoji sets and Android versions. */
internal class PreviewMenuIconDrawable(private val icon: PreviewMenuIcon) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(29, 29, 31)
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fullscreen = Path().apply {
        moveTo(3.5f, 9f); lineTo(3.5f, 3.5f); lineTo(9f, 3.5f)
        moveTo(15f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 9f)
        moveTo(20.5f, 15f); lineTo(20.5f, 20.5f); lineTo(15f, 20.5f)
        moveTo(9f, 20.5f); lineTo(3.5f, 20.5f); lineTo(3.5f, 15f)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val scale = minOf(bounds.width(), bounds.height()) / 24f
        val save = canvas.save()
        canvas.translate(bounds.exactCenterX() - 12f * scale, bounds.exactCenterY() - 12f * scale)
        canvas.scale(scale, scale)
        when (icon) {
            PreviewMenuIcon.FULLSCREEN -> canvas.drawPath(fullscreen, stroke)
            PreviewMenuIcon.MINIMIZE -> {
                canvas.drawRoundRect(3.5f, 3.5f, 20.5f, 20.5f, 1.8f, 1.8f, stroke)
                canvas.drawLine(8f, 12f, 16f, 12f, stroke)
            }
            PreviewMenuIcon.CLOSE -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                canvas.drawLine(8.5f, 8.5f, 15.5f, 15.5f, stroke)
                canvas.drawLine(15.5f, 8.5f, 8.5f, 15.5f, stroke)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
