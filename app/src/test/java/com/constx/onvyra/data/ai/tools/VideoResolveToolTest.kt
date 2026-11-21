package com.constx.onvyra.data.ai.tools

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import org.junit.Assert.*
import org.junit.Test

class VideoResolveToolTest {
    @Test fun `schema asks for an actual single video URL and prompt describes search then resolution`() {
        val tool = createVideoResolveTool { error("Unused") }
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals("video_resolve", tool.name)
        assertEquals(listOf("url"), schema.required)
        assertEquals(setOf("url", "content_type"), schema.properties.keys)
        assertEquals("string", schema.properties.getValue("url").jsonObject.getValue("type").jsonPrimitive.content)
        val prompt = tool.systemPrompt(Model(abilities = listOf(ModelAbility.TOOL)), emptyList())
        assertTrue(prompt.contains("web search"))
        assertTrue(prompt.contains("untrusted"))
        assertTrue(prompt.contains("video_resolve"))
        assertTrue(prompt.contains("full films from clips or trailers"))
        assertTrue(tool.description.contains("duration_seconds"))
        assertEquals("", tool.systemPrompt(Model(), emptyList()))
    }

    @Test fun `anime intent rejects commentary but general video can still resolve a requested commentary`() = runBlocking {
        val source = VideoPlaybackSource("https://cdn.example.test/commentary.mp4?token=private", VideoPlaybackKind.DIRECT,
            title = "鬼灭之刃解说", durationSeconds = 300.0)
        val tool = createVideoResolveTool { source }
        val rejected = tool.payload(buildJsonObject { put("url", "https://example.test/watch/1"); put("content_type", "anime") })
        assertEquals("content_mismatch", rejected.getValue("error_code").jsonPrimitive.content)
        assertFalse(rejected.containsKey("playback_id"))
        assertFalse(rejected.toString().contains("private"))
        val general = tool.payload(buildJsonObject { put("url", "https://example.test/watch/1") })
        assertEquals("ready", general.getValue("status").jsonPrimitive.content)
    }

    @Test fun `a short anime episode stays playable without claiming unknown work type or full length`() = runBlocking {
        val result = createVideoResolveTool { VideoPlaybackSource("https://cdn.example.test/one.mp4", VideoPlaybackKind.DIRECT,
            title = "鬼灭之刃 第1集", durationSeconds = 180.0) }.payload(buildJsonObject {
            put("url", "https://example.test/watch/1"); put("content_type", "anime")
        })
        assertEquals("ready", result.getValue("status").jsonPrimitive.content)
        assertEquals("anime", result.getValue("requested_content_type").jsonPrimitive.content)
        assertFalse(result.getValue("content_type_verified").jsonPrimitive.boolean)
        assertFalse(result.getValue("full_length_verified").jsonPrimitive.boolean)
    }

    @Test fun `tool resolves a generic video page and returns metadata but keeps signed streams local`() = runBlocking {
        var requested = ""
        val source = VideoPlaybackSource(
            url = "https://cdn.example.test/movie.mp4?signature=private-token",
            kind = VideoPlaybackKind.DIRECT,
            audioUrl = "https://cdn.example.test/audio.m4a?token=private-audio",
            title = "A public movie",
            headers = mapOf("Referer" to "https://video.example.test/watch", "User-Agent" to "Private fixture UA"),
        )
        val result = createVideoResolveTool { requested = it; source }
            .payload(buildJsonObject { put("url", " https://video.example.test/watch?id=123 ") })
        assertEquals("https://video.example.test/watch?id=123", requested)
        assertEquals("video_preview", result.getValue("type").jsonPrimitive.content)
        assertEquals("ready", result.getValue("status").jsonPrimitive.content)
        assertEquals("https://video.example.test/watch?id=123", result.getValue("original_url").jsonPrimitive.content)
        assertEquals("A public movie", result.getValue("title").jsonPrimitive.content)
        assertTrue(result.getValue("playable").jsonPrimitive.boolean)
        val key = result.getValue("playback_id").jsonPrimitive.content
        assertEquals(source, findResolvedVideoPreview(key))
        assertEquals(source, findResolvedVideoPreview(key)) // Recompositions must not consume the entry.
        val serialized = result.toString()
        assertFalse(serialized.contains("private-token"))
        assertFalse(serialized.contains("private-audio"))
        assertFalse(serialized.contains("Private fixture UA"))
        assertFalse(serialized.contains("Referer"))
    }

    @Test fun `invalid URL inputs are rejected without invoking the extractor`() = runBlocking {
        val tool = createVideoResolveTool { error("Extractor must not run") }
        val invalid = listOf<JsonElement>(JsonNull, JsonArray(emptyList()), JsonPrimitive("https://example.test/"),
            buildJsonObject {}, buildJsonObject { put("url", 123) },
            buildJsonObject { put("url", " ") }, buildJsonObject { put("url", "file:///private/video.mp4") },
            buildJsonObject { put("url", "javascript:alert(1)") },
            buildJsonObject { put("url", "https://name:password@example.test/video") },
            buildJsonObject { put("url", "https://example.test/" + "a".repeat(4_096)) })
        invalid.forEach { arguments ->
            val result = tool.payload(arguments)
            assertEquals("error", result.getValue("status").jsonPrimitive.content)
            assertEquals("invalid_url", result.getValue("error_code").jsonPrimitive.content)
            assertFalse(result.getValue("playable").jsonPrimitive.boolean)
            assertFalse(result.containsKey("playback_id"))
        }
    }

