package com.constx.onvyra.ui.pages.novel

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderStoreRepositoryTest {
    @Test fun publisherOriginalNameAndFullSynopsisSurviveTheCatalogCache() = runBlocking {
        val body = """{"ok":true,"total":3,"books":[{"_id":"retitled","title":"海报新名字","author":"书目作者",
            "originalName":"原来的作品名（又名：旧称）","shortIntro":"短简介","longIntro":"出版社提供的更完整简介"},
            {"_id":"single-alias","title":"新名字乙","author":"作者乙","aliases":"原来的书名乙"},
            {"_id":"ambiguous","title":"新名字丙","author":"作者丙","aliases":["可能名称一","可能名称二"]}]}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()
        val directory = temp.newFolder()
        val category = ReaderStoreCategory("test", "都市", "male")
        val page = ReaderStoreRepository(client, directory).category(category, 1)
        val first = page.books.first()
        assertEquals(listOf("原来的作品名（又名：旧称）", "原来的书名乙", ""), page.books.map { it.canonicalTitle })
        assertEquals("海报新名字", first.title)
        assertEquals("原来的作品名（又名：旧称）", first.canonicalTitle)
        assertEquals("原来的作品名", ReaderBookIdentity.searchTitle(first))
        assertEquals("出版社提供的更完整简介", first.description)
        val noNetwork = OkHttpClient.Builder().addInterceptor { throw AssertionError("Catalog cache should satisfy reopening") }.build()
        assertEquals(page, ReaderStoreRepository(noNetwork, directory).category(category, 1))
    }

    @get:Rule val temp = TemporaryFolder()
    private val requests = CopyOnWriteArrayList<Request>()
    private val offline = AtomicBoolean(false)
    private val taxonomy = """{"ok":true,"firstTags":[
        {"tagChannel":"male_v2","secondTags":[{"secondTagName":"热门","thirdTags":[{"name":"都市","alias":"dushi_v2"},{"name":"玄幻","alias":"xuanhuan_v2"}]},{"secondTagName":"情节","thirdTags":[{"name":"不应作为首页大分类","alias":"plot_v2"}]}]},
        {"tagChannel":"female_v2","secondTags":[{"secondTagName":"热门","thirdTags":[{"name":"现代言情","alias":"xianyan_v2"}]}]}]}"""
    private fun client(total: Int = 43, failedAlias: () -> String? = { null },
        beforeRequest: (Request) -> Unit = {},
    ) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); requests += request
        beforeRequest(request)
        assertNull(request.url.queryParameter("token")); assertNull(request.url.queryParameter("userid"))
        if (offline.get()) throw IOException("Offline fixture")
        if (failedAlias() != null && request.url.queryParameter("alias") == failedAlias()) throw IOException("Section unavailable")
        val body = if (request.url.encodedPath == "/tag/statics") taxonomy else {
            val start = request.url.queryParameter("start")!!.toInt(); val limit = request.url.queryParameter("limit")!!.toInt()
            val alias = request.url.queryParameter("alias")!!
            val books = (start until (start + limit).coerceAtMost(total)).map { number -> buildJsonObject {
                put("_id", "$alias-$number"); put("title", "作品$number"); put("author", "作者$number")
                put("cover", "/agent/http%3A%2F%2Fimg.example.test%2Fcover$number.jpg%2F")
                put("majorCate", request.url.queryParameter("query")); put("minorCate", "类别$number")
                put("shortIntro", "真实返回的简介$number"); put("lastChapter", "最新章$number"); put("isSerial", number % 2 == 0)
            } }
            buildJsonObject { put("ok",true);put("total",total);put("books",JsonArray(books)) }.toString()
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
    }.build()

    @Test fun homepageBuildsActualCategorySectionsAndSeparatesMaleFemaleChannels() = runBlocking {
        val repository = ReaderStoreRepository(client(), temp.newFolder())
        val male = repository.home("male")
        assertEquals(listOf("都市","玄幻"),male.sections.map { it.category.title })
        assertEquals(listOf(6,6),male.sections.map { it.books.size })
        assertEquals(2,male.featured.size); assertFalse(male.stale)
        assertTrue(male.sections.flatMap { it.books }.all { it.sourceId == NovelSourceEngine.SOURCE_CATALOG })
        val female = repository.home("female")
        assertEquals(listOf("现代言情"),female.sections.map { it.category.title })
        assertTrue(female.sections.all { it.category.channel == "female" })
        assertTrue(requests.filter { it.url.encodedPath == "/tag/fuzzy-search" && it.url.queryParameter("gender") == "female" }
            .all { it.url.queryParameter("alias") == "xianyan_v2" })
        val book = male.sections.first().books.first()
        assertEquals("catalog_dushi_v2-0",book.id)
        assertEquals("http://b02.zhuishushenqi.com/book/dushi_v2-0",book.detailUrl)
        assertEquals("http://statics.zhuishushenqi.com/agent/http%3A%2F%2Fimg.example.test%2Fcover0.jpg%2F",book.coverUrl)
        assertEquals("连载中",book.status);assertEquals("作者0",book.author)
    }

    @Test fun categoryUsesPageOffsetsAndTotalRatherThanRepeatingFourHomepageBooks() = runBlocking {
        val repository = ReaderStoreRepository(client())
        val category = ReaderStoreCategory("dushi_v2","都市","male")
        val pages = (1..3).map { repository.category(category,it) }
        assertEquals(listOf(20,20,3),pages.map { it.books.size })
        assertEquals(listOf(true,true,false),pages.map { it.hasMore })
        assertEquals(listOf("0","20","40"),requests.map { it.url.queryParameter("start") })
        assertEquals(43,pages.flatMap { it.books }.map { it.id }.distinct().size)
        assertTrue(requests.all { it.url.queryParameter("limit") == "20" && it.url.queryParameter("alias") == "dushi_v2" })
    }

    @Test fun savedHomepageReopensWithoutNetworkAndForcedOfflineRefreshIsExplicitlyStale() = runBlocking {
        val dir = temp.newFolder(); val client = client()
        val expected = ReaderStoreRepository(client,dir).home("male")
        val count = requests.size
        offline.set(true)
        val reopened = ReaderStoreRepository(client,dir)
        assertEquals(expected,reopened.home("male"));assertEquals(count,requests.size)
        val stale = reopened.home("male",force = true)
        assertTrue(stale.stale);assertTrue(stale.notice.orEmpty().contains("上次保存"))
        assertEquals(expected.sections,stale.sections)
        assertEquals(1,dir.listFiles()!!.count { it.name == "storefront-male-v1.json" })
    }

    @Test fun partialHomepageNamesMissingSectionsAndDoesNotCacheFailureForADay() = runBlocking {
        var failSecond = true
        val partial = client(failedAlias = { if (failSecond) "xuanhuan_v2" else null })
        val repository = ReaderStoreRepository(partial,temp.newFolder())
        val first = repository.home("male")
        assertEquals(1,first.sections.size);assertTrue(first.notice.orEmpty().contains("1 个分区"))
        failSecond = false
        val retry = repository.home("male")
        assertEquals(2,retry.sections.size);assertNull(retry.notice)
    }
    @Test fun coldHomepagePublishesUsableFirstSectionBeforeSlowTailCompletes() = runBlocking {
        val slowTail = CountDownLatch(1)
        val first = CompletableDeferred<ReaderStoreHome>()
        val slowClient = client(beforeRequest = { request ->
            if (request.url.queryParameter("alias") == "xuanhuan_v2")
                check(slowTail.await(10,TimeUnit.SECONDS)) { "Test did not release the delayed category" }
        })
        val repository = ReaderStoreRepository(slowClient,temp.newFolder())
        val completed = async(Dispatchers.Default) { repository.home("male",onUpdate = {
            if (it.sections.isNotEmpty()) first.complete(it)
        }) }
        try {
            val visible = withTimeout(5_000) { first.await() }
            assertFalse("First usable content must not wait for the blocked tail",completed.isCompleted)
            assertEquals(listOf("都市"),visible.sections.map { it.category.title })
            assertEquals(listOf("都市","玄幻"),visible.categories.map { it.title })
            assertNull(visible.notice)
            slowTail.countDown()
            val full = completed.await()
            assertEquals(2,full.sections.size)
            assertEquals(visible.featured.first(),full.featured.first())
        } finally { slowTail.countDown() }
    }

    @Test fun forcedHomepageRefreshPublishesSavedBooksBeforeWaitingForNetwork() = runBlocking {
        val directory = temp.newFolder()
        val client = client()
        val initial = ReaderStoreRepository(client,directory).home("male")
        offline.set(true)
        val requestsBeforeRefresh = requests.size
        val visible = mutableListOf<ReaderStoreHome>()
        val refreshed = ReaderStoreRepository(client,directory).home("male",force = true,onUpdate = {
            if (visible.isEmpty()) assertEquals("Saved content should be emitted before any network attempt",requestsBeforeRefresh,requests.size)
            visible += it
        })
        assertEquals(initial,visible.first())
        assertTrue(refreshed.stale)
        assertTrue(visible.last().stale)
    }

    @Test fun categoryReopenAndConcurrentCallsUseCacheWithoutLosingExplicitRefresh() = runBlocking {
        val directory = temp.newFolder()
        val client = client()
        val category = ReaderStoreCategory("dushi_v2","都市","male")
        val repository = ReaderStoreRepository(client,directory)
        val a = async(Dispatchers.Default) { repository.category(category,1) }
        val b = async(Dispatchers.Default) { repository.category(category,1) }
        assertEquals(a.await(),b.await())
        assertEquals(1,requests.size)
        offline.set(true)
        val saved = ReaderStoreRepository(client,directory).category(category,1)
        assertEquals(20,saved.books.size)
        assertEquals("Disk cache should reopen without network",1,requests.size)
        offline.set(false)
        val refreshed = repository.category(category,1,force = true)
        fun metadata(page: ReaderStoreCategoryPage) = page.books.map { book ->
            listOf(book.id,book.sourceId,book.detailUrl,book.title,book.author,book.coverUrl)
        }
        assertEquals(metadata(saved),metadata(refreshed))
        assertEquals(saved.page,refreshed.page)
        assertEquals(saved.hasMore,refreshed.hasMore)
        assertEquals("Explicit refresh should still request new data",2,requests.size)
        val female = ReaderStoreCategory("dushi_v2","都市","female")
        repository.category(female,1)
        assertEquals("Channel is part of the page cache key",3,requests.size)
    }

    @Test fun unwritableCachePathDoesNotHideSuccessfulNetworkContent() = runBlocking {
        // A file cannot contain cache files: exercise the real persistence failure path.
        val repository = ReaderStoreRepository(client(),temp.newFile())
        assertEquals(2,repository.home("male").sections.size)
        assertEquals(20,repository.category(ReaderStoreCategory("dushi_v2","都市","male"),1).books.size)
    }

}
