package com.constx.onvyra.ui.media

import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real two-pointer input and decoded screenshot pixels; no direct zoom-state mutation. */
@RunWith(AndroidJUnit4::class)
@Suppress("UnsafeOptInUsageError")
class VideoGestureTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun twoFingerPinchRevealsFullFrameZoomsPixelsAndPansWithoutResizingTheFullscreenWindow() {
        VideoFixtureServer(videoAsset = "inline-video-wide-test.mp4").use { server ->
            val native = showAndRender(server)
            val player = readMain { native.player as ExoPlayer }
            enterFullscreen()
            hideControls()
            val viewport = bounds(fullscreen = true)
            awaitFrame("video-gesture-cover.png") { it.edgeBackgroundFractions.all { fraction -> fraction > .65f } }.use {
                assertTrue("Default fullscreen should fill instead of letterbox the wide fixture", it.cornerCounts.all { count -> count < 20 })
            }

            // Contract to the minimum fit scale: four original source corners must reappear.
            pinch(Offset(viewport.width() / 2f, viewport.height() / 2f), 120f, 35f)
            hideControls()
            println("VIDEO_PINCH_AFTER_FIT_REQUEST=${transform(native)}; VIEWPORT=$viewport")
            val fit = awaitFrame("video-gesture-fit.png") { it.cornerCounts.all { count -> count >= 100 } && it.subtitleCount >= 10 }
            val fitOrangeCount: Int
            val orangeCenter: Offset
            fit.use {
                fitOrangeCount = it.orangeCount
                val orange = requireNotNull(it.orangeBounds)
                orangeCenter = Offset(orange.exactCenterX() - viewport.left, orange.exactCenterY() - viewport.top)
                assertTrue(it.edgeBackgroundFractions[2] < .1f && it.edgeBackgroundFractions[3] < .1f)
            }
            assertEquals(viewport, bounds(fullscreen = true))

            // Magnify around a visible feature, so its decoded orange pixel area is measurable.
            pinch(orangeCenter, 35f, 67f)
            hideControls()
            val zoom = awaitFrame("video-gesture-zoom.png") { it.orangeCount > fitOrangeCount * 2 }
            val beforePan = zoom.use { requireNotNull(it.orangeBounds).let(::Rect) }
            assertEquals(viewport, bounds(fullscreen = true))
            twoFingerPan(Offset(viewport.width() / 2f, viewport.height() / 2f), Offset(45f, 16f))
            hideControls()
            val afterPan = awaitFrame("video-gesture-pan.png") {
                val orange = it.orangeBounds
                orange != null && orange.exactCenterX() > beforePan.exactCenterX() + 30 &&
                    orange.exactCenterY() > beforePan.exactCenterY() + 8
            }.use { requireNotNull(it.orangeBounds).let(::Rect) }
            assertEquals(viewport, bounds(fullscreen = true))
            val transform = transform(native)
            revealControls()
            assertEquals("A single tap must not reset or alter the chosen zoom", transform, transform(native))
            hideControls()
            assertEquals(transform, transform(native))
            val currentView = focusedVideoTestPlayer()
            assertSame(player, readMain { currentView.player })
            assertFalse(readMain { player.playWhenReady })
            println("VIDEO_PINCH_PIXELS=cover-fit-zoom-pan; FIT_ORANGE_PIXELS=$fitOrangeCount; BEFORE_PAN=$beforePan; AFTER_PAN=$afterPan; VIEWPORT=$viewport")
        }
    }

    @Test fun cancelledTwoFingerGestureLeavesSingleTapsWorkingAndFullscreenExitRestoresInlineFit() {
        VideoFixtureServer(videoAsset = "inline-video-wide-test.mp4").use { server ->
            val native = showAndRender(server)
            val inline = bounds()
            val originalDirection = readMain { compose.activity.resources.configuration.orientation }
            enterFullscreen()
            hideControls()
            val viewport = bounds(fullscreen = true)
            awaitFrame("video-gesture-before-cancel.png") { it.edgeBackgroundFractions.all { fraction -> fraction > .65f } }.close()
            val defaultTransform = transform(native)
            compose.onNodeWithTag("video-tap-surface").performTouchInput {
                val focus = Offset(width / 2f, height / 2f)
                down(0, focus + Offset(-45f, 0f))
                down(1, focus + Offset(45f, 0f))
                repeat(6) { step ->
                    val distance = 45f + (step + 1) * 6f
                    updatePointerTo(0, focus + Offset(-distance, 0f))
                    updatePointerTo(1, focus + Offset(distance, 0f))
                    move(delayMillis = 30L)
                }
                cancel()
            }
            compose.waitForIdle()
            hideControls()
            val afterCancel = transform(native)
            assertTrue("The cancellation test must first have changed actual rendering scale", afterCancel[0] > defaultTransform[0] + .15f)
            revealControls()
            compose.onNodeWithTag("video-time").assertIsDisplayed()
            assertEquals(afterCancel, transform(native))
            assertEquals(viewport, bounds(fullscreen = true))
            compose.onNodeWithTag("video-exit-fullscreen").performClick()
            compose.waitUntil(15_000) { readMain { compose.activity.resources.configuration.orientation == originalDirection } }
            assertEquals(inline, bounds())
            hideControls()
            awaitFrame("video-gesture-exit-inline-fit.png", fullscreen = false) {
                it.cornerCounts.all { count -> count >= 20 } && it.subtitleCount >= 10
            }.close()
            enterFullscreen()
            hideControls()
            awaitFrame("video-gesture-reenter-cover.png") { it.edgeBackgroundFractions.all { fraction -> fraction > .65f } }.close()
            assertEquals("Re-entering fullscreen should start from its predictable fill mode", defaultTransform, transform(native))
            println("VIDEO_PINCH_CANCEL=tap-works-inline-fit-reenter-cover; DEFAULT=$defaultTransform; CANCELLED=$afterCancel")
        }
    }

    private fun showAndRender(server: VideoFixtureServer): PlayerView {
        instrumentation.runOnMainSync { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) { readMain { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT } }
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFFE8E8E8)), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(272.dp)) {
                            InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                                resolveVideo = { _, _ -> VideoPlaybackSource("${server.url}/clip.mp4", VideoPlaybackKind.DIRECT,
                                    title = "原创双指缩放测试", mimeType = "video/mp4") })
                        }
                    }
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("inline-video-preview").fetchSemanticsNodes().isNotEmpty() }
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

    private fun enterFullscreen() {
        revealControls()
        compose.onNodeWithTag("video-fullscreen").performClick()
        compose.waitUntil(15_000) { readMain { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }
        compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
    }

    private fun pinch(focus: Offset, startHalfSeparation: Float, endHalfSeparation: Float) {
        compose.onNodeWithTag("video-tap-surface").performTouchInput {
            down(0, focus + Offset(-startHalfSeparation, 0f))
            down(1, focus + Offset(startHalfSeparation, 0f))
            repeat(12) { step ->
                val distance = startHalfSeparation + (endHalfSeparation - startHalfSeparation) * (step + 1) / 12f
                updatePointerTo(0, focus + Offset(-distance, 0f))
                updatePointerTo(1, focus + Offset(distance, 0f))
                move(delayMillis = 25L)
            }
            up(1)
            up(0)
        }
        compose.waitForIdle()
    }

    private fun twoFingerPan(focus: Offset, delta: Offset) {
        compose.onNodeWithTag("video-tap-surface").performTouchInput {
            down(0, focus + Offset(-35f, 0f))
            down(1, focus + Offset(35f, 0f))
            repeat(10) { step ->
                val shift = delta * ((step + 1) / 10f)
                updatePointerTo(0, focus + Offset(-35f, 0f) + shift)
                updatePointerTo(1, focus + Offset(35f, 0f) + shift)
                move(delayMillis = 25L)
            }
            up(1)
            up(0)
        }
        compose.waitForIdle()
    }

    private fun awaitFrame(name: String, fullscreen: Boolean = true, matches: (VideoTestPixelFrame) -> Boolean): VideoTestPixelFrame {
        var latest: VideoTestPixelFrame? = null
        try {
            compose.waitUntil(6_000) {
                latest?.close()
                latest = captureVideoTestPixels(instrumentation, bounds(fullscreen))
                matches(latest!!)
            }
            return requireNotNull(latest).also { frame -> frame.save(instrumentation, name); latest = null }
        } finally { latest?.let {
            it.save(instrumentation, "$name-failed.png")
            println("VIDEO_GESTURE_LAST_FRAME=$name; CORNERS=${it.cornerCounts}; SUBTITLE=${it.subtitleCount}; ORANGE=${it.orangeCount}; EDGES=${it.edgeBackgroundFractions}")
            it.close()
        } }
    }

    private fun bounds(fullscreen: Boolean = false): Rect {
        val rect = compose.onNodeWithTag(if (fullscreen) "video-fullscreen-player" else "inline-video-preview")
            .fetchSemanticsNode().boundsInWindow
        return Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
    }

    private fun transform(native: PlayerView): List<Float> = readMain {
        val frame = native.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
        listOf(frame.scaleX, frame.scaleY, frame.translationX, frame.translationY)
    }

    private fun hideControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("video-tap-surface").performClick()
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
