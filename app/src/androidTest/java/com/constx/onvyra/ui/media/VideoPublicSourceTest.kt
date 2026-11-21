package com.constx.onvyra.ui.media

import android.view.View
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.C
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPreviewResolver
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import okhttp3.OkHttpClient
import org.hamcrest.Matcher
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Optional live-source smoke test. CI never depends on a public platform's availability. */
@RunWith(AndroidJUnit4::class)
class VideoPublicSourceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualQualitySelectsARealDecodedRenditionAndAutoClearsTheOverride() {
        val url = InstrumentationRegistry.getArguments().getString("publicVideoUrl")
        assumeTrue("Pass publicVideoUrl for the adaptive stream test", !url.isNullOrBlank())
        compose.setContent {
            MaterialExpressiveTheme {
                InlineVideoPreview(VideoLink(url!!, VideoPlatform.DIRECT), resolveVideo = { _, _ ->
                    com.constx.onvyra.data.media.VideoPlaybackSource(url, com.constx.onvyra.data.media.VideoPlaybackKind.DIRECT,
                        mimeType = "application/x-mpegURL")
                })
            }
        }
        compose.waitUntil(30_000) { runCatching { actualPlayer() }.isSuccess }
        val player = actualPlayer()
        compose.waitUntil(30_000) { readMain { player.playbackState == Player.STATE_READY } }
        val heights = readMain { player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group.getTrackFormat(it).height }
        }.filter { it > 0 }.distinct() }
        assertTrue("The adaptive fixture no longer supplies multiple renditions", heights.size >= 2)
        val chosen = heights.firstOrNull { it == 720 } ?: heights.min()
        revealControls()
        compose.onNodeWithTag("video-quality").performClick()
        compose.onNodeWithText("${chosen}p").performScrollTo().performClick()
        assertTrue(readMain { player.trackSelectionParameters.overrides.values.any { it.type == C.TRACK_TYPE_VIDEO } })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.seekTo(30_000); player.play() }
        compose.waitUntil(45_000) { readMain { player.videoFormat?.height == chosen && player.isPlaying && player.currentPosition > 30_500 } }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.pause() }
        assertSame(player, actualPlayer())
        revealControls()
        compose.onNodeWithTag("video-quality").performClick()
        compose.onNodeWithTag("video-quality-auto").performClick()
        assertTrue(readMain { player.trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO } })
        println("VIDEO_QUALITY_DECODED_HEIGHT=$chosen; AVAILABLE_HEIGHTS=$heights; AUTO_RESET=PASS")
    }

    private fun revealControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("video-tap-surface").performClick()
        }
    }

    @Test fun explicitlySuppliedPublicSourceIsExtractedAndActuallyPlays() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val url = InstrumentationRegistry.getArguments().getString("publicVideoUrl")
        assumeTrue("Pass -e publicVideoUrl to opt into a live-network check", !url.isNullOrBlank())
        val resolver = VideoPreviewResolver(instrument.targetContext, OkHttpClient())
        val resolutionFailure = AtomicReference<Throwable>()
        val resolvedSource = AtomicReference<VideoPlaybackSource>()
        val continuationThread = AtomicReference<String>()
        compose.setContent {
            MaterialExpressiveTheme {
                InlineVideoPreview(VideoLink(url!!, VideoPlatform.OTHER),
                    resolveVideo = { link, _ ->
                        try { resolver.resolve(link, enhanced = true).also {
                            resolvedSource.set(it)
                            continuationThread.set(Thread.currentThread().name)
                        } }
                        catch (error: Exception) { resolutionFailure.set(error); throw error }
                    })
            }
        }
        fun diagnostics(): String {
            val source = resolvedSource.get()
            val summary = "resolved=${source != null}, kind=${source?.kind}, mime=${source?.mimeType}, " +
                "separateAudio=${source?.audioUrl != null}, continuationThread=${continuationThread.get()}"
            return summary + "\n" + compose.onRoot(useUnmergedTree = true).printToString()
        }
        try {
            compose.waitUntil(60_000) {
                resolutionFailure.get() != null ||
                    compose.onAllNodesWithText("重试", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("video-speed").fetchSemanticsNodes().isNotEmpty() &&
                    runCatching { actualPlayer() }.isSuccess
            }
        } catch (failure: Exception) {
            throw AssertionError("Public video window did not appear. ${diagnostics()}", failure)
        }
        assertNull("Public video resolution failed: ${resolutionFailure.get()?.message}", resolutionFailure.get())
        val player = try { actualPlayer() } catch (failure: Exception) {
            throw AssertionError("Resolved public source did not create a native player. ${diagnostics()}", failure)
        }
        compose.waitUntil(25_000) { readMain { player.playbackState == Player.STATE_READY } }
        instrument.runOnMainSync { player.play() }
        compose.waitUntil(20_000) { readMain { player.isPlaying && player.currentPosition > 1_000 } }
        instrument.runOnMainSync { player.pause() }
    }

    private fun actualPlayer(): ExoPlayer {
        val result = AtomicReference<ExoPlayer>()
        onView(isAssignableFrom(PlayerView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
            override fun getDescription() = "inspect real public-source player"
            override fun perform(uiController: UiController, view: View) {
                result.set((view as PlayerView).player as ExoPlayer)
            }
        })
        return requireNotNull(result.get())
    }

    private fun <T> readMain(block: () -> T): T {
        val result = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(block()) }
        return result.get()
    }
}
