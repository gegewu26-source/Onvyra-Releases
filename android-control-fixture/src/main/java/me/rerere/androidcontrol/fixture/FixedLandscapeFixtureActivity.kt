package me.rerere.androidcontrol.fixture

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A genuine app orientation request; the test must never rotate its display itself. */
class FixedLandscapeFixtureActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var body: FrameLayout
    private var taps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = FrameLayout(this).apply { setBackgroundColor(Color.rgb(15, 110, 180)) }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 22f }
        body.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        body.addView(Button(this).apply {
            id = R.id.orientation_right_target
            text = "RIGHT TARGET"
            setOnClickListener { taps++; updateStatus() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        val requests = LinearLayout(this)
        for ((label, request, viewId) in listOf(
            Triple("Runtime portrait", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, R.id.request_portrait),
            Triple("Runtime reverse", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, R.id.request_reverse),
            Triple("Runtime landscape", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, R.id.request_landscape),
        )) requests.addView(Button(this).apply {
            id = viewId
            text = label
            setOnClickListener { requestedOrientation = request }
        })
        body.addView(requests, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT))
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateStatus() }
        setContentView(body)
        body.post { window.insetsController?.hide(WindowInsets.Type.systemBars()) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        body.post { updateStatus() }
    }

    private fun updateStatus() {
        status.text = "OWNED_ORIENTATION ${body.width}x${body.height} taps=$taps request=$requestedOrientation"
    }
}
