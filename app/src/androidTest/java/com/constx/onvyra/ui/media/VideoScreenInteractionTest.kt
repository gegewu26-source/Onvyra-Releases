package com.constx.onvyra.ui.media

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.Window
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fullscreen belongs to the app window, independently of decoder or webpage readiness. */
@RunWith(AndroidJUnit4::class)
@Suppress("UnsafeOptInUsageError")
class VideoScreenInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun leftSideVerticalGesturesChangeOnlyTheFullscreenWindowBrightnessAndExitRestoresIt() {
        VideoFixtureServer().use { server ->
            show(server) { directSource(server) }
            val native = awaitNative()
            val player = readMain { native.player as ExoPlayer }
            preparePaused(player)
            val globalBefore = globalBrightness()
            val activityBrightness = readMain { compose.activity.window.attributes.screenBrightness }
            enterFullscreen()
            val window = dialogWindow(native)
            val originalDialogBrightness = readMain { window.attributes.screenBrightness }
            instrumentation.runOnMainSync {
                window.attributes = window.attributes.apply { screenBrightness = .5f }
            }
            controlsVisible(false)
            val events = observe(player)

            beginVertical(xFraction = .25f, deltaYFraction = -.2f)
            compose.onNodeWithTag("video-gesture-brightness").assertIsDisplayed()
            assertEquals(.7f, readMain { window.attributes.screenBrightness }, .04f)
            assertEquals(globalBefore, globalBrightness())
            assertEquals(activityBrightness, readMain { compose.activity.window.attributes.screenBrightness }, .001f)
            finishVertical()
            awaitTag("video-gesture-brightness", visible = false)

            beginVertical(xFraction = .25f, deltaYFraction = .2f)
            compose.onNodeWithTag("video-gesture-brightness").assertIsDisplayed()
            assertEquals(.5f, readMain { window.attributes.screenBrightness }, .04f)
            finishVertical()
            awaitTag("video-gesture-brightness", visible = false)
            assertNoPlaybackChange(player, events)
            assertEquals(globalBefore, globalBrightness())

            exitFullscreen()
            compose.waitUntil(5_000) { readMain { abs(window.attributes.screenBrightness - originalDialogBrightness) < .001f } }
            assertEquals(globalBefore, globalBrightness())
            assertEquals(activityBrightness, readMain { compose.activity.window.attributes.screenBrightness }, .001f)
        }
    }

    @Test fun inheritedFullscreenBrightnessStartsFromTheEmulatorsActualSystemLevelWithoutWritingSettings() {
        assumeTrue("This case verifies the emulator's declared platform brightness scale", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val maximumId = context.resources.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        assertTrue("The emulator must declare its brightness range", maximumId != 0)
        val maximum = context.resources.getInteger(maximumId)
        val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        assertTrue("The emulator system brightness must fit its declared range", maximum > 0 && raw in 0..maximum)
        val systemFraction = raw.toFloat() / maximum
        val upwardDelta = if (systemFraction > .5f) -.1f else .1f
        val expected = (systemFraction.coerceIn(.02f, 1f) + upwardDelta).coerceIn(.02f, 1f)
        val globalBefore = globalBrightness()
        val normalizedBefore = Settings.System.getString(resolver, "screen_brightness_float")

        VideoFixtureServer().use { server ->
            show(server) { directSource(server) }
            val native = awaitNative()
            val player = readMain { native.player as ExoPlayer }
            preparePaused(player)
            enterFullscreen()
            val window = dialogWindow(native)
            assertEquals("This path must begin with inherited brightness, without a window override", -1f,
                readMain { window.attributes.screenBrightness }, .001f)
            controlsVisible(false)
            val events = observe(player)

            beginVertical(xFraction = .25f, deltaYFraction = -upwardDelta)
            compose.onNodeWithTag("video-gesture-brightness").assertIsDisplayed()
            assertEquals("The first gesture must offset the actual system level instead of an arbitrary preset", expected,
                readMain { window.attributes.screenBrightness }, .025f)
            finishVertical()
            awaitTag("video-gesture-brightness", visible = false)
            assertNoPlaybackChange(player, events)
            assertEquals(globalBefore, globalBrightness())
            assertEquals(normalizedBefore, Settings.System.getString(resolver, "screen_brightness_float"))

            exitFullscreen()
            compose.waitUntil(5_000) { readMain { window.attributes.screenBrightness < 0f } }
            assertEquals(-1f, readMain { window.attributes.screenBrightness }, .001f)
            assertEquals(globalBefore, globalBrightness())
            assertEquals(normalizedBefore, Settings.System.getString(resolver, "screen_brightness_float"))
        }
    }

    @Test fun rightSideVerticalGesturesChangeMediaVolumeWithoutChangingPlaybackAndKeepTheChosenVolumeOnExit() {
        val audio = instrumentation.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assumeFalse("This device has a fixed media volume", audio.isVolumeFixed)
        val minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        assumeTrue("The media stream needs room for an up and down gesture", maximum - minimum >= 5)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            VideoFixtureServer().use { server ->
                show(server) { directSource(server) }
                val native = awaitNative()
                val player = readMain { native.player as ExoPlayer }
                preparePaused(player)
                enterFullscreen()
                val window = dialogWindow(native)
                val brightness = readMain { window.attributes.screenBrightness }
                val baseline = (minimum + maximum) / 2
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, baseline, 0)
                compose.waitUntil(5_000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == baseline }
                controlsVisible(false)
                val events = observe(player)
                val louder = (baseline + ((maximum - minimum) * .2f).roundToInt()).coerceAtMost(maximum)

                beginVertical(xFraction = .75f, deltaYFraction = -.2f)
                compose.onNodeWithTag("video-gesture-volume").assertIsDisplayed()
                assertEquals(louder, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                assertEquals(brightness, readMain { window.attributes.screenBrightness }, .001f)
                finishVertical()
                awaitTag("video-gesture-volume", visible = false)

                beginVertical(xFraction = .75f, deltaYFraction = .2f)
                compose.onNodeWithTag("video-gesture-volume").assertIsDisplayed()
                assertEquals(baseline, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                finishVertical()
                awaitTag("video-gesture-volume", visible = false)
                assertNoPlaybackChange(player, events)

                // Leave a deliberate change behind when exiting; the test restores the device in finally.
                beginVertical(xFraction = .75f, deltaYFraction = -.2f)
                finishVertical()
                awaitTag("video-gesture-volume", visible = false)
                exitFullscreen()
                assertEquals("Leaving fullscreen must retain the user's chosen media volume", louder, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            compose.waitUntil(5_000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == originalVolume }
        }
    }

    @Test fun fullscreenCanOpenAndCloseWhileResolvingOrFailedAndLateNativeResolutionKeepsItsViewport() {
        VideoFixtureServer().use { server ->
            val attempts = AtomicInteger()
            val first = CompletableDeferred<VideoPlaybackSource>()
            val retry = CompletableDeferred<VideoPlaybackSource>()
            show(server) { if (attempts.incrementAndGet() == 1) first.await() else retry.await() }
            compose.waitUntil(5_000) { attempts.get() == 1 }
            compose.onNodeWithTag("video-fullscreen").assertIsEnabled()
            enterFullscreen()
            exitFullscreen()
            assertEquals("Moving the pending player must not restart resolution", 1, attempts.get())

            enterFullscreen()
            val resolvingViewport = viewport()
            first.completeExceptionally(IOException("fixture resolution rejected"))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("fixture resolution rejected", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(resolvingViewport, viewport())
            exitFullscreen()
            compose.onNodeWithTag("video-fullscreen").assertIsEnabled()

            enterFullscreen()
            val failedViewport = viewport()
            touch("video-retry")
            compose.waitUntil(5_000) { attempts.get() == 2 }
            assertEquals(failedViewport, viewport())
            retry.complete(directSource(server))
            val native = awaitNative()
            val player = readMain { native.player as ExoPlayer }
            compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
            assertEquals("The delayed decoder must continue in the already open fullscreen window", failedViewport, viewport())
            assertTrue(readMain { native.width > native.height })
            assertFalse(readMain { player.playWhenReady })
            exitFullscreen()
        }
    }

    @Test fun embeddedPagesUseTheAppFullscreenWindowBeforePlatformMetadataOrPlaybackIsReady() {
        VideoFixtureServer().use { server ->
            val pending = CompletableDeferred<VideoPlaybackSource>()
            show(server, embedded = true) { pending.await() }
            enterFullscreen()
            val beforePage = viewport()
            pending.complete(VideoPlaybackSource("${server.url}/page", VideoPlaybackKind.EMBEDDED, title = "本地网页片源"))
            val browser = awaitBrowser()
            assertTrue(readMain { browser.width > browser.height })
            assertTrue(readMain { browser.rootView.hasWindowFocus() })
            assertEquals("Webpage readiness must not resize or replace the app fullscreen window", beforePage, viewport())
            assertNotSame("The embedded page must live in the fullscreen dialog", compose.activity.window, dialogWindow(browser))
            exitFullscreen()
            assertSame(browser, awaitBrowser())
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
        }
    }

    private fun show(server: VideoFixtureServer, embedded: Boolean = false, resolve: suspend () -> VideoPlaybackSource) {
        instrumentation.runOnMainSync { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) { readMain { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT } }
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(272.dp)) {
                            InlineVideoPreview(
                                VideoLink("${server.url}/${if (embedded) "page" else "clip.mp4"}", if (embedded) VideoPlatform.OTHER else VideoPlatform.DIRECT),
                                resolveVideo = { _, _ -> resolve() },
                            )
                        }
                    }
                }
            }
        }
        awaitTag("inline-video-preview", visible = true)
    }

    private fun directSource(server: VideoFixtureServer) = VideoPlaybackSource(
        "${server.url}/clip.mp4", VideoPlaybackKind.DIRECT, title = "本地屏幕手势测试", mimeType = "video/mp4",
    )

    private fun preparePaused(player: ExoPlayer) {
        compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
        instrumentation.runOnMainSync { player.pause(); player.seekTo(2_000L) }
        compose.waitUntil(5_000) { readMain { player.playbackState == Player.STATE_READY && player.currentPosition == 2_000L } }
    }

    private fun enterFullscreen() {
        controlsVisible(true)
        touch("video-fullscreen")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("video-fullscreen-player").fetchSemanticsNodes().singleOrNull()
                ?.boundsInWindow?.let { it.width > it.height } == true
        }
        compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
    }

    private fun exitFullscreen() {
        controlsVisible(true)
        touch("video-exit-fullscreen")
        awaitTag("video-fullscreen-player", visible = false)
        compose.waitUntil(15_000) { readMain { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT } }
    }

    private fun viewport(): Rect = compose.onNodeWithTag("video-fullscreen-player").fetchSemanticsNode().boundsInWindow

    private fun beginVertical(xFraction: Float, deltaYFraction: Float) {
        compose.onNodeWithTag("video-tap-surface").performTouchInput {
            val start = Offset(width * xFraction, height * .5f)
            down(0, start)
            repeat(8) { step -> moveTo(0, start + Offset(0f, height * deltaYFraction * ((step + 1) / 8f)), delayMillis = 25L) }
        }
        awaitTag(if (xFraction < .5f) "video-gesture-brightness" else "video-gesture-volume", visible = true)
    }

    private fun finishVertical() = compose.onNodeWithTag("video-tap-surface").performTouchInput { up(0) }

    private fun controlsVisible(visible: Boolean) {
        val shown = compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty()
        if (shown != visible) {
            if (compose.onAllNodesWithTag("video-tap-surface").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("video-tap-surface").performTouchInput { click(Offset(width * .5f, height * .3f)) }
            } else {
                onView(allOf(isAssignableFrom(WebView::class.java), withTagValue(equalTo<Any>("inline-video-web-player"))))
                    .inRoot(focusedRoot()).perform(ViewActions.click())
            }
        }
        awaitTag("video-controls", visible)
    }

    private fun touch(tag: String) = compose.onNodeWithTag(tag).performTouchInput { click() }

    private fun awaitTag(tag: String, visible: Boolean) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == visible }
    }

    private fun awaitNative(): PlayerView {
        val found = AtomicReference<PlayerView>()
        compose.waitUntil(15_000) { runCatching { focusedVideoTestPlayer() }.onSuccess(found::set).isSuccess }
        return found.get()
    }

    private fun awaitBrowser(): WebView {
        val found = AtomicReference<WebView>()
        compose.waitUntil(15_000) {
            runCatching {
                onView(allOf(isAssignableFrom(WebView::class.java), withTagValue(equalTo<Any>("inline-video-web-player"))))
                    .inRoot(focusedRoot()).perform(object : ViewAction {
                        override fun getConstraints(): Matcher<View> = isAssignableFrom(WebView::class.java)
                        override fun getDescription() = "inspect the embedded page in its current app window"
                        override fun perform(uiController: UiController, view: View) { found.set(view as WebView) }
                    })
            }.isSuccess
        }
        return found.get()
    }

    private fun focusedRoot() = object : TypeSafeMatcher<Root>() {
        override fun describeTo(description: Description) { description.appendText("the focused video window") }
        override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
    }

    private fun dialogWindow(view: View): Window = readMain {
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor is DialogWindowProvider) return@readMain ancestor.window
            ancestor = ancestor.parent as? View
        }
        error("The fullscreen video has no DialogWindowProvider ancestor")
    }

    private fun globalBrightness(): Pair<Int, Int> {
        val resolver = instrumentation.targetContext.contentResolver
        return Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1) to
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
    }

    private fun observe(player: ExoPlayer) = PlaybackEvents().also { events ->
        instrumentation.runOnMainSync { player.addListener(events) }
    }

    private fun assertNoPlaybackChange(player: ExoPlayer, events: PlaybackEvents) {
        assertEquals("A screen gesture must not seek", 2_000L, readMain { player.currentPosition })
        assertFalse("A screen gesture must not start a paused video", readMain { player.playWhenReady || player.isPlaying })
        assertEquals(1f, readMain { player.playbackParameters.speed }, .01f)
        assertTrue(events.seeks.isEmpty())
        assertTrue(events.speeds.isEmpty())
        assertTrue(events.playIntentChanges.isEmpty())
        compose.onNodeWithTag("video-gesture-seek").assertDoesNotExist()
        compose.onNodeWithTag("video-gesture-fast").assertDoesNotExist()
    }

    private class PlaybackEvents : Player.Listener {
        val playIntentChanges = CopyOnWriteArrayList<Boolean>()
        val seeks = CopyOnWriteArrayList<Long>()
        val speeds = CopyOnWriteArrayList<Float>()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playIntentChanges += playWhenReady }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { speeds += playbackParameters.speed }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks += newPosition.positionMs
        }
    }

    private fun <T> readMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
