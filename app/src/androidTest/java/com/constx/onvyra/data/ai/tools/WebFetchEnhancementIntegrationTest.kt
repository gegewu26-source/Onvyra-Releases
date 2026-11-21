package com.constx.onvyra.data.ai.tools

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class WebFetchEnhancementIntegrationTest {
    @Test
    fun offlineRendererExecutesInlineAndExternalScriptsAndReadsDynamicGetWithoutBrowserCookies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requests = CopyOnWriteArrayList<okhttp3.Request>()
        // This cookie must remain invisible to both the page and all intercepted resource requests.
        val cookies = CookieManager.getInstance()
        cookies.setCookie("https://public.example.test", "reader_test_secret=not-for-fetch")
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val (type, body) = when (request.url.encodedPath) {
                    "/app" -> "text/html" to """
                        <html><head><title>Dynamic fixture</title><style>.private{display:none}</style></head>
                        <body><main id="root"></main><p class="private">HIDDEN_CSS_TEXT</p>
                        <script>window.cookieSnapshot = document.cookie;</script><script src="/reader.js"></script></body></html>
                    """.trimIndent()
                    "/reader.js" -> "application/javascript" to """
                        fetch('https://public.example.test/data.json').then(r=>r.json()).then(data=>{
                          document.getElementById('root').innerHTML='<h1>'+data.title+'</h1><p>'+data.text+'</p><p>cookie='+window.cookieSnapshot+'</p>';
                        });
                    """.trimIndent()
                    "/data.json" -> "application/json" to """{"title":"Live page title","text":"Rendered article loaded through a validated GET."}"""
                    else -> "text/plain" to "unexpected resource"
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(body.toResponseBody(type.toMediaType())).header("Set-Cookie", "must_not_be_saved=1").build()
            }.build()
            try {
                val result = WebPageFetcher(client, AndroidDynamicWebPageRenderer(context)).fetch("https://public.example.test/app")
                assertNull("reader error: ${result.error}, warning: ${result.warning}", result.error)
                assertTrue("rendered text: ${result.content}; warning: ${result.warning}", result.rendered)
                assertTrue("actual content=${result.content}; requests=${requests.map { it.url.encodedPath }}; warning=${result.warning}", result.content.contains("Rendered article loaded through a validated GET."))
                assertFalse(result.content.contains("HIDDEN_CSS_TEXT"))
                assertFalse(result.content.contains("reader_test_secret"))
                assertTrue(requests.any { it.url.encodedPath == "/reader.js" })
                assertTrue(requests.any { it.url.encodedPath == "/data.json" })
                requests.forEach { assertNull(it.header("Cookie")); assertNull(it.header("Authorization")) }
                assertFalse(cookies.getCookie("https://public.example.test").orEmpty().contains("must_not_be_saved"))
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        } finally {
            cookies.setCookie("https://public.example.test", "reader_test_secret=; Max-Age=0")
        }
    }

    @Test
    fun nativePdfParserReadsActualPdfAndReportsEmptyScannedPage() = runBlocking {
        val text = NativeWebPdfTextParser.parse(pdfFixture("PDF fixture selectable text."))
        assertEquals(1, text.pageCount)
        assertTrue(text.text.contains("PDF fixture selectable text."))
        assertFalse(text.truncated)
        val empty = NativeWebPdfTextParser.parse(pdfFixture(""))
        assertEquals("", empty.text)
        try {
            NativeWebPdfTextParser.parse("not a pdf".toByteArray())
            fail("invalid PDF must fail")
        } catch (_: Exception) { }
    }

    private fun pdfFixture(text: String): ByteArray {
        val stream = "BT /F1 12 Tf 40 150 Td ($text) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val result = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, content ->
            val offset = result.length
            result.append("${index + 1} 0 obj\n$content\nendobj\n")
            offset
        }
        val xref = result.length
        result.append("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { result.append("%010d 00000 n \n".format(it)) }
        result.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return result.toString().toByteArray(Charsets.US_ASCII)
    }
}
