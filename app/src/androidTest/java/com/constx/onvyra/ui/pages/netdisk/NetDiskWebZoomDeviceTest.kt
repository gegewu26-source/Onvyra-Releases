package com.constx.onvyra.ui.pages.netdisk

import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local documents in the separate .debug app: no provider requests, cookies, credentials or SettingsStore. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class NetDiskWebZoomDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var browser: NetDiskZoomWebView

    @Test fun twoFingerGesturesZoomBothWaysDespiteViewportAndPageTouchRestrictions() {
        showFixture()
        assertEquals("true", js("window.fixture.multiTouchBlockerInstalled"))
        val initial = scale()
        pinch(grow = true)
        await("A real two-finger spread must increase the rendered page scale") { scale() > initial * 1.15f }
        val enlarged = scale()
        pinch(grow = false)
        await("A real two-finger pinch must reduce the enlarged page scale") { scale() < enlarged * .9f }
        assertEquals("0", js("window.fixture.submits"))
        assertEquals("0", js("window.fixture.drags"))
    }

    @Test fun aGestureStartingInsideANestedCrossOriginFrameStillZoomsTheWholeWebView() {
        showFixture()
        await("Both local iframe levels must finish loading") { js("window.fixture.framesReady") == "true" }
        val framePoint = pointFor("nested-frame", .5, .55)
        val initial = scale()
        pinch(grow = true, center = framePoint)
        await("An iframe's prevented touch events must not trap the native page zoom") { scale() > initial * 1.15f }
        val enlarged = scale()
        pinch(grow = false, center = framePoint)
        await("The same nested-frame gesture must also allow shrinking") { scale() < enlarged * .9f }
        assertEquals("0", js("window.fixture.submits"))
    }

    @Test fun singleFingerClickTypingAndDraggingKeepTheirPageSemantics() {
        showFixture()
        val before = scale()
        val button = pointFor("submit")
        assertTrue(device.click(button.x, button.y))
        await("The page's own button must still receive a single tap") { js("window.fixture.submits") == "1" }
        val field = pointFor("account")
        assertTrue(device.click(field.x, field.y))
        await("Single-finger focus must reach the page input") { js("document.activeElement.id") == "\"account\"" }
        instrumentation.sendStringSync("fixturevalue")
        await("Actual keyboard events must reach the page input") { js("document.getElementById('account').value") == "\"fixturevalue\"" }
        instrumentation.runOnMainSync {
            browser.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(browser.windowToken, 0)
        }
        instrumentation.waitForIdleSync()
        val start = pointFor("drag", .1, .5)
        val end = pointFor("drag", .85, .5)
        swipe(start, end)
        await("A one-finger drag must still reach the local interactive control") { js("window.fixture.drags").toIntOrNull()?.let { it > 0 } == true }
        assertEquals("1", js("window.fixture.submits"))
        assertTrue("Single-finger controls must not activate page zoom", kotlin.math.abs(scale() - before) <= before * .15f)
    }

    private fun showFixture() {
        check(instrumentation.targetContext.packageName == "com.constx.onvyra.debug") {
            "Run only against the separate com.constx.onvyra.debug APK; never replace the installed release."
        }
        val loaded = CountDownLatch(1)
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                NetDiskZoomWebView(context, setOf(ORIGIN, CHILD_ORIGIN)).apply {
                    browser = this
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                            val url = request?.url
                            val html = when {
                                url?.scheme == "https" && url.host == "nested.zoom.onvyra.test" && url.path == "/outer" -> OUTER_FRAME
                                url?.scheme == "https" && url.host == "nested.zoom.onvyra.test" && url.path == "/inner" -> INNER_FRAME
                                else -> ""
                            }
                            // Every resource is answered locally, including unknown URLs. No connection is made.
                            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url == "$ORIGIN/") {
                                // Matches the production fallback on engines without document-start support.
                                view?.evaluateJavascript(NetDiskWebZoomPolicy.DOCUMENT_SCRIPT) { loaded.countDown() }
                            }
                        }
                    }
                    loadDataWithBaseURL("$ORIGIN/", PAGE, "text/html", "UTF-8", null)
                }
            }, onRelease = { it.stopLoading(); it.webViewClient = WebViewClient(); it.destroy() })
        }
        assertTrue("The local fixture must render", loaded.await(20, TimeUnit.SECONDS))
        await("The real WebView must have a visible touch surface") { readMain { browser.width > 200 && browser.height > 200 } }
        assertEquals("true", js("window.fixture != null"))
        instrumentation.waitForIdleSync()
    }

    private fun scale(): Float = readMain { browser.scale }

    private fun bounds(): Rect = readMain { Rect().also { assertTrue(browser.getGlobalVisibleRect(it)) } }

    private fun pointFor(id: String, horizontal: Double = .5, vertical: Double = .5): Point {
        val encoded = js("(function(){var r=document.getElementById('$id').getBoundingClientRect();return JSON.stringify({x:r.left+r.width*$horizontal,y:r.top+r.height*$vertical});})()")
        val result = JSONObject(JSONTokener(encoded).nextValue() as String)
        val visible = bounds()
        val factor = scale()
        val point = Point(visible.left + (result.getDouble("x") * factor).toInt(),
            visible.top + (result.getDouble("y") * factor).toInt())
        assertTrue("Fixture control $id must be in the visible WebView", visible.contains(point.x, point.y))
        return point
    }

    private fun pinch(grow: Boolean, center: Point? = null) {
        val visible = bounds()
        val pivot = center ?: Point(visible.centerX(), visible.top + visible.height() * 3 / 4)
        val room = minOf(pivot.x - visible.left, visible.right - pivot.x).coerceAtLeast(1)
        val small = (room * .15f).coerceAtLeast(16f)
        val large = (room * .75f).coerceAtLeast(small + 32f)
        check(pivot.x - large >= visible.left && pivot.x + large < visible.right) { "Pinch needs a visible two-finger surface" }
        val start = if (grow) small else large
        val finish = if (grow) large else small
        val time = SystemClock.uptimeMillis()
        fun send(action: Int, span: Float, count: Int = 2) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = pivot.x + if (index == 0) -span else span
                y = pivot.y.toFloat(); pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start)
        for (step in 1..32) {
            SystemClock.sleep(16)
            send(MotionEvent.ACTION_MOVE, start + (finish - start) * step / 32f)
        }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), finish)
        send(MotionEvent.ACTION_UP, finish, 1)
        instrumentation.waitForIdleSync()
    }

    private fun swipe(start: Point, end: Point) {
        assertTrue(device.swipe(start.x, start.y, end.x, end.y, 30))
        instrumentation.waitForIdleSync()
    }

    private fun js(script: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { browser.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JavaScript callback timed out", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun <T> readMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }

    private companion object {
        const val ORIGIN = "https://zoom.onvyra.test"
        const val CHILD_ORIGIN = "https://nested.zoom.onvyra.test"
        const val BLOCK_TOUCH = """
            document.addEventListener('touchmove',function(e){if(e.touches.length>1)e.preventDefault();},{passive:false});
            document.addEventListener('gesturestart',function(e){e.preventDefault();},{passive:false});
        """
        val PAGE = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no">
            <style>html,body{margin:0;padding:0;font:20px sans-serif}body{width:1800px;min-height:1800px}#controls{padding:16px;width:280px}input,button{font:20px sans-serif;box-sizing:border-box;height:44px;width:260px;margin-bottom:12px}#drag{width:260px;height:60px;background:#b5d3ff;touch-action:none}iframe{display:block;width:calc(100vw - 32px);height:300px;border:0;margin-left:16px}</style>
            <script>window.fixture={submits:0,drags:0,framesReady:false,multiTouchBlockerInstalled:true};
            window.addEventListener('message',function(e){if(e.origin==='https://nested.zoom.onvyra.test'&&e.data==='local-inner-ready')window.fixture.framesReady=true;});
            $BLOCK_TOUCH
            </script></head>
            <body><div id="controls"><button id="submit" type="button">Local action</button><input id="account" placeholder="Local input" autocomplete="off"><div id="drag">Local drag control</div></div>
            <iframe id="nested-frame" src="https://nested.zoom.onvyra.test/outer"></iframe><div style="height:1200px">Wide document</div>
            <script>
            document.getElementById('submit').onclick=function(){window.fixture.submits++;};
            var drag=document.getElementById('drag'),dragging=false;
            drag.addEventListener('pointerdown',function(e){dragging=true;drag.setPointerCapture(e.pointerId);});
            drag.addEventListener('pointermove',function(e){if(dragging)window.fixture.drags++;});
            drag.addEventListener('pointerup',function(){dragging=false;});
            </script></body></html>
        """.trimIndent()
        val OUTER_FRAME = """<!doctype html><html><head><meta name="viewport" content="width=device-width,user-scalable=no,maximum-scale=1"></head><body style="margin:0"><iframe src="https://nested.zoom.onvyra.test/inner" style="width:100%;height:300px;border:0"></iframe><script>$BLOCK_TOUCH</script></body></html>"""
        val INNER_FRAME = """<!doctype html><html><head><meta name="viewport" content="width=device-width,user-scalable=no,maximum-scale=1"></head><body style="margin:0;background:#d3e3ff;height:600px"><p>Nested local login-like content</p><script>$BLOCK_TOUCH window.top.postMessage('local-inner-ready','https://zoom.onvyra.test');</script></body></html>"""
    }
}
