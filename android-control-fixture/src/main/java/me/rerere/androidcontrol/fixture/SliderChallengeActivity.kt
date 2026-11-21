package me.rerere.androidcontrol.fixture

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Slider drag challenge rendered entirely on Canvas (no accessibility nodes),
 * judged like slidex's slider_sim: non-uniform speed, Y-axis tremor, end dwell,
 * no teleport steps, duration window, and the piece landing inside the gap.
 *
 * Geometry rule that makes local solving possible: the puzzle piece moves
 * 1:1 with the knob's center in the SAME pixel space as the gap. A solver that
 * measures gapX - knobCenterX therefore lands the piece exactly on the gap.
 */
class SliderChallengeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(SliderChallengeView(this))
        val displaySize = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
        window.setGravity(Gravity.CENTER)
        // Inset window so image pixels differ from display coordinates, like CanvasPopupFixtureActivity.
        window.setLayout((displaySize.x * .92f).toInt(), (displaySize.y * .78f).toInt())
    }
}

private class SliderChallengeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val scene = Bitmap.createBitmap(600, 260, Bitmap.Config.ARGB_8888)

    /** Logical 600x260 scene space; piece and gap live here and are scaled together. */
    private val sceneW = 600f
    private val sceneH = 260f
    private val pieceW = 72f
    private val pieceH = 96f
    private val pieceRestLeft = 18f
    private val gapLeft: Float
    private val gapTop = 82f
    private val pieceTop = 82f

    private val puzzleRect = RectF()
    private val trackRect = RectF()
    private val handleRect = RectF()
    private var knobProgress = 0f
    private var passed = false
    private var failedReason: String? = null
    private var dragStarted = false

    private val samples = ArrayList<FloatArray>() // [x, y, tMs]
    private var downTime = 0L

    init {
        gapLeft = Random.nextInt(190, (sceneW - pieceW - 24f).toInt()).toFloat()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = false
        isFocusable = false
        contentDescription = "拼图对齐挑战"
        paintScene()
    }

    /** Painted scene: smooth gradients plus structural detail, like a real puzzle image. */
    private fun paintScene() {
        val c = Canvas(scene)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Sky-to-ground gradient: broad luminance steps the solver can key on.
        for (y in 0 until sceneH.toInt()) {
            val t = y / sceneH
            p.color = Color.rgb(
                (40 + 120 * t).toInt(),
                (70 + 100 * t).toInt(),
                (120 + 80 * (1 - t)).toInt(),
            )
            c.drawRect(0f, y.toFloat(), sceneW, y + 1f, p)
        }
        // Horizontal bands and vertical posts: coherent edges on many rows.
        p.color = Color.rgb(210, 200, 170)
        c.drawRect(0f, 150f, sceneW, 178f, p)
        p.color = Color.rgb(60, 80, 60)
        var x = 40f
        while (x < sceneW) {
            c.drawRect(x, 150f, x + 16f, sceneH, p)
            x += 90f
        }
        p.color = Color.rgb(235, 235, 245)
        c.drawCircle(120f, 46f, 22f, p)
        c.drawCircle(430f, 38f, 15f, p)
    }

    /** Scene-space -> view-space scale, shared by piece, gap and knob. */
    private fun scale(): Float = (width * .88f) / sceneW
    private fun sceneLeft(): Float = width * .06f
    private fun sceneTop(): Float = height * .10f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(24, 30, 52))
        val s = scale()
        val left = sceneLeft()
        val top = sceneTop()

        // Puzzle background.
        puzzleRect.set(left, top, left + sceneW * s, top + sceneH * s)
        canvas.drawBitmap(scene, null, puzzleRect, null)

        // Gap: recessed slot, drawn ON the scene so its borders are real edges.
        paint.color = Color.argb(240, 12, 16, 28)
        canvas.drawRect(left + gapLeft * s, top + gapTop * s,
            left + (gapLeft + pieceW) * s, top + (gapTop + pieceH) * s, paint)
        paint.color = Color.argb(120, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(left + gapLeft * s, top + gapTop * s,
            left + (gapLeft + pieceW) * s, top + (gapTop + pieceH) * s, paint)
        paint.style = Paint.Style.FILL

        // Piece: travels 1:1 with the knob center, same scene space as the gap.
        val pieceLeft = pieceRestLeft + knobTravelScene()
        paint.color = Color.rgb(110, 170, 255)
        canvas.drawRect(left + pieceLeft * s, top + pieceTop * s,
            left + (pieceLeft + pieceW) * s, top + (pieceTop + pieceH) * s, paint)
        paint.color = Color.argb(200, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        canvas.drawRect(left + pieceLeft * s, top + pieceTop * s,
            left + (pieceLeft + pieceW) * s, top + (pieceTop + pieceH) * s, paint)
        paint.style = Paint.Style.FILL

        // Track: a rounded bar below the puzzle.
        val trackTop = top + (sceneH + 34f) * s
        val trackH = 26f * s
        trackRect.set(left, trackTop, left + sceneW * s, trackTop + trackH)
        paint.color = Color.rgb(45, 55, 84)
        canvas.drawRoundRect(trackRect, trackH / 2, trackH / 2, paint)

        // Knob: a SQUARE that slides across the track; its center is the drag anchor.
        val knobSize = trackH * 1.7f
        val trackInner = trackRect.width() - knobSize
        val knobCenterX = trackRect.left + knobSize / 2 + knobProgress * trackInner
        handleRect.set(knobCenterX - knobSize / 2, trackRect.centerY() - knobSize / 2,
            knobCenterX + knobSize / 2, trackRect.centerY() + knobSize / 2)
        paint.color = Color.WHITE
        canvas.drawRoundRect(handleRect, knobSize * .18f, knobSize * .18f, paint)
        paint.color = Color.rgb(29, 112, 210)
        canvas.drawRoundRect(RectF(handleRect.centerX() - knobSize * .22f, handleRect.centerY() - knobSize * .04f,
            handleRect.centerX() + knobSize * .22f, handleRect.centerY() + knobSize * .04f),
            knobSize * .04f, knobSize * .04f, paint)

        // Status text.
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = width * .05f
        canvas.drawText("拼图对齐挑战", width / 2f, height * .05f, paint)
        paint.textSize = width * .042f
        val status = when {
            passed -> "PASSED ✓"
            failedReason != null -> "FAILED: $failedReason"
            dragStarted -> "DRAGGING..."
            else -> "向右拖动滑块完成拼图"
        }
        canvas.drawText(status, width / 2f, height * .95f, paint)
    }

    /** Knob travel converted into scene-space piece offset (1:1 in view pixels). */
    private fun knobTravelScene(): Float {
        val s = scale()
        if (s <= 0f) return 0f
        val knobSize = 26f * s * 1.7f
        val trackInner = trackRect.width() - knobSize
        return (knobProgress * trackInner) / s
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (handleRect.contains(event.x, event.y)) {
                    dragStarted = true
                    downTime = event.eventTime
                    samples.clear()
                    samples.add(floatArrayOf(event.x, event.y, 0f))
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragStarted) {
                samples.add(floatArrayOf(event.x, event.y, (event.eventTime - downTime).toFloat()))
                val knobSize = 26f * scale() * 1.7f
                val trackInner = (trackRect.width() - knobSize).coerceAtLeast(1f)
                knobProgress = ((event.x - (trackRect.left + knobSize / 2)) / trackInner).coerceIn(0f, 1f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragStarted) {
                samples.add(floatArrayOf(event.x, event.y, (event.eventTime - downTime).toFloat()))
                failedReason = judge()
                passed = failedReason == null
                invalidate()
                if (!passed) postDelayed({
                    failedReason = null; knobProgress = 0f; dragStarted = false; invalidate()
                }, 2500)
            }
        }
        return true
    }

    /** slidex slider_sim-style judgement over the recorded touch stream. */
    private fun judge(): String? {
        if (samples.size < 12) return "too_few_points"
        val totalMs = samples.last()[2]
        if (totalMs < 500 || totalMs > 5000) return "duration_${totalMs.toInt()}ms"
        var maxStep = 0f
        val speeds = ArrayList<Float>()
        var tremorCount = 0
        for (i in 1 until samples.size) {
            val dx = samples[i][0] - samples[i - 1][0]
            val dy = abs(samples[i][1] - samples[i - 1][1])
            val dt = (samples[i][2] - samples[i - 1][2]).coerceAtLeast(1f)
            maxStep = maxOf(maxStep, abs(dx))
            if (dy >= 1f) tremorCount++
            if (dx != 0f) speeds.add(abs(dx) / dt * 1000f)
        }
        if (maxStep > 40f) return "teleport_${maxStep.toInt()}px"
        if (tremorCount < 8) return "no_y_tremor"
        if (speeds.size < 8) return "too_few_moves"
        val mean = speeds.average()
        if (mean <= 0) return "no_movement"
        val sd = sqrt(speeds.map { (it - mean) * (it - mean) }.average())
        if (sd / mean <= 0.25) return "uniform_speed"
        // End dwell: the final 300ms stays within 3px of the end position.
        val endX = samples.last()[0]
        var i = samples.size - 1
        while (i >= 0 && samples[i][2] >= totalMs - 300) {
            if (abs(samples[i][0] - endX) > 3f) return "no_end_dwell"
            i--
        }
        // Landing: the piece's left edge must be within 40% of a piece width of the gap.
        val pieceLeftScene = pieceRestLeft + knobTravelScene()
        if (abs(pieceLeftScene - gapLeft) > pieceW * 0.4f) {
            return "miss_${(pieceLeftScene - gapLeft).toInt()}px"
        }
        return null
    }
}
