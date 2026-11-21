package com.constx.onvyra.ui.pages.novel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** A metadata race may be won only by an independently proven readable source. */
class ReaderCatalogReadinessTest {
    @Test fun fastExactMatchesWith404OrLoginBodyCannotBeatTheSlowerHealthySource() = runBlocking {
        val releaseHealthy = CountDownLatch(1)
        val brokenChecked = CountDownLatch(2)
        val requests = CopyOnWriteArrayList<String>()
        val hosts = listOf("catalog404.fixture.test", "login.fixture.test", "healthy.fixture.test")
        val engine = fixture(hosts.map { it to it }, requests) { host, path ->
            if (host == "healthy.fixture.test" && path == "/search") releaseHealthy.await(5, TimeUnit.SECONDS)
            if (host == "catalog404.fixture.test" && path == "/toc") { brokenChecked.countDown(); 404 to "" }
            else if (host == "login.fixture.test" && path == "/chapter") {
                brokenChecked.countDown(); 200 to "<div id='content'>请登录后阅读。</div>"
            } else null
        }
        val result = async { engine.resolveCatalogBook(wanted()) }
        try {
            withTimeout(2_000) { while (brokenChecked.count != 0L) delay(5) }
            assertFalse("Metadata matches with unusable catalogs/bodies must not win", result.isCompleted)
            releaseHealthy.countDown()
            val readable = withTimeout(2_000) { result.await() }
            assertEquals("healthy.fixture.test", readable.detailUrl.substringAfter("https://").substringBefore('/'))
            val before = requests.size
            assertEquals(readable, engine.resolveCatalogBook(wanted()))
            engine.getBookDetail(readable)
            val chapters = engine.getCatalog(readable.detailUrl, readable.sourceId)
            assertTrue(engine.getChapterContent(chapters.first().url, readable.sourceId).contains("晨光"))
            assertEquals("Verified detail, catalog and first body must be reused by opening the reader", before, requests.size)
        } finally { releaseHealthy.countDown() }
    }

    @Test fun sixHighPriorityAliasesOfTwoSlowHostsCannotOccupyAllCatalogWorkersAheadOfAThirdHost() = runBlocking {
        val releaseSlow = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        val sources = (0 until 4).map { "rulianshi.fixture.test" to "slow-a-$it" } +
            (0 until 2).map { "yetianlian.fixture.test" to "slow-b-$it" } + listOf("healthy.fixture.test" to "healthy")
        val engine = fixture(sources, requests) { host, path ->
            if (host != "healthy.fixture.test" && path == "/search") {
                releaseSlow.await(5, TimeUnit.SECONDS)
                200 to ""
            } else null
        }
        try {
            val readable = withTimeout(2_000) { engine.resolveCatalogBook(wanted()) }
            assertTrue(readable.detailUrl.startsWith("https://healthy.fixture.test/"))
            assertEquals("Different hosts must run before more aliases of a slow host", 1L, releaseSlow.count)
        } finally { releaseSlow.countDown() }
    }

    private fun wanted() = NovelBook("catalog-readiness", "查源核验作品", "真实作者",
        detailUrl = "https://catalog.fixture.test/book/1", sourceId = NovelSourceEngine.SOURCE_CATALOG)

    private fun fixture(sources: List<Pair<String, String>>, requests: MutableList<String>,
        responseOverride: (String, String) -> Pair<Int, String>?): NovelSourceEngine {
        val library = ReaderSourceLibrary { JsonArray(sources.map { (host, name) -> buildJsonObject {
            put("bookSourceName", name); put("bookSourceUrl", "https://$host")
            put("bookSourceType", 0); put("enabled", true); put("searchUrl", "/search?q={{key}}")
            putJsonObject("ruleSearch") { put("bookList", ".book"); put("name", "a@text"); put("author", "b@text"); put("bookUrl", "a@href") }
            putJsonObject("ruleBookInfo") { put("name", "h1@text"); put("author", ".writer@text"); put("tocUrl", ".toc@href") }
            putJsonObject("ruleToc") { put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href") }
            putJsonObject("ruleContent") { put("content", "#content@html") }
        } }).toString() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val host = request.url.host; val path = request.url.encodedPath
            requests += "$host$path"
            val supplied = responseOverride(host, path)
            val html = supplied?.second ?: when (path) {
                "/search" -> "<div class='book'><a href='/book'>查源核验作品</a><b>真实作者</b></div>"
                "/book" -> "<h1>查源核验作品</h1><b class='writer'>真实作者</b><a class='toc' href='/toc'>目录</a>"
                "/toc" -> "<div class='chapters'><a href='/chapter'>第一章 初见</a></div>"
                "/chapter" -> "<div id='content'>晨光穿过窗帘，他翻开书桌上的旧信，开始了新的旅程。</div>"
                else -> error("Unexpected readiness fixture path: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(supplied?.first ?: 200).message("fixture")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        return NovelSourceEngine(client, sourceLibrary = library)
    }
}
