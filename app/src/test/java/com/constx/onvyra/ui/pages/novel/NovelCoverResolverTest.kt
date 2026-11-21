package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NovelCoverResolverTest {
    @Test fun xuanjianEmptySourceImageOriginsCannotDisplaceTheLaterExactWorkCover() {
        val primary = NovelBook("existing-reading-id", "玄鉴仙族", author = "作者：季越人",
            sourceId = "legado_yetian_fixture", detailUrl = "http://www.xyetianlian.com/yt128036/")
        val broken = primary.copy(id = "mobile-hit", author = "季越人", sourceId = "legado_mobile_fixture",
            detailUrl = "http://m.rulianshi.cc/xuanjianxianzu/", coverUrl = "http://www.rulianshi.cc/")
        val placeholder = primary.copy(id = "noimg-hit", sourceId = "legado_placeholder_fixture",
            coverUrl = "http://www.cxzz958.com/css/noimg.jpg")
        val actual = primary.copy(id = "xxsy-hit", author = "季越人", sourceId = "legado_xxsy_fixture",
            detailUrl = "https://m.xxsy.net/book/24488947201576304",
            coverUrl = "https://bookcover.yuewen.com/qdbimg/349573/c_24488947201576304/90",
            coverReferer = "https://m.xxsy.net/search/玄鉴仙族",
            coverRequestHeaders = mapOf("Referer" to "https://m.xxsy.net/search/玄鉴仙族", "User-Agent" to "fixture-publisher-agent"))
        val merged = ReaderBookIdentity.merge("玄鉴仙族", listOf(primary, broken, placeholder, actual)).single()
        assertEquals(primary.id, merged.id)
        assertEquals(primary.sourceId, merged.sourceId)
        assertEquals(primary.detailUrl, merged.detailUrl)
        assertEquals(actual.coverUrl, merged.coverUrl)
        assertEquals(actual.coverReferer, merged.coverReferer)
        assertEquals(actual.artworkHeaders, merged.artworkHeaders)
        assertEquals(4, merged.sourceCount)
    }

    @Test fun missingSourceArtworkOriginsAndNoimgAreSkippedButQueryImageEndpointsRemainValid(): Unit = runBlocking {
        val current = book("季越人").copy(title = "玄鉴仙族", sourceId = "legado_yetian_fixture", coverUrl = "http://www.rulianshi.cc/")
        assertTrue(NovelCoverResolver.needsCover(current))
        assertTrue(NovelCoverResolver.needsCover(current.copy(coverUrl = "http://www.cxzz958.com/css/noimg.jpg")))
        assertFalse(NovelCoverResolver.needsCover(current.copy(coverUrl = "https://images.example/?book=123")))
        val actual = current.copy(id = "healthy", sourceId = "legado_xxsy_fixture",
            detailUrl = "https://m.xxsy.net/book/24488947201576304",
            coverUrl = "https://bookcover.yuewen.com/qdbimg/349573/c_24488947201576304/90",
            coverReferer = "https://m.xxsy.net/search/玄鉴仙族")
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; error("Already matched healthy artwork must not require another lookup") }.build()
        val resolver = NovelCoverResolver(client, referenceProviders = true)
        val result = resolver.resolve(current.copy(sources = listOf(current.asSource(), actual.asSource())))!!
        assertEquals(actual.coverUrl, result.url)
        assertEquals(actual.coverReferer, result.referer)
        assertEquals(0, requests)
    }

    @Test fun explicitRetryRevisitsTheFailedUrlAndInvalidatesOnlyThisBooksMiss(): Unit = runBlocking {
        val lookups = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("<html></html>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        val resolver = NovelCoverResolver(client)
        val first = book()
        val other = book().copy(id = "other", title = "另一部作品")
        fun coverFor(candidate: NovelBook) = ResolvedCover("https://images.example/${candidate.id}.jpg",
            "https://source.example/", candidate.detailUrl)
        resolver.useSourceLookup { candidate, excluded ->
            lookups.incrementAndGet()
            coverFor(candidate).takeUnless { it.url in excluded }
        }
        val firstCover = resolver.resolve(first)!!
        val otherCover = resolver.resolve(other)!!
        assertNull(resolver.resolve(first, firstCover.url))
        assertNull(resolver.resolve(other, otherCover.url))
        val beforeRetry = lookups.get()
        resolver.retry(first, setOf(firstCover.url))
        assertEquals(firstCover, resolver.resolve(first))
        assertEquals(beforeRetry + 1, lookups.get())
        // Retrying one visible cover must not clear another work's negative cache.
        assertNull(resolver.resolve(other))
        assertEquals(beforeRetry + 1, lookups.get())
        // Its failed URL remains excluded even if its metadata is explicitly retried.
        resolver.retry(other, emptySet())
        assertNull(resolver.resolve(other))
    }

    @Test fun explicitRetryCanRecoverMissingArtworkBeforeTheNegativeCacheExpires(): Unit = runBlocking {
        val calls = AtomicInteger()
        var recovered = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val body = if (recovered) """<a data-bid="2"><h2>同名作品</h2><p class="author">真实作者</p><img data-src="//bookcover.yuewen.com/recovered.jpg"></a>"""
                else "<html></html>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val resolver = NovelCoverResolver(client)
        assertNull(resolver.resolve(book()))
        recovered = true
        assertNull(resolver.resolve(book()))
        assertEquals(1, calls.get())
        resolver.retry(book(), emptySet())
        assertEquals("https://bookcover.yuewen.com/recovered.jpg", resolver.resolve(book())!!.url)
        assertEquals(2, calls.get())
    }

    @Test fun referencePrimaryWinsEvenWhenBackupReturnsFirstAndDeletedBitmapIsMissingArtwork(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val primary = chain.request().url.host == "pre-api.tuishujun.com"
            if (primary) Thread.sleep(150)
            val body = if (primary) """{"data":{"data":[{"title":"藏夏","author_nickname":"艾鱼","cover":"https://tsj.youdubook.com/cover/real.jpg"}]}}"""
                else """{"data":{"data":[{"novel_name":"藏夏","author_name":"艾鱼","novel_img":"https://s2.ax1x.com/2019/09/23/uifQCd.jpg"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val original = book().copy(title = "藏夏", author = "艾鱼")
        assertEquals("https://tsj.youdubook.com/cover/real.jpg",
            NovelCoverResolver(client, referenceProviders = true).resolve(original)!!.url)
        assertTrue(NovelCoverResolver.needsCover(original.copy(coverUrl = "https://s2.ax1x.com/2019/09/23/uifQCd.jpg")))
    }
    @Test fun referenceArtworkAliasDoesNotMergeReadingIdentitiesOrUsePreviousVolume() {
        val source = book().copy(title = "开局地摊卖大力江南夏瑶", author = "弈青锋")
        assertTrue(referenceArtworkTitleMatches(source, "开局地摊卖大力"))
        assertFalse(referenceArtworkTitleMatches(source.copy(title = "开局地摊卖大力第二部"), "开局地摊卖大力"))
        assertFalse(referenceArtworkTitleMatches(source.copy(sourceId = "legado_other"), "开局地摊卖大力"))
        assertFalse(ReaderBookIdentity.matches(source, source.copy(title = "开局地摊卖大力")))
    }
    @Test fun referenceApkMetadataSupportsNonQidianBooksAndPersistsVerifiedIdentity(): Unit = runBlocking {
        val directory = Files.createTempDirectory("reader7-reference-art").toFile()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val json = if (chain.request().url.host == "pre-api.tuishujun.com") """{"data":{"data":[
                {"title":"藏夏","author_nickname":"错误作者","cover":"https://images.example/wrong.jpg"},
                {"title":"藏夏","author_nickname":"艾鱼","cover":"https://tsj.youdubook.com/cover/book_cover_133112.jpg"}]}}"""
            else """{"data":{"data":[]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val original = book().copy(title = "藏夏", author = "艾鱼")
            val cover = NovelCoverResolver(client, directory, referenceProviders = true).resolve(original)!!
            assertEquals("https://tsj.youdubook.com/cover/book_cover_133112.jpg", cover.url)
            val count = calls.get()
            assertEquals(cover, NovelCoverResolver(client, directory, referenceProviders = true).resolve(original))
            assertEquals(count, calls.get())
            assertTrue("Metadata must not change the reading route", original.sourceId == "biquge_skin")
        } finally { directory.deleteRecursively() }
    }

    private fun book(author: String = "真实作者") = NovelBook("unchanged-id", "同名作品", author = author,
        sourceId = "biquge_skin", detailUrl = "https://www.biquge.skin/novel?id=12")
    private fun resolver(calls: AtomicInteger, responseCode: Int = 200) = NovelCoverResolver(
        OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("m.qidian.com", chain.request().url.host)
            assertEquals("同名作品", chain.request().url.queryParameter("kw"))
            val html = """<a data-bid="100"><h2>同名作品</h2><p class="_searchBookAuthor_hash">另一个作者</p><img data-src="//bookcover.yuewen.com/wrong.jpg"></a>
                <a data-bid="200"><h2><mark>同名作品</mark></h2><p class="_searchBookAuthor_hash">真实作者</p><img src="https://cdn.example.com/placeholder.png" data-src="//bookcover.yuewen.com/from-actual-page.jpg"></a>"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(responseCode).message("fixture")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build())

    @Test fun exactTitleAndAuthorAndLazyImageAreRequiredAndCached(): Unit = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver(calls)
        val cover = resolver.resolve(book())!!
        assertEquals("https://bookcover.yuewen.com/from-actual-page.jpg", cover.url)
        assertTrue(cover.referer.startsWith("https://m.qidian.com/search/"))
        assertEquals(cover, resolver.resolve(book()))
        assertEquals(1, calls.get())
        assertNull(resolver.resolve(book("完全不同的作者")))
        assertNull(resolver.resolve(book("完全不同的作者")))
        assertEquals(2, calls.get())
    }

    @Test fun absentAuthorAndComicsDoNotIssueRequestsAndHttpErrorsRemainOptional(): Unit = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver(calls, 202)
        assertNull(resolver.resolve(book("")))
        assertNull(resolver.resolve(book().copy(isComic = true)))
        assertEquals(0, calls.get())
        assertNull(resolver.resolve(book()))
        assertEquals(1, calls.get())
    }

    @Test fun knownSourcePlaceholderIsEligibleButActualCoverIsPreserved() {
        assertTrue(NovelCoverResolver.needsCover(book()))
        assertTrue(NovelCoverResolver.needsCover(book().copy(coverUrl = "https://pic.biquge.hair/d21ca920e63ba5e820f07ad60a3b82855680512a4c70ad683d0f6ee2e85ac972.jpg")))
        assertFalse(NovelCoverResolver.needsCover(book().copy(coverUrl = "https://bookcover.yuewen.com/qdbimg/1/200/180")))
        assertFalse(NovelCoverResolver.needsCover(book().copy(isComic = true)))
    }
    @Test fun publisherCoverSurvivesRecreationAndExplicitMetadataTailIsCleaned(): Unit = runBlocking {
        val directory = Files.createTempDirectory("reader-cover-disk").toFile()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""<a data-bid="2"><h2>同名作品</h2><p class="author">真实作者</p><img data-src="//bookcover.yuewen.com/qdbimg/349573/2/180"></a>"""
                    .toResponseBody("text/html".toMediaType())).build()
        }.build()
        try {
            val first = NovelCoverResolver(client, directory).resolve(book("作者：真实作者，分类：玄幻，状态：全本"))!!
            val restored = NovelCoverResolver(client, directory).resolve(book())!!
            assertEquals(first, restored)
            assertEquals(1, calls.get())
            assertEquals(1, directory.listFiles()!!.size)
            // A different writer must not reuse another book's cached image.
            assertNull(NovelCoverResolver(client, directory).resolve(book("同名作者二")))
            assertEquals(2, calls.get())
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedCdnUrlIsNotReturnedAgainAndAlternativeKeepsTheMatchedBookIdentity(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""<a data-bid="2"><h2>同名作品</h2><p class="author">真实作者</p><img data-src="//bookcover.yuewen.com/qdbimg/349573/2/180"></a>"""
                    .toResponseBody("text/html".toMediaType())).build()
        }.build()
        val resolver = NovelCoverResolver(client)
        val normal = resolver.resolve(book())!!
        val alternative = resolver.resolve(book(), failedUrl = normal.url)!!
        assertEquals("https://bookcover.yuewen.com/qdbimg/349573/2/300", alternative.url)
        assertNull(resolver.resolve(book(), failedUrl = alternative.url))
    }

    @Test fun simultaneousVisibleRowAndWarmupShareOneIdentityRequest(): Unit = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver(calls)
        val results = (1..8).map { async { resolver.resolve(book()) } }.awaitAll()
        assertTrue(results.all { it == results.first() && it != null })
        assertEquals(1, calls.get())
    }

    @Test fun relativeAndProtocolRelativeImageUrlsRetainTheSourceBase() {
        assertEquals("https://source.example/books/cover.jpg", normalizedReaderImageUrl("../cover.jpg", "https://source.example/books/12/"))
        assertEquals("https://images.example/cover.jpg", normalizedReaderImageUrl("//images.example/cover.jpg", ""))
        assertEquals("", normalizedReaderImageUrl("", "https://source.example/books/12/"))
    }

}
