package com.constx.onvyra.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.constx.onvyra.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** A small system overlay owned and destroyed by [ChatKeepAliveService]. No activity is retained. */
internal class ChatFloatingBall(
    private val context: Context,
    private val onClick: () -> Unit,
    private val onWindowFailure: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val size = (52 * context.resources.displayMetrics.density).roundToInt()
    private val peekSize = (12 * context.resources.displayMetrics.density).roundToInt()
    private var view: BallView? = null
    private var animator: ValueAnimator? = null
    private var bounds = usableBounds()
    private var ballX = bounds.maxX
    private var tucked = false
    private var dockedOnRight = true
    private val params = WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = bounds.maxX
        y = (bounds.maxY * .6f).roundToInt()
        // Relative to the safe content area: the system keeps the window clear of bars and cutouts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setFitInsetsIgnoringVisibility(true)
        }
        title = "${context.getString(R.string.app_name)} floating shortcut"
    }
    private val dock = Runnable { dockToEdge() }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        val ball = BallView(context, size).apply {
            contentDescription = "${context.getString(R.string.app_name)} 悬浮球；点击返回应用，拖动调整位置"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            setOnClickListener {
                handler.removeCallbacks(dock)
                animator?.cancel()
                // A tap on the exposed edge only reveals the ball, including during its animation.
                if (tucked || ballX != bounds.clampX(ballX)) {
                    tucked = false
                    updateDescription()
                    animateTo(bounds.clampX(ballX), targetAlpha = 1f)
                } else {
                    onClick()
                }
                scheduleDock()
            }
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        ball.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(dock)
                    animator?.cancel()
                    touchedView.alpha = 1f
                    downX = event.rawX
                    downY = event.rawY
                    startX = bounds.clampX(ballX)
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragging = true
                    if (dragging) {
                        if (tucked) {
                            tucked = false
                            updateDescription()
                        }
                        ballX = bounds.clampX(startX + dx.roundToInt())
                        params.y = bounds.clampY(startY + dy.roundToInt())
                        dockedOnRight = bounds.nearestEdgeX(ballX) > 0
                        updateWindow()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) touchedView.performClick()
                    scheduleDock()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    scheduleDock()
                    true
                }

                else -> false
            }
        }
        windowManager.addView(ball, params)
        view = ball
        scheduleDock()
    }

    fun onConfigurationChanged() {
        animator?.cancel()
        handler.removeCallbacks(dock)
        bounds = usableBounds()
        ballX = if (tucked) bounds.tuckedX(dockedOnRight, peekSize) else if (dockedOnRight) bounds.maxX else 0
        params.y = bounds.clampY(params.y)
        updateWindow()
        if (!tucked) scheduleDock()
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        animator?.cancel()
        animator = null
        view?.let { runCatching { windowManager.removeViewImmediate(it) } }
        view = null
    }

    private fun scheduleDock() {
        handler.removeCallbacks(dock)
        if (view != null) handler.postDelayed(dock, 3_000L)
    }

    private fun dockToEdge() {
        if (view == null) return
        dockedOnRight = bounds.nearestEdgeX(ballX) > 0
        tucked = true
        updateDescription()
        animateTo(bounds.tuckedX(dockedOnRight, peekSize), targetAlpha = .6f)
    }

    private fun animateTo(destination: Int, targetAlpha: Float) {
        animator?.cancel()
        val initialAlpha = view?.alpha ?: return
        animator = ValueAnimator.ofInt(ballX, destination).apply {
            duration = 240
            addUpdateListener {
                ballX = it.animatedValue as Int
                view?.alpha = initialAlpha + (targetAlpha - initialAlpha) * it.animatedFraction
                updateWindow()
            }
            start()
        }
    }

    private fun updateWindow() {
        view?.let {
            // Only the visible slice owns a window/touch target; the hidden part never blocks other apps.
            params.x = bounds.visibleX(ballX)
            params.width = bounds.visibleWidth(ballX)
            it.circleOffsetX = ballX - params.x
            runCatching { windowManager.updateViewLayout(it, params) }.onFailure { onWindowFailure() }
        }
    }

    private fun updateDescription() {
        view?.contentDescription = if (tucked) {
            "${context.getString(R.string.app_name)} 悬浮球；已收起，点击展开"
        } else {
            "${context.getString(R.string.app_name)} 悬浮球；点击返回应用，拖动调整位置"
        }
    }

    private fun usableBounds(): FloatingBallBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return FloatingBallBounds(
                width = metrics.bounds.width() - insets.left - insets.right,
                height = metrics.bounds.height() - insets.top - insets.bottom,
                ballSize = size,
            )
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        return FloatingBallBounds(metrics.widthPixels, metrics.heightPixels, size)
    }

    private class BallView(context: Context, private val diameter: Int) : View(context) {
        var circleOffsetX = 0
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        private val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.apply {
            setBounds(0, 0, diameter, diameter)
        }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(103, 80, 164) }
        private val clip = Path().apply {
            val radius = diameter / 2f
            addCircle(radius, radius, radius, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = diameter / 2f
            val saved = canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.translate(circleOffsetX.toFloat(), 0f)
            canvas.drawCircle(radius, radius, radius, backgroundPaint)
            canvas.clipPath(clip)
            icon?.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }
}