    @Test fun `failure is structured and upstream signed URLs and cookie details are never echoed`() = runBlocking {
        val result = createVideoResolveTool {
            throw IOException("Cookie sid=private; failed at https://cdn.example.test/file?signature=private-token")
        }.payload(buildJsonObject { put("url", "https://example.test/watch/1") })
        assertEquals("error", result.getValue("status").jsonPrimitive.content)
        assertEquals("extraction_failed", result.getValue("error_code").jsonPrimitive.content)
        assertEquals("https://example.test/watch/1", result.getValue("original_url").jsonPrimitive.content)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun `cancellation propagates rather than being converted into an extraction error`() = runBlocking {
        val cancellation = CancellationException("Fixture cancellation")
        val error = runCatching {
            createVideoResolveTool { throw cancellation }.execute(buildJsonObject { put("url", "https://example.test/watch") })
        }.exceptionOrNull()
        assertSame(cancellation, error)
    }

    @Test fun `webpage placeholders and unsafe extracted URLs are not declared playable`() = runBlocking {
        listOf(
            VideoPlaybackSource("https://example.test/embed", VideoPlaybackKind.EMBEDDED),
            VideoPlaybackSource("file:///private/video.mp4", VideoPlaybackKind.DIRECT),
            VideoPlaybackSource("https://user:password@cdn.example.test/file.mp4", VideoPlaybackKind.DIRECT),
            VideoPlaybackSource("https://cdn.example.test/file.mp4", VideoPlaybackKind.DIRECT, audioUrl = "javascript:alert(1)"),
        ).forEach { source ->
            val result = createVideoResolveTool { source }.payload(buildJsonObject { put("url", "https://example.test/watch") })
            assertEquals("error", result.getValue("status").jsonPrimitive.content)
            assertFalse(result.getValue("playable").jsonPrimitive.boolean)
        }
    }

    @Test fun `local playback cache expires and least recently used entries are bounded`() {
        var time = 0L
        val cache = ResolvedVideoPreviewCache(maximumSize = 2, ttlNanos = 100) { time }
        val source = VideoPlaybackSource("https://cdn.example.test/file.mp4", VideoPlaybackKind.DIRECT)
        val first = cache.put(source)
        val second = cache.put(source.copy(title = "Second"))
        assertEquals(source, cache.get(first))
        val third = cache.put(source.copy(title = "Third"))
        assertNull(cache.get(second))
        assertNotNull(cache.get(first))
        assertNotNull(cache.get(third))
        time = 100
        assertNull(cache.get(first))
        assertNull(cache.get(third))
    }

    @Test fun `result builder returns distinct local playback IDs and bounded title`() {
        val cache = ResolvedVideoPreviewCache()
        val source = VideoPlaybackSource("https://cdn.example.test/file.mp4?signature=private", VideoPlaybackKind.DIRECT,
            title = "a".repeat(4_000))
        val first = buildVideoPreviewSuccess("https://example.test/watch", source, cache)
        val second = buildVideoPreviewSuccess("https://example.test/watch", source, cache)
        val firstId = first.getValue("playback_id").jsonPrimitive.content
        val secondId = second.getValue("playback_id").jsonPrimitive.content
        assertNotEquals(firstId, secondId)
        assertEquals(512, first.getValue("title").jsonPrimitive.content.length)
        assertEquals(source, cache.get(firstId))
        assertFalse(first.toString().contains("signature"))
        assertTrue(first.toString().length < 1_000)
    }

    @Test fun `duration metadata distinguishes a resolved clip without asserting full film completeness`() {
        val cache = ResolvedVideoPreviewCache()
        val source = VideoPlaybackSource("https://cdn.example.test/clip.mp4", VideoPlaybackKind.DIRECT,
            title = "Movie trailer", durationSeconds = 30.0)
        val result = buildVideoPreviewSuccess("https://example.test/watch", source, cache)
        assertEquals(30.0, result.getValue("duration_seconds").jsonPrimitive.double, 0.0)
        assertFalse(result.containsKey("full_film"))
        assertFalse(buildVideoPreviewSuccess("https://example.test/watch", source.copy(durationSeconds = null), cache)
            .containsKey("duration_seconds"))
        assertFalse(buildVideoPreviewSuccess("https://example.test/watch", source.copy(durationSeconds = Double.NaN), cache)
            .containsKey("duration_seconds"))
    }


    private suspend fun Tool.payload(arguments: JsonElement): JsonObject =
        Json.parseToJsonElement((execute(arguments).single() as UIMessagePart.Text).text).jsonObject
}
