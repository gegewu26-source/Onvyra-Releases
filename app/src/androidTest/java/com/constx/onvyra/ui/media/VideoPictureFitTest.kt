package com.constx.onvyra.ui.media

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.graphics.Rect
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import com.constx.onvyra.ui.components.message.VideoSourceOption
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

/** Stable player windows and real decoded pixels in the production Activity. */
@RunWith(AndroidJUnit4::class)
@Suppress("UnsafeOptInUsageError")
class VideoPictureFitTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun inlineWindowStaysFixedThroughLoadingControlsSourceChangesAndFailedSelection() {
        VideoFixtureServer(videoAsset = "inline-video-wide-test.mp4").use { wide ->
            VideoFixtureServer().use { normal ->
                val release = CompletableDeferred<Unit>()
                show(wide, release, normal)
                val initial = windowBounds()
                assertEquals("Inline player must use a fixed 16:9 window even before metadata arrives",
                    16f / 9f, initial.width().toFloat() / initial.height(), .025f)
                release.complete(Unit)
                val native = awaitRenderedVideo()
                val player = readMain { native.player as ExoPlayer }
                assertEquals(initial, windowBounds())
                hideControls()
                assertCompleteWideFrame("video-fixed-inline-hidden.png")
                val picture = nativeGeometry(native)
                repeat(3) {
                    revealControls()
                    compose.onNodeWithTag("video-time").assertIsDisplayed()
                    assertEquals("Showing controls resized the card", initial, windowBounds())
                    assertEquals("Showing controls resized the decoded picture", picture, nativeGeometry(native))
                    hideControls()
                    assertEquals(initial, windowBounds())
                    assertEquals(picture, nativeGeometry(native))
                }
                revealControls()
                compose.onNodeWithTag("video-source").performClick()
                compose.onNodeWithTag("video-source-option-normal").performScrollTo().performClick()
                compose.waitUntil(15_000) { readMain {
                    player.currentMediaItem?.localConfiguration?.uri.toString().startsWith(normal.url) &&
                        player.playbackState == Player.STATE_READY && player.videoSize.width == 320
                } }
                assertSame(player, readMain { native.player })
                assertEquals("A new source's aspect ratio must not resize its chat window", initial, windowBounds())
                revealControls()
                compose.onNodeWithTag("video-source").performClick()
                compose.onNodeWithTag("video-source-option-failed").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("video-source-error").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("A source error must overlay the same window", initial, windowBounds())
                assertTrue(readMain { player.currentMediaItem?.localConfiguration?.uri.toString().startsWith(normal.url) })
                println("VIDEO_FIXED_INLINE=loading-ready-controls-source-error; WINDOW=$initial; PLAYER_REUSED=true")
            }
        }
    }

    @Test fun initialResolutionErrorKeepsTheLoadingWindowSize() {
        VideoFixtureServer().use { server ->
            val release = CompletableDeferred<Unit>()
            show(server, release, failInitial = true)
            val initial = windowBounds()
            release.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("video-retry").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Initial failure changed the fixed viewport", initial, windowBounds())
            assertEquals(16f / 9f, initial.width().toFloat() / initial.height(), .025f)
        }
    }

    @Test fun fullscreenFillsTheFixedWindowWithoutTapResizingAndPreservesPlaybackOnExit() {
        VideoFixtureServer(videoAsset = "inline-video-wide-test.mp4").use { server ->
            show(server)
            val originalView = awaitRenderedVideo()
            val originalActivity = compose.activity
            val player = readMain { originalView.player as ExoPlayer }
            val originalRequest = readMain { originalActivity.requestedOrientation }
            val originalDirection = readMain { originalActivity.resources.configuration.orientation }
            val inline = windowBounds()
            revealControls()
            compose.onNodeWithTag("video-speed").performClick()
            compose.onNodeWithTag("video-speed-option-1.5").performScrollTo().performClick()
            val position = readMain { player.currentPosition }
            compose.onNodeWithTag("video-fullscreen").performClick()
            compose.waitUntil(15_000) { readMain { originalActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }
            compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
            assertSame(originalActivity, compose.activity)
            assertSame(originalView, focusedVideoTestPlayer())
            val fullscreen = windowBounds(fullscreen = true)
            hideControls()
            assertCoverFrame("video-fixed-fullscreen-hidden.png")
            val geometry = nativeGeometry(originalView)
            repeat(3) {
                revealControls()
                assertEquals("A fullscreen tap resized the viewport", fullscreen, windowBounds(fullscreen = true))
                assertEquals("A fullscreen tap changed the image transform", geometry, nativeGeometry(originalView))
                hideControls()
                assertEquals(fullscreen, windowBounds(fullscreen = true))
                assertEquals(geometry, nativeGeometry(originalView))
            }
            revealControls()
            captureVideoTestPixels(instrumentation, fullscreen).use { it.save(instrumentation, "video-fixed-fullscreen-controls.png") }
            assertEquals(1.5f, readMain { player.playbackParameters.speed }, .01f)
            assertTrue(readMain { player.currentPosition >= position - 100 })
            assertFalse(readMain { player.playWhenReady })
            compose.onNodeWithTag("video-exit-fullscreen").performClick()
            compose.waitUntil(15_000) { readMain {
                originalActivity.resources.configuration.orientation == originalDirection &&
                    originalActivity.requestedOrientation == originalRequest
            } }
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
            assertSame(originalActivity, compose.activity)
            assertSame(originalView, focusedVideoTestPlayer())
            assertSame(player, readMain { originalView.player })
            assertEquals(inline, windowBounds())
            hideControls()
            assertCompleteWideFrame("video-fixed-inline-returned.png")
            println("VIDEO_FIXED_FULLSCREEN=cover-controls-stable-return; WINDOW=$fullscreen; GEOMETRY=$geometry")
        }
    }

    private fun show(server: VideoFixtureServer, release: CompletableDeferred<Unit>? = null,
        other: VideoFixtureServer? = null, failInitial: Boolean = false) {
        instrumentation.runOnMainSync { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) { readMain { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT } }
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFFE8E8E8)), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(272.dp)) {
                            InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                                resolveVideo = { _, _ ->
                                    release?.await()
                                    if (failInitial) throw IOException("Fixture resolution failure")
                                    source(server)
                                },
                                loadSourceOptions = if (other == null) null else ({ listOf(
                                    VideoSourceOption("wide", "当前宽视频", true),
                                    VideoSourceOption("normal", "普通比例视频"),
                                    VideoSourceOption("failed", "失效来源"),
                                ) }),
                                resolveSourceOption = if (other == null) null else ({ id ->
                                    if (id == "failed") throw IOException("Fixture source failure")
                                    source(if (id == "normal") other else server)
                                }),
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("inline-video-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun source(server: VideoFixtureServer) = VideoPlaybackSource("${server.url}/clip.mp4",
        VideoPlaybackKind.DIRECT, title = "原创固定窗口与缩放测试", mimeType = "video/mp4")

    private fun awaitRenderedVideo(): PlayerView {
        val found = AtomicReference<PlayerView>()
        compose.waitUntil(15_000) { runCatching { focusedVideoTestPlayer() }.onSuccess(found::set).isSuccess }
        val native = found.get()
        val player = readMain { native.player as ExoPlayer }
        compose.waitUntil(20_000) { readMain { player.playbackState == Player.STATE_READY } }
        instrumentation.runOnMainSync { player.play() }
        compose.waitUntil(15_000) { readMain { player.isPlaying && player.currentPosition > 350 } }
        instrumentation.runOnMainSync { player.pause() }
        return native
    }

    private fun windowBounds(fullscreen: Boolean = false): Rect {
        val bounds = compose.onNodeWithTag(if (fullscreen) "video-fullscreen-player" else "inline-video-preview")
            .fetchSemanticsNode().boundsInWindow
        return Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    }

    private fun nativeGeometry(native: PlayerView): List<Float> = readMain {
        val frame = native.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
        listOf(native.width.toFloat(), native.height.toFloat(), frame.width.toFloat(), frame.height.toFloat(),
            frame.scaleX, frame.scaleY, frame.translationX, frame.translationY)
    }

    private fun assertCompleteWideFrame(name: String) {
        var frame: VideoTestPixelFrame? = null
        try {
            compose.waitUntil(6_000) {
                frame?.close()
                frame = captureVideoTestPixels(instrumentation, windowBounds())
                frame!!.cornerCounts.all { it >= 20 } && frame!!.subtitleCount >= 10
            }
            frame!!.save(instrumentation, name)
            println("VIDEO_FIXED_FRAME=$name; CORNERS=${frame!!.cornerCounts}; SUBTITLE=${frame!!.subtitleCount}; WINDOW=${frame!!.viewport}")
        } finally { frame?.close() }
    }

    private fun assertCoverFrame(name: String) {
        var frame: VideoTestPixelFrame? = null
        try {
            compose.waitUntil(6_000) {
                frame?.close()
                frame = captureVideoTestPixels(instrumentation, windowBounds(fullscreen = true))
                frame!!.edgeBackgroundFractions.all { it > .65f } && frame!!.orangeCount > 100
            }
            frame!!.save(instrumentation, name)
            println("VIDEO_FIXED_COVER=$name; EDGES=${frame!!.edgeBackgroundFractions}; CORNERS=${frame!!.cornerCounts}; WINDOW=${frame!!.viewport}")
            assertTrue("The wide source must crop horizontally when filling a taller screen", frame!!.cornerCounts.all { it < 20 })
        } finally { frame?.close() }
    }

    private fun hideControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("video-tap-surface").performClick()
        // A single tap is confirmed after the platform double-tap interval.
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("video-controls").assertDoesNotExist()
        compose.waitForIdle()
    }
    private fun revealControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("video-tap-surface").performClick()
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("video-controls").assertIsDisplayed()
        compose.waitForIdle()
    }
    private fun <T> readMain(block: () -> T): T = readVideoTestMain(instrumentation, block)
}

