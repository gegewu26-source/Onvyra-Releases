package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebFetchEnhancementTest {
    @Test
    fun `continuations reconstruct the snapshot without rerequests gaps or duplicated characters`() = runBlocking {
        val source = (0..4000).joinToString("\n") { "$it: \"quoted\" \\ 😀" }
        var requests = 0
        val fetcher = WebPageFetcher(client(source) { requests++ })
        var result = fetcher.fetch("https://example.test/long", 1001)
        val id = result.pageId!!
        val output = StringBuilder(result.content)
        while (result.nextOffset != null) {
            val expectedOffset = output.length
            result = fetcher.fetch("https://example.test/long", 1001, result.nextOffset!!, id)
            assertEquals(expectedOffset, result.offset)
            output.append(result.content)
        }
        assertEquals(source, output.toString())
        assertEquals(1, requests)
        assertEquals(source.length, result.totalChars)
        assertFalse(result.truncated)
    }

    @Test
    fun `escaped tool payload continuation starts after the actual returned text`() = runBlocking {
        val source = ("\"\\😀".repeat(12000))
        val tool = createWebFetchTool(WebPageFetcher(client(source)))
        var offset = 0
        var id: String? = null
        val output = StringBuilder()
        do {
            val parts = tool.execute(buildJsonObject {
                put("url", "https://example.test/long")
                put("maxChars", 24000)
                put("offset", offset)
                id?.let { put("pageId", it) }
            })
            val payload = (parts.single() as UIMessagePart.Text).text
            assertTrue(payload.length <= 30000)
            val result = Json.parseToJsonElement(payload).jsonObject
            val text = result.getValue("content").jsonPrimitive.content
            assertFalse(text.lastOrNull()?.isHighSurrogate() == true)
            output.append(text)
            id = result.getValue("pageId").jsonPrimitive.content
            offset = result["nextOffset"]?.jsonPrimitive?.intOrNull ?: -1
            if (offset >= 0) assertEquals(output.length, offset)
        } while (offset >= 0)
        assertEquals(source, output.toString())
    }

    @Test
    fun `continuation tokens cannot silently fetch a different document or invalid boundary`() = runBlocking {
        val fetcher = WebPageFetcher(client("abc😀more"))
        val result = fetcher.fetch("https://example.test/a", 3)
        for ((url, offset, id) in listOf(
            Triple("https://example.test/b", 3, result.pageId),
            Triple("https://example.test/a", 3, "expired"),
            Triple("https://example.test/a", 3, null),
            Triple("https://example.test/a", 4, result.pageId),
            Triple("https://example.test/a", -1, result.pageId),
            Triple("https://example.test/a", 999, result.pageId),
        )) {
            try { fetcher.fetch(url, 5, offset, id); fail("expected invalid continuation") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun `PDF MIME and magic route through bounded parser with honest source truncation`() = runBlocking {
        var parsed = 0
        val parser = WebPdfTextParser { bytes ->
            assertTrue(bytes.toString(Charsets.UTF_8).startsWith("%PDF-"))
            parsed++
            WebPdfText("PDF text ".repeat(3000), 123, true)
        }
        for (type in listOf("application/pdf", "application/octet-stream")) {
            val result = WebPageFetcher(client("%PDF-fixture", type), pdfParser = parser)
                .fetch("https://example.test/file.pdf", 1000)
            assertEquals(123, result.pageCount)
            assertEquals(1000, result.content.length)
            assertTrue(result.sourceTruncated)
            assertNotNull(result.nextOffset)
        }
        assertEquals(2, parsed)
        val oversized = WebPageFetcher(client("%PDF-" + "a".repeat(8 * 1024 * 1024), "application/pdf"), pdfParser = parser)
            .fetch("https://example.test/file.pdf")
        assertTrue(oversized.error!!.contains("exceeds"))
        assertEquals(2, parsed)
    }

    @Test
    fun `PDF parser errors and scans cannot masquerade as successful document text`() = runBlocking {
        val invalid = WebPageFetcher(client("%PDF-fixture", "application/pdf"), pdfParser = WebPdfTextParser { throw IOException("Encrypted PDF") })
            .fetch("https://example.test/a.pdf")
        assertEquals("", invalid.content)
        assertTrue(invalid.error!!.contains("Encrypted PDF"))
        val scanned = WebPageFetcher(client("%PDF-fixture", "application/pdf"), pdfParser = WebPdfTextParser { WebPdfText("", 3, false) })
            .fetch("https://example.test/a.pdf")
        assertTrue(scanned.error!!.contains("OCR"))
        assertEquals(3, scanned.pageCount)
    }

    @Test
    fun `loading shells use renderer but readable static articles and challenges do not`() = runBlocking {
        var renders = 0
        val renderer = DynamicWebPageRenderer { _, url, _ ->
            renders++
            RenderedWebPage(extractWebPageContent("<main>Actual JavaScript article</main>", url))
        }
        val shell = WebPageFetcher(client("<div id='root'></div><script src='/app.js'></script>", "text/html"), renderer)
            .fetch("https://example.test/app")
        assertTrue(shell.rendered)
        assertEquals("Actual JavaScript article", shell.content)
        val article = WebPageFetcher(client("<main>A readable article.</main><script src='/app.js'></script>", "text/html"), renderer)
            .fetch("https://example.test/article")
        assertFalse(article.rendered)
        val intro = WebPageFetcher(client("<h1>Tools</h1><p>A useful notes website.</p><script src='/analytics.js'></script>", "text/html"), renderer)
            .fetch("https://example.test/intro")
        assertFalse("A short static introduction is useful without JavaScript.", intro.rendered)
        assertTrue(intro.content.contains("useful notes"))
        val blocked = WebPageFetcher(client("<title>Just a moment...</title><script src='/app.js'></script>", "text/html"), renderer)
            .fetch("https://example.test/challenge")
        assertNotNull(blocked.error)
        assertEquals(1, renders)
    }

    @Test
    fun `cancellation reaches a PDF parser and a pending dynamic renderer`() = runBlocking {
        for (pdf in listOf(false, true)) {
            val started = CountDownLatch(1)
            val released = CountDownLatch(1)
            val parser = WebPdfTextParser {
                try { started.countDown(); awaitCancellation() } finally { released.countDown() }
            }
            val renderer = DynamicWebPageRenderer { _, _, _ ->
                try { started.countDown(); awaitCancellation() } finally { released.countDown() }
            }
            val fetcher = WebPageFetcher(client(if (pdf) "%PDF-test" else "<div id='root'></div>", if (pdf) "application/pdf" else "text/html"), renderer, parser)
            val job = launch(Dispatchers.Default) { fetcher.fetch("https://example.test/a") }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(released.await(1, TimeUnit.SECONDS))
        }
    }

    private fun client(body: String, type: String = "text/plain", requested: () -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            requested()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()
}
