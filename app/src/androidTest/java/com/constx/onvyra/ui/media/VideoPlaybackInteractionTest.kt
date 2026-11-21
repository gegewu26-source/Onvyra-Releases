package com.constx.onvyra.ui.media

import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Touch input drives the controls; assertions inspect the real, prepared Media3 player. */
@RunWith(AndroidJUnit4::class)
@Suppress("UnsafeOptInUsageError")
class VideoPlaybackInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val holdDurationMs = ViewConfiguration.getLongPressTimeout().toLong() + 150L

    @Test fun aPendingSingleTapDoesNotDismissASpeedMenuOpenedImmediatelyAfterIt() {
        VideoFixtureServer().use { server ->
            val player = show(server).player
            controlsVisible(true)
            compose.onNodeWithTag("video-tap-surface").performTouchInput {
                click(Offset(width * .5f, height * .3f))
            }
            touch("video-speed")
            awaitTag("video-options-panel", visible = true)
            settleInput()
            compose.onNodeWithTag("video-options-panel").assertIsDisplayed()
            assertFalse(readMain { player.playWhenReady })
            assertEquals(1f, readMain { player.playbackParameters.speed }, .001f)
        }
    }

    @Test fun playButtonPausesAndTheFirstReplayClickRestartsAnEndedPlayer() {
        VideoFixtureServer().use { server ->
            val player = show(server).player
            position(player, 700L)
            controlsVisible(true)
            playButtonDescription("播放")
            touch("video-play")
            awaitPlayingPast(player, 850L)
            playButtonDescription("暂停")

            touch("video-play")
            awaitPaused(player)
            val pausedAt = readMain { player.currentPosition }
            settleInput()
            assertEquals("Pause must stop the actual playback clock", pausedAt, readMain { player.currentPosition })
            playButtonDescription("播放")

            instrumentation.runOnMainSync {
                player.seekTo(player.duration - 150L)
                player.play()
            }
            compose.waitUntil(5_000) { readMain { player.playbackState == Player.STATE_ENDED } }
            assertTrue("Media3 normally retains play intent at the end; replay must handle that state", readMain { player.playWhenReady })
            val events = observe(player)
            controlsVisible(true)
            playButtonDescription("重新播放")
            touch("video-play")
            compose.waitUntil(5_000) { readMain { player.isPlaying && player.currentPosition in 1L..1_500L } }
            assertEquals("The first click must seek to the beginning exactly once", listOf(0L), events.seeks.toList())
            playButtonDescription("暂停")
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
        }
    }

    @Test fun eachDoubleTapChangesPlaybackOnceWithoutADelayedSingleTapAction() {
        VideoFixtureServer().use { server ->
            val player = show(server).player
            position(player, 1_000L)
            controlsVisible(false)
            val events = observe(player)

            doubleTapSurface()
            awaitPlayingPast(player, 1_150L)
            settleInput()
            assertEquals(listOf(true), events.playIntentChanges.toList())
            compose.onNodeWithTag("video-controls").assertIsDisplayed()
            playButtonDescription("暂停")

            doubleTapSurface()
            awaitPaused(player)
            val pausedAt = readMain { player.currentPosition }
            settleInput()
            assertEquals("A double tap must not be followed by a second toggle", listOf(true, false), events.playIntentChanges.toList())
            assertEquals(pausedAt, readMain { player.currentPosition })
            compose.onNodeWithTag("video-controls").assertIsDisplayed()
            playButtonDescription("播放")
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
        }
    }

    @Test fun bufferingPlayButtonShowsPauseForPendingPlaybackAndASecondClickCancelsIt() {
        VideoFixtureServer().use { server ->
            val bodyWaiting = CountDownLatch(1)
            val releaseBody = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath != "/clip.mp4") return@addInterceptor response
                val body = requireNotNull(response.body)
                val gatedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyWaiting.countDown()
                        if (!releaseBody.await(20, TimeUnit.SECONDS)) throw IOException("Timed out waiting to release the fixture video body")
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = gatedSource
                }).build()
            }.build()
            try {
                instrumentation.runOnMainSync {
                    compose.activity.setContent {
                        MaterialExpressiveTheme {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Box(Modifier.width(272.dp)) {
                                    InlineVideoPreview(
                                        VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                                        resolveVideo = { _, _ -> VideoPlaybackSource("${server.url}/clip.mp4", VideoPlaybackKind.DIRECT,
                                            title = "本地缓冲播放意图测试", mimeType = "video/mp4") },
                                        playbackHttpClient = client,
                                    )
                                }
                            }
                        }
                    }
                }
                awaitTag("inline-video-preview", visible = true)
                val found = AtomicReference<PlayerView>()
                compose.waitUntil(15_000) { runCatching { focusedVideoTestPlayer() }.onSuccess(found::set).isSuccess }
                val player = readMain { found.get().player as ExoPlayer }
                compose.waitUntil(5_000) { bodyWaiting.count == 0L && readMain { player.playbackState == Player.STATE_BUFFERING && !player.isPlaying } }
                controlsVisible(true)
                playButtonDescription("播放")
                val events = observe(player)

                touch("video-play")
                compose.waitUntil(5_000) { readMain { player.playWhenReady } }
                assertEquals(Player.STATE_BUFFERING, readMain { player.playbackState })
                assertFalse("The gated body must prevent actual playback while play intent is pending", readMain { player.isPlaying })
                playButtonDescription("暂停")

                touch("video-play")
                compose.waitUntil(5_000) { readMain { !player.playWhenReady } }
                assertEquals(Player.STATE_BUFFERING, readMain { player.playbackState })
                playButtonDescription("播放")
                assertEquals("The second click must cancel pending playback", listOf(true, false), events.playIntentChanges.toList())

                releaseBody.countDown()
                compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
                val pausedAt = readMain { player.currentPosition }
                settleInput()
                assertFalse("Completing the buffer must not restart cancelled playback or audio", readMain { player.playWhenReady || player.isPlaying })
                assertEquals(pausedAt, readMain { player.currentPosition })
                assertEquals(listOf(true, false), events.playIntentChanges.toList())
            } finally {
                releaseBody.countDown()
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun holdingTemporarilyBoostsPlaybackAndReleaseOrCancelRestoresTheChosenSpeed() {
        VideoFixtureServer().use { server ->
            val player = show(server).player
            chooseSpeed(1.5f)
            position(player, 500L, playing = true)
            controlsVisible(false)
            val events = observe(player)

            beginHold()
            awaitSpeed(player, 2f)
            compose.onNodeWithTag("video-gesture-fast").assertIsDisplayed()
            assertTrue(readMain { player.isPlaying })
            finishPointer()
            awaitSpeed(player, 1.5f)
            awaitTag("video-gesture-fast", visible = false)
            assertEquals(listOf(2f, 1.5f), events.speeds.toList())
            assertTrue("Releasing a hold must retain the original play intent", readMain { player.playWhenReady })
            assertTrue(events.playIntentChanges.isEmpty())

            position(player, 500L, playing = true)
            controlsVisible(false)
            events.clear()
            beginHold()
            awaitSpeed(player, 2f)
            finishPointer(cancelled = true)
            awaitSpeed(player, 1.5f)
            awaitTag("video-gesture-fast", visible = false)
            assertEquals(listOf(2f, 1.5f), events.speeds.toList())
            assertTrue(readMain { player.playWhenReady })
            assertTrue(events.playIntentChanges.isEmpty())

            position(player, 1_500L)
            controlsVisible(false)
            events.clear()
            beginHold()
            settleInput(holdDurationMs)
            assertFalse("Holding a paused video must never start audio or advance playback", readMain { player.playWhenReady || player.isPlaying })
            assertEquals(1_500L, readMain { player.currentPosition })
            assertEquals(1.5f, readMain { player.playbackParameters.speed }, .01f)
            assertTrue("A paused hold must not briefly enable temporary speed", events.speeds.isEmpty())
            compose.onNodeWithTag("video-gesture-fast").assertDoesNotExist()
            finishPointer()
            settleInput()
            assertTrue(events.playIntentChanges.isEmpty())
            assertFalse(readMain { player.playWhenReady })
        }
    }

    @Test fun backgroundingDuringAHoldRestoresSpeedAndReturningDoesNotResumePlayback() {
        VideoFixtureServer().use { server ->
            val player = show(server).player
            chooseSpeed(1.5f)
            position(player, 500L, playing = true)
            controlsVisible(false)
            beginHold()
            awaitSpeed(player, 2f)
            compose.onNodeWithTag("video-gesture-fast").assertIsDisplayed()

            try {
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                compose.waitUntil(5_000) { readMain {
                    !player.playWhenReady && !player.isPlaying && abs(player.playbackParameters.speed - 1.5f) < .01f
                } }
            } finally {
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            }
            awaitTag("video-tap-surface", visible = true)
            finishPointer(cancelled = true)
            val pausedAt = readMain { player.currentPosition }
            settleInput()
            assertFalse("Returning to the chat must not restart a held video", readMain { player.playWhenReady || player.isPlaying })
            assertEquals(pausedAt, readMain { player.currentPosition })
            assertEquals(1.5f, readMain { player.playbackParameters.speed }, .01f)
            compose.onNodeWithTag("video-gesture-fast").assertDoesNotExist()
        }
    }

    @Test fun horizontalDragPreviewsThenCommitsOnceAndCancellationDoesNotJump() {
        VideoFixtureServer().use { server ->
            val player = show(server).player
            position(player, 2_000L)
            controlsVisible(false)
            val duration = readMain { player.duration }
            assertTrue("The device-local fixture must contain enough room for both seek directions", duration >= 5_500L)
            val events = observe(player)
            val forwardTarget = 2_000L + (duration * .2f).toLong()

            beginSeek(.2f)
            compose.onNodeWithTag("video-gesture-seek").assertIsDisplayed()
            assertEquals("Moving a held pointer must only update the preview", 2_000L, readMain { player.currentPosition })
            assertTrue(events.seeks.isEmpty())
            finishPointer()
            awaitPosition(player, forwardTarget)
            assertEquals(1, events.seeks.size)
            assertFalse(readMain { player.playWhenReady })
            awaitTag("video-gesture-seek", visible = false)

            beginSeek(-.2f)
            compose.onNodeWithTag("video-gesture-seek").assertIsDisplayed()
            assertTrue(abs(forwardTarget - readMain { player.currentPosition }) <= 80L)
            assertEquals(1, events.seeks.size)
            finishPointer()
            awaitPosition(player, 2_000L)
            assertEquals(2, events.seeks.size)
            assertFalse(readMain { player.playWhenReady })

            val beforeCancel = readMain { player.currentPosition }
            beginSeek(.3f)
            compose.onNodeWithTag("video-gesture-seek").assertIsDisplayed()
            finishPointer(cancelled = true)
            settleInput()
            assertEquals("Cancellation must discard the preview without a seek", beforeCancel, readMain { player.currentPosition })
            assertEquals(2, events.seeks.size)
            assertFalse(readMain { player.playWhenReady })
            compose.onNodeWithTag("video-gesture-seek").assertDoesNotExist()

            position(player, 500L, playing = true)
            controlsVisible(false)
            events.clear()
            val playingAt = readMain { player.currentPosition }
            beginSeek(.15f)
            compose.onNodeWithTag("video-gesture-seek").assertIsDisplayed()
            assertTrue("A playing preview must also wait for release before seeking", events.seeks.isEmpty())
            finishPointer()
            compose.waitUntil(5_000) { events.seeks.size == 1 }
            val committed = events.seeks.single()
            assertTrue("A playing drag must commit the previewed forward offset", abs(playingAt + (duration * .15f).toLong() - committed) <= 350L)
            awaitPlayingPast(player, committed + 100L)
            assertTrue("Seeking a playing video must keep it playing", readMain { player.playWhenReady })
            assertTrue(events.playIntentChanges.isEmpty())
            compose.onNodeWithTag("video-fullscreen-player").assertDoesNotExist()
        }
    }

    @Test fun verticalChatScrollAndTwoFingerPinchDoNotSeekOrStartFastPlayback() {
        VideoFixtureServer().use { server ->
            val scroll = ScrollState(0)
            val fixture = show(server, scroll)
            val player = fixture.player
            position(player, 2_000L)
            controlsVisible(false)
            val events = observe(player)

            compose.onNodeWithTag("video-tap-surface").performTouchInput {
                val from = Offset(width * .5f, height * .75f)
                val to = Offset(width * .5f, height * .25f)
                down(0, from)
                repeat(8) { step -> moveTo(0, from + (to - from) * ((step + 1) / 8f), delayMillis = 35L) }
                move(delayMillis = 180L)
                up(0)
            }
            compose.waitUntil(5_000) { readMain { scroll.value > 10 } }
            settleInput()
            assertEquals("A vertical drag must scroll the surrounding chat instead of seeking", 2_000L, readMain { player.currentPosition })
            assertTrue(events.seeks.isEmpty())
            assertTrue(events.speeds.isEmpty())

            val beforeScale = pictureScale(fixture.view)
            compose.onNodeWithTag("video-tap-surface").performTouchInput {
                val focus = Offset(width * .5f, height * .5f)
                down(0, focus + Offset(-35f, 0f))
                down(1, focus + Offset(35f, 0f))
                repeat(10) { step ->
                    val halfSeparation = 35f + (step + 1) * 6f
                    updatePointerTo(0, focus + Offset(-halfSeparation, 0f))
                    updatePointerTo(1, focus + Offset(halfSeparation, 0f))
                    move(delayMillis = 30L)
                }
                up(1)
                up(0)
            }
            compose.waitUntil(5_000) { pictureScale(fixture.view) > beforeScale + .15f }
            settleInput()
            assertEquals("Pinch must transform the picture without changing its playback position", 2_000L, readMain { player.currentPosition })
            assertTrue(events.seeks.isEmpty())
            assertTrue(events.speeds.isEmpty())
            assertTrue(events.playIntentChanges.isEmpty())
            assertFalse(readMain { player.playWhenReady || player.isPlaying })
            compose.onNodeWithTag("video-gesture-seek").assertDoesNotExist()
            compose.onNodeWithTag("video-gesture-fast").assertDoesNotExist()
        }
    }

    private data class Fixture(val view: PlayerView, val player: ExoPlayer)

    private fun show(server: VideoFixtureServer, scroll: ScrollState? = null): Fixture {
        instrumentation.runOnMainSync {
            compose.activity.setContent {
                MaterialExpressiveTheme {
                    if (scroll == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(272.dp)) { Preview(server) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(180.dp))
                            Box(Modifier.width(272.dp)) { Preview(server) }
                            Spacer(Modifier.height(1_400.dp))
                        }
                    }
                }
            }
        }
        awaitTag("inline-video-preview", visible = true)
        val found = AtomicReference<PlayerView>()
        compose.waitUntil(15_000) { runCatching { focusedVideoTestPlayer() }.onSuccess(found::set).isSuccess }
        val view = found.get()
        val player = readMain { view.player as ExoPlayer }
        compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
        assertFalse("Inline videos must initially stay paused", readMain { player.playWhenReady })
        return Fixture(view, player)
    }

    @androidx.compose.runtime.Composable
    private fun Preview(server: VideoFixtureServer) {
        InlineVideoPreview(
            VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
            resolveVideo = { _, _ -> VideoPlaybackSource("${server.url}/clip.mp4", VideoPlaybackKind.DIRECT,
                title = "本地播放手势测试", mimeType = "video/mp4") },
        )
    }

    private fun position(player: ExoPlayer, target: Long, playing: Boolean = false) {
        instrumentation.runOnMainSync { player.pause(); player.seekTo(target) }
        awaitPosition(player, target)
        if (playing) {
            instrumentation.runOnMainSync { player.play() }
            compose.waitUntil(5_000) { readMain { player.isPlaying } }
        } else awaitPaused(player)
    }

    private fun awaitPosition(player: ExoPlayer, expected: Long) {
        compose.waitUntil(5_000) { readMain { player.playbackState == Player.STATE_READY && abs(player.currentPosition - expected) <= 80L } }
    }

    private fun awaitPaused(player: ExoPlayer) {
        compose.waitUntil(5_000) { readMain { !player.playWhenReady && !player.isPlaying } }
    }

    private fun awaitPlayingPast(player: ExoPlayer, position: Long) {
        compose.waitUntil(5_000) { readMain { player.isPlaying && player.currentPosition > position } }
    }

    private fun awaitSpeed(player: ExoPlayer, speed: Float) {
        compose.waitUntil(5_000) { readMain { abs(player.playbackParameters.speed - speed) < .01f } }
    }

    private fun chooseSpeed(speed: Float) {
        controlsVisible(true)
        touch("video-speed")
        compose.onNodeWithTag("video-speed-option-$speed").performScrollTo().performTouchInput { click() }
    }

    private fun controlsVisible(visible: Boolean) {
        val shown = compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty()
        if (shown != visible) {
            compose.onNodeWithTag("video-tap-surface").performTouchInput { click(Offset(width * .5f, height * .35f)) }
        }
        awaitTag("video-controls", visible)
    }

    private fun playButtonDescription(description: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("video-play") and hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("video-play").assertContentDescriptionEquals(description)
    }

    private fun touch(tag: String) = compose.onNodeWithTag(tag).performTouchInput { click() }

    private fun doubleTapSurface() {
        compose.onNodeWithTag("video-tap-surface").performTouchInput { doubleClick(Offset(width * .5f, height * .35f)) }
    }

    private fun beginHold() {
        compose.onNodeWithTag("video-tap-surface").performTouchInput {
            down(0, Offset(width * .5f, height * .35f))
            advanceEventTime(holdDurationMs)
            move()
        }
    }

    private fun beginSeek(fraction: Float) {
        compose.onNodeWithTag("video-tap-surface").performTouchInput {
            val start = Offset(width * .45f, height * .35f)
            down(0, start)
            repeat(8) { step -> moveTo(0, start + Offset(width * fraction * ((step + 1) / 8f), 0f), delayMillis = 25L) }
        }
        awaitTag("video-gesture-seek", visible = true)
    }

    private fun finishPointer(cancelled: Boolean = false) {
        compose.onNodeWithTag("video-tap-surface").performTouchInput { if (cancelled) cancel() else up(0) }
    }

    private fun awaitTag(tag: String, visible: Boolean) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == visible }
    }

    // Check both Compose time and wall time so a queued single-tap timeout cannot escape an assertion.
    private fun settleInput(durationMs: Long = 450L) {
        val after = SystemClock.uptimeMillis() + durationMs
        compose.mainClock.advanceTimeBy(durationMs)
        compose.waitUntil(durationMs + 2_000L) { SystemClock.uptimeMillis() >= after }
        compose.waitForIdle()
    }

    private fun pictureScale(view: PlayerView): Float = readMain {
        view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame).scaleX
    }

    private fun observe(player: ExoPlayer) = PlaybackEvents().also { events ->
        instrumentation.runOnMainSync { player.addListener(events) }
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
        fun clear() { playIntentChanges.clear(); seeks.clear(); speeds.clear() }
    }

    private fun <T> readMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
