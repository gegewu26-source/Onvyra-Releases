package me.rerere.search

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BingSearchServiceTest {
    private val previousClient = SearchService.httpClient

    @After fun restoreClient() { SearchService.httpClient = previousClient }

    @Test fun configuredTransportReceivesEncodedQueryAndResultsRespectTheRequestedLimit() = runBlocking {
        var observed: Request? = null
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (observed == null) observed = chain.request()
            response(chain.request(), html = result("First", "https://video.example/first") +
                result("Second", "https://video.example/second"))
        }.build()

        val found = search("电影 & a+b", 1).getOrThrow()

        assertEquals("电影 & a+b", observed!!.url.queryParameter("q"))
        assertEquals("www.bing.com", observed!!.url.host)
        assertNull(observed!!.header("Authorization"))
        assertEquals("SRCHHPGUSR=ULSR=1", observed!!.header("Cookie"))
        assertEquals(listOf("https://video.example/first"), found.items.map { it.url })
    }

    @Test fun encodedRedirectsResolveToActualPagesWhileBadOrCredentialedLinksAreDiscarded() {
        val target = "https://video.example/watch?q=中文&part=1"
        val wrapped = "/ck/a?!&&u=a1${target.encodeUtf8().base64Url()}&ntb=1"
        val credentialed = "/ck/a?u=a1${"https://name:secret@video.example/private".encodeUtf8().base64Url()}"
        val html = result("Correct", wrapped) + result("Duplicate", target) +
            result("Broken", "/ck/a?u=a1!") + result("Missing", "/ck/a?p=abc") +
            result("Search page", "/search?q=film") + result("Credentials", credentialed) +
            result("Unsafe protocol", "javascript:alert(1)")

        val found = parseBingSearchResults(html, "https://www.bing.com/search?q=film".toHttpUrl(), 10)

        assertEquals(1, found.size)
        assertEquals("Correct", found.single().title)
        assertEquals(target.toHttpUrl().toString(), found.single().url)
    }

    @Test fun captchaAndInformationOnlyResponsesAreFailuresRatherThanFabricatedResults() = runBlocking {
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), html = "<div id='b_captcha'>Verify</div>" + result("Noise", "https://video.example/a"))
        }.build()
        assertTrue(search().isFailure)
        assertTrue(parseBingSearchResults("<main>No results</main>", "https://www.bing.com/".toHttpUrl(), 10).isEmpty())
    }

    @Test fun oversizedUnknownLengthResponsesAreBoundedAndClosed() = runBlocking {
        val closed = AtomicBoolean()
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), body = unknownBody("x".repeat(2 * 1024 * 1024 + 64), closed))
        }.build()

        val failure = search().exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("size limit"))
        assertTrue("The rejected response was left open", closed.get())
    }

    @Test fun httpErrorsDoNotExposeTheResponseBodyAndCloseIt() = runBlocking {
        val closed = AtomicBoolean()
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 403, body = unknownBody("private=must-not-be-returned", closed))
        }.build()

        val failure = search().exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("403"))
        assertFalse(failure.message.orEmpty().contains("private"))
        assertTrue(closed.get())
    }

    @Test fun cancellingTheAwaitingCoroutineActuallyCancelsTheHttpCall() = runBlocking {
        val call = WaitingCall()
        val job = launch { call.await() }
        yield()
        assertTrue(call.isExecuted())

        job.cancelAndJoin()

        assertTrue("Cancellation abandoned the HTTP call", call.isCanceled())
        assertTrue(job.isCancelled)
    }

    private suspend fun search(query: String = "film", size: Int = 6): Result<SearchResult> =
        BingSearchService.search(buildJsonObject { put("query", query) }, SearchCommonOptions(size), SearchServiceOptions.BingLocalOptions())

    private fun result(title: String, url: String) =
        "<li class='b_algo'><h2><a href='${url.replace("&", "&amp;")}'>$title</a></h2><div class='b_caption'><p>Snippet</p></div></li>"

    private fun response(request: Request, code: Int = 200, html: String = "", body: ResponseBody? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body ?: html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()

    private fun unknownBody(content: String, closed: AtomicBoolean): ResponseBody {
        val source = object : ForwardingSource(Buffer().writeUtf8(content)) {
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = "text/html; charset=utf-8".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
    }

    private class WaitingCall(
        private val delegate: Call = OkHttpClient().newCall(
            Request.Builder().url("https://www.bing.com/search?q=fixture").build()),
    ) : Call by delegate {
        private var executed = false
        private var cancelled = false
        override fun request() = Request.Builder().url("https://www.bing.com/search?q=fixture").build()
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) { executed = true }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = WaitingCall()
    }
}
