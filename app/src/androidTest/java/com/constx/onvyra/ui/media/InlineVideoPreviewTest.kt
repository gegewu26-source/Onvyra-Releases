package com.constx.onvyra.ui.media

import android.view.View
import android.os.Looper
import android.webkit.WebView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.action.ViewActions.click
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android decoder and cross-origin WebView tests, independent of public video services. */
@RunWith(AndroidJUnit4::class)
@Suppress("UnsafeOptInUsageError")
class InlineVideoPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun suspendedBackgroundResolutionStillCreatesThePlayerOnTheAndroidMainThread() {
        VideoFixtureServer().use { server ->
            compose.setContent {
                MaterialExpressiveTheme {
                    InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                        resolveVideo = { _, _ ->
                            withContext(Dispatchers.IO) { delay(80); directSource(server) }
                        })
                }
            }
            val native = awaitNativePlayer()
            val player = readMain { requireNotNull(native.player) as ExoPlayer }
            awaitReady(player)
            assertSame(Looper.getMainLooper(), readMain { player.applicationLooper })
        }
    }

    @Test
    fun separateVideoAndAudioSourcesProduceBothSelectedTracks() {
        VideoFixtureServer().use { server ->
            compose.setContent {
                MaterialExpressiveTheme {
                    InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT), resolveVideo = { _, _ ->
                        directSource(server).copy(audioUrl = "${server.url}/audio.wav")
                    })
                }
            }
            val native = awaitNativePlayer()
            val player = readMain { requireNotNull(native.player) as ExoPlayer }
            awaitReady(player)
            assertTrue(readMain { player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } })
            assertTrue(readMain { player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } })
            instrumentation.runOnMainSync { player.play() }
            compose.waitUntil(15_000) { readMain { player.isPlaying && player.currentPosition > 250 } }
        }
    }

    @Test
    fun nativePlaybackSpeedAndPositionSurviveFullscreenAndDisposalReleasesThePlayer() {
        VideoFixtureServer().use { server ->
            val shown = mutableStateOf(true)
            val link = VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT)
            compose.setContent {
                MaterialExpressiveTheme {
                    if (shown.value) InlineVideoPreview(link, resolveVideo = { _, _ -> directSource(server) })
                }
            }
            val originalView = awaitNativePlayer()
            val originalPlayer = readMain { requireNotNull(originalView.player) as ExoPlayer }
            awaitReady(originalPlayer)
            assertFalse("Opening a message must not autoplay audio", readMain { originalPlayer.playWhenReady })
            instrumentation.runOnMainSync { originalPlayer.play() }
            compose.waitUntil(15_000) { readMain { originalPlayer.currentPosition > 250 } }
            instrumentation.runOnMainSync { originalPlayer.pause(); originalPlayer.seekTo(900) }
            compose.waitUntil(10_000) { readMain { originalPlayer.currentPosition >= 850 } }

            chooseSpeed("2×")
            assertEquals(2f, readMain { originalPlayer.playbackParameters.speed }, 0.01f)
            val savedPosition = readMain { originalPlayer.currentPosition }
            compose.onNodeWithTag("video-fullscreen").performClick()
            compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
            val fullscreenView = currentView(PlayerView::class.java, NativeTag)
            assertSame("Fullscreen created a different PlayerView", originalView, fullscreenView)
            assertSame(originalPlayer, readMain { fullscreenView.player })
            assertTrue("Fullscreen reset the playback position", readMain { originalPlayer.currentPosition } >= savedPosition - 100)
            assertEquals(2f, readMain { originalPlayer.playbackParameters.speed }, 0.01f)

            compose.onNodeWithTag("video-exit-fullscreen").performClick()
            assertSame(originalView, currentView(PlayerView::class.java, NativeTag))
            assertSame(originalPlayer, readMain { originalView.player })
            assertTrue(readMain { originalPlayer.currentPosition } >= savedPosition - 100)
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            assertNull("Removing the message left its native player attached", readMain { originalView.player })
        }
    }

    @Test
    fun embeddedCrossOriginFrameAcceptsSpeedAndFullscreenPreservesTheRunningWebView() {
        VideoFixtureServer().use { server ->
            val link = VideoLink("${server.url}/embedded", VideoPlatform.OTHER)
            compose.setContent {
                MaterialExpressiveTheme {
                    InlineVideoPreview(link, resolveVideo = { _, _ ->
                        VideoPlaybackSource("${server.url}/embedded", VideoPlaybackKind.EMBEDDED)
                    })
                }
            }
            awaitPreview()
            val original = currentView(WebView::class.java, WebTag)
            awaitJavaScript(original, "window.fixtureCounter", "17")
            chooseSpeed("2×")
            awaitJavaScript(original, "window.fixtureRate", "2")
            assertEquals("99", evaluateJavaScript(original, "window.fixtureCounter=99"))

            compose.onNodeWithTag("video-fullscreen").performClick()
            compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
            assertSame(original, currentView(WebView::class.java, WebTag))
            assertEquals("99", evaluateJavaScript(original, "window.fixtureCounter"))
            assertEquals("2", evaluateJavaScript(original, "window.fixtureRate"))

            compose.onNodeWithTag("video-exit-fullscreen").performClick()
            assertSame(original, currentView(WebView::class.java, WebTag))
            assertEquals("99", evaluateJavaScript(original, "window.fixtureCounter"))
            assertEquals("2", evaluateJavaScript(original, "window.fixtureRate"))
        }
    }

    @Test
    fun scrollingNativePlayerOutOfTheViewportPausesPlayback() {
        VideoFixtureServer().use { server ->
            lateinit var scroll: ScrollState
            compose.setContent {
                MaterialExpressiveTheme {
                    scroll = rememberScrollState()
                    Column(Modifier.fillMaxWidth().height(360.dp).verticalScroll(scroll)) {
                        InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                            resolveVideo = { _, _ -> directSource(server) })
                        Spacer(Modifier.height(2400.dp))
                    }
                }
            }
            val native = awaitNativePlayer()
            val player = readMain { requireNotNull(native.player) as ExoPlayer }
            awaitReady(player)
            instrumentation.runOnMainSync { player.play() }
            compose.waitUntil(15_000) { readMain { player.isPlaying && player.currentPosition > 100 } }
            compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
            compose.waitUntil(10_000) { readMain { !player.playWhenReady } }
            assertFalse(readMain { player.isPlaying })
        }
    }

    @Test
    fun failedPublicResolutionCanBeRetriedWithTheEnhancedResolver() {
        VideoFixtureServer().use { server ->
            val attempts = mutableListOf<Boolean>()
            compose.setContent {
                MaterialExpressiveTheme {
                    InlineVideoPreview(VideoLink("${server.url}/page", VideoPlatform.OTHER),
                        resolveVideo = { _, enhanced ->
                            attempts.add(enhanced)
                            if (!enhanced) throw IOException("fixture public player unavailable")
                            directSource(server)
                        })
                }
            }
            awaitPreview()
            compose.onNodeWithText("fixture public player unavailable").assertIsDisplayed()
            showControls()
            compose.onNodeWithTag("video-more").performClick()
            compose.onNodeWithTag("video-enhanced").performClick()
            val native = awaitNativePlayer()
            awaitReady(readMain { requireNotNull(native.player) as ExoPlayer })
            assertEquals(listOf(false, true), attempts.toList())
        }
    }

    private fun directSource(server: VideoFixtureServer) =
        VideoPlaybackSource("${server.url}/clip.mp4", VideoPlaybackKind.DIRECT, mimeType = "video/mp4")

    private fun awaitPreview() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("inline-video-preview").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("inline-video-preview").assertIsDisplayed()
        // The resolution callback is asynchronous even when its fixture returns immediately.
        compose.waitForIdle()
    }

    private fun awaitNativePlayer(): PlayerView {
        awaitPreview()
        val found = AtomicReference<PlayerView>()
        compose.waitUntil(15_000) {
            runCatching { currentView(PlayerView::class.java, NativeTag) }
                .onSuccess(found::set).isSuccess
        }
        return requireNotNull(found.get())
    }

    private fun awaitReady(player: ExoPlayer) {
        compose.waitUntil(20_000) { readMain { player.playbackState == Player.STATE_READY } }
    }

    private fun chooseSpeed(text: String) {
        showControls()
        compose.onNodeWithTag("video-speed").performClick()
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun showControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty()) {
            if (compose.onAllNodesWithTag("video-tap-surface").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("video-tap-surface").performClick()
            } else {
                onView(isAssignableFrom(WebView::class.java)).perform(click())
            }
        }
    }

    private fun <T : View> currentView(type: Class<T>, tag: String): T {
        val value = AtomicReference<T>()
        onView(allOf(isAssignableFrom(type), withTagValue(equalTo<Any>(tag))))
            .inRoot(object : org.hamcrest.TypeSafeMatcher<androidx.test.espresso.Root>() {
                override fun describeTo(description: org.hamcrest.Description) {
                    description.appendText("the focused video window, including its fullscreen dialog")
                }
                override fun matchesSafely(root: androidx.test.espresso.Root) = root.decorView.hasWindowFocus()
            }).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(type)
            override fun getDescription() = "inspect actual inline video view"
            override fun perform(uiController: UiController, view: View) { value.set(type.cast(view)) }
        })
        return requireNotNull(value.get())
    }

    private fun <T> readMain(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun awaitJavaScript(browser: WebView, script: String, expected: String) {
        compose.waitUntil(15_000) { evaluateJavaScript(browser, script) == expected }
    }

    private fun evaluateJavaScript(browser: WebView, script: String): String {
        val completed = CountDownLatch(1)
        val value = AtomicReference<String>()
        instrumentation.runOnMainSync {
            browser.evaluateJavascript(script) { value.set(it); completed.countDown() }
        }
        assertTrue("Embedded video script did not answer", completed.await(5, TimeUnit.SECONDS))
        return requireNotNull(value.get())
    }

    private companion object {
        const val NativeTag = "inline-video-native-player"
        const val WebTag = "inline-video-web-player"
    }
}
