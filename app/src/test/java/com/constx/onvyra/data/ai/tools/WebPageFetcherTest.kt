package com.constx.onvyra.data.ai.tools

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.Charset
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebPageFetcherTest {
    @Test
    fun `html pages return meaningful text metadata and absolute links`() = runBlocking {
        val fetcher = fetcher {
            response(it, body = """
                <!doctype html><html lang="zh-CN"><head>
                <title>Example &amp; Tools</title><meta name="description" content="A useful website">
                <style>.hidden { display:none }</style></head><body>
                <nav>Unrelated navigation</nav><main><h1>Writing tools</h1>
                <p>Use this website to edit documents &amp; organize notes.</p>
                <a href="/about">About us</a></main>
                <script>SECRET_SCRIPT_CONTENT</script></body></html>
            """.trimIndent())
        }

        val result = fetcher.fetch("https://example.test/home#section")

        assertEquals("https://example.test/home", result.finalUrl)
        assertEquals(200, result.status)
        assertEquals("Example & Tools", result.title)
        assertEquals("A useful website", result.description)
        assertEquals("zh-CN", result.language)
        assertTrue(result.content.contains("edit documents & organize notes"))
        assertFalse(result.content.contains("SECRET_SCRIPT_CONTENT"))
        assertFalse(result.content.contains("Unrelated navigation"))
        assertEquals("https://example.test/about", result.links.single().url)
        assertFalse(result.truncated)
        assertNull(result.error)
        assertTrue(result.retrievedAt.endsWith("Z"))
    }

    @Test
    fun `relative redirects resolve against current page and update final url`() = runBlocking {
        val paths = mutableListOf<String>()
        val result = fetcher { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/old/start") {
                response(request, 302, location = "../guide")
            } else {
                response(request, body = "<main>A readable guide <a href='next'>Next</a></main>")
            }
        }.fetch("https://example.test/old/start")

        assertEquals(listOf("/old/start", "/guide"), paths)
        assertEquals("https://example.test/guide", result.finalUrl)
        assertEquals("https://example.test/next", result.links.single().url)
        assertTrue(result.content.contains("readable guide"))
    }

    @Test
    fun `redirect loops and excessive redirect chains stop requesting`() = runBlocking {
        var calls = 0
        val loop = fetcher { request ->
            calls++
            response(request, 301, location = "/same")
        }
        expectFailure<IllegalStateException> { loop.fetch("https://example.test/same") }
        assertEquals(1, calls)

        calls = 0
        val chain = fetcher { request ->
            calls++
            response(request, 302, location = "/page$calls")
        }
        expectFailure<IllegalStateException> { chain.fetch("https://example.test/start") }
        assertEquals(6, calls)
    }

    @Test
    fun `redirects to a private host or credentialed URL are rejected`() = runBlocking {
        for (destination in listOf("http://127.0.0.1/admin", "https://user:password@example.test/private")) {
            var calls = 0
            val fetcher = fetcher { request ->
                calls++
                response(request, 302, location = destination)
            }
            expectFailure<IllegalArgumentException> { fetcher.fetch("https://example.test/start") }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `http errors and binary files cannot masquerade as page content`() = runBlocking {
        val forbidden = fetcher {
            response(it, 403, body = "<html><main>Access denied; do not treat this as the article</main></html>")
        }.fetch("https://example.test/protected")
        assertEquals(403, forbidden.status)
        assertEquals("", forbidden.content)
        assertNotNull(forbidden.error)
        assertNull(forbidden.title)

        val binary = fetcher {
            response(it, body = "not a PDF parser", type = "application/pdf")
        }.fetch("https://example.test/file.pdf")
        assertEquals("", binary.content)
        assertTrue(binary.error!!.contains("PDF text extraction failed"))
    }

    @Test
    fun `plain text is preserved without interpreting tags and maxChars is honored`() = runBlocking {
        val result = fetcher {
            response(it, body = "<h1>This is source text</h1>\n" + "Long text. ".repeat(400), type = "text/plain; charset=utf-8")
        }.fetch("https://example.test/source", maxChars = 1_000)

        assertTrue(result.content.startsWith("<h1>This is source text</h1>"))
        assertEquals(1_000, result.content.length)
        assertTrue(result.truncated)
        assertTrue(result.links.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun `plain text truncation never splits a surrogate pair`() = runBlocking {
        val result = fetcher {
            response(it, body = "a".repeat(999) + "😀 and more", type = "text/plain; charset=utf-8")
        }.fetch("https://example.test/unicode", maxChars = 1_000)

        assertEquals("a".repeat(999), result.content)
        assertTrue(result.truncated)
    }

    @Test
    fun `html charset metadata and http charset are honored`() = runBlocking {
        val gbHtml = "<html><head><meta charset='gb18030'><title>中文标题</title></head><body><main>中文正文内容</main></body></html>"
        val gb = fetcher { request ->
            response(request, bytes = gbHtml.toByteArray(Charset.forName("GB18030")), type = "text/html")
        }.fetch("https://example.test/chinese")
        assertEquals("中文标题", gb.title)
        assertTrue(gb.content.contains("中文正文内容"))

        val latinHtml = "<html><head><title>Café</title></head><body><main>Café information</main></body></html>"
        val latin = fetcher { request ->
            response(request, bytes = latinHtml.toByteArray(Charsets.ISO_8859_1), type = "text/html; charset=iso-8859-1")
        }.fetch("https://example.test/cafe")
        assertEquals("Café", latin.title)
        assertTrue(latin.content.contains("Café information"))
    }

    @Test
    fun `large responses are bounded before extracting content`() = runBlocking {
        val result = fetcher {
            response(it, body = "a".repeat(2 * 1024 * 1024 + 16_384), type = "text/plain")
        }.fetch("https://example.test/large", maxChars = 24_000)

        assertEquals(24_000, result.content.length)
        assertTrue(result.truncated)
        assertNull(result.error)
    }

    @Test
    fun `invalid schemes local addresses credentials and oversized URLs make no request`() = runBlocking {
        var calls = 0
        val fetcher = fetcher { request ->
            calls++
            response(request)
        }
        for (url in listOf(
            "", "example.test", "file:///etc/passwd", "javascript:alert(1)",
            "http://localhost/", "http://test.local/", "http://127.0.0.1/", "http://192.168.1.2/",
            "http://169.254.169.254/", "http://[::1]/", "http://[fc00::1]/", "http://224.0.0.1/",
            "https://user:password@example.test/", "https://example.test/" + "a".repeat(4_096),
        )) {
            expectFailure<IllegalArgumentException> { fetcher.fetch(url) }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `hostname resolving to private address fails through normal network callback`() = runBlocking {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns { listOf(InetAddress.getByName("127.0.0.1")) }
            .build()

        expectFailure<UnknownHostException> { WebPageFetcher(client).fetch("https://private.example.test/") }
    }

    @Test
    fun `cancelling while reading a response cancels the underlying http call`() = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    canceled.countDown()
                }
            })
            .addInterceptor { chain ->
                val body = object : ResponseBody() {
                    private val source = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            reading.countDown()
                            release.await(5, TimeUnit.SECONDS)
                            return -1
                        }

                        override fun timeout(): Timeout = Timeout.NONE
                        override fun close() {
                            release.countDown()
                        }
                    }.buffer()

                    override fun contentType() = "text/plain".toMediaType()
                    override fun contentLength(): Long = -1
                    override fun source(): BufferedSource = source
                }
                response(chain.request()).newBuilder().body(body).build()
            }.build()

        try {
            val job = launch(Dispatchers.Default) { WebPageFetcher(client).fetch("https://example.test/slow") }
            assertTrue("response reading should begin", reading.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("cancellation must reach the HTTP call", canceled.await(1, TimeUnit.SECONDS))
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
        }
    }

    private fun fetcher(handler: (Request) -> Response): WebPageFetcher = WebPageFetcher(
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain.request()) }.build(),
        pdfParser = WebPdfTextParser { throw java.io.IOException("Invalid PDF fixture") },
    )

    private fun response(
        request: Request,
        status: Int = 200,
        body: String = "<main>Example webpage</main>",
        type: String = "text/html; charset=utf-8",
        location: String? = null,
        bytes: ByteArray? = null,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("Fixture")
        .body(bytes?.toResponseBody(type.toMediaType()) ?: body.toResponseBody(type.toMediaType()))
        .apply { if (location != null) header("Location", location) }
        .build()

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
