package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Deliberately opt-in; a successful proxy-only run is insufficient for the reader. */
class ReaderDirectLiveProbeTest {
    @Test fun searchDetailFullCatalogAndFirstChapterWithoutProxy(): Unit = runBlocking {
        assumeTrue(System.getenv("ONVYRA_READER_DIRECT_LIVE") == "1")
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
        val project = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
        val seed = File(project, "app/src/main/assets/reader/skin-search-v1.bin")
        val engine = NovelSourceEngine(client, File(project, "work/reader5-live-cache"), skinIndexSeed = { seed.inputStream() })
        val checks = mutableListOf<JsonObject>()
        for (query in listOf("诡秘之主", "我的职业太有个性")) {
            val started = System.nanoTime()
            val books = engine.search(query, "biquge_skin")
            val searched = System.nanoTime()
            val matched = books.first { it.title == query }
            assertEquals(query, books.first().title)
            val detail = engine.getBookDetail(matched)
            val progress = mutableListOf<Int>()
            val earlyUrl = engine.initialChapterUrl(detail)
            val early = earlyUrl?.let { async { runCatching { engine.getChapterContent(it, detail.sourceId) } } }
            val chapters = engine.getCatalog(detail.detailUrl, detail.sourceId) { progress += it.size }
            val first = if (earlyUrl == chapters.first().url) early?.await()?.getOrNull()
                ?: engine.getChapterContent(chapters.first().url, detail.sourceId)
                else engine.getChapterContent(chapters.first().url, detail.sourceId)
            val opened = System.nanoTime()
            val cachedStart = System.nanoTime()
            assertEquals(first, engine.getChapterContent(chapters.first().url, detail.sourceId))
            val cachedMs = (System.nanoTime() - cachedStart) / 1_000_000.0
            var coverStatus = 0
            var coverType = ""
            if (detail.coverUrl.isNotBlank()) client.newCall(Request.Builder().url(detail.coverUrl).build())
                .execute().use { coverStatus = it.code; coverType = it.header("Content-Type").orEmpty() }
            assertTrue(chapters.size > 100)
            assertTrue(first.length > 100)
            assertEquals(chapters.size, progress.last())
            checks += buildJsonObject {
                put("query", query); put("source", detail.sourceId); put("resultCount", books.size)
                put("catalogCount", chapters.size); put("firstChapter", chapters.first().title)
                put("firstContentCharacters", first.length); put("coverProvided", detail.coverUrl.isNotBlank())
                put("coverHttp", coverStatus); put("coverMime", coverType)
                put("seconds", (System.nanoTime() - started) / 1_000_000_000.0)
                put("searchMs", (searched - started) / 1_000_000.0)
                put("openToTextMs", (opened - searched) / 1_000_000.0)
                put("cachedChapterMs", cachedMs)
            }
        }
        File(project, "outputs/reader5-direct-live.json").writeText(Json { prettyPrint = true }
            .encodeToString(JsonObject.serializer(), buildJsonObject {
                put("network", "JVM OkHttp Proxy.NO_PROXY; standard TLS validation")
                put("checks", JsonArray(checks)); put("allPassed", true)
                put("bodySaved", false)
            }))
    }
}
