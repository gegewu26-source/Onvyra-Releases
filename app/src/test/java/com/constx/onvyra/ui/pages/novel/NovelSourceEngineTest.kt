package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NovelSourceEngineTest {
    private fun engine(reply: (String) -> Pair<Int, String>) = NovelSourceEngine(OkHttpClient.Builder()
        .addInterceptor { chain ->
            val (status, text) = reply(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(text.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build())

    @Test fun actualSearchJsonFieldsPreserveRealCoverAndMetadata() = runBlocking {
        val books = engine { 200 to """{"code":200,"data":{"list":[{"id":49936,"title":"验证作品","author":"作者","imgUrl":"https://cdn.example.test/actual.jpg","des":"实际简介","update_content":"尾章","update_time":"2026-01-01"}]}}""" }
            .search("验证", "bqgnovels")
        assertEquals(1, books.size)
        assertEquals("https://cdn.example.test/actual.jpg", books.single().coverUrl)
        assertEquals("https://www.bqgnovels.com/book/49936", books.single().detailUrl)
        assertEquals("实际简介", books.single().description)
        assertEquals("尾章", books.single().latestChapter)
        assertEquals("bqgnovels", books.single().sourceId)
    }

    @Test fun htmlSearchKeepsRelatedTitlesReturnedByTheSourceWithoutKeepingUnrelatedPromotions() = runBlocking {
        val books = engine { 200 to """
            <ul>
              <li><a href='/190/190260/'>我有一群地球玩家</a><span class='author'>真实作者</span></li>
              <li><a href='/190/190261/'>我有一群地球人</a><span class='author'>另一作者</span></li>
              <li><a href='/190/190262/'>广告推广</a><span class='author'>推广用户</span></li>
            </ul>
        """ }.search("我有一群地球人", NovelSourceEngine.SOURCE_PIQUGE)
        assertEquals(setOf("我有一群地球人", "我有一群地球玩家"), books.map { it.title }.toSet())
        assertTrue(books.all { it.sourceId == NovelSourceEngine.SOURCE_PIQUGE })
    }

    @Test fun directoryCollectsEveryPageAndReindexesDistinctChapters() = runBlocking {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val engine = engine { url ->
            requested += url
            val page = Regex("/190/190260/(\\d+)/").find(url)?.groupValues?.get(1)?.toInt() ?: 1
            val chapters = ((page - 1) * 100 + 1..page * 100).joinToString("") {
                "<dd><a href='/190/190260/$it.html'>第${it}章</a></dd>"
            }
            200 to "<div class='chapterlist'><div class='all'><dl>$chapters</dl></div></div>" +
                if (page < 3) "<a href='/190/190260/${page + 1}/'>下一页</a>" else ""
        }
        val chapters = engine.getCatalog("https://www.piquge.com/190/190260/", "piquge")
        assertEquals(300, chapters.size)
        assertEquals(300, chapters.map { it.url }.distinct().size)
        assertEquals(299, chapters.last().index)
        assertEquals("第300章", chapters.last().title)
        assertEquals(3, requested.size)
    }

    @Test fun chapterReadsContinuationButDoesNotConsumeNextChapter() = runBlocking {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val engine = engine { url ->
            requested += url
            200 to if (url.endsWith("10.html")) "<div id='content'>第一页正文</div><a href='10_2.html'>下一页</a>"
            else "<div id='content'>第二页正文</div><a rel='next' href='11.html'>下一章</a>"
        }
        val text = engine.getChapterContent("https://www.piquge.com/190/190260/10.html", "piquge")
        assertTrue(text.contains("第一页正文") && text.contains("第二页正文"))
        assertEquals(2, requested.size)
        assertFalse(requested.any { it.endsWith("11.html") })
    }

    @Test fun piqugeRecentUpdatesAndFirstPageAliasDoNotCorruptFullCatalog() = runBlocking {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val source = engine { url ->
            requested += url
            val page = Regex("/190/190260/(\\d+)/").find(url)?.groupValues?.get(1)?.toInt() ?: 1
            val last = minOf(page * 100, 230)
            val chapters = ((page - 1) * 100 + 1..last).joinToString("") {
                if (page == 1) "<li><a href='/190/190260/$it.html'>真实第${it}章</a></li>"
                else "<li><a href='javascript:;' onclick=\"location.href='/190/190260/$it.html'\">真实第${it}章</a></li>"
            }
            val latest = if (page == 1) """<div class='last'><h4>最新章节【共12章】</h4><ul><li><a href='/190/190260/230.html'>最近更新230</a></li><li><a href='/190/190260/229.html'>最近更新229</a></li></ul></div>""" else ""
            val navigation = if (page == 1) "<a href='/190/190260/2/'>下一页</a>"
                else """<div class='listpage'><a href='/190/190260/${page - 1}/'>上一页</a><select><option value='/190/190260/1/'>1-100</option><option value='/190/190260/2/'>101-200</option><option value='/190/190260/3/'>201-230</option></select></div>"""
            200 to "<div class='chapterlist'>$latest<div class='all'><h4>全部章节列表【第${(page - 1) * 100 + 1}-$last 章 共230章】</h4><ul>$chapters</ul>$navigation</div></div>"
        }
        val chapters = source.getCatalog("https://www.piquge.com/190/190260/", "piquge")
        assertEquals(230, chapters.size)
        assertEquals("真实第1章", chapters.first().title)
        assertEquals("真实第230章", chapters.last().title)
        assertEquals((1..230).map { "https://www.piquge.com/190/190260/$it.html" }, chapters.map { it.url })
        assertEquals(3, requested.size)
        assertFalse(requested.any { it.endsWith("/1/") })
    }

    @Test fun bqgnovelsReadsSiblingVuetifyCatalogAndEverySeoNextPage() = runBlocking {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val source = engine { url ->
            requested += url
            val page = Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
            val last = minOf(page * 100, 253)
            val rows = ((page - 1) * 100 + 1..last).joinToString("") {
                "<a class='list_item v-list-item' href='/book/49936/$it'><div class='v-list-item__title'>来源章节$it</div></a>"
            }
            val next = if (page < 3) "<div class='seo_page'><a href='/book/49936?sort=0&amp;page=${page + 1}'>下一页</a></div>" else ""
            200 to """<aside><div class='v-list'><a href='/book/7'>推荐作品</a></div></aside><main><div class='chapter'><h3>章节列表</h3></div><div class='v-card'><div role='list' class='v-list list v-sheet'>$rows</div></div>$next</main>"""
        }
        val chapters = source.getCatalog("https://www.bqgnovels.com/book/49936", "bqgnovels")
        assertEquals(253, chapters.size)
        assertEquals("来源章节1", chapters.first().title)
        assertEquals("来源章节253", chapters.last().title)
        assertEquals(3, requested.size)
        assertTrue(requested.all { it.contains("sort=0") })
    }

    @Test fun bqgnovelsExtractsOnlyTextAfterChapterHeadingAndNotNavigation() = runBlocking {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val source = engine { url ->
            requested += url
            200 to """<div class='text'>网页装饰文字</div><main><h1 class='text_tit'>真实末章</h1><div class='next'><a href='/book/49936/1452'>上一章</a></div><div class='text'><!--go-->第一段真实正文<br><br>第二段真实正文<!--over--></div><br><div class='next'><a href='/book/49936/1452'>上一章</a></div></main>"""
        }
        val content = source.getChapterContent("https://www.bqgnovels.com/book/49936/1453", "bqgnovels")
        assertTrue(content.contains("第一段真实正文") && content.contains("第二段真实正文"))
        assertFalse(content.contains("网页装饰") || content.contains("上一章") || content.contains("真实末章"))
        assertEquals(1, requested.size)
    }

    @Test fun declaredCatalogTotalRejectsMissingLastPage() = runBlocking {
        val source = engine {
            200 to """<div class='chapterlist'><div class='all'><h4>全部章节列表【第1-2章 共3章】</h4><a href='/190/190260/1.html'>第一章</a><a href='/190/190260/2.html'>第二章</a></div></div>"""
        }
        try {
            source.getCatalog("https://www.piquge.com/190/190260/", "piquge")
            fail("An incomplete catalog must not be returned")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("目录不完整"))
        }
    }

    @Test fun repeatedDifferentCatalogPageStillFailsInsteadOfBeingSilentlySkipped() = runBlocking {
        val source = engine { url ->
            val next = if (url.endsWith("/190260/")) "<a href='/190/190260/2/'>下一页</a>" else ""
            200 to "<div class='chapterlist'><div class='all'><a href='/190/190260/1.html'>第一章</a>$next</div></div>"
        }
        try {
            source.getCatalog("https://www.piquge.com/190/190260/", "piquge")
            fail("A different page repeating all chapters must be rejected")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("目录分页重复"))
        }
    }

    @Test fun sourceSelectionCannotBeIgnoredForAnUnrelatedUrl() = runBlocking {
        var hits = 0
        val engine = engine { hits++; 200 to "" }
        try {
            engine.search("https://www.piquge.com/190/190260/", "bqgnovels")
            fail("Mismatched source must be rejected")
        } catch (_: IOException) { }
        assertEquals(0, hits)
    }

    @Test fun httpFailureIsAnErrorRatherThanFakeProse() = runBlocking {
        val engine = engine { 503 to "unavailable" }
        try {
            engine.getChapterContent("https://www.piquge.com/190/190260/10.html", "piquge")
            fail("An HTTP error must not become chapter text")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("503")) }
    }

    @Test fun cleanupPreservesOrdinaryWordsInsteadOfDeletingPublisherNamesAnywhere() {
        val text = engine { 200 to "" }.cleanNovelText("<p>这里是故事的起点，纵横天地。</p><p>另一段。</p>")
        assertTrue(text.contains("故事的起点，纵横天地"))
        assertTrue(text.contains("另一段"))
    }

    @Test fun directoryProgressPublishesFirstPageBeforeFetchingRemainingPage(): Unit = runBlocking {
        val progress = mutableListOf<Int>()
        val source = engine { url ->
            val second = url.endsWith("/2/")
            if (second) assertEquals(listOf(1), progress)
            val number = if (second) 2 else 1
            val next = if (!second) "<a href='/190/190260/2/'>下一页</a>" else ""
            200 to "<div class='chapterlist'><div class='all'><a href='/190/190260/$number.html'>第${number}章</a>$next</div></div>"
        }
        val chapters = source.getCatalog("https://www.piquge.com/190/190260/", "piquge") { progress += it.size }
        assertEquals(listOf(1, 2), progress)
        assertEquals(2, chapters.size)
    }
}

