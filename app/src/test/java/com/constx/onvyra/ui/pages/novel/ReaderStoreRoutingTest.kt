package com.constx.onvyra.ui.pages.novel

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

class ReaderStoreRoutingTest {
    @Test fun publisherOriginalTitleAvoidsAnAuthorFallbackAndCachesTheResolvedRoute() = runBlocking {
        val queries = mutableListOf<String?>()
        val client = catalogFixtureClient { request ->
            queries += request.url.queryParameter("q")
            "<div class='book'><a href='/wrong'>旧日山河</a><b class='author'>另一位作者</b></div>" +
                "<div class='book'><a href='/correct'>旧日山河</a><b class='author'>本书作者</b></div>"
        }
        val engine = NovelSourceEngine(client, sourceLibrary = catalogFixtureSource())
        val wanted = catalogFixtureBook("海报上的新名称", "本书作者").copy(canonicalTitle = "旧日山河")
        val actual = engine.resolveCatalogBook(wanted)
        assertEquals(listOf("旧日山河"), queries)
        assertTrue(actual.detailUrl.endsWith("/correct"))
        assertTrue(ReaderBookIdentity.matches(wanted, actual))
        assertEquals(actual, engine.resolveCatalogBook(wanted))
        assertEquals("Reopening must reuse the route, not search every source again", 1, queries.size)
    }

