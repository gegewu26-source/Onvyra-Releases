package com.constx.onvyra.ui.pages.netdisk

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Two-finger page zoom remains a browser gesture even when a website consumes touch events. */
internal class NetDiskZoomWebView(context: Context, allowedOriginRules: Set<String>) : WebView(context) {
    private var documentScript: ScriptHandler? = null
    private var pinching = false
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0f) zoomBy(factor.coerceIn(0.5f, 2f))
                return true
            }
        })

    init {
        scaleDetector.isQuickScaleEnabled = false
        scaleDetector.isStylusScaleEnabled = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentScript = runCatching {
                WebViewCompat.addDocumentStartJavaScript(this, NetDiskWebZoomPolicy.DOCUMENT_SCRIPT, allowedOriginRules)
            }.getOrNull()
        }
    }

    /** Also covers older WebViews and pages that replace their viewport during navigation. */
    fun applyZoomPolicy() {
        evaluateJavascript(NetDiskWebZoomPolicy.DOCUMENT_SCRIPT, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        if (event.pointerCount >= 2 || pinching) {
            if (!pinching) {
                pinching = true
                parent?.requestDisallowInterceptTouchEvent(true)
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pinching = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        scaleDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun destroy() {
        runCatching { documentScript?.remove() }
        documentScript = null
        super.destroy()
    }
}
