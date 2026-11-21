package com.constx.onvyra.ui.media

import android.view.View
import android.os.SystemClock
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.tools.createVideoSearchTool
import com.constx.onvyra.data.media.MovieCatalogClient
import com.constx.onvyra.data.media.MoviePlaybackResolver
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPreviewResolver
import com.constx.onvyra.data.media.parseVideoLink
import com.constx.onvyra.data.media.parseVideoPreviewPayload
import com.constx.onvyra.ui.components.message.ResolvedVideoPreview
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: probes live catalog metadata and starts only a brief playback sample. */
@RunWith(AndroidJUnit4::class)
class MovieCatalogLiveTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun requestedMovieIsSearchedResolvedAndPlayedInChat() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val title = arguments.getString("movieTitle")
        assumeTrue("Pass movieTitle to opt into the live-source check", !title.isNullOrBlank())
        val year = arguments.getString("movieYear")?.toIntOrNull()
        val networkEvents = CopyOnWriteArrayList<String>()
        val nextCallId = AtomicInteger()
        val client = OkHttpClient.Builder().apply {
            if (arguments.getString("movieNetworkDiagnostics") == "true") eventListenerFactory {
                object : EventListener() {
                    private val id = nextCallId.incrementAndGet()
                    private val started = SystemClock.elapsedRealtime()
                    private val host = it.request().url.host
                    private val phase = if (it.request().url.queryParameter("ac") == "detail") "detail" else "lookup"
                    private fun record(event: String) {
                        val line = "MOVIE_NETWORK call=$id host=$host phase=$phase ms=${SystemClock.elapsedRealtime() - started} $event"
                        networkEvents += line
                        println(line)
                    }
                    override fun callStart(call: Call) = record("start")
                    override fun dnsStart(call: Call, domainName: String) = record("dns_start")
                    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = record("dns_end addresses=${inetAddressList.size}")
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = record("connect_start proxy=${proxy.type()}")
                    override fun secureConnectStart(call: Call) = record("tls_start")
                    override fun secureConnectEnd(call: Call, handshake: Handshake?) = record("tls_end")
                    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = record("connect_end protocol=$protocol")
                    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = record("connect_failed error=${ioe.javaClass.simpleName}")
                    override fun responseHeadersEnd(call: Call, response: Response) = record("headers status=${response.code}")
                    override fun responseBodyEnd(call: Call, byteCount: Long) = record("body_end bytes=$byteCount")
                    override fun callEnd(call: Call) = record("end")
                    override fun callFailed(call: Call, ioe: IOException) = record("failed error=${ioe.javaClass.simpleName} cancelled=${call.isCanceled()}")
                }
            }
        }.build()
        val previewResolver = VideoPreviewResolver(instrumentation.targetContext, client)
        val attempts = mutableListOf<String>()
        val movieResolver = MoviePlaybackResolver(MovieCatalogClient(client)) { url ->
            try {
                previewResolver.resolve(parseVideoLink(url) ?: VideoLink(url, VideoPlatform.OTHER), enhanced = true)
            } catch (error: Exception) {
                attempts.add("${url.toHttpUrlOrNull()?.host}: ${error.javaClass.simpleName} ${error.message?.take(120)}")
                throw error
            }
        }
        val tool = createVideoSearchTool { name, releaseYear, episode -> movieResolver.searchAndResolve(name, releaseYear, episode) }
        val resultText = (tool.execute(buildJsonObject {
            put("title", title!!); year?.let { put("year", it) }
        }).single() as UIMessagePart.Text).text
        val payload = parseVideoPreviewPayload(resultText)
        assertNotNull("Movie lookup/verification failed: $resultText; attempts=$attempts; network=$networkEvents", payload)
        println("MOVIE_METADATA title=${payload!!.title} source=${payload.catalogSourceId} item=${payload.catalogItemId}")
        compose.setContent { MaterialExpressiveTheme { ResolvedVideoPreview(payload) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("inline-video-preview").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        val native = AtomicReference<ExoPlayer>()
        onView(isAssignableFrom(PlayerView::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
            override fun getDescription() = "inspect actual catalog playback"
            override fun perform(uiController: UiController, view: View) { native.set((view as PlayerView).player as ExoPlayer) }
        })
        val player = native.get()
        compose.waitUntil(25_000) { readMain { player.playbackState == Player.STATE_READY || player.playerError != null } }
        assertNull("Media source failed after metadata verification", readMain { player.playerError })
        val duration = readMain { player.duration }
        println("MOVIE_DURATION_MS=$duration")
        println("MOVIE_VIDEO_SIZE=${readMain { player.videoFormat?.width }}x${readMain { player.videoFormat?.height }}")
        val minimumDuration = arguments.getString("minMovieDurationSeconds")?.toLongOrNull()
        if (minimumDuration != null) assertTrue("A short clip was substituted for the movie", duration >= minimumDuration * 1_000)
        instrumentation.runOnMainSync { player.play() }
        compose.waitUntil(20_000) { readMain { player.isPlaying && player.currentPosition > 1_000 } }
        instrumentation.runOnMainSync { player.pause() }
        // The same movie player must survive fullscreen without re-querying catalogs.
        revealControls()
        compose.onNodeWithTag("video-fullscreen").performClick()
        compose.onNodeWithTag("video-exit-fullscreen").performClick()
        assertTrue(readMain { player.currentPosition > 1_000 })
        if (arguments.getString("verifySourceSwitch") == "true") {
            val originalUri = readMain { player.currentMediaItem?.localConfiguration?.uri }
            val originalSource = payload.title.orEmpty().substringAfterLast(" · ", "")
            val originalLine = payload.catalogLine
            val attempted = mutableSetOf<String>()
            val menuStarted = SystemClock.elapsedRealtime()
            var firstResultMs: Long? = null
            var sourceCount = 0
            data class Choice(val tag: String, val label: String, val selected: Boolean)
            val choiceMatcher = SemanticsMatcher("a movie source option") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("video-source-option-") == true
            }
            fun choices(): List<Choice> {
                val found = compose.onAllNodes(choiceMatcher).fetchSemanticsNodes().map { node ->
                    val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                    Choice(node.config[SemanticsProperties.TestTag], text.joinToString(" "),
                        node.config.getOrNull(SemanticsProperties.Selected) ?: text.any { it == "✓" })
                }
                if (found.isNotEmpty() && firstResultMs == null) firstResultMs = SystemClock.elapsedRealtime() - menuStarted
                sourceCount = maxOf(sourceCount, found.size)
                return found
            }
            var switched = false
            // A live catalog can contain expired or duplicate renditions. Try a few real,
            // unselected choices instead of requiring a particular metadata quality label.
            for (attempt in 1..3) {
                revealControls()
                compose.onNodeWithTag("video-source").performClick()
                try {
                    compose.waitUntil(55_000) { choices().any { !it.selected && it.tag !in attempted } }
                } catch (_: ComposeTimeoutException) { break }
                val next = choices().filter { !it.selected && it.tag !in attempted }.maxByOrNull { choice ->
                    val parts = choice.label.split(" · ")
                    (if (originalSource.isNotBlank() && parts.firstOrNull() != originalSource) 2 else 0) +
                        (if (originalLine != null && parts.getOrNull(2) != originalLine) 1 else 0)
                } ?: break
                attempted += next.tag
                println("MOVIE_SOURCE_SWITCH_ATTEMPT=$attempt; SOURCE_OPTIONS_FIRST_MS=$firstResultMs; SOURCE_OPTIONS_COUNT=$sourceCount; CHOICE=${next.label}")
                compose.onNodeWithTag(next.tag).performScrollTo().performClick()
                try {
                    compose.waitUntil(45_000) {
                        compose.onAllNodesWithTag("video-source-error").fetchSemanticsNodes().isNotEmpty() || readMain {
                            player.currentMediaItem?.localConfiguration?.uri != originalUri &&
                                (player.playbackState == Player.STATE_READY || player.playerError != null)
                        }
                    }
                } catch (_: ComposeTimeoutException) { continue }
                switched = readMain {
                    player.currentMediaItem?.localConfiguration?.uri != originalUri &&
                        player.playbackState == Player.STATE_READY && player.playerError == null
                }
                if (switched) break
            }
            assertTrue("No unselected catalog choice became a different playable stream; attempted=${attempted.size}, options=$sourceCount", switched)
            assertNull(readMain { player.playerError })
            println("MOVIE_SWITCHED_VIDEO_SIZE=${readMain { player.videoFormat?.width }}x${readMain { player.videoFormat?.height }}")
            instrumentation.runOnMainSync { player.play() }
            val before = readMain { player.currentPosition }
            compose.waitUntil(20_000) { readMain { player.isPlaying && player.currentPosition > before + 500 } }
            instrumentation.runOnMainSync { player.pause() }
        }
    }

    private fun revealControls() {
        if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("video-tap-surface").performClick()
        }
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun <T> readMain(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }
}
