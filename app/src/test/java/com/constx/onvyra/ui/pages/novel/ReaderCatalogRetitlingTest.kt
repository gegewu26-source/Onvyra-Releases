package com.constx.onvyra.ui.pages.novel

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unknown names must be resolved from source evidence, never a hardcoded per-book table. */
class ReaderCatalogRetitlingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oneSlowSameAuthorDetailCannotBlockTheNextIndependentlyProvenCandidate() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), "从未见过的名称", "原来的独立作品", "原作者", holdFirstDecoy = true)
        try {
            val actual = withTimeout(2_000) { fixture.engine().resolveCatalogBook(fixture.wanted) }
            assertEquals("原来的独立作品", actual.canonicalTitle)
            assertEquals("The slow preceding detail is still blocked when the healthy candidate wins", 1L, fixture.releaseDecoy.count)
            assertTrue(fixture.requests.contains("/chapter/correct"))
        } finally { fixture.releaseDecoy.countDown() }
    }

    @Test fun eighthSameAuthorCandidateUsesIndependentOpenGraphWhenTheConfiguredIntroSelectorIsEmpty() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), "未见过的新名称", "独立旧作品", "原作者",
            decoyCount = 7, ogIntroOnly = true)
        val actual = fixture.engine().resolveCatalogBook(fixture.wanted)
        assertEquals("独立旧作品", actual.canonicalTitle)
        assertTrue("Seven earlier same-author works must be rejected rather than stopping at three", fixture.requests.contains("/wrong-6"))
        assertTrue(fixture.requests.contains("/chapter/correct"))
        assertTrue(actual.description.length >= 72)
    }

    @Test fun synopsisAndLatestChapterHintsPrioritizeCandidatesBeyondTheDetailInspectionCap() = runBlocking {
        for (hint in listOf("intro", "latest")) {
            val fixture = Fixture(temporary.newFolder(), "新名称$hint", "旧名称$hint", "原作者",
                decoyCount = 15, metadataHint = hint)
            val actual = fixture.engine().resolveCatalogBook(fixture.wanted)
            assertEquals("旧名称$hint", actual.canonicalTitle)
            assertTrue("The correct candidate originally beyond the cap must be inspected before all 15 decoys",
                fixture.requests.count { it.startsWith("/wrong") } < 15)
            assertTrue(fixture.requests.contains("/correct"))
            assertTrue(fixture.requests.contains("/chapter/correct"))
        }
    }

    @Test fun twoUnknownRetitledWorksRequireRealDetailsAndReadableBodiesAndReopenWithZeroSearches() = runBlocking {
        listOf("海报上的陌生书名甲" to "旧镇星灯", "从未收录过的新书名乙" to "远海来信").forEachIndexed { index, names ->
            val fixture = Fixture(temporary.newFolder(), names.first, names.second, "测试作者$index")
            val resolved = fixture.engine().resolveCatalogBook(fixture.wanted)
            assertEquals(names.second, resolved.canonicalTitle)
            assertEquals(names.second, resolved.title)
            assertTrue(fixture.requests.any { it.startsWith("/chapter/") })
            assertEquals(listOf(names.first, fixture.author), fixture.queries)
            val before = fixture.requests.size
            val reopened = fixture.engine().resolveCatalogBook(fixture.wanted)
            assertEquals(resolved, reopened)
            assertEquals("A fresh process must reuse the verified ID/author/kind alias route", before, fixture.requests.size)
        }
    }

    @Test fun sameAuthorsDifferentWorkWithALongUnrelatedSynopsisIsRejectedBeforeReadingItsBody() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), "未知新名", "独立旧名", "同一作者", includeCorrect = false)
        assertUnavailable(fixture.engine(), fixture.wanted)
        assertTrue(fixture.requests.contains("/wrong"))
        assertFalse(fixture.requests.any { it.startsWith("/chapter/") })
    }

    @Test fun wrongAuthorAndWrongMediaCannotBorrowARenamedWorksPersistedRoute() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), "未知新名", "独立旧名", "真实作者")
        fixture.engine().resolveCatalogBook(fixture.wanted)
        assertUnavailable(fixture.engine(), fixture.wanted.copy(author = "另一个作者"))
        assertUnavailable(fixture.engine(), fixture.wanted.copy(isAudio = true))
        assertUnavailable(fixture.engine(), fixture.wanted.copy(isComic = true))
    }

    @Test fun anotherCatalogIdNeedsItsOwnProofAndAnAliasCannotMaskAContradictedDetail() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), "陌生新名称", "真实旧名称", "真实作者")
        val actual = fixture.engine().resolveCatalogBook(fixture.wanted)
        val before = fixture.queries.size
        fixture.engine().resolveCatalogBook(fixture.wanted.copy(id = "catalog_other_id"))
        assertTrue("The alias binding must include the original catalog ID", fixture.queries.size > before)
        fixture.detailName = "来源已变成另一部小说"
        try { fixture.engine().getBookDetail(actual); fail("A learned name must not hide contradictory fetched metadata") }
        catch (_: IOException) { }
    }

    @Test fun renamedBodyLoginFailureNeverCreatesAProvenAliasAndMissingSynopsisNeverQueriesAuthor() = runBlocking {
        val blocked = Fixture(temporary.newFolder(), "新名称", "旧名称", "真实作者", readable = false)
        assertUnavailable(blocked.engine(), blocked.wanted)
        assertTrue(blocked.requests.any { it.startsWith("/chapter/") })
        val insufficient = Fixture(temporary.newFolder(), "别的新名", "别的旧名", "另位作者")
        assertUnavailable(insufficient.engine(), insufficient.wanted.copy(description = "简介太短"))
        assertEquals(listOf("别的新名"), insufficient.queries)
    }

    @Test fun unknownExplicitPublisherNameHandlesPunctuationWithoutAnAuthorSearch() = runBlocking {
        val fixture = Fixture(temporary.newFolder(), "新名称", "旧名（又名：别称）", "原作者")
        fixture.searchByOriginal = true
        val actual = fixture.engine().resolveCatalogBook(fixture.wanted.copy(canonicalTitle = "旧名（又名：别称）"))
        assertEquals(listOf("旧名"), fixture.queries)
        assertEquals("旧名（又名：别称）", actual.title)
    }

    private suspend fun assertUnavailable(engine: NovelSourceEngine, book: NovelBook) {
        try { engine.resolveCatalogBook(book); fail("Unproven metadata must not become a reading route") }
        catch (_: IOException) { }
    }

    private class Fixture(private val directory: File, private val displayed: String, private val original: String,
        val author: String, private val includeCorrect: Boolean = true, private val readable: Boolean = true,
        private val decoyCount: Int = 1, private val ogIntroOnly: Boolean = false, private val metadataHint: String = "",
        private val holdFirstDecoy: Boolean = false) {
        val requests = CopyOnWriteArrayList<String>()
        val queries = CopyOnWriteArrayList<String>()
        var searchByOriginal = false
        var detailName = original
        val releaseDecoy = CountDownLatch(1)
        private val synopsis = "小城的钟表匠在一次暴雨后收到失踪父亲留下的密信，信中记录了废弃灯塔的潮汐规律。" +
            "他与守塔人的女儿沿着海岸寻找七座石碑，逐渐发现旧商船沉没背后的秘密。" +
            "当最后一场冬潮即将抵达，两个人必须在保护家乡与追寻真相之间作出自己的选择，" +
            "那些看似微小的善意最终连接了整个小镇，也让他们重新理解亲情与承诺。"
        val wanted = NovelBook("catalog_fixture_$displayed", displayed, author,
            detailUrl = "https://catalog.fixture.test/book/$displayed", sourceId = NovelSourceEngine.SOURCE_CATALOG,
            description = synopsis, latestChapter = "终章 归途与新世界的黎明")
        private val library = ReaderSourceLibrary { """[{"bookSourceName":"通用旧名验证站","bookSourceUrl":"https://renamed.fixture.test","bookSourceType":0,"enabled":true,
          "searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":".book","name":"a@text","author":"b@text","bookUrl":"a@href","intro":".synopsis@text","lastChapter":".latest@text"},
          "ruleBookInfo":{"name":"h1@text","author":".writer@text","intro":"${if (ogIntroOnly) "#missing@ownText" else "#intro@text"}","tocUrl":".toc@href"},
          "ruleToc":{"chapterList":".chapters a","chapterName":"text","chapterUrl":"href"},"ruleContent":{"content":"#content@html"}}]""" }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath; requests += path
            if (holdFirstDecoy && path == "/wrong") releaseDecoy.await(5, TimeUnit.SECONDS)
            val html = when (path) {
                "/search" -> {
                    val q = request.url.queryParameter("q").orEmpty(); queries += q
                    if (q == author || searchByOriginal && q == ReaderBookIdentity.searchTitle(original))
                        (0 until decoyCount).joinToString("") { index ->
                            "<div class='book'><a href='${if (index == 0) "/wrong" else "/wrong-$index"}'>作者的另一部小说$index</a><b>$author</b></div>"
                        } + if (includeCorrect) "<div class='book'><a href='/correct'>$original</a><b>$author</b>" +
                            (if (metadataHint == "intro") "<p class='synopsis'>$synopsis</p>" else "") +
                            (if (metadataHint == "latest") "<p class='latest'>终章 归途与新世界的黎明</p>" else "") + "</div>" else ""
                    else ""
                }
                "/correct" -> "<h1>$detailName</h1><b class='writer'>$author</b>" +
                    (if (ogIntroOnly) "<meta property='og:description' content='$synopsis'>" else "<div id='intro'>$synopsis</div>") +
                    "<a class='toc' href='/toc/correct'>目录</a>"
                "/toc/correct" -> "<div class='chapters'><a href='/chapter/correct'>第一章 暴雨来信</a></div>"
                "/chapter/correct" -> "<div id='content'>${if (readable) "风雨拍打着窗户，他拆开了父亲留下的最后一封信。" else "请登录后阅读。"}</div>"
                else -> if (path.startsWith("/wrong")) "<h1>作者的另一部小说</h1><b class='writer'>$author</b><div id='intro'>" +
                    "在沙漠王国中，年轻的驯兽师带领商队穿越流沙寻找古老神庙，途中遭遇了变幻莫测的风暴和敌对部落。".repeat(4) + "</div><a class='toc' href='/toc/wrong'>目录</a>"
                    else error("Unexpected alias fixture request: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        fun engine() = NovelSourceEngine(client, directory, library)
    }
}