internal fun focusedVideoTestPlayer(): PlayerView {
    val result = AtomicReference<PlayerView>()
    onView(isAssignableFrom(PlayerView::class.java)).inRoot(object : TypeSafeMatcher<Root>() {
        override fun describeTo(description: Description) { description.appendText("focused video window") }
        override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
    }).perform(object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
        override fun getDescription() = "inspect the native player in the focused video window"
        override fun perform(uiController: UiController, view: View) { result.set(view as PlayerView) }
    })
    return requireNotNull(result.get())
}

internal fun <T> readVideoTestMain(instrumentation: Instrumentation, block: () -> T): T {
    val result = AtomicReference<T>()
    instrumentation.runOnMainSync { result.set(block()) }
    return result.get()
}

/** Screenshot analysis uses composited video pixels, not the controller's zoom state. */
internal class VideoTestPixelFrame(
    val image: Bitmap,
    val viewport: Rect,
    val cornerCounts: List<Int>,
    val subtitleCount: Int,
    val orangeCount: Int,
    val orangeBounds: Rect?,
    val edgeBackgroundFractions: List<Float>,
) : AutoCloseable {
    fun save(instrumentation: Instrumentation, name: String) {
        instrumentation.targetContext.cacheDir.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    override fun close() = image.recycle()
}

internal fun captureVideoTestPixels(instrumentation: Instrumentation, requested: Rect): VideoTestPixelFrame {
    val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    val viewport = Rect(requested)
    check(viewport.intersect(0, 0, image.width, image.height))
    val counts = IntArray(4)
    var orange = 0
    var orangeBounds: Rect? = null
    for (y in viewport.top until viewport.bottom) for (x in viewport.left until viewport.right) {
        val pixel = image.getPixel(x, y)
        val r = PixelColor.red(pixel); val g = PixelColor.green(pixel); val b = PixelColor.blue(pixel)
        // Match the fixture's solid corner colors rather than H.264 chroma fringes on
        // the orange square and white text, which remain visible after a correct crop.
        if (r > 190 && g < 85 && b < 85) counts[0]++
        if (g > 180 && r < 90 && b < 100) counts[1]++
        if (b > 190 && r < 85 && g < 130) counts[2]++
        if (r > 190 && g > 175 && b < 100) counts[3]++
        if (r > 180 && g in 75..175 && b < 100) {
            orange++
            if (orangeBounds == null) orangeBounds = Rect(x, y, x + 1, y + 1)
            else orangeBounds!!.union(x, y)
        }
    }
    val fitWidth = minOf(viewport.width().toFloat(), viewport.height() * 8f / 3f)
    val fitHeight = fitWidth * 3f / 8f
    val fitLeft = viewport.exactCenterX() - fitWidth / 2
    val fitTop = viewport.exactCenterY() - fitHeight / 2
    val subtitle = Rect(floor(fitLeft + 340 * fitWidth / 960f).toInt(), floor(fitTop + 320 * fitHeight / 360f).toInt(),
        ceil(fitLeft + 620 * fitWidth / 960f).toInt(), ceil(fitTop + 348 * fitHeight / 360f).toInt())
    var whites = 0
    for (y in subtitle.top.coerceAtLeast(viewport.top) until subtitle.bottom.coerceAtMost(viewport.bottom)) {
        for (x in subtitle.left.coerceAtLeast(viewport.left) until subtitle.right.coerceAtMost(viewport.right)) {
            val pixel = image.getPixel(x, y)
            val r = PixelColor.red(pixel); val g = PixelColor.green(pixel); val b = PixelColor.blue(pixel)
            if (minOf(r, g, b) > 175 && maxOf(r, g, b) - minOf(r, g, b) < 45) whites++
        }
    }
    val edges = listOf(
        Rect(viewport.left + 2, viewport.top + viewport.height() / 3, viewport.left + 8, viewport.bottom - viewport.height() / 3),
        Rect(viewport.right - 8, viewport.top + viewport.height() / 3, viewport.right - 2, viewport.bottom - viewport.height() / 3),
        Rect(viewport.left + viewport.width() / 3, viewport.top + 2, viewport.right - viewport.width() / 3, viewport.top + 8),
        Rect(viewport.left + viewport.width() / 3, viewport.bottom - 8, viewport.right - viewport.width() / 3, viewport.bottom - 2),
    ).map { edge ->
        var matched = 0
        for (y in edge.top until edge.bottom) for (x in edge.left until edge.right) {
            val pixel = image.getPixel(x, y)
            if (PixelColor.red(pixel) in 15..100 && PixelColor.green(pixel) in 20..130 && PixelColor.blue(pixel) in 40..130) matched++
        }
        matched.toFloat() / (edge.width() * edge.height())
    }
    return VideoTestPixelFrame(image, viewport, counts.toList(), whites, orange, orangeBounds, edges)
}
