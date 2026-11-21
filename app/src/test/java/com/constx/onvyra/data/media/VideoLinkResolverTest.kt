package com.constx.onvyra.data.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class VideoLinkResolverTest {
    @Test fun `full public players and direct media need no HTTP resolution`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected HTTP request") }.build()
        val resolver = VideoLinkResolver(client)
        val source = resolver.resolve(parseVideoLink("https://www.bilibili.com/video/BV1xx411c7mD")!!)
        assertEquals(VideoPlaybackKind.EMBEDDED, source.kind)
        assertTrue(source.url.startsWith("https://player.bilibili.com/player.html?bvid="))
        val direct = resolver.resolve(parseVideoLink("https://cdn.example.test/public.m3u8?token=fixture")!!)
        assertEquals(VideoPlaybackKind.DIRECT, direct.kind)
        assertEquals("application/x-mpegURL", direct.mimeType)
    }

    @Test fun `sharing redirect resolves canonical webpage and caches it without fetching video`() = runBlocking {
        val calls = AtomicInteger()
        val closed = AtomicBoolean()
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = error("A video response body must not be read")
            override fun close() { closed.set(true) }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/html".toMediaType()
            override fun contentLength() = Long.MAX_VALUE
            override fun source(): BufferedSource = source
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("bytes=0-0", chain.request().header("Range"))
            response(chain, 302, body, "Location" to "https://www.douyin.com/video/7123456789012345678")
        }.build()
        val resolver = VideoLinkResolver(client)
        val short = parseVideoLink("https://v.douyin.com/Abc123/")!!
        val canonical = resolver.resolveLink(short)
        assertEquals("7123456789012345678", canonical.videoId)
        assertEquals("https://www.douyin.com/video/7123456789012345678", canonicalExtractorUrl(canonical))
        assertTrue(resolver.resolve(short).url.startsWith("https://open.douyin.com/player/video"))
        assertEquals(1, calls.get())
        assertTrue(closed.get())
    }

    @Test fun `redirected content type can identify a signed stream without a media extension`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() == 1) response(chain, 307,
                headers = arrayOf("Location" to "https://cdn.example.test/play?signature=fixture"))
            else response(chain, 206, headers = arrayOf("Content-Type" to "video/mp4; charset=binary"))
        }.build()
        val resolver = VideoLinkResolver(client)
        val link = parseVideoLink("https://b23.tv/fixture")!!
        val result = resolver.resolve(link)
        assertEquals(VideoPlaybackKind.DIRECT, result.kind)
        assertEquals("video/mp4", result.mimeType)
        assertEquals("https://cdn.example.test/play?signature=fixture", result.url)
        assertEquals(VideoPlatform.DIRECT, resolver.resolveLink(link).platform)
        assertEquals(2, calls.get())
    }

    @Test fun `final HTML landing page is retained for embedded or enhanced resolution`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, 200, headers = arrayOf("Content-Type" to "text/html; charset=utf-8"))
        }.build()
        val resolver = VideoLinkResolver(client)
        val short = parseVideoLink("https://v.douyin.com/fixture")!!
        assertEquals(VideoPlatform.OTHER, resolver.resolveLink(short).platform)
        assertEquals(VideoPlaybackKind.EMBEDDED, resolver.resolve(short).kind)
    }

    @Test fun `redirect cycles missing locations unsafe destinations and errors terminate`() = runBlocking {
        val cases = listOf<Pair<Int, String?>>(302 to "/loop", 302 to null,
            302 to "https://user:password@cdn.example.test/clip.mp4", 302 to "file:///private/clip.mp4", 403 to null)
        cases.forEach { (status, location) ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain, status, headers = location?.let { arrayOf("Location" to it) } ?: emptyArray())
            }.build()
            val error = runCatching { VideoLinkResolver(client).resolve(parseVideoLink("https://b23.tv/loop")!!) }.exceptionOrNull()
            assertTrue("$status $location: $error", error is IOException)
        }
    }

    @Test fun `too many distinct redirects are bounded`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, 302, headers = arrayOf("Location" to "/hop-${calls.incrementAndGet()}"))
        }.build()
        assertTrue(runCatching {
            VideoLinkResolver(client).resolve(parseVideoLink("https://b23.tv/fixture")!!)
        }.exceptionOrNull() is IOException)
        assertEquals(7, calls.get())
    }

    @Test fun `untrusted caller supplied embed is ignored and unsupported URLs rejected`() = runBlocking {
        val resolver = VideoLinkResolver(OkHttpClient.Builder().addInterceptor { error("No HTTP expected") }.build())
        val fake = VideoLink("https://example.test/ordinary", VideoPlatform.YOUTUBE, "fixture", "javascript:alert(1)")
        assertTrue(runCatching { resolver.resolve(fake) }.exceptionOrNull() is IOException)
        val public = parseVideoLink("https://youtu.be/dQw4w9WgXcQ")!!.copy(embedUrl = "javascript:alert(1)")
        assertTrue(resolver.resolve(public).url.startsWith("https://www.youtube-nocookie.com/embed/"))
    }

    @Test fun `cancelling short link lookup cancels its HTTP call promptly`() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var call: Call? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call = chain.call()
            started.countDown()
            finish.await(5, TimeUnit.SECONDS)
            response(chain, 200, headers = arrayOf("Content-Type" to "text/html"))
        }.build()
        try {
            val job = launch { VideoLinkResolver(client).resolve(parseVideoLink("https://b23.tv/cancel")!!) }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            withTimeout(1_000) { job.cancelAndJoin() }
            assertTrue(call!!.isCanceled())
        } finally { finish.countDown() }
    }

    @Test fun `direct verification follows redirects reads no video body and retains the request user agent`() = runBlocking {
        val calls = AtomicInteger()
        val closed = AtomicBoolean()
        var userAgent: String? = null
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = error("Verification must not read video bytes")
            override fun close() { closed.set(true) }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "video/mp4".toMediaType()
            override fun contentLength() = 5_000_000L
            override fun source(): BufferedSource = source
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=0-0", chain.request().header("Range"))
            userAgent = chain.request().header("User-Agent")
            if (calls.incrementAndGet() == 1) response(chain, 302,
                headers = arrayOf("Location" to "https://cdn.example.test/stream?signature=fixture"))
            else response(chain, 206, body, "Content-Type" to "video/mp4")
        }.build()
        val result = VideoLinkResolver(client).verifyDirect(parseVideoLink("https://example.test/video.mp4")!!)
        assertEquals("https://cdn.example.test/stream?signature=fixture", result.url)
        assertEquals("video/mp4", result.mimeType)
        assertEquals(VideoPlaybackKind.DIRECT, result.kind)
        assertEquals(mapOf("User-Agent" to userAgent), result.headers)
        assertTrue(closed.get())
        assertEquals(2, calls.get())
    }

    @Test fun `direct verification accepts streaming MIME aliases and missing or generic MIME for known media paths`() = runBlocking {
        listOf(
            "application/vnd.apple.mpegurl" to "application/x-mpegURL",
            "audio/x-mpegurl" to "application/x-mpegURL",
            "application/dash+xml" to "application/dash+xml",
            "application/octet-stream" to "video/mp4",
            "" to "video/mp4",
        ).forEach { (received, expected) ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain, 200, headers = if (received.isEmpty()) emptyArray() else arrayOf("Content-Type" to received))
            }.build()
            val result = VideoLinkResolver(client).verifyDirect(parseVideoLink("https://example.test/movie.mp4")!!)
            assertEquals(received, expected, result.mimeType)
        }
    }

    @Test fun `direct verification rejects HTML JSON errors login redirects and loops even for mp4 paths`() = runBlocking {
        val replies = listOf<Pair<Int, Pair<String, String>?>>(
            200 to ("Content-Type" to "text/html; charset=utf-8"),
            200 to ("Content-Type" to "application/json"),
            403 to null,
            302 to ("Location" to "https://user:secret@example.test/movie.mp4"),
            302 to ("Location" to "https://example.test/movie.mp4"),
        )
        replies.forEach { (status, header) ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain, status, headers = header?.let { arrayOf(it) } ?: emptyArray())
            }.build()
            val error = runCatching {
                VideoLinkResolver(client).verifyDirect(parseVideoLink("https://example.test/movie.mp4")!!)
            }.exceptionOrNull()
            assertTrue("$status $header: $error", error is IOException)
        }
        val client = OkHttpClient.Builder().addInterceptor { error("Not a direct media path") }.build()
        assertTrue(runCatching {
            VideoLinkResolver(client).verifyDirect(VideoLink("https://example.test/info", VideoPlatform.DIRECT))
        }.exceptionOrNull() is IOException)
    }

    @Test fun `cancelling direct stream verification closes its HTTP call`() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var call: Call? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call = chain.call()
            started.countDown()
            finish.await(5, TimeUnit.SECONDS)
            response(chain, 200, headers = arrayOf("Content-Type" to "video/mp4"))
        }.build()
        try {
            val job = launch { VideoLinkResolver(client).verifyDirect(parseVideoLink("https://example.test/movie.mp4")!!) }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            withTimeout(1_000) { job.cancelAndJoin() }
            assertTrue(call!!.isCanceled())
        } finally { finish.countDown() }
    }

    private fun response(chain: Interceptor.Chain, status: Int,
        body: ResponseBody = "".toResponseBody(), vararg headers: Pair<String, String>): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .body(body).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
}
