package com.constx.onvyra.data.ai.tools

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.media.MovieCatalogItem
import com.constx.onvyra.data.media.MoviePlaybackSelection
import com.constx.onvyra.data.media.MovieTitleAmbiguousException
import com.constx.onvyra.data.media.MovieWebCandidate
import com.constx.onvyra.data.media.MovieWebSearchOutcome
import com.constx.onvyra.data.media.MovieWebSearchSelection
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.data.media.parseVideoPreviewPayload
import org.junit.Assert.*
import org.junit.Test

class VideoSearchFallbackToolTest {
    private val title = "让子弹飞"
    private fun source(duration: Double? = 7_200.0) = VideoPlaybackSource(
        "https://cdn.example.org/film.m3u8?token=private-stream-token", VideoPlaybackKind.DIRECT,
        title = title, durationSeconds = duration, headers = mapOf("Cookie" to "private-cookie"),
    )
    private fun catalog(id: String = "primary", episode: Int = 1) = MoviePlaybackSelection(
        MovieCatalogItem("film-id", title, 2010, "https://$id.example.org/detail/film",
            sourceId = id, sourceName = id), source(), episode, "hls",
    )
    private fun request() = buildJsonObject { put("title", title); put("year", 2010); put("episode", 2) }
    private suspend fun result(tool: Tool, arguments: JsonObject = request()): Pair<String, JsonObject> {
        val text = (tool.execute(arguments).single() as UIMessagePart.Text).text
        return text to Json.parseToJsonElement(text).jsonObject
    }

    @Test fun primarySuccessKeepsItsLocatorAndDoesNotStartAnyDiscoveryOrWebCall() = runBlocking {
        val calls = mutableListOf<String>()
        val received = mutableListOf<Triple<String, Int?, Int>>()
        val tool = createVideoSearchTool(
            discoverAndPlay = { _, _, _ -> calls += "discovery"; error("must not be called") },
            webFallback = { _, _, _ -> calls += "web"; error("must not be called") },
        ) { name, year, episode -> calls += "primary"; received += Triple(name, year, episode); catalog(episode = episode) }
        val (text, payload) = result(tool)
        assertEquals(listOf("primary"), calls)
        assertEquals(listOf(Triple(title, 2010, 2)), received)
        assertEquals("primary", payload["catalog_source_id"]!!.jsonPrimitive.content)
        assertEquals(2, parseVideoPreviewPayload(text)!!.catalogEpisode)
        assertFalse(payload.containsKey("discovery"))
    }

    @Test fun anUpstreamCatalogArgumentErrorFallsThroughToDiscoveryWithoutBecomingInvalidUserInput() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = createVideoSearchTool(
            discoverAndPlay = { _, _, episode -> calls += "discovery"; catalog("discovered", episode) },
            webFallback = { _, _, _ -> calls += "web"; error("must not be called") },
        ) { _, _, _ -> calls += "primary"; throw IllegalArgumentException("unusable upstream metadata") }
        val (_, payload) = result(tool)
        assertEquals(listOf("primary", "discovery"), calls)
        assertEquals("ready", payload["status"]!!.jsonPrimitive.content)
        assertEquals("discovered", payload["catalog_source_id"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("error_code"))
    }

