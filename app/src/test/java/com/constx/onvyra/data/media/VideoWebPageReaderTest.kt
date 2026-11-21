package com.constx.onvyra.data.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class VideoWebPageReaderTest {
    @Test fun readsOnlyRealVideoFieldsAndResolvesActualRelativeLinks() {
        val parsed = parseVideoPageDocument("""
            <!doctype html><html><head><title>完整影片 2010</title>
            <meta name="description" content="原页面说明">
            <meta property="og:video:secure_url" content="//cdn.example.test/film.m3u8?part=1&amp;mode=hd"></head>
            <body><video src="../film.mp4"><source src="/other.m3u8"></video>
            <source src="/unrelated.mp3"><audio src="/audio.mp3"></audio>
            <iframe src="../player/1" title="正片播放"></iframe><a href="/watch/2">第二集</a>
            <script>window.fake='https://secret.example.test/fake.mp4';</script></body></html>
        """.trimIndent(), "https://movie.example.test/detail/film")
        assertEquals("完整影片 2010", parsed.page.title)
        assertEquals("原页面说明", parsed.page.description)
        assertEquals(listOf("https://movie.example.test/film.mp4", "https://movie.example.test/other.m3u8",
            "https://cdn.example.test/film.m3u8?part=1&mode=hd"), parsed.mediaUrls)
        assertEquals(listOf("https://movie.example.test/player/1", "https://movie.example.test/watch/2"), parsed.page.links.map { it.url })
        assertFalse(parsed.page.text.contains("window.fake"))
    }

    @Test fun jsonLdFindsVideoObjectInsideGraphWithoutTreatingArticleAndImageAsMovies() {
        val parsed = parseVideoPageDocument("""<html><body><script type="application/ld+json">{
          "@graph":[{"@type":"Article","contentUrl":"https://example.test/article.mp4","image":"https://example.test/image.jpg"},
          {"@type":["CreativeWork","https://schema.org/VideoObject"],"name":"真实视频标题 2010",
          "description":"视频说明","contentUrl":"/movie.m3u8","embedUrl":"/player"}]}</script></body></html>""",
            "https://example.test/detail")
        assertEquals("真实视频标题 2010", parsed.page.title)
        assertEquals("视频说明", parsed.page.description)
        assertEquals(listOf("https://example.test/movie.m3u8"), parsed.mediaUrls)
        assertEquals("https://example.test/player", parsed.page.links.single().url)
    }

    @Test fun unsafeUrlsNeverBecomeMediaOrPlayerCandidatesAndListsAreBounded() {
        val bad = listOf("javascript:alert(1)", "file:///private/file.mp4", "http://127.0.0.1/private.mp4",
            "http://localhost/player", "http://192.168.1.2/movie.mp4", "http://[::1]/player",
            "https://user:password@example.test/private.mp4")
        val html = buildString {
            append("<html><title>影片</title><body>")
            bad.forEach { append("<video src='$it'></video><iframe src='$it'></iframe>") }
            repeat(40) { append("<video src='/movie$it.mp4'></video><a href='/play/$it'>播放$it</a>") }
            append("</body></html>")
        }
        val parsed = parseVideoPageDocument(html, "https://example.test/")
        assertEquals(8, parsed.mediaUrls.size)
        assertEquals(30, parsed.page.links.size)
        assertTrue(parsed.mediaUrls.all { it.startsWith("https://example.test/movie") })
        assertTrue(parsed.page.links.all { it.url.startsWith("https://example.test/play/") })
    }

    @Test fun malformedAndDeepJsonLdDoesNotStopActualHtmlVideoDiscovery() {
        val excessiveDepth = "[".repeat(30) + "{}" + "]".repeat(30)
        val parsed = parseVideoPageDocument("""<html><title>有效页面</title><body>
          <script type="application/ld+json">not JSON</script>
          <script type="application/ld+json">$excessiveDepth</script>
          <video src="/real.mp4"></video></body></html>""", "https://example.test/")
        assertEquals(listOf("https://example.test/real.mp4"), parsed.mediaUrls)
    }

    @Test fun successfulReadsAreCachedByCanonicalUrlAndExpireWithBoundedCapacity() = runBlocking {
        val calls = mutableListOf<String>()
        var now = 0L
        val reader = VideoWebPageReader(client { request -> calls += request.url.encodedPath; response(request) },
            maximumEntries = 2, ttlNanos = 100L, nanoTime = { now })
        val first = reader.read("https://example.test/a#one")
        assertSame(first, reader.read("https://example.test/a#two"))
        reader.read("https://example.test/b")
        reader.read("https://example.test/c")
        reader.read("https://example.test/a")
        assertEquals(listOf("/a", "/b", "/c", "/a"), calls)
        now = 101
        reader.read("https://example.test/a")
        assertEquals(5, calls.size)
    }

    @Test fun rejectsNonHtmlAndHttpFailuresWithoutCachingThem() = runBlocking {
        for (type in listOf("application/pdf", "application/json", "video/mp4", "application/vnd.apple.mpegurl")) {
            val reader = VideoWebPageReader(client { request -> response(request, type = type) })
            expectFailure<IOException> { reader.read("https://example.test/movie") }
        }
        val calls = AtomicInteger()
        val reader = VideoWebPageReader(client { request ->
            response(request, status = if (calls.incrementAndGet() == 1) 503 else 200)
        })
        expectFailure<IOException> { reader.read("https://example.test/movie") }
        assertEquals("影片 2010", reader.read("https://example.test/movie").page.title)
        assertEquals(2, calls.get())
        val disguisedJson = VideoWebPageReader(client { request -> response(request, body = """{"title":"not HTML"}""", type = "text/plain") })
        expectFailure<IOException> { disguisedJson.read("https://example.test/movie") }
    }

    @Test fun cancellationCancelsHttpAndDoesNotLeaveACachedPartialPage() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor { chain ->
            if (calls.incrementAndGet() != 1) response(chain.request()) else {
                val body = object : ResponseBody() {
                    private val input = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long { reading.countDown(); release.await(5, TimeUnit.SECONDS); return -1 }
                        override fun timeout(): Timeout = Timeout.NONE
                        override fun close() { release.countDown() }
                    }.buffer()
                    override fun contentType() = "text/html".toMediaType()
                    override fun contentLength(): Long = -1L
                    override fun source(): BufferedSource = input
                }
                response(chain.request()).newBuilder().body(body).build()
            }
        }.build()
        val reader = VideoWebPageReader(http)
        try {
            val job = launch(Dispatchers.Default) { reader.read("https://example.test/slow") }
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            assertTrue(job.isCancelled)
            assertEquals("影片 2010", reader.read("https://example.test/slow").page.title)
            assertEquals(2, calls.get())
        } finally { release.countDown() }
    }

    private fun client(handler: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
    private fun response(request: Request, status: Int = 200, body: String = "<html><title>影片 2010</title><video src='/film.mp4'></video></html>",
                         type: String = "text/html; charset=utf-8") = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(status).message("Fixture").body(body.toResponseBody(type.toMediaType())).build()
    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (failure: Throwable) { if (failure !is T) throw failure }
    }
}
