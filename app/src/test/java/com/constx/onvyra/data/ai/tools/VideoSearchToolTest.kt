package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import com.constx.onvyra.data.media.*
import org.junit.Assert.*
import org.junit.Test

class VideoSearchToolTest {
    @Test fun animeIntentIsPassedToEveryFallbackAndUnknownWorkTypeIsNotClaimedVerified() = runBlocking {
        val kinds = mutableListOf<MovieContentType>()
        val tool = createTypedVideoSearchTool(
            discoverAndPlay = { _, _, _, type -> kinds += type; throw java.io.IOException("unavailable") },
            webFallback = { _, _, _, type -> kinds += type; MovieWebSearchOutcome(
                selection = MovieWebSearchSelection("https://example.org/watch/1", VideoPlaybackSource(
                    "https://cdn.example.org/one.m3u8", VideoPlaybackKind.DIRECT, title = "鬼灭之刃 第1集")), status = "ready") },
        ) { _, _, _, type -> kinds += type; throw java.io.IOException("unavailable") }
        val text = (tool.execute(buildJsonObject { put("title", "鬼灭之刃"); put("content_type", "anime") }).single()
            as me.rerere.ai.ui.UIMessagePart.Text).text
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf(MovieContentType.ANIME, MovieContentType.ANIME, MovieContentType.ANIME), kinds)
        assertEquals("anime", result["requested_content_type"]!!.jsonPrimitive.content)
        assertEquals("ready", result["status"]!!.jsonPrimitive.content)
        assertFalse(result["content_type_verified"]!!.jsonPrimitive.boolean)
        assertFalse(result["full_length_verified"]!!.jsonPrimitive.boolean)
    }

    @Test fun anAnimeRequestCannotAcceptACommentaryStreamReturnedByALegacyCallback() = runBlocking {
        var discoveryCalled = false
        val source = VideoPlaybackSource("https://cdn.example.org/one.m3u8", VideoPlaybackKind.DIRECT, title = "鬼灭之刃解说")
        val tool = createVideoSearchTool(discoverAndPlay = { _, _, _ -> discoveryCalled = true; error("No full episode") }) { _, _, ep ->
            MoviePlaybackSelection(MovieCatalogItem("1", "鬼灭之刃", detailUrl = "https://example.org/one", category = "日韩动漫"), source, ep, "hls")
        }
        val text = (tool.execute(buildJsonObject { put("title", "鬼灭之刃"); put("content_type", "anime") }).single()
            as me.rerere.ai.ui.UIMessagePart.Text).text
        assertTrue(discoveryCalled)
        assertEquals("error", Json.parseToJsonElement(text).jsonObject["status"]!!.jsonPrimitive.content)
        assertFalse(text.contains("playback_id"))
    }

    @Test fun readyMovieCarriesCatalogLocatorButDoesNotExposeStreamSecretsToModel() = runBlocking {
        val item = MovieCatalogItem("8136", "让子弹飞", 2010, "https://example.org/api?ids=8136", sourceId = "fixture", sourceName = "Fixture")
        val source = VideoPlaybackSource("https://cdn.example.org/movie.m3u8?token=private", VideoPlaybackKind.DIRECT,
            title = "让子弹飞", headers = mapOf("Cookie" to "private"))
        var received: Triple<String, Int?, Int>? = null
        val tool = createVideoSearchTool { title, year, episode ->
            received = Triple(title, year, episode)
            MoviePlaybackSelection(item, source, episode, "hls")
        }
        val text = (tool.execute(buildJsonObject { put("title", "让子弹飞"); put("year", 2010) }).single() as me.rerere.ai.ui.UIMessagePart.Text).text
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals(Triple("让子弹飞", 2010, 1), received)
        assertEquals("ready", result["status"]?.jsonPrimitive?.content)
        assertEquals("fixture", result["catalog_source_id"]?.jsonPrimitive?.content)
        val payload = parseVideoPreviewPayload(text)!!
        assertEquals("8136", payload.catalogItemId)
        assertEquals(source, findResolvedVideoPreview(payload.playbackId))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("cdn.example.org"))
    }

    @Test fun invalidInputDoesNotInvokeNetworkAndNullOptionalsAreAccepted() = runBlocking {
        var calls = 0
        val tool = createVideoSearchTool { _, _, _ -> calls++; throw IllegalStateException("No source") }
        listOf("{}", """{"title":17}""", """{"title":"film","episode":0}""",
            """{"title":"film","content_type":"guess"}""", """{"title":"film","content_type":7}""").forEach { json ->
            val output = tool.execute(Json.parseToJsonElement(json)).single() as me.rerere.ai.ui.UIMessagePart.Text
            assertEquals("invalid_arguments", Json.parseToJsonElement(output.text).jsonObject["error_code"]?.jsonPrimitive?.content)
        }
        assertEquals(0, calls)
        tool.execute(Json.parseToJsonElement("""{"title":"film","year":null,"episode":null}"""))
        assertEquals(1, calls)
    }

    @Test fun cancellationPropagatesAndExternalErrorsDoNotLeakDetails() = runBlocking {
        val cancelled = createVideoSearchTool { _, _, _ -> throw CancellationException("cancelled") }
        try { cancelled.execute(buildJsonObject { put("title", "film") }); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
        val failed = createVideoSearchTool { _, _, _ -> error("https://private.example/?secret=token") }
        val result = (failed.execute(buildJsonObject { put("title", "film") }).single() as me.rerere.ai.ui.UIMessagePart.Text).text
        assertFalse(result.contains("secret"))
        assertTrue(result.contains("catalog_unavailable"))
    }
}
