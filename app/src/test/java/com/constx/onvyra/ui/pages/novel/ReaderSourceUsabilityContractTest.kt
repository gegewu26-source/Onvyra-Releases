package com.constx.onvyra.ui.pages.novel

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real preparation flow: a metadata hit is not evidence that a source can read this chapter. */
class ReaderSourceUsabilityContractTest {
    @Test
    fun matchedMetadataCannotMakeAnHttp404CatalogUsable() = runBlocking {
        Fixture(catalogCode = 404).use { fixture ->
            val previous = fixture.previousBook()
            try {
                fixture.engine.prepareSourceSwitch(previous, fixture.sourceId, "第一章 初见", 0)
                fail("A matched search hit with an HTTP 404 catalog must fail source preparation")
            } catch (_: IOException) {
                assertTrue(fixture.requests.contains("/toc/1"))
                assertFalse("An unreadable catalog must not fall through to a guessed chapter URL",
                    fixture.requests.contains("/chapter/1"))
            }
        }
    }

    @Test
    fun differentWorkInTheFetchedDetailCannotInheritTheSearchHitsIdentity() = runBlocking {
        Fixture(detailTitle = "另一部明确不同的作品").use { fixture ->
            val previous = fixture.previousBook()
            try {
                fixture.engine.prepareSourceSwitch(previous, fixture.sourceId, "第一章 初见", 0)
                fail("The actual detail page contradicted the matched metadata")
            } catch (_: IOException) {
                assertTrue(fixture.requests.contains("/book/1"))
                assertFalse("A mismatched work must be rejected before its catalog is considered usable",
                    fixture.requests.contains("/toc/1"))
                assertFalse(fixture.requests.contains("/chapter/1"))
            }
        }
    }

    @Test
    fun aGenuinelyShortChapterIsReadableWithoutAnArbitraryLengthThreshold() = runBlocking {
        Fixture(body = "完。").use { fixture ->
            val prepared = fixture.engine.prepareSourceSwitch(fixture.previousBook(), fixture.sourceId, "第一章 初见", 0)

            assertEquals(fixture.sourceId, prepared.book.sourceId)
            assertEquals("验证作品", prepared.book.title)
            assertEquals("验证作者", prepared.book.author)
            assertEquals(0, prepared.chapterIndex)
            assertEquals("第一章 初见", prepared.chapters.single().title)
            assertEquals("完。", prepared.content.trim())
            assertTrue(fixture.requests.contains("/chapter/1"))
        }
    }

    private class Fixture(
        private val catalogCode: Int = 200,
        private val detailTitle: String = "验证作品",
        private val body: String = "他推开门，故事从这里开始。",
    ) : Closeable {
        val requests = CopyOnWriteArrayList<String>()
        private val rule = buildJsonObject {
            put("bookSourceName", "Source usability fixture")
            put("bookSourceUrl", "https://usability.fixture.test")
            put("bookSourceType", 0)
            put("enabled", true)
            put("searchUrl", "/search?q={{key}}")
            putJsonObject("ruleSearch") {
                put("bookList", ".book"); put("name", "a@text")
                put("author", ".author@text"); put("bookUrl", "a@href")
            }
            putJsonObject("ruleBookInfo") {
                put("name", "h1@text"); put("author", ".author@text"); put("tocUrl", ".toc@href")
            }
            putJsonObject("ruleToc") {
                put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href")
            }
            putJsonObject("ruleContent") { put("content", "#content@html") }
        }
        private val library = ReaderSourceLibrary { JsonArray(listOf(rule)).toString() }
        val sourceId = runBlocking { library.all().single().id }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "usability.fixture.test")
            val path = request.url.encodedPath
            requests += path
            val code = if (path == "/toc/1") catalogCode else 200
            val html = when (path) {
                "/search" -> "<div class='book'><a href='/book/1'>验证作品</a><b class='author'>验证作者</b></div>"
                "/book/1" -> "<h1>$detailTitle</h1><p class='author'>验证作者</p><a class='toc' href='/toc/1'>目录</a>"
                "/toc/1" -> "<div class='chapters'><a href='/chapter/1'>第一章 初见</a></div>"
                "/chapter/1" -> "<div id='content'><p>$body</p></div>"
                else -> error("Unexpected source-usability fixture route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val engine = NovelSourceEngine(client, sourceLibrary = library)

        suspend fun previousBook(): NovelBook {
            val hit = engine.search("验证作品", sourceId).single()
            return NovelBook("old-work", "验证作品", author = "验证作者", sourceId = "previous-fixture",
                detailUrl = "https://previous.fixture.test/book/1", sources = listOf(hit.asSource()))
        }

        override fun close() {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
