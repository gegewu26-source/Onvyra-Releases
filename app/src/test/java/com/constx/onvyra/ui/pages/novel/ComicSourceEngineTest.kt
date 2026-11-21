package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicSourceEngineTest {
    private val source = ComicSourceEngine.SOURCES.first { it.id == "dmmh" }
    private val origin = source.baseUrl
    private val detail = "$origin/FANTASY/fixture/list?title_no=1804"

    private fun episode(number: Int, title: String = "真实标题 $number") = """
        <li data-episode-no="$number"><a href="/FANTASY/fixture/episode/viewer?title_no=1804&amp;episode_no=$number">
            <span class="subj"><span>$title</span></span><img src="/episode-thumb-$number.jpg">
        </a></li>
    """.trimIndent()

    private fun page(numbers: List<Int>, pagination: String = "") = """
        <html><title>作品目录</title><ul id="_listUl">${numbers.joinToString("") { episode(it) }}</ul>
        <div class="paginate">$pagination</div></html>
    """.trimIndent()

    private fun searchHtml() = """
        <html><div class="card_wrap search"><h3 class="search_result">相关结果为1</h3><ul class="card_lst">
        <li><a href="/episodeList?titleNo=1804"><img src="//cdn.dongmanmanhua.cn/real-cover.jpg">
        <p class="subj">非人哉</p><p class="author">一汪空气</p><span class="genre">奇幻</span></a></li>
        </ul></div><div class="ranking_lst"><a href="/episodeList?titleNo=999"><p class="subj">推荐广告作品</p></a></div></html>
    """.trimIndent()

    @Test
    fun `complete ascending catalog is cached and explicit refresh invalidates it`() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(page(listOf(3, 2, 1)).toResponseBody("text/html".toMediaType())).build()
        }.build()
        val engine = NovelSourceEngine(client)
        val progress = mutableListOf<List<Int>>()
        val chapters = engine.getComicCatalog(detail, "dmmh") { progress += it.map { chapter -> chapter.index } }
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        engine.getComicCatalog("$origin/episodeList?titleNo=1804", "dmmh") {
            progress += it.map { chapter -> chapter.index }
        }
        assertEquals(1, requests)
        assertEquals(listOf(listOf(0, 1, 2), listOf(0, 1, 2)), progress)
        engine.invalidateCatalog(detail, "dmmh")
        engine.getComicCatalog(detail, "dmmh")
        assertEquals(2, requests)
    }

    @Test
    fun `leaving reader cancels an in flight comic network call promptly`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Boolean>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(Unit)
            val limit = System.nanoTime() + 5_000_000_000L
            while (!chain.call().isCanceled() && System.nanoTime() < limit) Thread.sleep(5)
            finished.complete(chain.call().isCanceled())
            throw java.io.IOException("fixture cancelled")
        }.build()
        val job = launch(Dispatchers.Default) { ComicSourceEngine(client).getComicCatalog(detail, "dmmh") }
        withTimeout(2000) { started.await() }
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(withTimeout(2000) { finished.await() })
    }

    @Test
    fun `search keeps real IDs URLs author cover and referer`() {
        val pageUrl = "$origin/search?keyword=fixture"
        val books = ComicHtmlParser.search(searchHtml(), pageUrl, source)
        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("dmmh_1804", book.id)
        assertEquals("非人哉", book.title)
        assertEquals("一汪空气", book.author)
        assertEquals("$origin/episodeList?titleNo=1804", book.detailUrl)
        assertEquals("https://cdn.dongmanmanhua.cn/real-cover.jpg", book.coverUrl)
        assertEquals(pageUrl, book.coverReferer)
        assertTrue(book.isComic)
    }

    @Test
    fun `zero search results never become unrelated recommendations`() {
        val html = """<div class="card_wrap search"><span class="ico_nosearch"></span></div>
            <div class="ranking_lst"><a href="/episodeList?titleNo=999"><p class="subj">推荐作品</p></a></div>"""
        assertTrue(ComicHtmlParser.search(html, "$origin/search?keyword=nothing", source).isEmpty())
    }

    @Test
    fun `discover returns actual page cards and deduplicates repeated title IDs`() {
        val html = searchHtml() + searchHtml()
        val books = ComicHtmlParser.discover(html, "$origin/", source)
        assertEquals(listOf("dmmh_1804", "dmmh_999"), books.map { it.id })
    }

    @Test
    fun `catalog chooses immediate next page instead of skipping pagination groups`() {
        val links = """<a href="#"><span class="on">1</span></a>
            <a href="?title_no=1804&amp;page=2">2</a>
            <a href="?title_no=1804&amp;page=3">3</a>
            <a class="pg_next" href="?title_no=1804&amp;page=11">下一页</a>"""
        val parsed = ComicHtmlParser.catalog(page(listOf(3, 2), links), "$detail&page=1", source)
        assertEquals(listOf(3, 2), parsed.chapters.map { it.index })
        assertEquals("$detail&page=2", parsed.nextUrl)
        assertEquals("真实标题 3", parsed.chapters.first().title)
    }

    @Test
    fun `chapter parser excludes transparent placeholders advertisements and episode thumbnails`() {
        val html = """<img src="https://cdn.example/ad.jpg">
            <div class="episode_lst"><img class="_thumbnailImages" data-url="https://cdn.example/thumbnail.jpg"></div>
            <div id="_imageList">
              <img class="_images _centerImg" src="https://cdn.example/transparent.png" data-url="https://cdn.dongmanmanhua.cn/page-one.jpg">
              <img class="_images" data-url="https://cdn.dongmanmanhua.cn/page-two.jpg?quality=90&amp;type=full">
            </div>"""
        assertEquals(listOf("https://cdn.dongmanmanhua.cn/page-one.jpg", "https://cdn.dongmanmanhua.cn/page-two.jpg?quality=90&type=full"),
            ComicHtmlParser.chapterPages(html, "$origin/FANTASY/fixture/viewer?title_no=1804&episode_no=1"))
    }

    @Test
    fun `paywall captcha and changed markup raise explicit errors without placeholders`() {
        for (html in listOf("<html>请登录购买本章节<img src='/logo.jpg'></html>",
            "<title>Just a moment...</title><form id='challenge-form'></form>")) {
            try {
                ComicHtmlParser.chapterPages(html, "$detail&episode_no=1")
                throw AssertionError("Expected an explicit unavailable error")
            } catch (expected: ComicSourceException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun `unknown or unimplemented sources do not silently query another source`() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            throw AssertionError("No request should occur")
        }.build()
        val engine = ComicSourceEngine(client)
        for (id in listOf("kbmh", "dmzj", "made-up-source")) {
            try {
                engine.searchComics("真实搜索", id)
                throw AssertionError("Unsupported source must fail")
            } catch (expected: ComicSourceException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
        assertEquals(0, requests)
        assertEquals(listOf("dmmh"), ComicSourceEngine.SOURCES.filter { it.enable }.map { it.id })
    }

    @Test
    fun `catalog fetches all pages deduplicates overlaps and restores reading order`() = runBlocking {
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            requested += url.toString()
            val body = when (url.queryParameter("page")) {
                "1" -> page(listOf(5, 4), "<a href='?title_no=1804&amp;page=2'>2</a>")
                "2" -> page(listOf(4, 3, 2), "<a href='?title_no=1804&amp;page=3'>3</a>")
                "3" -> page(listOf(1))
                else -> throw AssertionError("Unexpected catalog URL: $url")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val chapters = ComicSourceEngine(client).getComicCatalog(detail, "dmmh")
        assertEquals(3, requested.size)
        assertEquals(listOf(0, 1, 2, 3, 4), chapters.map { it.index })
        assertEquals(listOf(1, 2, 3, 4, 5), chapters.map { it.url.substringAfter("episode_no=").toInt() })
        assertEquals("真实标题 1", chapters.first().title)
        assertEquals("真实标题 5", chapters.last().title)
    }

    @Test
    fun `repeated upstream page is an incomplete-catalog error`() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val next = if (requests == 1) "2" else "3"
            val body = page(listOf(3, 2), "<a href='?title_no=1804&amp;page=$next'>next</a>")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        try {
            ComicSourceEngine(client).getComicCatalog(detail, "dmmh")
            throw AssertionError("Duplicate-only pagination must not return partial success")
        } catch (expected: ComicSourceException) {
            assertTrue(expected.message.orEmpty().contains("重复"))
        }
        assertEquals(2, requests)
    }

    @Test
    fun `source URL validation rejects unrelated hosts credentials and nonstandard ports`() {
        for (url in listOf("https://example.org/list?title_no=1", "https://www.dongmanmanhua.cn:8443/list?title_no=1",
            "http://www.dongmanmanhua.cn/list?title_no=1", "https://user:pass@www.dongmanmanhua.cn/list?title_no=1")) {
            try {
                ComicHtmlParser.validateUrl(url, source)
                throw AssertionError("Invalid source URL was accepted")
            } catch (_: ComicSourceException) {
                // Expected: URLs never migrate a title to another provider.
            }
        }
        assertFalse(source.baseUrl.contains("example"))
    }
}
