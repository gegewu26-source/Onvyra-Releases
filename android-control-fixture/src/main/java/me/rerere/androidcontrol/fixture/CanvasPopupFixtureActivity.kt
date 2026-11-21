package me.rerere.androidcontrol.fixture

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View

/** Game-like pixels only: neither painted action is an accessibility button or text node. */
class CanvasPopupFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContentView(CanvasPopupView(this))
        val displaySize = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        window.setGravity(Gravity.CENTER)
        // A deliberately inset app window makes image pixels differ from display coordinates.
        window.setLayout((displaySize.x * .82f).toInt(), (displaySize.y * .70f).toInt())
    }
}

private class CanvasPopupView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val confirm = RectF()
    private val resume = RectF()
    private var confirmed = false
    private var playing = false
    private var misses = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = false
        isLongClickable = false
        isFocusable = false
        contentDescription = "Canvas popup awaiting first frame"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A readable accessibility tree can precede the first rendered surface buffer.
        // Publish test readiness only after HWUI has committed the actual painted frame.
        viewTreeObserver.registerFrameCommitCallback {
            post { contentDescription = "Canvas popup surface" }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(20, 28, 48))
        val margin = width * .12f
        val buttonHeight = height * .13f
        confirm.set(margin, height * .40f, width - margin, height * .40f + buttonHeight)
        resume.set(margin, height * .63f, width - margin, height * .63f + buttonHeight)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = width * .055f
        canvas.drawText("游戏弹窗", width / 2f, height * .15f, paint)
        paint.textSize = width * .046f
        canvas.drawText(if (playing) "CANVAS PLAYING" else if (confirmed) "CANVAS CONFIRMED" else "CANVAS WAITING",
            width / 2f, height * .25f, paint)
        paint.textSize = width * .035f
        canvas.drawText("MISSES $misses", width / 2f, height * .88f, paint)
        drawButton(canvas, confirm, "确定", "CONFIRM", Color.rgb(29, 112, 210))
        drawButton(canvas, resume, "继续游戏", "RESUME", Color.rgb(24, 124, 86))
    }

    private fun drawButton(canvas: Canvas, bounds: RectF, label: String, english: String, color: Int) {
        paint.color = color
        canvas.drawRoundRect(bounds, width * .035f, width * .035f, paint)
        paint.color = Color.WHITE
        paint.textSize = width * .085f
        paint.textAlign = Paint.Align.CENTER
        val baseline = bounds.centerY() - bounds.height() * .13f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, bounds.centerX(), baseline, paint)
        paint.textSize = width * .04f
        canvas.drawText(english, bounds.centerX(), bounds.bottom - bounds.height() * .10f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                confirm.contains(event.x, event.y) -> confirmed = true
                resume.contains(event.x, event.y) -> playing = true
                else -> misses++
            }
            invalidate()
        }
        return true
    }
}