    @Test fun processRestartReusesVerifiedRouteWhileAnotherAuthorStillSearchesItsOwnWork() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("reader-route-restart-").toFile()
        try {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val client = catalogFixtureClient {
                calls.incrementAndGet()
                "<div class='book'><a href='/a'>同名作品</a><b class='author'>作者甲</b></div>" +
                    "<div class='book'><a href='/b'>同名作品</a><b class='author'>作者乙</b></div>"
            }
            val firstProcess = NovelSourceEngine(client, directory, catalogFixtureSource())
            val wanted = catalogFixtureBook("同名作品", "作者甲")
            val route = firstProcess.resolveCatalogBook(wanted)
            assertEquals(1, calls.get())
            val secondProcess = NovelSourceEngine(client, directory, catalogFixtureSource())
            assertEquals(route, secondProcess.resolveCatalogBook(wanted))
            assertEquals("A new engine must load the saved route without any source search", 1, calls.get())
            assertTrue(secondProcess.resolveCatalogBook(wanted.copy(author = "作者乙")).detailUrl.endsWith("/b"))
            assertEquals(2, calls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun verifiedMingCatalogRetitlingSearchesCanonicalTitleAndStillVerifiesAuthor() = runBlocking {
        val queries = mutableListOf<String?>()
        val client = catalogFixtureClient { request ->
            queries += request.url.queryParameter("q")
            "<div class='book'><a href='/wrong'>大明：家父永乐，永镇山河</a><b class='author'>另一位作者</b></div>" +
                "<div class='book'><a href='/correct'>大明：家父永乐，永镇山河</a><b class='author'>朕闻上古</b></div>"
        }
        val wanted = catalogFixtureBook("大明铁血监国：我爹朱棣，杀穿瓦剌", "朕闻上古")
        val actual = NovelSourceEngine(client, sourceLibrary = catalogFixtureSource()).resolveCatalogBook(wanted)
        assertEquals(listOf("大明：家父永乐，永镇山河"), queries)
        assertTrue(actual.detailUrl.endsWith("/correct"))
        assertTrue(ReaderBookIdentity.matches(wanted, actual))
    }

    @Test fun removedOrChangedSourceRulesCannotBeLoadedFromAFormerProcessRoute() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("reader-route-disable-").toFile()
        try {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val client = catalogFixtureClient {
                calls.incrementAndGet()
                "<div class='book'><a href='/a'>同名作品</a><b class='author'>作者甲</b></div>"
            }
            val wanted = catalogFixtureBook("同名作品", "作者甲")
            NovelSourceEngine(client, directory, catalogFixtureSource()).resolveCatalogBook(wanted)
            try {
                NovelSourceEngine(client, directory, ReaderSourceLibrary { "[]" }).resolveCatalogBook(wanted)
                fail("A removed rule must not be used")
            } catch (_: IOException) { }
            assertEquals(1, calls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun concurrentCatalogOpensReuseExactRouteWithoutRepeatingSearchAndNeverMixAuthors() = runBlocking {
        val source = catalogFixtureSource()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = catalogFixtureClient {
            requests.incrementAndGet()
            val html = "<div class='book'><a href='/a'>同一本书</a><b class='author'>作者甲</b></div><div class='book'><a href='/b'>同一本书</a><b class='author'>作者乙</b></div>"
            html
        }
        val engine = NovelSourceEngine(client, sourceLibrary = source)
        val first = NovelBook("catalog_a", "同一本书", "作者甲", detailUrl = "https://catalog.fixture.test/book/a", sourceId = NovelSourceEngine.SOURCE_CATALOG)
        val routes = List(8) { async { engine.resolveCatalogBook(first) } }.awaitAll()
        assertTrue(routes.all { it.detailUrl.endsWith("/a") })
        assertEquals("The same work must not repeat its source scan", 1, requests.get())
        val second = engine.resolveCatalogBook(first.copy(id = "catalog_b", author = "作者乙"))
        assertTrue(second.detailUrl.endsWith("/b")); assertEquals(2, requests.get())
    }

    @Test fun acceptedAggregateHitCanOpenCatalogWorkWithoutSearchingAgain() = runBlocking {
        val source = catalogFixtureSource()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = catalogFixtureClient { request ->
            val html = if (request.url.host == "source.fixture.test") {
                requests.incrementAndGet()
                "<div class='book'><a href='/correct'>同一本书</a><b class='author'>本书作者</b></div>"
            } else "<html><body></body></html>"
            html
        }
        val engine = NovelSourceEngine(client, sourceLibrary = source)
        engine.searchAggregate("同一本书", 0) {}
        assertEquals(1, requests.get())
        val wanted = NovelBook("catalog_1", "同一本书", "本书作者", detailUrl = "https://catalog.fixture.test/book/1", sourceId = NovelSourceEngine.SOURCE_CATALOG)
        assertTrue(engine.resolveCatalogBook(wanted).detailUrl.endsWith("/correct"))
        assertEquals("Accepted search hits should be reused immediately", 1, requests.get())
    }

    @Test fun catalogOnlyBecomesExactTitleAuthorReadingHitAndRetainsMetadataArtwork()=runBlocking {
        val source = catalogFixtureSource()
        val client = catalogFixtureClient {
            val html="<div class='book'><a href='/wrong'>同一本书</a><b class='author'>另一个作者</b></div><div class='book'><a href='/correct'>同一本书</a><b class='author'>本书作者</b></div>"
            html
        }
        val wanted=NovelBook("catalog_1","同一本书","本书作者",coverUrl="https://catalog.fixture.test/cover.jpg",detailUrl="https://catalog.fixture.test/book/1",sourceId=NovelSourceEngine.SOURCE_CATALOG)
        val actual=NovelSourceEngine(client,sourceLibrary=source).resolveCatalogBook(wanted)
        assertTrue(actual.sourceId.startsWith("legado_"));assertEquals("https://source.fixture.test/correct",actual.detailUrl)
        assertEquals(wanted.coverUrl,actual.coverUrl);assertTrue(ReaderBookIdentity.matches(wanted,actual))
        assertEquals(0,wanted.sourceCount);assertEquals(1,actual.sourceCount)
    }
    @Test fun catalogMetadataWithNoMatchedSourcesStaysUnavailableInsteadOfPretendingItHasChapters()=runBlocking {
        val wanted=NovelBook("catalog_1","缺失作品","作者",detailUrl="http://b02.zhuishushenqi.com/book/1",sourceId=NovelSourceEngine.SOURCE_CATALOG)
        try {NovelSourceEngine(sourceLibrary=ReaderSourceLibrary {"[]"}).resolveCatalogBook(wanted);fail("A catalog is not a reading source")}
        catch(expected:IOException){assertTrue(expected.message.orEmpty().contains("暂未找到"))}
    }

    @Test fun explicitCatalogAliasUsesMainTitleForSourceSearch() = runBlocking {
        val queries = mutableListOf<String?>()
        val source = catalogFixtureSource()
        val client = catalogFixtureClient { request ->
            queries += request.url.queryParameter("q")
            if (request.url.queryParameter("q") == "万古神帝")
                "<div class='book'><a href='/correct'>万古神帝</a><b class='author'>飞天鱼</b></div>"
            else ""
        }
        val wanted = catalogFixtureBook("万古神帝（又名：盖世神帝）", "飞天鱼")
        val actual = NovelSourceEngine(client, sourceLibrary = source).resolveCatalogBook(wanted)
        assertEquals(listOf("万古神帝"), queries)
        assertEquals("https://source.fixture.test/correct", actual.detailUrl)
        assertTrue(ReaderBookIdentity.matches(wanted, actual))
        assertEquals("万古神帝（又名：盖世神帝）", wanted.title)
    }

    @Test fun missingSearchAuthorRequiresSameTitleAndAuthorFromSourceDetail() = runBlocking {
        val requested = mutableListOf<String>()
        val client = catalogFixtureClient(customDetails = true) { request ->
            requested += request.url.encodedPath
            when (request.url.encodedPath) {
                "/search" -> "<div class='book'><a href='/wrong-author'>万古神帝</a></div>" +
                    "<div class='book'><a href='/wrong-title'>万古神帝</a></div>" +
                    "<div class='book'><a href='/correct'>万古神帝</a></div>"
                "/wrong-author" -> "<h1>万古神帝</h1><b class='writer'>另一个作者</b>"
                "/wrong-title" -> "<h1>万古神帝：光明神帝</h1><b class='writer'>飞天鱼</b>"
                "/correct" -> "<h1>万古神帝</h1><b class='writer'>作者：飞天鱼</b>"
                else -> ""
            }
        }
        val actual = NovelSourceEngine(client, sourceLibrary = catalogFixtureSource())
            .resolveCatalogBook(catalogFixtureBook("万古神帝（又名：盖世神帝）", "飞天鱼"))
        assertEquals("https://source.fixture.test/correct", actual.detailUrl)
        assertEquals(listOf("/search", "/wrong-author", "/wrong-title", "/correct"), requested)
    }

    @Test fun missingAuthorInBothSearchAndDetailNeverBecomesAReadingRoute() = runBlocking {
        val client = catalogFixtureClient(customDetails = true) { request ->
            if (request.url.encodedPath == "/search")
                "<div class='book'><a href='/unknown'>万古神帝</a></div>"
            else "<h1>万古神帝</h1>"
        }
        try {
            NovelSourceEngine(client, sourceLibrary = catalogFixtureSource())
                .resolveCatalogBook(catalogFixtureBook("万古神帝", "飞天鱼"))
            fail("A missing author must never act as a wildcard")
        } catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("暂未找到")) }
    }

    private fun catalogFixtureBook(title: String, author: String) = NovelBook("catalog_fixture", title, author,
        detailUrl = "https://catalog.fixture.test/book/fixture", sourceId = NovelSourceEngine.SOURCE_CATALOG)

    private fun catalogFixtureSource() = ReaderSourceLibrary { """[{"bookSourceName":"阅读站","bookSourceUrl":"https://source.fixture.test","enabled":true,"bookSourceType":0,"searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":".book","name":"a@text","author":".author@text","bookUrl":"a@href"},"ruleBookInfo":{"name":"h1@text","author":".writer@text","tocUrl":".toc@href"},"ruleToc":{"chapterList":".chapters a","chapterName":"text","chapterUrl":"href"},"ruleContent":{"content":"#content@html"}}]""" }

    private fun catalogFixtureClient(customDetails: Boolean = false, body: (Request) -> String): OkHttpClient {
        val rows = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val html = when {
                request.url.host != "source.fixture.test" || path == "/search" -> body(request).also { page ->
                    Jsoup.parse(page).select(".book").forEach { row ->
                        val anchor = row.selectFirst("a") ?: return@forEach
                        rows[anchor.attr("href")] = anchor.text() to row.selectFirst(".author")?.text().orEmpty()
                    }
                }
                path.startsWith("/fixture-toc/") -> "<div class='chapters'><a href='/fixture-chapter/${path.substringAfter("/fixture-toc/")}'>第一章 初见</a></div>"
                path.startsWith("/fixture-chapter/") -> "<div id='content'>他推开门，晨光落在书桌上，今天的故事从这里开始。</div>"
                customDetails -> body(request) + "<a class='toc' href='/fixture-toc/${path.removePrefix("/")}'>目录</a>"
                else -> rows[path]?.let { (title, author) ->
                    "<h1>$title</h1><b class='writer'>$author</b><a class='toc' href='/fixture-toc/${path.removePrefix("/")}'>目录</a>"
                } ?: error("Unexpected catalog fixture detail path: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
    }
}
