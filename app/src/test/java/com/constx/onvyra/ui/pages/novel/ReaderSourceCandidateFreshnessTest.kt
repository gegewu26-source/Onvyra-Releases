package com.constx.onvyra.ui.pages.novel

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderSourceCandidateFreshnessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun forcedFreshMetadataReplacesAnOldBrokenAlternativeUrlWithoutChangingThePinnedPrimary() = runBlocking {
        val title = "来源地址更新验证"
        val author = "同一本书的作者"
        val chapter = "第一章 初见"
        val moved = AtomicBoolean()
        val requests = CopyOnWriteArrayList<String>()
        val library = ReaderSourceLibrary { JsonArray(listOf("primary.fixture.test", "moving.fixture.test").map { host ->
            buildJsonObject {
                put("bookSourceName", host); put("bookSourceUrl", "https://$host")
                put("bookSourceType", 0); put("enabled", true); put("searchUrl", "/search?q={{key}}")
                putJsonObject("ruleSearch") {
                    put("bookList", ".book"); put("name", "a@text"); put("author", ".author@text"); put("bookUrl", "a@href")
                }
                putJsonObject("ruleBookInfo") { put("name", "h1@text"); put("author", ".author@text") }
                putJsonObject("ruleToc") {
                    put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href")
                }
                putJsonObject("ruleContent") { put("content", "#content@html") }
            }
        }).toString() }
        val ids = library.all().associate { it.name to it.id }
        val primary = NovelBook("primary", title, author, detailUrl = "https://primary.fixture.test/primary",
            sourceId = ids.getValue("primary.fixture.test"), preferredSourceId = ids.getValue("primary.fixture.test"),
            lastReadChapterTitle = chapter, lastReadChapterUrl = "https://primary.fixture.test/chapter/1", lastReadChapterIndex = 0)
        val movingId = ids.getValue("moving.fixture.test")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val path = request.url.encodedPath
            if (host !in ids) throw IOException("No external network in candidate freshness test")
            requests += "$host$path"
            val oldUrl = host == "moving.fixture.test" && path == "/old"
            val body = when {
                path == "/search" -> {
                    val destination = if (host == "primary.fixture.test") "/primary" else if (moved.get()) "/new" else "/old"
                    "<div class='book'><a href='$destination'>$title</a><b class='author'>$author</b></div>"
                }
                path in setOf("/primary", "/new", "/old") -> "<h1>$title</h1><b class='author'>$author</b>" +
                    "<div class='chapters'><a href='/chapter/1'>$chapter</a></div>"
                path == "/chapter/1" -> "<div id='content'>完。</div>"
                else -> throw IOException("Unexpected fixture route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (oldUrl) 404 else 200)
                .message("fixture").body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        try {
            val directory = temporary.newFolder()
            val engine = NovelSourceEngine(client, sourceLibrary = library).apply { useSourceDiscoveryDirectory(directory) }
            var first: ReaderSourceDiscoveryState? = null
            engine.discoverBookSources(primary, chapterTitle = chapter,
                onState = { first = it }, onProgress = { _, _ -> })
            val oldKey = ReaderSourceAvailability.key(movingId, "https://moving.fixture.test/old")
            assertEquals(ReaderSourceAvailability.FAILED, first!!.availability.getValue(oldKey).status)
            assertEquals("https://moving.fixture.test/old", first!!.book.sources.single { it.sourceId == movingId }.detailUrl)

            moved.set(true)
            val freshAt = requests.size
            var fresh: ReaderSourceDiscoveryState? = null
            engine.discoverBookSources(first!!.book, retryDeferred = true, chapterTitle = chapter,
                onState = { fresh = it }, onProgress = { _, _ -> })
            val updated = fresh!!
            val candidate = updated.book.sources.single { it.sourceId == movingId }
            val newKey = ReaderSourceAvailability.key(movingId, "https://moving.fixture.test/new")
            assertEquals("https://moving.fixture.test/new", candidate.detailUrl)
            assertEquals(ReaderSourceAvailability.USABLE, updated.availability.getValue(newKey).status)
            assertFalse("Old URL verification must not be applied to the fresh route", updated.availability.containsKey(oldKey))
            assertTrue(requests.drop(freshAt).contains("moving.fixture.test/new"))
            assertTrue(requests.drop(freshAt).contains("moving.fixture.test/chapter/1"))
            assertFalse(requests.drop(freshAt).contains("moving.fixture.test/old"))
            assertEquals(primary.sourceId, updated.book.sourceId)
            assertEquals(primary.detailUrl, updated.book.detailUrl)
            assertEquals(primary.preferredSourceId, updated.book.preferredSourceId)
            assertEquals(primary.lastReadChapterUrl, updated.book.lastReadChapterUrl)
            assertEquals(primary.lastReadChapterIndex, updated.book.lastReadChapterIndex)
            val prepared = engine.prepareSourceSwitch(updated.book, movingId, chapter, 0)
            assertEquals(candidate.detailUrl, prepared.book.detailUrl)
            assertEquals("完。", prepared.content.trim())

            // A separate engine must retain the refreshed URL, even when the caller still has the older row.
            val reopened = NovelSourceEngine(client, sourceLibrary = library).apply { useSourceDiscoveryDirectory(directory) }
            val restored = reopened.restoreBookSources(first!!.book)
            assertEquals(candidate.detailUrl, restored.book.sources.single { it.sourceId == movingId }.detailUrl)
            assertEquals(ReaderSourceAvailability.USABLE, restored.availability.getValue(newKey).status)
            assertFalse(restored.availability.containsKey(oldKey))
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
