package com.constx.onvyra.ui.media

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.tools.createVideoSearchTool
import com.constx.onvyra.data.ai.tools.findResolvedVideoPreview
import com.constx.onvyra.data.media.MovieWebCandidate
import com.constx.onvyra.data.media.MovieWebSearchFallback
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.data.media.VideoPreviewResolver
import com.constx.onvyra.data.media.parseVideoPreviewPayload
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled public-address HTML and original media, decoded by real Media3 on Android. */
@RunWith(AndroidJUnit4::class)
class MovieWebFallbackPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun exhaustedCatalogsContinueThroughCheckedHtmlAndPreviewCacheToActualNativePlayback() = runBlocking {
        val pageUrl = "https://movie.example.test/watch/film.html"
        val mediaUrl = "https://media.example.test/test.mp4"
        val requests = CopyOnWriteArrayList<String>()
        val stages = CopyOnWriteArrayList<String>()
        val mediaProbes = AtomicInteger()
        val video = instrumentation.context.assets.open("inline-video-test.mp4").use { it.readBytes() }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url.host}${request.url.encodedPath}"
            when (request.url.host) {
                "movie.example.test" -> response(request, """<!doctype html><html><head>
                    <title>测试电影 2026</title><meta name="description" content="上映年份：2026"></head>
                    <body><h1>测试电影</h1><video src="$mediaUrl" controls></video></body></html>""".toByteArray(), "text/html; charset=utf-8")
                "media.example.test" -> {
                    if (request.header("Range") == "bytes=0-0") mediaProbes.incrementAndGet()
                    val from = request.header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                        ?.coerceIn(0, video.lastIndex)
                    val bytes = if (request.method == "HEAD") byteArrayOf() else video.copyOfRange(from ?: 0, video.size)
                    response(request, bytes, "video/mp4", if (from == null) 200 else 206).newBuilder().apply {
                        header("Accept-Ranges", "bytes")
                        if (request.method == "HEAD") header("Content-Length", video.size.toString())
                        if (from != null) header("Content-Range", "bytes $from-${video.lastIndex}/${video.size}")
                    }.build()
                }
                else -> throw IOException("Unexpected fixture host")
            }
        }.build()
        val resolver = VideoPreviewResolver(instrumentation.targetContext, http)
        val fallback = MovieWebSearchFallback(
            searchWeb = { stages += "web-search"; listOf(MovieWebCandidate("测试电影 2026", pageUrl)) },
            readPage = resolver::inspectPage,
            resolveVideo = { resolver.resolve(VideoLink(it, VideoPlatform.OTHER), enhanced = true) },
        )
        val tool = createVideoSearchTool(
            discoverAndPlay = { _, _, _ -> stages += "discovery"; throw IOException("No compatible discovered source") },
            webFallback = fallback::search,
        ) { _, _, _ -> stages += "catalogs"; throw IOException("Catalog sources unavailable") }
        val text = (tool.execute(buildJsonObject { put("title", "测试电影"); put("year", 2026) })
            .single() as UIMessagePart.Text).text
        val output = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("catalogs", "discovery", "web-search"), stages.toList())
        assertEquals("ready", output["status"]?.jsonPrimitive?.content)
        assertEquals("web_search", output["discovery"]?.jsonPrimitive?.content)
        assertEquals("false", output["duration_known"]?.jsonPrimitive?.content)
        assertEquals("false", output["full_length_verified"]?.jsonPrimitive?.content)
        assertFalse(output.containsKey("catalog_source_id"))
        assertFalse("Model-facing output exposed the resolved media URL", text.contains(mediaUrl))
        val preview = requireNotNull(parseVideoPreviewPayload(text))
        assertEquals(pageUrl, preview.originalUrl)
        val cached = requireNotNull(findResolvedVideoPreview(preview.playbackId))
        assertEquals(mediaUrl, cached.url)
        assertEquals("测试电影 2026", cached.title)
        assertTrue(requests.contains("GET movie.example.test/watch/film.html"))
        assertTrue("The actual media tag must be verified before returning ready", mediaProbes.get() > 0)
        val pageReads = requests.count { it == "GET movie.example.test/watch/film.html" }

        // Consume the same preview cache entry as the chat adapter, injecting only this
        // test's transport rather than changing the application's global Koin network.
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.width(272.dp)) {
                    InlineVideoPreview(VideoLink(preview.originalUrl, VideoPlatform.OTHER),
                        resolveVideo = { _, _ -> requireNotNull(findResolvedVideoPreview(preview.playbackId)) },
                        playbackHttpClient = http)
                }
            }
        }
        val found = AtomicReference<ExoPlayer>()
        compose.waitUntil(15_000) { runCatching { currentPlayer() }.onSuccess(found::set).isSuccess }
        val player = found.get()
        compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
        assertEquals(320, readMain { player.videoSize.width })
        assertEquals(180, readMain { player.videoSize.height })
        assertFalse(readMain { player.playWhenReady })
        instrumentation.runOnMainSync { player.play() }
        compose.waitUntil(10_000) { readMain { player.isPlaying && player.currentPosition > 250 } }
        instrumentation.runOnMainSync { player.pause() }
        assertNull(readMain { player.playerError })
        assertTrue(requests.any { it == "GET media.example.test/test.mp4" })
        assertEquals("Cache playback must not repeat webpage extraction", pageReads,
            requests.count { it == "GET movie.example.test/watch/film.html" })
        println("VIDEO_WEB_FALLBACK=decoded-after-catalog-and-discovery-failure; STAGES=$stages; POSITION=${readMain { player.currentPosition }}; SIZE=${readMain { player.videoSize.width }}x${readMain { player.videoSize.height }}; FULL_LENGTH_VERIFIED=false")
    }

    @Test fun failedWebpageWithoutCatalogNotifiesAiRecoveryOnceAcrossTapsAndRecomposition() {
        val notified = AtomicInteger()
        val width = mutableIntStateOf(272)
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.width(width.intValue.dp)) {
                    InlineVideoPreview(VideoLink("https://movie.example.test/watch/failed.html", VideoPlatform.OTHER),
                        resolveVideo = { _, _ -> throw IOException("The webpage source failed") },
                        onSourcesExhausted = { notified.incrementAndGet() })
                }
            }
        }
        compose.waitUntil(10_000) { notified.get() == 1 }
        compose.onNodeWithTag("video-retry").assertIsDisplayed()
        repeat(3) {
            compose.onNodeWithTag("video-tap-surface").performClick()
            compose.runOnIdle { width.intValue = if (width.intValue == 272) 273 else 272 }
            compose.waitForIdle()
        }
        assertEquals("One failed player must not repeatedly request AI recovery", 1, notified.get())
        println("VIDEO_WEB_FALLBACK=failed-webpage-recovery-notified-once; NOTIFICATIONS=${notified.get()}")
    }

    private fun response(request: Request, body: ByteArray, type: String, status: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .body(body.toResponseBody(type.toMediaType())).build()

    private fun currentPlayer(): ExoPlayer {
        val result = AtomicReference<ExoPlayer>()
        onView(isAssignableFrom(PlayerView::class.java)).inRoot(object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) { description.appendText("the focused video window") }
            override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
        }).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
            override fun getDescription() = "inspect native player after automatic webpage fallback"
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
