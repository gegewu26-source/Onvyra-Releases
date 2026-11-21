package com.constx.onvyra.ui.media

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.constx.onvyra.data.media.MovieCatalogClient
import com.constx.onvyra.data.media.MovieCatalogSource
import com.constx.onvyra.data.media.MovieCatalogSourceDiscovery
import com.constx.onvyra.data.media.MovieCatalogSourceRegistry
import com.constx.onvyra.data.media.MovieDiscoverySeed
import com.constx.onvyra.data.media.MoviePlaybackResolver
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoLinkResolver
import com.constx.onvyra.data.media.VideoPlatform
import com.constx.onvyra.ui.components.message.InlineVideoPreview
import com.constx.onvyra.ui.components.message.VideoSourceOption
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

/** Configuration discovery must produce a matching movie and a decoded frame, not just HTTP 200. */
@RunWith(AndroidJUnit4::class)
class MovieDiscoveredPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun newlyDiscoveredMatchingCatalogAppearsInTheExistingMenuAndActuallyPlays() = runBlocking {
        val directory = File.createTempFile("discovered-playback-", "", instrumentation.targetContext.cacheDir)
            .apply { delete(); mkdirs() }
        try {
            VideoFixtureServer().use { server ->
                val registry = MovieCatalogSourceRegistry(directory)
                val requestedPaths = mutableListOf<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    when (request.url.host) {
                        "config.example.test" -> response(request, """{"sites":[
                            {"name":"新增备用源","type":1,"api":"https://new.example.test/api.php/provide/vod/"},
                            {"name":"无匹配影片源","type":1,"api":"https://empty.example.test/api.php/provide/vod/"},
                            {"name":"不可执行插件","type":3,"api":"csp_Provider","ext":"https://plugin.example.test/remote.jar"}
                        ]}""")
                        "base.example.test", "new.example.test", "empty.example.test" -> {
                            synchronized(requestedPaths) { requestedPaths += request.url.toString() }
                            response(request, if (request.url.host == "empty.example.test") """{"list":[]}"""
                                else catalog(if (request.url.host == "new.example.test") "${server.url}/clip-alt.mp4" else "${server.url}/clip.mp4"))
                        }
                        else -> chain.proceed(request)
                    }
                }.build()
                // Construct the client/resolver before discovery to prove the dynamic supplier
                // and existing menu cache notice new sources without recreating the player.
                val catalog = MovieCatalogClient(client,
                    listOf(MovieCatalogSource("base", "初始来源", "https://base.example.test/api.php/provide/vod/")),
                    extraSources = { registry.all() })
                val direct = VideoLinkResolver(client)
                val resolver = MoviePlaybackResolver(catalog) { address ->
                    direct.verifyDirect(VideoLink(address, VideoPlatform.DIRECT))
                }
                val initial = resolver.searchAndResolve("原创测试影片", 2026)
                assertEquals("base", initial.item.sourceId)
                val optionsBefore = resolver.listOptions("base", "original", 1, "fixture")
                assertEquals(listOf("base"), optionsBefore.map { it.sourceId })

                val discovery = MovieCatalogSourceDiscovery(instrumentation.targetContext, client, registry,
                    initialSeeds = listOf(MovieDiscoverySeed("fixture", "https://config.example.test/root.json")),
                    sourcePages = emptyList())
                val discovered = discovery.discover()
                assertEquals(2, discovered.size)
                assertFalse(discovered.any { it.apiUrl.contains("plugin") })
                val optionsAfter = resolver.listOptions("base", "original", 1, "fixture")
                assertEquals("Config availability alone cannot create a movie source option", 2, optionsAfter.size)
                val newOption = optionsAfter.single { it.label.contains("新增备用源") }
                assertFalse(optionsAfter.any { it.label.contains("无匹配影片源") })
                assertTrue(requestedPaths.any { it.contains("new.example.test") && it.contains("wd=") })

                compose.setContent {
                    MaterialExpressiveTheme {
                        Box(Modifier.width(272.dp)) {
                            InlineVideoPreview(VideoLink(initial.source.url, VideoPlatform.DIRECT),
                                resolveVideo = { _, _ -> initial.source },
                                playbackHttpClient = client,
                                loadSourceOptions = { optionsAfter.map { VideoSourceOption(it.id, it.label, it.selected) } },
                                resolveSourceOption = { id -> resolver.resolveOption(optionsAfter.single { it.id == id }).source })
                        }
                    }
                }
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("inline-video-preview").fetchSemanticsNodes().isNotEmpty() }
                val found = AtomicReference<PlayerView>()
                compose.waitUntil(15_000) { runCatching { currentNativeView() }.onSuccess(found::set).isSuccess }
                val originalView = found.get()
                val player = readMain { originalView.player as ExoPlayer }
                compose.waitUntil(15_000) { readMain { player.playbackState == Player.STATE_READY } }
                if (compose.onAllNodesWithTag("video-controls").fetchSemanticsNodes().isEmpty())
                    compose.onNodeWithTag("video-tap-surface").performClick()
                compose.onNodeWithTag("video-source").performClick()
                compose.onNodeWithTag("video-source-option-${newOption.id}").performScrollTo().assertIsDisplayed().performClick()
                compose.waitUntil(15_000) { readMain { player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("/clip-alt.mp4") &&
                    player.playbackState == Player.STATE_READY } }
                assertSame(originalView, currentNativeView())
                assertSame(player, readMain { originalView.player })
                instrumentation.runOnMainSync { player.play() }
                compose.waitUntil(15_000) { readMain { player.isPlaying && player.currentPosition > 350 && player.videoSize.width == 320 } }
                instrumentation.runOnMainSync { player.pause() }
                assertEquals(180, readMain { player.videoSize.height })
                println("MOVIE_DISCOVERED_PLAYBACK=PASS; CONFIG_SOURCES=${discovered.size}; MATCHING_OPTIONS=${optionsAfter.size}; DECODED=320x180; PROGRESS=${readMain { player.currentPosition }}")
            }
        } finally { directory.deleteRecursively() }
    }

    private fun catalog(media: String) = buildJsonObject {
        put("list", buildJsonArray { add(buildJsonObject {
            put("vod_id", "original"); put("vod_name", "原创测试影片"); put("vod_year", "2026")
            put("type_name", "测试影片"); put("vod_play_from", "fixture"); put("vod_play_url", "正片\$$media")
        }) })
    }.toString()

    private fun response(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("Fixture").body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()

    private fun currentNativeView(): PlayerView {
        val result = AtomicReference<PlayerView>()
        onView(isAssignableFrom(PlayerView::class.java)).inRoot(object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) { description.appendText("the focused movie player window") }
            override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
        }).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(PlayerView::class.java)
            override fun getDescription() = "inspect actual discovered-source playback"
            override fun perform(uiController: UiController, view: View) { result.set(view as PlayerView) }
        })
        return requireNotNull(result.get())
    }

    private fun <T> readMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }
}
