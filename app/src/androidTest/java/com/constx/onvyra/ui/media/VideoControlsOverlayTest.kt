package com.constx.onvyra.ui.media

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import com.constx.onvyra.R
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import com.constx.onvyra.ui.components.message.VideoSourceOption
import com.constx.onvyra.ui.components.message.VideoEpisodeOption
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.OkHttpClient
import java.net.Proxy

@RunWith(AndroidJUnit4::class)
class VideoControlsOverlayTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun sourceAndQualityMenusShareOneLookupAndUnlabelledRealLinesRemainSelectable() {
        VideoFixtureServer().use { server ->
            val subscriptions = AtomicInteger()
            show(server, sources = true, streamSubscriptions = subscriptions)
            val player = awaitPlayer()
            reveal()
            compose.onNodeWithTag("video-quality").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-catalog-quality-b").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video-catalog-quality-b").assertExists()
            dismissMenuOutside()
            compose.onNodeWithTag("video-source").performClick()
            compose.onNodeWithTag("video-source-option-b").assertExists()
            dismissMenuOutside()
            compose.onNodeWithTag("video-quality").performClick()
            compose.onNodeWithTag("video-catalog-quality-b").assertExists()
            compose.runOnIdle { assertEquals("Menu changes must retain the original source lookup", 1, subscriptions.get()) }
            compose.onNodeWithTag("video-catalog-quality-b").performScrollTo().performClick()
            compose.waitUntil(10_000) { readMain { player.playbackState == Player.STATE_READY &&
                player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") } }
            assertSame(player, currentPlayer())
            assertAdFraudNoticeBelowViewport()
        }
    }

