package com.constx.onvyra.ui.pages.novel

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Discovery statuses must come from actual chapter validation, including an explicit forced retry. */
class ReaderSourceDiscoveryUsabilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualReadableChapterIsPublishedWhileAnotherSourcesMetadataIsStillWaiting() = runBlocking {
        Fixture(temporary.newFolder(), blockSlowSearch = true).use { fixture ->
            val usable = CompletableDeferred<ReaderSourceDiscoveryState>()
            val discovery = launch {
                fixture.engine.discoverBookSources(fixture.book, chapterTitle = Chapter, chapterIndex = 0,
                    onState = { state ->
                        if (state.availability.values.any { it.status == ReaderSourceAvailability.USABLE }) usable.complete(state)
                    }, onProgress = { _, _ -> })
            }
            try {
                val early = withTimeout(2_000) { usable.await() }
                assertEquals(ReaderSourceAvailability.USABLE, fixture.availability(early, ShortHost).status)
                assertEquals("Slow metadata must not delay real detail/catalog/body validation", 1L, fixture.releaseSlow.count)
                assertFalse(discovery.isCompleted)
            } finally { fixture.releaseSlow.countDown(); discovery.cancelAndJoin() }
        }
    }

    @Test fun exactMetadataHitsWithBrokenCatalogOrLoginBodyAreFailedWhileAShortChapterIsUsable() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            val states = CopyOnWriteArrayList<ReaderSourceDiscoveryState>()
            fixture.engine.discoverBookSources(fixture.book, chapterTitle = Chapter, chapterIndex = 0,
                onState = { states += it }, onProgress = { _, _ -> })

            val result = states.last()
            assertEquals(fixture.discoveredIds, result.book.sources.map { it.sourceId }.filter { it in fixture.discoveredIds }.toSet())
            val catalog404 = fixture.availability(result, BrokenHost)
            val login = fixture.availability(result, LoginHost)
            val readable = fixture.availability(result, ShortHost)
            assertEquals(ReaderSourceAvailability.FAILED, catalog404.status)
            assertEquals(ReaderSourceAvailability.FAILED, login.status)
            assertTrue(catalog404.reason.isNotBlank())
            assertTrue(login.reason.isNotBlank())
            assertEquals(ReaderSourceAvailability.USABLE, readable.status)
            assertEquals(Chapter, readable.chapterTitle)
            assertEquals("https://$ShortHost/chapter/1", readable.chapterUrl)
            assertEquals(0, readable.chapterIndex)
            assertTrue(readable.checkedAt > 0)
            assertTrue(fixture.requests.contains("$BrokenHost/toc/1"))
            assertFalse(fixture.requests.contains("$BrokenHost/chapter/1"))
            assertTrue(fixture.requests.contains("$LoginHost/chapter/1"))
            assertTrue(fixture.requests.contains("$ShortHost/chapter/1"))
            assertFalse("The failed routes must never be published as usable", states.any { state ->
                state.availability.values.any { value -> value.sourceId in fixture.failedIds && value.status == ReaderSourceAvailability.USABLE }
            })
        }
    }

    @Test fun forcedDiscoveryRequeriesEveryValidationLayerAndDemotesAnOldUsableSourceAfterBodyFailure() = runBlocking {
        Fixture(temporary.newFolder()).use { fixture ->
            var first: ReaderSourceDiscoveryState? = null
            fixture.engine.discoverBookSources(fixture.book, chapterTitle = Chapter, chapterIndex = 0,
                onState = { first = it }, onProgress = { _, _ -> })
            assertEquals(ReaderSourceAvailability.USABLE, fixture.availability(first!!, ShortHost).status)
            val requestStart = fixture.requests.size
            fixture.shortBodyFails.set(true)

            var retried: ReaderSourceDiscoveryState? = null
            fixture.engine.discoverBookSources(first!!.book, retryDeferred = true, chapterTitle = Chapter, chapterIndex = 0,
                onState = { retried = it }, onProgress = { _, _ -> })

            val freshRequests = fixture.requests.drop(requestStart)
            for (path in listOf("/search", "/book/1", "/toc/1", "/chapter/1")) {
                assertTrue("Forced retry reused an old successful $path instead of querying the source",
                    freshRequests.contains("$ShortHost$path"))
            }
            val failed = fixture.availability(retried!!, ShortHost)
            assertEquals(ReaderSourceAvailability.FAILED, failed.status)
            assertTrue(failed.reason.isNotBlank())
            assertTrue(failed.retryAfter > failed.checkedAt)
            val restored = fixture.engine.restoreBookSources(fixture.book)
            assertEquals("An old usable status must not be revived from persistence",
                ReaderSourceAvailability.FAILED, fixture.availability(restored, ShortHost).status)
        }
    }

    private class Fixture(directory: File, blockSlowSearch: Boolean = false) : Closeable {
        val requests = CopyOnWriteArrayList<String>()
        val shortBodyFails = AtomicBoolean()
        val releaseSlow = CountDownLatch(1)
        private val hosts = listOf(BrokenHost, LoginHost, ShortHost) + if (blockSlowSearch) listOf(SlowHost) else emptyList()
        private val library = ReaderSourceLibrary { JsonArray(hosts.map(::source)).toString() }
        private val ids = runBlocking { library.all().associate { it.name to it.id } }
        val discoveredIds = ids.values.toSet()
        val failedIds = setOf(ids.getValue(BrokenHost), ids.getValue(LoginHost))
        val book = NovelBook("discovery-work", Title, Author,
            sourceId = "existing-fixture", detailUrl = "https://existing.fixture.test/book/1")
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val path = request.url.encodedPath
            // The real aggregate also includes built-in sources. Reject these in
            // the interceptor so this test has no network and only known matches.
            if (host !in hosts) throw IOException("No external network in source-discovery QA")
            requests += "$host$path"
            if (host == SlowHost && path == "/search") releaseSlow.await(3, TimeUnit.SECONDS)
            val code = when {
                host == BrokenHost && path == "/toc/1" -> 404
                host == ShortHost && path == "/chapter/1" && shortBodyFails.get() -> 503
                else -> 200
            }
            val html = when (path) {
                "/search" -> if (host == SlowHost) "" else "<div class='book'><a href='/book/1'>$Title</a><b class='author'>$Author</b></div>"
                "/book/1" -> "<h1>$Title</h1><p class='author'>$Author</p><a class='toc' href='/toc/1'>目录</a>"
                "/toc/1" -> "<div class='chapters'><a href='/chapter/1'>$Chapter</a></div>"
                "/chapter/1" -> "<div id='content'><p>${if (host == LoginHost) "请登录后阅读。" else "完。"}</p></div>"
                else -> error("Unexpected source-discovery fixture route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val engine = NovelSourceEngine(client, sourceLibrary = library).apply { useSourceDiscoveryDirectory(directory) }

        fun availability(state: ReaderSourceDiscoveryState, host: String): ReaderSourceAvailability =
            state.availability.getValue(ReaderSourceAvailability.key(ids.getValue(host), "https://$host/book/1"))

        override fun close() {
            releaseSlow.countDown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        private const val Title = "来源可读性验证作品"
        private const val Author = "已核实作者"
        private const val Chapter = "第一章 初见"
        private const val BrokenHost = "catalog404.fixture.test"
        private const val LoginHost = "login.fixture.test"
        private const val ShortHost = "short.fixture.test"
        private const val SlowHost = "slow.fixture.test"

        private fun source(host: String) = buildJsonObject {
            put("bookSourceName", host); put("bookSourceUrl", "https://$host")
            put("bookSourceType", 0); put("enabled", true); put("searchUrl", "/search?q={{key}}")
            putJsonObject("ruleSearch") {
                put("bookList", ".book"); put("name", "a@text"); put("author", ".author@text"); put("bookUrl", "a@href")
            }
            putJsonObject("ruleBookInfo") {
                put("name", "h1@text"); put("author", ".author@text"); put("tocUrl", ".toc@href")
            }
            putJsonObject("ruleToc") {
                put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href")
            }
            putJsonObject("ruleContent") { put("content", "#content@html") }
        }
    }
}
