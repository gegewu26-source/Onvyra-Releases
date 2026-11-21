package com.constx.onvyra.ui.media

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

/** Real Media3 failures, rather than just a mocked selection callback. */
@RunWith(AndroidJUnit4::class)
class VideoAutomaticSourceSwitchTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun initialResolutionFailureRetriesOptionsOnceAndContinuesPastAnUnresolvableSource() {
        VideoFixtureServer().use { server ->
            val optionLoads = AtomicInteger()
            val attempts = CopyOnWriteArrayList<String>()
            val selected = AtomicReference<String>()
            compose.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.width(272.dp)) {
                        InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                            resolveVideo = { _, _ -> throw IOException("Initial source is no longer available") },
                            loadSourceOptions = {
                                if (optionLoads.incrementAndGet() == 1) throw IOException("Transient catalog outage")
                                listOf(VideoSourceOption("initial", "当前失效来源", true),
                                    VideoSourceOption("bad", "无法解析来源"), VideoSourceOption("good", "可播放来源"))
                            },
                            resolveSourceOption = { id ->
                                attempts += id
                                if (id == "bad") throw IOException("No playback address")
                                source(server, "clip-alt.mp4")
                            },
                            onSourceSelected = selected::set,
                        )
                    }
                }
            }
            compose.waitUntil(15_000) { selected.get() == "good" }
            val player = currentPlayer()
            awaitReady(player)
            assertEquals(listOf("bad", "good"), attempts.toList())
            assertEquals(2, optionLoads.get())
            assertTrue(readMain { player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") })
            assertFalse("Recovering an unopened message must not start audio", readMain { player.playWhenReady })
            assertNull(readMain { player.playerError })
            compose.onNodeWithTag("video-auto-source-exhausted").assertDoesNotExist()
            println("VIDEO_AUTO_SOURCE=initial-resolution-recovered; ATTEMPTS=$attempts; LIST_LOADS=${optionLoads.get()}")
        }
    }

    @Test fun streamedOptionsWaitForALaterPlayableSourceInsteadOfExhaustingTheCurrentOnlySnapshot() {
        VideoFixtureServer().use { server ->
            val releaseAlternative = CompletableDeferred<Unit>()
            val firstSnapshot = AtomicInteger()
            val fallbackLoads = AtomicInteger()
            val recoveries = AtomicInteger()
            val attempts = CopyOnWriteArrayList<String>()
            val selected = AtomicReference<String>()
            val updates = flow {
                emit(listOf(VideoSourceOption("initial", "当前失效来源", true)))
                firstSnapshot.incrementAndGet()
                releaseAlternative.await()
                emit(listOf(VideoSourceOption("initial", "当前失效来源", true),
                    VideoSourceOption("good", "稍后找到的可播放来源")))
            }
            compose.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.width(272.dp)) {
                        InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                            resolveVideo = { _, _ -> throw IOException("Unavailable initial stream") },
                            loadSourceOptions = { fallbackLoads.incrementAndGet(); emptyList() },
                            sourceOptionsUpdates = updates,
                            resolveSourceOption = { id -> attempts += id; source(server, "clip-alt.mp4") },
                            onSourceSelected = selected::set,
                            onSourcesExhausted = { recoveries.incrementAndGet() },
                        )
                    }
                }
            }
            compose.waitUntil(10_000) { firstSnapshot.get() == 1 }
            compose.onNodeWithTag("video-auto-source-exhausted").assertDoesNotExist()
            assertTrue("A current-only partial snapshot must wait for further source results", attempts.isEmpty())
            assertEquals(0, recoveries.get())
            assertEquals("The stream must not also run the all-at-once loader", 0, fallbackLoads.get())
            releaseAlternative.complete(Unit)
            compose.waitUntil(15_000) { selected.get() == "good" }
            awaitReady(currentPlayer())
            assertEquals(listOf("good"), attempts.toList())
            compose.onNodeWithTag("video-auto-source-exhausted").assertDoesNotExist()
            assertEquals(0, recoveries.get())
            println("VIDEO_AUTO_SOURCE=late-stream-recovered; EARLY_EXHAUSTION=false; ATTEMPTS=$attempts")
        }
    }

    @Test fun sourceMenuDisplaysCachedChoicesBeforeTheLookupCompletesAndAcceptsALaterChoice() {
        VideoFixtureServer().use { server ->
            val releaseAlternative = CompletableDeferred<Unit>()
            val collections = AtomicInteger()
            val fallbackLoads = AtomicInteger()
            val selected = AtomicReference<String>()
            val updates = flow {
                collections.incrementAndGet()
                emit(listOf(VideoSourceOption("initial", "当前片源", true)))
                releaseAlternative.await()
                emit(listOf(VideoSourceOption("initial", "当前片源", true),
                    VideoSourceOption("good", "稍后找到的备用片源")))
            }
            compose.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.width(272.dp)) {
                        InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                            resolveVideo = { _, _ -> source(server, "clip.mp4") },
                            loadSourceOptions = { fallbackLoads.incrementAndGet(); emptyList() },
                            sourceOptionsUpdates = updates,
                            resolveSourceOption = { source(server, "clip-alt.mp4") },
                            onSourceSelected = selected::set,
                        )
                    }
                }
            }
            val original = awaitPlayer()
            awaitReady(original)
            assertEquals("Source lookup must remain lazy while healthy playback has no open menu", 0, collections.get())
            revealControls()
            compose.onNodeWithTag("video-source").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("video-source-option-initial").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("video-source-option-initial").assertIsDisplayed()
            assertEquals(1, collections.get())
            assertEquals(0, fallbackLoads.get())
            releaseAlternative.complete(Unit)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("video-source-option-good").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("video-source-option-good").performScrollTo().performClick()
            compose.waitUntil(15_000) { selected.get() == "good" }
            awaitReady(original)
            assertSame("Incremental source selection must keep the existing player", original, currentPlayer())
            assertTrue(readMain { original.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") })
            assertFalse(readMain { original.playWhenReady })
            assertNull(readMain { original.playerError })
            println("VIDEO_SOURCE_MENU=incremental-choices; FALLBACK_BATCH_LOADS=${fallbackLoads.get()}; SAME_PLAYER=true")
        }
    }

    @Test fun actualNativePlaybackFailureSkipsBadMediaAndKeepsThePlayerPositionSpeedAndFullscreen() {
        VideoFixtureServer().use { server ->
            val optionLoads = AtomicInteger()
            val attempts = CopyOnWriteArrayList<String>()
            val selected = AtomicReference<String>()
            compose.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.width(272.dp)) {
                        InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                            resolveVideo = { _, _ -> source(server, "clip.mp4") },
                            loadSourceOptions = {
                                optionLoads.incrementAndGet()
                                listOf(VideoSourceOption("initial", "初始来源", true),
                                    VideoSourceOption("unresolvable", "解析失效来源"),
                                    VideoSourceOption("bad-media", "媒体失效来源"),
                                    VideoSourceOption("good", "备用可播放来源"))
                            },
                            resolveSourceOption = { id ->
                                attempts += id
                                if (id == "unresolvable") throw IOException("Expired locator")
                                source(server, if (id == "bad-media") "missing-alternative.mp4" else "clip-alt.mp4")
                            },
                            onSourceSelected = selected::set,
                        )
                    }
                }
            }
            val original = awaitPlayer()
            awaitReady(original)
            assertTrue("A healthy paused source must not trigger background source discovery", attempts.isEmpty())
            assertEquals(0, optionLoads.get())
            instrumentation.runOnMainSync { original.seekTo(1_500) }
            revealControls()
            compose.onNodeWithTag("video-speed").performClick()
            compose.onNodeWithTag("video-speed-option-1.5").performScrollTo().performClick()
            compose.onNodeWithTag("video-fullscreen").performClick()
            compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
            assertSame(original, currentPlayer())

            // A real 404 is fed to Media3 after the original stream was usable. The UI still
            // owns the same player and must respond to its real onPlayerError callback.
            instrumentation.runOnMainSync {
                original.setMediaItem(MediaItem.fromUri("${server.url}/missing-current.mp4"), 1_500)
                original.prepare()
            }
            compose.waitUntil(20_000) { selected.get() == "good" }
            awaitReady(original)
            assertSame(original, currentPlayer())
            assertEquals(listOf("unresolvable", "bad-media", "good"), attempts.toList())
            assertTrue("Automatic recovery reset the saved position", readMain { original.currentPosition >= 1_400 })
            assertEquals(1.5f, readMain { original.playbackParameters.speed }, .01f)
            assertFalse(readMain { original.playWhenReady })
            assertNull(readMain { original.playerError })
            compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
            revealControls()
            compose.onNodeWithTag("video-exit-fullscreen").performClick()
            println("VIDEO_AUTO_SOURCE=native-error-recovered; ATTEMPTS=$attempts; SAME_PLAYER=true; POSITION=${readMain { original.currentPosition }}; SPEED=${readMain { original.playbackParameters.speed }}")
        }
    }

    @Test fun failedSourcesAreAttemptedOnceWithASixChoiceLimitAndManualRecoveryRemainsAvailable() {
        VideoFixtureServer().use { server ->
            val attempts = CopyOnWriteArrayList<String>()
            compose.setContent {
                MaterialExpressiveTheme {
                    Box(Modifier.width(272.dp)) {
                        InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                            resolveVideo = { _, _ -> throw IOException("Unavailable initial stream") },
                            loadSourceOptions = {
                                listOf(VideoSourceOption("initial", "当前来源", true)) +
                                    (1..9).map { VideoSourceOption("alt-$it", "备用来源 $it") }
                            },
                            resolveSourceOption = { id -> attempts += id; throw IOException("No media") },
                        )
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("video-auto-source-exhausted").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals((1..6).map { "alt-$it" }, attempts.toList())
            assertEquals(6, attempts.distinct().size)
            compose.onNodeWithTag("video-auto-source-exhausted").assertIsDisplayed()
            compose.onNodeWithText("已尝试可用片源，可让AI继续搜索其他来源").assertIsDisplayed()
            compose.onNodeWithTag("video-retry").assertIsDisplayed()
            compose.onNodeWithTag("video-source").assertIsDisplayed().performClick()
            compose.onNodeWithTag("video-source-option-alt-9").performScrollTo().assertIsDisplayed()
            assertEquals("Opening the manual menu must not restart exhausted automatic retries", 6, attempts.size)
            println("VIDEO_AUTO_SOURCE=exhausted; ATTEMPTS=$attempts; MANUAL_CHOICES_AVAILABLE=true")
        }
    }

    private fun source(server: VideoFixtureServer, file: String) = VideoPlaybackSource(
        "${server.url}/$file", VideoPlaybackKind.DIRECT, title = "原创自动换源测试", mimeType = "video/mp4")

    private fun revealControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("video-tap-surface").performClick()
    }

    private fun awaitPlayer(): ExoPlayer {
        val result = AtomicReference<ExoPlayer>()
        compose.waitUntil(15_000) { runCatching { currentPlayer() }.onSuccess(result::set).isSuccess }
        return requireNotNull(result.get())
    }

    private fun awaitReady(player: ExoPlayer) {
        compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
    }

    private fun currentPlayer(): ExoPlayer {
        val result = AtomicReference<ExoPlayer>()
        onView(isAssignableFrom(PlayerView::class.java)).inRoot(object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) { description.appendText("the focused video window") }
            override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
        }).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
            override fun getDescription() = "inspect the player retained during automatic source recovery"
            override fun perform(uiController: UiController, view: View) { result.set((view as PlayerView).player as ExoPlayer) }
        })
        return requireNotNull(result.get())
    }

    private fun <T> readMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
