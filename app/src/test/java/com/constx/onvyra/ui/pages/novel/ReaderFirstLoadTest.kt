package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ReaderFirstLoadTest {
    @Test fun removedSkinIsAbsentAndSavedRoutesFailBeforeAnyNetworkRequest(): Unit = runBlocking {
        val calls = AtomicInteger()
        val engine = NovelSourceEngine(OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw AssertionError("Retired source must not request HTTP") }.build())
        assertFalse(NovelSourceEngine.AVAILABLE_SOURCES.any { it.id == NovelSourceEngine.SOURCE_SKIN })
        val book = NovelBook("old", "旧书", author = "作者",detailUrl = "https://www.biquge.skin/novel?id=2189",sourceId = NovelSourceEngine.SOURCE_SKIN)
        for (action in listOf<suspend () -> Any>({engine.search("旧书",book.sourceId)}, {engine.getDiscoverBooks(book.sourceId)},
            {engine.getBookDetail(book)}, {engine.getCatalog(book.detailUrl,book.sourceId)},
            {engine.getChapterContent("https://www.biquge.skin/reader?novelId=2189&chapter=1",book.sourceId)})) {
            try { action(); fail("Removed source was accepted") } catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("已移除")) }
        }
        engine.warmBook(book);assertNull(engine.initialChapterUrl(book));assertEquals(0,calls.get())
    }
    @Test fun aggregateBookstoreUsesActualImportedHomepageWithReturnedCovers(): Unit = runBlocking {
        val library = ReaderSourceLibrary { """[{"bookSourceName":"主页验证","bookSourceUrl":"http://www.yetianlian.info","enabled":true,"bookSourceType":0,"searchUrl":"/s.php?q={{key}}","ruleSearch":{"bookList":".bookbox","name":"h4@text","bookUrl":"a@href"}}]""" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("www.xyetianlian.com",chain.request().url.host)
            val body="<div class='item'><div class='image'><img src='/cover.jpg'></div><dl><dt><span>作者甲</span><a href='/yt123/'>首页返回的真实书名</a></dt><dd>首页返回的简介</dd></dl></div>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val engine=NovelSourceEngine(client,sourceLibrary=library)
        val books=engine.getDiscoverBooks()
        assertEquals(1,books.size);assertEquals("首页返回的真实书名",books.single().title)
        assertEquals("http://www.xyetianlian.com/cover.jpg",books.single().coverUrl)
        assertEquals(library.all().single().id,books.single().sourceId)
    }
}
