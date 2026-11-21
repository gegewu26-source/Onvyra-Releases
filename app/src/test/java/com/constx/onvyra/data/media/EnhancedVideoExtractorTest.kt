package com.constx.onvyra.data.media

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnhancedVideoExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `muxed video carries validated playback headers and title`() {
        val result = parseYtDlpVideoInfo("""{
            "title":"Public fixture", "url":"https://cdn.example.test/movie.mp4?token=fixture", "ext":"mp4",
            "vcodec":"avc1", "acodec":"aac",
            "http_headers":{"User-Agent":"fixture UA","Referer":"https://www.bilibili.com/","Origin":"https://www.bilibili.com"}
        }""")
        assertEquals(VideoPlaybackKind.DIRECT, result.kind)
        assertEquals("https://cdn.example.test/movie.mp4?token=fixture", result.url)
        assertEquals("Public fixture", result.title)
        assertEquals("video/mp4", result.mimeType)
        assertEquals("fixture UA", result.headers["User-Agent"])
        assertNull(result.audioUrl)
    }

    @Test fun `selected separate video and audio remain separate playable streams`() {
        val result = parseYtDlpVideoInfo("""{
            "title":"DASH fixture", "http_headers":{"Referer":"https://www.bilibili.com/"},
            "requested_formats":[
                {"url":"https://cdn.example.test/track.m4s","ext":"mp4","vcodec":"avc1","acodec":"none"},
                {"url":"https://cdn.example.test/audio.m4s","ext":"m4a","vcodec":"none","acodec":"aac"}
            ]
        }""")
        assertEquals("https://cdn.example.test/track.m4s", result.url)
        assertEquals("https://cdn.example.test/audio.m4s", result.audioUrl)
        assertEquals("video/mp4", result.mimeType)
    }

    @Test fun `nested requested download selections keep root title`() {
        val result = parseYtDlpVideoInfo("""{
            "title":"Nested fixture", "requested_downloads":[{"requested_formats":[
                {"url":"https://cdn.example.test/v.mp4","ext":"mp4","acodec":"none","vcodec":"avc1"},
                {"url":"https://cdn.example.test/a.m4a","ext":"m4a","acodec":"aac","vcodec":"none"}
            ]}]
        }""")
        assertEquals("Nested fixture", result.title)
        assertEquals("https://cdn.example.test/a.m4a", result.audioUrl)
    }

    @Test fun `HLS and DASH manifests identify their transport`() {
        val hls = parseYtDlpVideoInfo("""{"url":"https://cdn.example.test/hls?id=1","protocol":"m3u8_native","ext":"mp4"}""")
        val dash = parseYtDlpVideoInfo("""{"url":"https://cdn.example.test/video.mpd?auth=fixture"}""")
        assertEquals("application/x-mpegURL", hls.mimeType)
        assertEquals("application/dash+xml", dash.mimeType)
    }

    @Test fun `unsafe schemes DRM and audio only metadata do not become video sources`() {
        listOf(
            """{"url":"file:///data/private.mp4","ext":"mp4"}""",
            """{"url":"javascript:alert(1)","ext":"mp4"}""",
            """{"url":"https://user:password@cdn.example.test/video.mp4","ext":"mp4"}""",
            """{"url":"https://cdn.example.test/video.mp4","has_drm":true}""",
            """{"url":"https://cdn.example.test/audio.m4a","ext":"m4a","vcodec":"none"}""",
        ).forEach { content -> assertNotNull(content, runCatching { parseYtDlpVideoInfo(content) }.exceptionOrNull()) }
    }

    @Test fun `header selection never broadcasts session cookies authorization or ranges`() {
        val result = parseYtDlpVideoInfo("""{
            "url":"https://cdn.example.test/video.mp4", "ext":"mp4",
            "http_headers":{"Cookie":"session=private","Authorization":"Bearer private","Host":"wrong.example.test",
                "Range":"bytes=2-3","User-Agent":"valid","Referer":"bad\r\nInjected: value"}
        }""")
        assertEquals(mapOf("User-Agent" to "valid"), result.headers)
    }

    @Test fun `one video in a resolved list is accepted but a full playlist is not`() {
        val source = parseYtDlpVideoInfo("""{"entries":[{"url":"https://cdn.example.test/video.mp4","ext":"mp4"}]}""")
        assertEquals("https://cdn.example.test/video.mp4", source.url)
        assertNotNull(runCatching {
            parseYtDlpVideoInfo("""{"entries":[{"url":"https://cdn.example.test/1.mp4"},{"url":"https://cdn.example.test/2.mp4"}]}""")
        }.exceptionOrNull())
    }

    @Test fun `missing selection can use a real video format rather than an audio track`() {
        val source = parseYtDlpVideoInfo("""{"formats":[
            {"url":"https://cdn.example.test/a.m4a","ext":"m4a","vcodec":"none"},
            {"url":"https://cdn.example.test/360.mp4","ext":"mp4","height":360},
            {"url":"https://cdn.example.test/720.mp4","ext":"mp4","height":720}
        ]}""")
        assertEquals("https://cdn.example.test/720.mp4", source.url)
    }

    @Test fun `cookies are stored for the exact HTTPS host in a private temporary file`() {
        val file = createOriginCookieFile(temporary.root, "https://www.douyin.com/video/123", "sid=private-value; token=a=b; Path=/; Secure")!!
        try {
            val lines = file.readLines().filterNot { it.startsWith('#') || it.isBlank() }
            assertEquals(2, lines.size)
            lines.forEach {
                val fields = it.split('\t')
                assertEquals("www.douyin.com", fields[0])
                assertEquals("FALSE", fields[1])
                assertEquals("TRUE", fields[3])
                assertTrue(fields[4].toLong() > System.currentTimeMillis() / 1000L)
            }
            assertTrue(lines.last().endsWith("token\ta=b"))
        } finally { file.delete() }
        assertFalse(file.exists())
    }

    @Test fun `invalid cookie lines and non web URLs create no cookie file`() {
        assertNull(createOriginCookieFile(temporary.root, "file:///private", "sid=value"))
        assertNull(createOriginCookieFile(temporary.root, "https://www.douyin.com/", "sid=bad\nvalue; Path=/; HttpOnly"))
        assertEquals(emptyList<File>(), temporary.root.listFiles()!!.toList())
    }
    @Test fun `fallback formats favor complete video over a higher resolution silent track`() {
        val source = parseYtDlpVideoInfo("""{"formats":[
            {"url":"https://cdn.example.test/high.mp4","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"},
            {"url":"https://cdn.example.test/muxed.mp4","ext":"mp4","height":720,"vcodec":"avc1","acodec":"aac"},
            {"url":"https://cdn.example.test/audio.m4a","ext":"m4a","vcodec":"none","acodec":"aac"}
        ]}""")
        assertEquals("https://cdn.example.test/muxed.mp4", source.url)
        assertNull(source.audioUrl)
    }

    @Test fun `fallback separate streams pair video with an audio track and skip DRM`() {
        val source = parseYtDlpVideoInfo("""{"formats":[
            {"url":"https://cdn.example.test/drm.mp4","ext":"mp4","height":2160,"has_drm":true},
            {"url":"https://cdn.example.test/video.mp4","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"},
            {"url":"https://cdn.example.test/audio.m4a","ext":"m4a","vcodec":"none","acodec":"aac","abr":128}
        ]}""")
        assertEquals("https://cdn.example.test/video.mp4", source.url)
        assertEquals("https://cdn.example.test/audio.m4a", source.audioUrl)
    }

    @Test fun `Douyin iframe metadata is converted back into the extractor canonical URL`() {
        assertEquals("https://www.douyin.com/video/7123456789012345678",
            canonicalExtractorUrl(parseVideoLink("https://open.douyin.com/player/video?vid=7123456789012345678")!!))
    }

    @Test fun `finite positive source duration is retained and missing or invalid values stay unknown`() {
        val fixture = "\"url\":\"https://cdn.example.test/video.mp4\",\"ext\":\"mp4\""
        val source = parseYtDlpVideoInfo("{$fixture,\"duration\":7620.75}")
        assertEquals(7620.75, source.durationSeconds!!, 0.0)
        assertNull(parseYtDlpVideoInfo("{$fixture}").durationSeconds)
        listOf("0", "-1", "null", "\"NaN\"", "\"Infinity\"").forEach { duration ->
            assertNull(duration, parseYtDlpVideoInfo("{$fixture,\"duration\":$duration}").durationSeconds)
        }
    }

}