    @Test fun nextEpisodeAndEpisodeMenuWorkLocallyAndAFailedEpisodeKeepsTheCurrentVideo() {
        VideoFixtureServer().use { server ->
            show(server, episodes = true)
            val player = awaitPlayer()
            assertAdFraudNoticeBelowViewport()
            instrumentation.runOnMainSync { player.seekTo(2_500); player.pause() }
            reveal()
            compose.onNodeWithTag("video-fullscreen").performClick()
            compose.onNodeWithTag("video-next-episode").performClick()
            compose.waitUntil(10_000) { readMain { player.playbackState == Player.STATE_READY &&
                player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") } }
            assertSame(player, currentPlayer())
            assertTrue("A new episode starts from zero instead of the previous episode's position", readMain { player.currentPosition < 1_000 })
            compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
            reveal()
            compose.onNodeWithTag("video-episode").performClick()
            compose.onNodeWithTag("video-episode-2").assertIsSelected()
            compose.onNodeWithTag("video-episode-3").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-source-error").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(readMain { player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") })
            reveal()
            compose.onNodeWithTag("video-exit-fullscreen").performClick()
            assertAdFraudNoticeBelowViewport()
        }
    }

    @Test fun qualityMenuCanChooseASecondRealCatalogLineFromTheSameProvider() {
        VideoFixtureServer().use { server ->
            show(server, sources = true, catalogQualities = true)
            val player = awaitPlayer()
            assertAdFraudNoticeBelowViewport()
            reveal()
            compose.onNodeWithTag("video-quality").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("video-catalog-quality-b").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video-catalog-quality-a").assertExists()
            compose.onNodeWithTag("video-catalog-quality-b").performScrollTo().performClick()
            compose.waitUntil(10_000) { readMain { player.playbackState == Player.STATE_READY &&
                player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") } }
            assertSame(player, currentPlayer())
            assertAdFraudNoticeBelowViewport()
        }
    }

    @Test fun inlineTapShowsTimeAndAllActionsInsideSmallVideoThenTheyHideAutomatically() {
        VideoFixtureServer().use { server ->
            show(server, sources = true)
            val player = awaitPlayer()
            reveal()
            savePicture("player-controls-inline.png")
            println("INLINE_CLOCK_NATIVE_DURATION=${readMain { player.duration }}; UI=${compose.onNodeWithTag("video-time").fetchSemanticsNode().config}")
            val clock = compose.onNodeWithTag("video-time").assertIsDisplayed().fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.substringAfter(" / ")
            val displayedSeconds = clock.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
            assertEquals("The inline clock must show the player's actual duration", readMain { player.duration / 1_000 }, displayedSeconds)
            val picture = compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInRoot
            listOf("video-time", "video-seek", "video-speed", "video-quality", "video-source", "video-fullscreen").forEach { tag ->
                val control = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("$tag escaped the video picture", control.left >= picture.left && control.right <= picture.right &&
                    control.top >= picture.top && control.bottom <= picture.bottom)
            }
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
            savePicture("player-controls-inline.png")
            compose.waitUntil(6_000) { compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("video-tap-surface").performClick()
            compose.waitUntil(2_000) { compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video-time").assertIsDisplayed()
        }
    }

    @Test fun inlineProgressBarActuallySeeksWithoutOpeningFullscreen() {
        VideoFixtureServer().use { server ->
            show(server)
            val player = awaitPlayer()
            reveal()
            compose.onNodeWithTag("video-seek").performTouchInput {
                swipe(Offset(width * .2f, height / 2f), Offset(width * .65f, height / 2f), 250)
            }
            compose.waitUntil(5_000) { readMain { player.currentPosition > 2_500 } }
            assertFalse(readMain { player.playWhenReady })
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
        }
    }

    @Test fun sourceSwitchKeepsPlayerProgressSpeedAndFullscreenAndFailedChoiceKeepsOldSource() {
        VideoFixtureServer().use { server ->
            show(server, sources = true)
            val player = awaitPlayer()
            instrumentation.runOnMainSync { player.seekTo(1_500) }
            reveal()
            compose.onNodeWithTag("video-speed").performClick()
            compose.onNodeWithText("1.5×").performScrollTo().performClick()
            compose.onNodeWithTag("video-fullscreen").performClick()
            compose.onNodeWithTag("video-source").performClick()
            compose.onNodeWithTag("video-source-option-b").performScrollTo().performClick()
            compose.waitUntil(10_000) { readMain { player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") &&
                player.playbackState == Player.STATE_READY } }
            assertSame(player, currentPlayer())
            assertTrue(readMain { player.currentPosition >= 1_400 })
            assertEquals(1.5f, readMain { player.playbackParameters.speed }, .01f)
            assertFalse(readMain { player.playWhenReady })
            compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
            reveal()
            compose.onNodeWithTag("video-source").performClick()
            compose.onNodeWithTag("video-source-option-bad").performScrollTo().performClick()
            compose.onNodeWithTag("video-source-error").assertIsDisplayed()
            assertTrue(readMain { player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") })
            assertSame(player, currentPlayer())
            compose.onNodeWithTag("video-exit-fullscreen").performClick()
            assertAdFraudNoticeBelowViewport()
        }
    }

    @Test fun singleResolutionSourceDoesNotInventHdChoices() {
        VideoFixtureServer().use { server ->
            show(server)
            awaitPlayer()
            reveal()
            compose.onNodeWithTag("video-quality").performClick()
            compose.onNode(SemanticsMatcher("a real quality option") {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) &&
                    it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("video-quality-option-")
            } and hasText("180p")).assertIsDisplayed()
            compose.onNodeWithText("1080p").assertDoesNotExist()
            compose.onNodeWithText("720p").assertDoesNotExist()
            compose.onNodeWithText("此片源仅提供以上画质", substring = true, useUnmergedTree = true)
                .assertDoesNotExist()
        }
    }

    @Test fun inlineSpeedAndQualityUseNarrowScrollingMenusWithoutResizingThePicture() {
        VideoFixtureServer().use { server ->
            show(server)
            val player = awaitPlayer()
            reveal()
            val original = compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInWindow
            compose.onNodeWithTag("video-speed").performClick()
            assertCompactMenu("inline-video-preview", "video-speed", 64)
            assertEquals(original, compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInWindow)
            savePicture("player-menu-speed-inline.png")
            compose.onNodeWithTag("video-speed-option-3.0").performScrollTo().performClick()
            assertEquals(3f, readMain { player.playbackParameters.speed }, .01f)
            compose.onNodeWithTag("video-options-panel").assertDoesNotExist()
            assertEquals(original, compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInWindow)

            compose.onNodeWithTag("video-quality").performClick()
            assertCompactMenu("inline-video-preview", "video-quality", 88)
            compose.onNodeWithTag("video-quality-auto").assertIsSelected()
            dismissMenuOutside()
            compose.onNodeWithTag("video-options-panel").assertDoesNotExist()
            assertEquals(original, compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInWindow)
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
        }
    }

    @Test fun longNarrowSourceListScrollsToALaterChoiceAndKeepsTheSamePlayer() {
        VideoFixtureServer().use { server ->
            show(server, sources = true, extraSources = 18)
            val player = awaitPlayer()
            reveal()
            val original = compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInWindow
            compose.onNodeWithTag("video-source").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("video-source-option-extra-17").fetchSemanticsNodes().isNotEmpty()
            }
            assertCompactMenu("inline-video-preview", "video-source", 112)
            compose.onNodeWithTag("video-source-option-extra-17").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { readMain {
                player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") &&
                    player.playbackState == Player.STATE_READY
            } }
            assertSame(player, currentPlayer())
            assertEquals(original, compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInWindow)
            compose.onNodeWithTag("video-source").performClick()
            compose.onNodeWithTag("video-source-option-extra-17").performScrollTo().assertIsSelected()
            dismissMenuOutside()
            assertFalse(readMain { player.playWhenReady })
        }
    }

    @Test fun fullscreenSpeedQualityAndSourceMenusStayNarrowInsideTheFixedViewport() {
        VideoFixtureServer().use { server ->
            show(server, sources = true)
            awaitPlayer()
            reveal()
            compose.onNodeWithTag("video-fullscreen").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("video-fullscreen-player").fetchSemanticsNodes().singleOrNull()
                    ?.boundsInWindow?.let { it.width > it.height } == true
            }
            compose.waitForIdle()
            val original = compose.onNodeWithTag("video-fullscreen-player").fetchSemanticsNode().boundsInWindow
            for (tag in listOf("video-speed", "video-quality", "video-source")) {
                reveal()
                compose.onNodeWithTag(tag).performClick()
                assertCompactMenu("video-fullscreen-player", tag, when (tag) {
                    "video-speed" -> 64
                    "video-quality" -> 200
                    else -> 112
                })
                assertEquals(original, compose.onNodeWithTag("video-fullscreen-player").fetchSemanticsNode().boundsInWindow)
                if (tag == "video-source") {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        instrumentation.targetContext.cacheDir.resolve("player-menu-source-fullscreen.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
                dismissMenuOutside()
                compose.onNodeWithTag("video-options-panel").assertDoesNotExist()
                assertEquals(original, compose.onNodeWithTag("video-fullscreen-player").fetchSemanticsNode().boundsInWindow)
            }
            compose.onNodeWithTag("video-exit-fullscreen").performClick()
        }
    }

    private fun assertAdFraudNoticeBelowViewport() {
        val viewport = compose.onNodeWithTag("inline-video-preview").fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithTag("video-ad-fraud-notice").assertIsDisplayed()
            .assertTextEquals(instrumentation.targetContext.getString(R.string.movie_ad_fraud_notice))
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The persistent notice must be below the player rather than covering its picture", notice.top >= viewport.bottom)
    }

    private fun assertCompactMenu(viewportTag: String, buttonTag: String, widthDp: Int) {
        val viewport = compose.onNodeWithTag(viewportTag).fetchSemanticsNode().boundsInWindow
        val panel = compose.onNodeWithTag("video-options-panel").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val anchor = compose.onNodeWithTag(buttonTag).fetchSemanticsNode().boundsInWindow
        val density = instrumentation.targetContext.resources.displayMetrics.density
        assertEquals("The list must stay a small strip in either playback mode", widthDp * density, panel.width, 1.5f)
        assertEquals("The strip must open immediately above its own button", anchor.top - 4f * density, panel.bottom, 1.5f)
        val expectedLeft = (anchor.center.x - panel.width / 2f)
            .coerceIn(viewport.left + 4f * density, viewport.right - panel.width - 4f * density)
        assertEquals("The strip must follow the chosen button rather than a fixed screen edge", expectedLeft, panel.left, 1.5f)
        assertTrue("The menu escaped the fixed video viewport", panel.left >= viewport.left && panel.right <= viewport.right &&
            panel.top >= viewport.top && panel.bottom <= viewport.bottom)
        assertTrue("The list must leave space above and below the video", panel.height < viewport.height)
        compose.onNodeWithTag("video-options-close").assertDoesNotExist()
        compose.onNodeWithText("播放倍速").assertDoesNotExist()
        println("VIDEO_BUTTON_POPUP=$buttonTag; WIDTH_DP=$widthDp; BUTTON=$anchor; PANEL=$panel; VIEWPORT=$viewport")
    }

    private fun dismissMenuOutside() {
        compose.onNodeWithTag("video-options-dismiss").performTouchInput {
            click(Offset(width * .05f, height * .5f))
        }
    }

    private fun show(server: VideoFixtureServer, sources: Boolean = false, extraSources: Int = 0,
        episodes: Boolean = false, catalogQualities: Boolean = false,
        streamSubscriptions: AtomicInteger? = null) {
        compose.setContent {
            var episode by remember { mutableIntStateOf(1) }
            val sourceStream = remember(server, streamSubscriptions) {
                if (streamSubscriptions == null) null else flow<List<VideoSourceOption>> {
                    streamSubscriptions.incrementAndGet()
                    emit(listOf(
                        VideoSourceOption("a", "测试来源 A", true),
                        VideoSourceOption("b", "测试来源 B"),
                    ))
                    awaitCancellation()
                }
            }
            MaterialExpressiveTheme {
                Box(Modifier.width(272.dp)) {
                    InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                        playbackHttpClient = remember { OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build() },
                        resolveVideo = { _, _ -> source(server, "clip.mp4") },
                        loadSourceOptions = if (!sources) null else ({ buildList {
                            add(VideoSourceOption("a", "测试来源 A", true, if (catalogQualities) "高清 · 同一来源 · 线路A" else null))
                            add(VideoSourceOption("b", "测试来源 B", qualityLabel = if (catalogQualities) "蓝光 · 同一来源 · 线路B" else null))
                            add(VideoSourceOption("bad", "不可用来源"))
                            repeat(extraSources) { add(VideoSourceOption("extra-$it", "备用来源 ${it + 1} · 正片线路")) }
                        } }),
                        sourceOptionsUpdates = sourceStream,
                        resolveSourceOption = if (!sources) null else ({ id ->
                            if (id == "bad") throw IOException("fixture failed")
                            source(server, if (id == "b" || id.startsWith("extra-")) "clip-alt.mp4" else "clip.mp4")
                        }),
                        episodeOptions = if (!episodes) emptyList() else (1..3).map { VideoEpisodeOption(it, "第${it}集", it == episode) },
                        resolveEpisode = if (!episodes) null else ({ number ->
                            if (number == 3) throw IOException("episode fixture unavailable")
                            source(server, if (number == 2) "clip-alt.mp4" else "clip.mp4")
                        }),
                        onEpisodeSelected = { episode = it },
                    )
                }
            }
        }
    }

    private fun source(server: VideoFixtureServer, file: String) =
        VideoPlaybackSource("${server.url}/$file", VideoPlaybackKind.DIRECT, title = "自生成测试视频", mimeType = "video/mp4")

    private fun reveal() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("video-tap-surface").performClick()
        }
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitPlayer(): ExoPlayer {
        val found = AtomicReference<ExoPlayer>()
        compose.waitUntil(15_000) { runCatching { currentPlayer() }.onSuccess(found::set).isSuccess }
        val player = found.get()
        compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
        return player
    }

    private fun currentPlayer(): ExoPlayer {
        val result = AtomicReference<ExoPlayer>()
        onView(isAssignableFrom(PlayerView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
            override fun getDescription() = "inspect overlaid video player"
            override fun perform(uiController: UiController, view: View) { result.set((view as PlayerView).player as ExoPlayer) }
        })
        return requireNotNull(result.get())
    }

    private fun savePicture(name: String) {
        compose.onNodeWithTag("inline-video-preview").captureToImage().asAndroidBitmap().let { bitmap ->
            instrumentation.targetContext.cacheDir.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            instrumentation.targetContext.cacheDir.resolve("screen-$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun <T> readMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