    @Test fun webpageSuccessRunsAfterBothCatalogStagesAndFeedsTheExistingPlayerCacheWithoutSecrets() = runBlocking {
        val calls = mutableListOf<String>()
        val stream = source(duration = null)
        val original = "https://web.example.org/watch/film"
        val tool = createVideoSearchTool(
            discoverAndPlay = { _, _, _ -> calls += "discovery"; throw IOException("discovery failed") },
            webFallback = { _, _, _ -> calls += "web"; MovieWebSearchOutcome(
                selection = MovieWebSearchSelection(original, stream, durationKnown = false), status = "ready") },
        ) { _, _, _ -> calls += "primary"; throw IOException("https://private.example/?secret=upstream") }
        val (text, payload) = result(tool)
        assertEquals(listOf("primary", "discovery", "web"), calls)
        assertEquals("web_search", payload["discovery"]!!.jsonPrimitive.content)
        assertEquals(false, payload["duration_known"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, payload["full_length_verified"]!!.jsonPrimitive.booleanOrNull)
        assertFalse(payload.keys.any { it.startsWith("catalog_") })
        val preview = requireNotNull(parseVideoPreviewPayload(text))
        assertEquals(original, preview.originalUrl)
        assertNull(preview.catalogSourceId)
        assertEquals(stream, findResolvedVideoPreview(preview.playbackId))
        listOf("private-stream-token", "private-cookie", "cdn.example.org", "secret=upstream").forEach {
            assertFalse("The model-visible result leaked $it", text.contains(it))
        }
    }

    @Test fun allAutomaticFailuresReturnLimitedCheckedPagesAndAnExplicitAutonomousContinuation() = runBlocking {
        val candidates = (1..6).map { MovieWebCandidate("候选$it", "https://site$it.example.org/watch/film", "private-snippet") }
        val tool = createVideoSearchTool(
            discoverAndPlay = { _, _, _ -> throw IOException("private-discovery-error") },
            webFallback = { _, _, _ -> MovieWebSearchOutcome(candidates = candidates, status = "unverified_sources") },
        ) { _, _, _ -> throw IOException("private-catalog-error") }
        val (text, payload) = result(tool)
        assertEquals("sources_unavailable", payload["error_code"]!!.jsonPrimitive.content)
        assertEquals("unverified_sources", payload["web_search_status"]!!.jsonPrimitive.content)
        assertEquals(4, payload["candidates"]!!.jsonArray.size)
        val next = payload["next_action"]!!.jsonPrimitive.content
        listOf("video_find_sources", "web_fetch", "video_resolve").forEach { assertTrue(next.contains(it)) }
        assertFalse(text.contains("private-"))
        assertNull(parseVideoPreviewPayload(text))
    }

    @Test fun invalidUserInputStopsBeforeEveryPotentialNetworkStage() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = createVideoSearchTool(
            discoverAndPlay = { _, _, _ -> calls += "discovery"; catalog() },
            webFallback = { _, _, _ -> calls += "web"; MovieWebSearchOutcome(status = "no_results") },
        ) { _, _, _ -> calls += "primary"; catalog() }
        val invalid = buildJsonObject { put("title", title); put("episode", 0) }
        val (_, payload) = result(tool, invalid)
        assertEquals("invalid_arguments", payload["error_code"]!!.jsonPrimitive.content)
        assertTrue(calls.isEmpty())
    }

    @Test fun aSameNameAmbiguityInEitherCatalogStageStopsRatherThanSelectingAnArbitraryWebVersion() = runBlocking {
        for (ambiguousStage in 0..1) {
            val calls = mutableListOf<String>()
            val tool = createVideoSearchTool(
                discoverAndPlay = { _, _, _ -> calls += "discovery"; throw MovieTitleAmbiguousException() },
                webFallback = { _, _, _ -> calls += "web"; MovieWebSearchOutcome(status = "no_results") },
            ) { _, _, _ -> calls += "primary"; if (ambiguousStage == 0) throw MovieTitleAmbiguousException()
                else throw IOException("no built-in source") }
            val (_, payload) = result(tool)
            assertEquals("ambiguous_title", payload["error_code"]!!.jsonPrimitive.content)
            assertEquals(if (ambiguousStage == 0) listOf("primary") else listOf("primary", "discovery"), calls)
            assertFalse(payload.containsKey("candidates"))
        }
    }

    @Test fun cancellationAtAnyStageNeverStartsLaterStagesOrBecomesASourceFailure() = runBlocking {
        for (cancelledStage in 0..2) {
            val calls = mutableListOf<String>()
            val tool = createVideoSearchTool(
                discoverAndPlay = { _, _, _ -> calls += "discovery"; if (cancelledStage == 1) throw CancellationException("stopped")
                    else throw IOException("not found") },
                webFallback = { _, _, _ -> calls += "web"; throw CancellationException("stopped") },
            ) { _, _, _ -> calls += "primary"; if (cancelledStage == 0) throw CancellationException("stopped")
                else throw IOException("not found") }
            try { result(tool); fail("Cancelled stage $cancelledStage was swallowed") }
            catch (_: CancellationException) { }
            assertEquals(listOf("primary", "discovery", "web").take(cancelledStage + 1), calls)
        }
    }

    @Test fun legacyCallersKeepTheirOldFailureWhileANewWebFailureHasAnActionableStatus() = runBlocking {
        val legacy = createVideoSearchTool { _, _, _ -> throw IOException("private legacy detail") }
        val (_, oldPayload) = result(legacy)
        assertEquals("catalog_unavailable", oldPayload["error_code"]!!.jsonPrimitive.content)
        assertFalse(oldPayload.containsKey("next_action"))
        val current = createVideoSearchTool(webFallback = { _, _, _ -> throw IOException("private web detail") }) {
                _, _, _ -> throw IOException("not found")
        }
        val (text, currentPayload) = result(current)
        assertEquals("sources_unavailable", currentPayload["error_code"]!!.jsonPrimitive.content)
        assertEquals("search_unavailable", currentPayload["web_search_status"]!!.jsonPrimitive.content)
        assertTrue(currentPayload.containsKey("next_action"))
        assertFalse(text.contains("private"))
    }
}
