package com.constx.onvyra.ui.mascot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.BuildConfig
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.ui.components.ui.MascotIcon
import com.constx.onvyra.ui.components.ui.RabbitLoadingIndicator
import com.constx.onvyra.ui.context.LocalSettings
import org.hamcrest.Matcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MascotIconTest {
    @get:Rule
    val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun staticArtworkRemainsVisibleAndStillAtBothDisplaySizes() {
        showMascots(animated = false)
        val large = captureMascot(LargeTag)
        val small = captureMascot(SmallTag)
        try {
            large.assertContainsArtwork("128 dp static mascot")
            small.assertContainsArtwork("28 dp static mascot")
            large.savePreview("static")
            compose.mainClock.advanceTimeBy(960L)
            compose.waitForIdle()
            captureMascot(LargeTag).useBitmap {
                assertArrayEquals("Static artwork moved", large.pixels(), it.pixels())
            }
            captureMascot(SmallTag).useBitmap {
                assertArrayEquals("Small static artwork moved", small.pixels(), it.pixels())
            }
        } finally {
            large.recycle()
            small.recycle()
        }
    }

    @Test
    fun loadingMascotMovesItsEyesAndStillAnimatesAtChatIndicatorSize() {
        showMascots(animated = true)
        awaitVisibleAnimation()
        val large = captureMascot(LargeTag)
        val small = captureMascot(SmallTag)
        try {
            large.assertContainsArtwork("128 dp loading mascot")
            small.assertContainsArtwork("28 dp loading mascot")
            large.savePreview("animated-a")
            compose.mainClock.advanceTimeBy(960L)
            compose.waitForIdle()
            captureMascot(LargeTag).useBitmap { next ->
                next.assertContainsArtwork("Next loading frame")
                assertTrue(
                    "Loading frames did not change within the visible eyes",
                    large.changedPixels(next, left = 0.37f, top = 0.42f, right = 0.64f, bottom = 0.55f) > 16,
                )
                next.savePreview("animated-b")
            }
            captureMascot(SmallTag).useBitmap { next ->
                next.assertContainsArtwork("Next 28 dp loading frame")
                assertTrue("Chat-sized loading icon stayed still", small.changedPixels(next) > 3)
            }
        } finally {
            large.recycle()
            small.recycle()
        }
    }

    @Test
    fun chatMascotDancesHopsAndRunsWithoutMovingLayoutOrRecomposingParent() {
        val parentCompositions = AtomicInteger()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SideEffect { parentCompositions.incrementAndGet() }
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialExpressiveTheme {
                    Dialog(onDismissRequest = {}) {
                        Column(Modifier.background(androidx.compose.ui.graphics.Color.White)) {
                            RabbitLoadingIndicator(Modifier.testTag(SceneTag), playful = true)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(SceneTag).assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.mainClock.advanceTimeBy(64L)
            captureMascot(SceneTag).useBitmap { it.artworkBounds() != null }
        }
        val stage = compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInWindow
        val originalCompositions = parentCompositions.get()
        val frames = mutableListOf<android.graphics.Rect>()
        // Sample all four routines, including both ends of the running path.
        repeat(33) { index ->
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            assertEquals("Animation changed its layout", stage, compose.onNodeWithTag(SceneTag).fetchSemanticsNode().boundsInWindow)
            captureMascot(SceneTag).useBitmap { bitmap ->
                val bounds = requireNotNull(bitmap.artworkBounds()) { "Chat mascot disappeared" }
                assertTrue("Mascot reached the stage edge: $bounds", bounds.left > 0 && bounds.top > 0 && bounds.right < bitmap.width && bounds.bottom < bitmap.height)
                assertTrue("Dance lost the green scarf", bitmap.greenPixels() > 4)
                frames += bounds
                if (index in setOf(0, 8, 17, 24)) bitmap.savePreview("dance-$index")
            }
        }
        assertTrue("Mascot did not run across the stage", frames.maxOf { it.centerX() } - frames.minOf { it.centerX() } > stage.width * 0.4f)
        assertTrue("Mascot did not hop", frames.maxOf { it.bottom } - frames.minOf { it.bottom } > stage.height * 0.06f)
        assertEquals("Draw frames recomposed the parent", originalCompositions, parentCompositions.get())
    }

    @Test
    fun launcherThemedAndNotificationIconsResolveAndRenderNewMascot() {
        val launcher = requireNotNull(AppCompatResources.getDrawable(context, R.mipmap.ic_launcher))
        assertTrue("Launcher lost its adaptive icon", launcher is AdaptiveIconDrawable)
        render(launcher).useBitmap { bitmap ->
            assertTrue("Launcher is blank", bitmap.pixels().any { Color.alpha(it) > 0 })
            // The debug variant overlays a DEV badge at y=62..78 in its 108-unit viewport.
            // That badge covers this artwork's scarf; check the unbadged foreground below.
            if (!BuildConfig.DEBUG) {
                assertTrue("Launcher is missing the supplied green scarf", bitmap.greenPixels() > 100)
            }
        }
        render(requireNotNull(AppCompatResources.getDrawable(context, R.mipmap.ic_launcher_foreground))).useBitmap {
            assertTrue("Foreground is missing the supplied artwork", it.greenPixels() > 100)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val monochrome = requireNotNull((launcher as AdaptiveIconDrawable).monochrome)
            render(monochrome).useBitmap { it.assertUsefulAlphaMask("Themed launcher icon") }
        }
        val notification = render(requireNotNull(AppCompatResources.getDrawable(context, R.drawable.ic_stat_onvyra)))
        try {
            notification.assertUsefulAlphaMask("Notification icon")
            render(requireNotNull(AppCompatResources.getDrawable(context, R.drawable.small_icon))).useBitmap {
                it.assertUsefulAlphaMask("Web server notification icon")
                assertArrayEquals("Notification services display different mascots", notification.pixels(), it.pixels())
            }
        } finally {
            notification.recycle()
        }
    }

    private fun showMascots(animated: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialExpressiveTheme {
                Dialog(onDismissRequest = {}) {
                    Column(Modifier.background(androidx.compose.ui.graphics.Color.White)) {
                        MascotIcon(Modifier.size(128.dp).testTag(LargeTag), animated = animated)
                        MascotIcon(Modifier.size(28.dp).testTag(SmallTag), animated = animated)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(LargeTag).assertIsDisplayed()
        compose.onNodeWithTag(SmallTag).assertIsDisplayed()
    }

    private fun awaitVisibleAnimation() {
        // Asset decoding may finish asynchronously. Wait for actual painted motion rather than
        // assuming Compose idleness also means its background decoding work has completed.
        val initial = captureMascot(LargeTag)
        try {
            compose.waitUntil(timeoutMillis = 10_000L) {
                compose.mainClock.advanceTimeBy(160L)
                compose.waitForIdle()
                captureMascot(LargeTag).useBitmap { frame ->
                    initial.changedPixels(frame) > 16
                }
            }
            compose.mainClock.advanceTimeBy(160L)
            compose.waitForIdle()
        } finally {
            initial.recycle()
        }
    }

    private fun captureMascot(tag: String): Bitmap {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        var captured: Bitmap? = null
        // Use the real composed View drawing: headless ATD screenshots can be entirely black.
        onView(isRoot()).inRoot(isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription(): String = "capture the rendered mascot frame"
            override fun perform(uiController: UiController, view: View) {
                check(view.width > 0 && view.height > 0)
                val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(full))
                    val left = bounds.left.roundToInt().coerceIn(0, full.width - 1)
                    val top = bounds.top.roundToInt().coerceIn(0, full.height - 1)
                    val width = bounds.width.roundToInt().coerceIn(1, full.width - left)
                    val height = bounds.height.roundToInt().coerceIn(1, full.height - top)
                    captured = Bitmap.createBitmap(full, left, top, width, height)
                } finally {
                    full.recycle()
                }
            }
        })
        return requireNotNull(captured)
    }

    private fun render(drawable: Drawable): Bitmap =
        Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }

    private fun Bitmap.assertContainsArtwork(label: String) {
        val pixels = pixels()
        assertTrue("$label is empty", pixels.count { Color.alpha(it) > 240 && Color.red(it) < 100 } > pixels.size / 10)
        assertTrue("$label lost its green scarf", greenPixels() > pixels.size / 100)
        assertTrue(
            "$label did not expose its white eye/background pixels",
            pixels.count { Color.alpha(it) > 240 && Color.red(it) > 235 && Color.green(it) > 235 && Color.blue(it) > 225 } > pixels.size / 10,
        )
    }

    private fun Bitmap.assertUsefulAlphaMask(label: String) {
        val opaque = pixels().count { Color.alpha(it) > 127 }
        assertTrue("$label is empty or a solid rectangle", opaque in (width * height / 20)..(width * height * 9 / 10))
    }

    private fun Bitmap.greenPixels(): Int = pixels().count {
        Color.alpha(it) > 180 && Color.green(it) > Color.red(it) * 1.08f &&
            Color.green(it) > Color.blue(it) * 1.06f && Color.green(it) > 40
    }

    private fun Bitmap.artworkBounds(): android.graphics.Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = getPixel(x, y)
                if (Color.alpha(pixel) > 180 && (Color.red(pixel) < 210 || Color.green(pixel) < 210 || Color.blue(pixel) < 210)) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        return if (right < 0) null else android.graphics.Rect(left, top, right + 1, bottom + 1)
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }

    private fun Bitmap.savePreview(name: String) {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        File(directory, "mascot-preview-$name.png").outputStream().use {
            check(compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun Bitmap.changedPixels(
        other: Bitmap,
        left: Float = 0f,
        top: Float = 0f,
        right: Float = 1f,
        bottom: Float = 1f,
    ): Int {
        check(width == other.width && height == other.height)
        var changed = 0
        for (y in (height * top).toInt() until (height * bottom).toInt()) {
            for (x in (width * left).toInt() until (width * right).toInt()) {
                if (getPixel(x, y) != other.getPixel(x, y)) changed++
            }
        }
        return changed
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T {
        return try {
            block(this)
        } finally {
            recycle()
        }
    }

    private companion object {
        const val LargeTag = "mascot-large"
        const val SmallTag = "mascot-chat-size"
        const val SceneTag = "mascot-chat-dance"
    }
}
