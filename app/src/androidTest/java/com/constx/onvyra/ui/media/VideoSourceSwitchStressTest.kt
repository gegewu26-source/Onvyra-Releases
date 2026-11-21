package com.constx.onvyra.ui.media

import android.view.View
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol

/** Exercise real player reparenting and source-selection cancellation under repeated use. */
@RunWith(AndroidJUnit4::class)
@Suppress("UnsafeOptInUsageError")
class VideoSourceSwitchStressTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun twelveDecodedSourceChangesRetainThePlayerPositionSpeedAndFullscreen() {
        VideoFixtureServer().use { server ->
            val selections = CopyOnWriteArrayList<String>()
            val fixtureNetwork = FixturePlaybackNetwork()
            val diagnostic = StressDiagnostic("twelve-switches", selections, fixtureNetwork)
            diagnostic.run {
                diagnostic.phase("initial-content")
                compose.setContent {
                    MaterialExpressiveTheme {
                        Box(Modifier.width(272.dp)) {
                            InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                                playbackHttpClient = fixtureNetwork.client,
                                resolveVideo = { _, _ -> diagnostic.event("initial-resolve"); source(server, "clip.mp4") },
                                loadSourceOptions = { diagnostic.event("load-options"); listOf(VideoSourceOption("a", "本地线路 A", true),
                                    VideoSourceOption("b", "本地线路 B")) },
                                resolveSourceOption = { id -> diagnostic.event("resolve-option=$id"); source(server, if (id == "b") "clip-alt.mp4" else "clip.mp4") },
                                onSourceSelected = { selections += it; diagnostic.event("selected=$it count=${selections.size}") },
                            )
                        }
                    }
                }
                val originalView = awaitNativeView()
                val player = readMain { originalView.player as ExoPlayer }
                diagnostic.observeOriginal(originalView, player)
                diagnostic.phase("initial-ready")
                awaitReady(player)
                diagnostic.checkpoint()
                diagnostic.phase("seek-1500")
                instrumentation.runOnMainSync { player.seekTo(1_500) }
                compose.waitUntil(5_000) { readMain { player.currentPosition >= 1_400 } }
                revealControls()
                diagnostic.phase("select-speed")
                compose.onNodeWithTag("video-speed").performClick()
                compose.onNodeWithTag("video-speed-option-1.5").performScrollTo().performClick()
                diagnostic.phase("select-quality")
                compose.onNodeWithTag("video-quality").performClick()
                val quality = SemanticsMatcher("an actual supported video track") {
                    it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) &&
                        it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("video-quality-option-")
                }
                compose.onNode(quality).performScrollTo().performClick()
                diagnostic.phase("enter-fullscreen")
                compose.onNodeWithTag("video-fullscreen").performClick()
                awaitLandscapeFullscreen()
                diagnostic.checkpoint()
                for (index in 0 until 12) {
                    val id = if (index % 2 == 0) "b" else "a"
                    val expected = "${server.url}/${if (id == "b") "clip-alt.mp4" else "clip.mp4"}"
                    diagnostic.switchIndex = index
                    diagnostic.expectedUri = expected
                    diagnostic.phase("choose-source-$id")
                    diagnostic.checkpoint()
                    val before = readMain { player.currentPosition }
                    chooseSource(id)
                    diagnostic.phase("await-source-$id-ready")
                    compose.waitUntil(10_000) { selections.size == index + 1 && readMain {
                        player.currentMediaItem?.localConfiguration?.uri.toString() == expected &&
                            player.playbackState == Player.STATE_READY
                    } }
                    diagnostic.phase("assert-source-$id-state")
                    diagnostic.checkpoint()
                    val current = currentNativeView()
                    assertSame("Repeated source changes created a new view", originalView, current)
                    assertSame("Repeated source changes created a new player", player, readMain { current.player })
                    assertTrue("Source change $index reset playback position", readMain { player.currentPosition >= before - 100 })
                    assertEquals(1.5f, readMain { player.playbackParameters.speed }, .01f)
                    assertFalse("Source change $index started paused playback", readMain { player.playWhenReady })
                    assertNull(readMain { player.playerError })
                    compose.onNodeWithTag("video-fullscreen-player").assertIsDisplayed()
                    if (index == 5) {
                        diagnostic.phase("middle-exit-fullscreen")
                        revealControls()
                        compose.onNodeWithTag("video-exit-fullscreen").performClick()
                        compose.waitUntil(15_000) {
                            compose.onAllNodesWithTag("video-fullscreen-player").fetchSemanticsNodes().isEmpty()
                        }
                        revealControls()
                        diagnostic.phase("middle-enter-fullscreen")
                        compose.onNodeWithTag("video-fullscreen").performClick()
                        awaitLandscapeFullscreen()
                        diagnostic.checkpoint()
                    }
                }
                diagnostic.phase("assert-real-playback-progress")
                val lastPosition = readMain { player.currentPosition }
                instrumentation.runOnMainSync { player.play() }
                compose.waitUntil(10_000) { readMain { player.isPlaying && player.currentPosition > lastPosition + 300 } }
                instrumentation.runOnMainSync { player.pause() }
                revealControls()
                diagnostic.phase("final-exit-fullscreen")
                compose.onNodeWithTag("video-exit-fullscreen").performClick()
                assertEquals(12, selections.size)
                println("VIDEO_SOURCE_STRESS=12-decoded-switches; SAME_PLAYER_AND_VIEW=true; SPEED=1.5; POSITION=${readMain { player.currentPosition }}")
            }
        }
    }

    @Test fun aSupersededOrDisposedSlowResolutionCannotReplaceOrResurrectTheNativePlayer() {
        VideoFixtureServer().use { server ->
            val shown = mutableStateOf(true)
            val started = AtomicInteger()
            val completed = AtomicInteger()
            val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
            val selections = CopyOnWriteArrayList<String>()
            val fixtureNetwork = FixturePlaybackNetwork()
            val diagnostic = StressDiagnostic("late-resolutions", selections, fixtureNetwork)
            try {
                diagnostic.run {
                    diagnostic.phase("initial-content")
                    compose.setContent {
                        MaterialExpressiveTheme {
                            Box(Modifier.width(272.dp)) {
                                if (shown.value) InlineVideoPreview(VideoLink("${server.url}/clip.mp4", VideoPlatform.DIRECT),
                                    playbackHttpClient = fixtureNetwork.client,
                                    resolveVideo = { _, _ -> diagnostic.event("initial-resolve"); source(server, "clip.mp4") },
                                    loadSourceOptions = { diagnostic.event("load-options"); listOf(VideoSourceOption("initial", "初始来源", true),
                                        VideoSourceOption("slow", "较慢来源"), VideoSourceOption("fast", "新选择来源")) },
                                    resolveSourceOption = { id ->
                                        diagnostic.event("resolve-option=$id started=${started.get()} completed=${completed.get()}")
                                        if (id == "slow") {
                                            val index = started.getAndIncrement()
                                            // Simulate a backend which finishes despite cancellation. The UI must
                                            // reject its late answer both after a new choice and after disposal.
                                            withContext(NonCancellable) {
                                                gates[index].await()
                                                completed.incrementAndGet()
                                                diagnostic.event("late-complete=$index")
                                                source(server, "clip.mp4?late=$index")
                                            }
                                        } else source(server, "clip-alt.mp4?selected=fast")
                                    },
                                    onSourceSelected = { selections += it; diagnostic.event("selected=$it count=${selections.size}") },
                                )
                            }
                        }
                    }
                    val originalView = awaitNativeView()
                    val player = readMain { originalView.player as ExoPlayer }
                    diagnostic.observeOriginal(originalView, player)
                    diagnostic.phase("initial-ready")
                    awaitReady(player)
                    diagnostic.phase("choose-first-slow")
                    chooseSource("slow")
                    compose.waitUntil(5_000) { started.get() == 1 }
                    diagnostic.phase("supersede-with-fast")
                    chooseSource("fast")
                    compose.waitUntil(10_000) { selections == listOf("fast") && readMain {
                        player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4?selected=fast") &&
                            player.playbackState == Player.STATE_READY
                    } }
                    diagnostic.checkpoint()
                    diagnostic.phase("release-first-slow")
                    gates[0].complete(Unit)
                    compose.waitUntil(5_000) { completed.get() == 1 }
                    instrumentation.waitForIdleSync()
                    compose.waitForIdle()
                    assertEquals(listOf("fast"), selections.toList())
                    assertSame(player, readMain { originalView.player })
                    assertTrue(readMain { player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4?selected=fast") })

                    diagnostic.phase("choose-second-slow")
                    chooseSource("slow")
                    diagnostic.phase("dispose-second-slow")
                    compose.waitUntil(5_000) { started.get() == 2 }
                    compose.runOnIdle { shown.value = false }
                    compose.waitUntil(5_000) { readMain { originalView.player == null && originalView.parent == null } }
                    gates[1].complete(Unit)
                    diagnostic.phase("assert-disposed-late-answer")
                    compose.waitUntil(5_000) { completed.get() == 2 }
                    instrumentation.waitForIdleSync()
                    compose.waitForIdle()
                    assertNull("A late answer reattached the released player", readMain { originalView.player })
                    assertNull(readMain { originalView.parent })
                    assertEquals("A stale selection callback escaped cancellation", listOf("fast"), selections.toList())
                    compose.onNodeWithTag("inline-video-preview").assertDoesNotExist()
                    compose.runOnIdle { shown.value = true }
                    diagnostic.phase("recreate-after-disposal")
                    val replacement = awaitNativeView()
                    assertNotSame(originalView, replacement)
                    val replacementPlayer = readMain { replacement.player as ExoPlayer }
                    diagnostic.observe(replacementPlayer)
                    awaitReady(replacementPlayer)
                    diagnostic.checkpoint()
                    assertNull("A new message revived the previous player", readMain { originalView.player })
                    println("VIDEO_SOURCE_STRESS=late-resolutions-rejected; BACKEND_COMPLETIONS=${completed.get()}; CALLBACKS=$selections; RELEASED_VIEW_REATTACHED=false")
                }
            } finally {
                gates.forEach { it.complete(Unit) }
            }
        }
    }

    private fun chooseSource(id: String) {
        revealControls()
        compose.onNodeWithTag("video-source").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("video-source-option-$id").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("video-source-option-$id").performScrollTo().performClick()
    }

    private fun revealControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("video-tap-surface").performClick()
        }
    }

    private fun awaitLandscapeFullscreen() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("video-fullscreen-player").fetchSemanticsNodes().singleOrNull()
                ?.boundsInWindow?.let { it.width > it.height } == true
        }
        compose.waitForIdle()
    }

    private fun awaitReady(player: ExoPlayer) {
        compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
    }

    private fun awaitNativeView(): PlayerView {
        val found = AtomicReference<PlayerView>()
        compose.waitUntil(15_000) { runCatching { currentNativeView() }.onSuccess(found::set).isSuccess }
        return requireNotNull(found.get())
    }

    private fun currentNativeView(): PlayerView {
        val found = AtomicReference<PlayerView>()
        onView(isAssignableFrom(PlayerView::class.java)).inRoot(object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) { description.appendText("the focused playback window") }
            override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
        }).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
            override fun getDescription() = "inspect the native view during repeated source changes"
            override fun perform(uiController: UiController, view: View) { found.set(view as PlayerView) }
        })
        return requireNotNull(found.get())
    }

    private fun source(server: VideoFixtureServer, file: String) = VideoPlaybackSource(
        "${server.url}/$file", VideoPlaybackKind.DIRECT, title = "原创切源压力测试", mimeType = "video/mp4",
    )

    /** An explicit, stable client for device-local media: system proxy settings cannot reroute it. */
    private class FixturePlaybackNetwork {
        private val startedAt = SystemClock.elapsedRealtime()
        private val history = CopyOnWriteArrayList<String>()
        private fun record(value: String) { history += "${SystemClock.elapsedRealtime() - startedAt}ms $value" }

        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .eventListener(object : EventListener() {
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    record("connect ${call.request().url.encodedPath} target=$inetSocketAddress proxy=$proxy")
                }

                override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
                                           protocol: Protocol?, ioe: IOException) {
                    record("connect-failed ${call.request().url.encodedPath} target=$inetSocketAddress proxy=$proxy ${errorDetails(ioe)}")
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    record("call-failed ${call.request().url.encodedPath} ${errorDetails(ioe)}")
                }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                record("request ${request.method} ${request.url} range=${request.header("Range")}")
                chain.proceed(request).also { record("response ${request.url.encodedPath} status=${it.code} range=${it.header("Content-Range")}") }
            }.build()

        fun snapshot(): String = "explicitProxy=${client.proxy}; running=${client.dispatcher.runningCallsCount()}; " +
            "queued=${client.dispatcher.queuedCallsCount()}; events=\n${history.takeLast(40).joinToString("\n")}"
    }

    private inner class StressDiagnostic(
        private val testName: String,
        private val selections: CopyOnWriteArrayList<String>,
        private val network: FixturePlaybackNetwork,
    ) {
        private val startedAt = SystemClock.elapsedRealtime()
        private val history = CopyOnWriteArrayList<String>()
        private val listeners = CopyOnWriteArrayList<Pair<ExoPlayer, Player.Listener>>()
        private var originalView: PlayerView? = null
        private var originalPlayer: ExoPlayer? = null
        @Volatile private var currentPhase = "not-started"
        @Volatile var switchIndex = -1
        @Volatile var expectedUri = ""

        fun phase(value: String) { currentPhase = value; event("phase=$value index=$switchIndex") }
        fun event(value: String) { history += "${SystemClock.elapsedRealtime() - startedAt}ms $value" }

        fun observeOriginal(view: PlayerView, player: ExoPlayer) {
            originalView = view
            originalPlayer = player
            observe(player)
        }

        fun observe(player: ExoPlayer) = instrumentation.runOnMainSync {
            if (listeners.any { it.first === player }) return@runOnMainSync
            val listener = object : Player.Listener {
                override fun onEvents(changed: Player, events: Player.Events) {
                    event("player-events=${events.size()} ${playerSnapshot(player)}")
                }

                override fun onRenderedFirstFrame() {
                    event("first-frame ${playerSnapshot(player)}")
                }

                override fun onPlayerError(error: PlaybackException) {
                    event("player-error code=${error.errorCodeName} ${errorDetails(error)} ${playerSnapshot(player)}")
                }
            }
            player.addListener(listener)
            listeners += player to listener
            event("observe ${playerSnapshot(player)}")
        }

        fun checkpoint() {
            val value = snapshot()
            event("checkpoint $value")
            println("VIDEO_SOURCE_STRESS_CHECKPOINT $value")
        }

        fun <T> run(block: () -> T): T = try {
            println("VIDEO_SOURCE_STRESS_FIXTURE test=$testName explicitProxy=${network.client.proxy}")
            block()
        }
        catch (failure: Throwable) {
            val details = runCatching {
                "${snapshot()}\nPLAYER_EVENTS:\n${history.takeLast(80).joinToString("\n")}\nNETWORK:\n${network.snapshot()}\n" +
                    "SEMANTICS:\n${runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 8) }.getOrElse { errorDetails(it) }}"
            }.getOrElse { "diagnostic-failed=${errorDetails(it)}; phase=$currentPhase; index=$switchIndex; selections=$selections" }
            println("VIDEO_SOURCE_STRESS_FAILURE $details")
            Log.e("VideoSourceStress", details, failure)
            throw AssertionError("$testName failed: $details", failure)
        } finally {
            runCatching { instrumentation.runOnMainSync {
                listeners.forEach { (player, listener) -> runCatching { player.removeListener(listener) } }
            } }
        }

        private fun snapshot(): String {
            val current = runCatching { currentNativeView() }
            val state = readMain {
                "originalView=${viewSnapshot(originalView)}; originalPlayer=${playerSnapshot(originalPlayer)}; " +
                    "currentView=${viewSnapshot(current.getOrNull())}; currentPlayer=${playerSnapshot(current.getOrNull()?.player as? ExoPlayer)}"
            }
            val fullscreen = runCatching { compose.onAllNodesWithTag("video-fullscreen-player").fetchSemanticsNodes()
                .map { it.boundsInWindow }.toString() }.getOrElse { errorDetails(it) }
            return "test=$testName phase=$currentPhase index=$switchIndex expected=$expectedUri selections=${selections.toList()}; " +
                "$state; focusedViewLookup=${current.exceptionOrNull()?.let(::errorDetails) ?: "ok"}; fullscreen=$fullscreen"
        }

        private fun viewSnapshot(view: PlayerView?): String = view?.let {
            "${identity(it)}(player=${identity(it.player)}, attached=${it.isAttachedToWindow}, parent=${identity(it.parent)}, " +
                "focused=${it.hasWindowFocus()}, size=${it.width}x${it.height}, orientation=${it.resources.configuration.orientation})"
        } ?: "null"

        /** Called on the player's application thread, including after disposal. */
        private fun playerSnapshot(player: ExoPlayer?): String = player?.let { native -> runCatching {
            val counters = native.videoDecoderCounters
            "${identity(native)}(released=${native.isReleased}, uri=${native.currentMediaItem?.localConfiguration?.uri}, " +
                "state=${stateName(native.playbackState)}, position=${native.currentPosition}, buffered=${native.bufferedPosition}, " +
                "duration=${native.duration}, playWhenReady=${native.playWhenReady}, playing=${native.isPlaying}, " +
                "speed=${native.playbackParameters.speed}, video=${native.videoSize.width}x${native.videoSize.height}, " +
                "rendered=${counters?.renderedOutputBufferCount}, decoderInit=${counters?.decoderInitCount}, decoderRelease=${counters?.decoderReleaseCount}, " +
                "error=${native.playerError?.errorCodeName}:${errorDetails(native.playerError)})"
        }.getOrElse { "${identity(native)}(snapshot-error=${errorDetails(it)})" } } ?: "null"
    }

    private companion object {
        fun identity(value: Any?): String = value?.let { "${it.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(it))}" } ?: "null"
        fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }

        fun errorDetails(error: Throwable?): String = if (error == null) "none" else
            generateSequence(error) { it.cause }.take(4).joinToString(" <- ") {
                "${it.javaClass.simpleName}:${it.message.orEmpty().replace('\n', ' ').take(240)}"
            }
    }

    private fun <T> readMain(block: () -> T): T {
        val found = AtomicReference<T>()
        instrumentation.runOnMainSync { found.set(block()) }
        return found.get()
    }
}

