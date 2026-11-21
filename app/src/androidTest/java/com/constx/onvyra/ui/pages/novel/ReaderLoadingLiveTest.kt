package com.constx.onvyra.ui.pages.novel

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual public network timings; emulator numbers never imply physical-phone speed. */
class ReaderLoadingLiveTest {
    @Test fun recordColdFirstSectionWarmStoreAndExactReadingRouteTimings() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "CodexReaderQA")
        val base = instrumentation.targetContext
        val dir = File(base.cacheDir, "reader10-latency-${System.nanoTime()}").apply { mkdirs() }
        val requests = CopyOnWriteArrayList<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.queryParameter("token") == null && request.url.queryParameter("userid") == null)
            check(request.url.host !in setOf("biquge.skin", "www.biquge.skin", "apibi.cc"))
            val start = SystemClock.elapsedRealtime()
            chain.proceed(request).also { response -> requests += buildJsonObject {
                put("host", request.url.host); put("path", request.url.encodedPath)
                put("ms", SystemClock.elapsedRealtime() - start); put("code", response.code)
            } }
        }.build()
        try {
            val repository = ReaderStoreRepository(client, File(dir, "store"))
            var first = -1L
            val start = SystemClock.elapsedRealtime()
            val home = withTimeout(55_000) { repository.home("male", true) {
                if (first < 0 && it.sections.isNotEmpty()) first = SystemClock.elapsedRealtime() - start
            } }
            val total = SystemClock.elapsedRealtime() - start
            assertTrue(first >= 0 && first <= total)
            assertTrue(home.sections.size >= 8)
            val beforeWarm = requests.size
            val warmStart = SystemClock.elapsedRealtime()
            val warm = repository.home("male")
            val warmMs = SystemClock.elapsedRealtime() - warmStart
            assertEquals(home.sections, warm.sections); assertEquals(beforeWarm, requests.size)
            val sources = ReaderSourceLibrary { base.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() } }
            val sourcesStart = SystemClock.elapsedRealtime()
            val sourceCount = sources.all().size
            val sourceLoadMs = SystemClock.elapsedRealtime() - sourcesStart
            val engine = NovelSourceEngine(client, sourceLibrary = sources)
            val work = home.sections.flatMap { it.books }.firstOrNull { it.title == "仙逆" }
                ?: home.sections.flatMap { it.books }.first { it.title == "完美世界" }
            val routeStart = SystemClock.elapsedRealtime()
            val book = withTimeout(20_000) { engine.resolveCatalogBook(work) }
            val routeMs = SystemClock.elapsedRealtime() - routeStart
            val detailsStart = SystemClock.elapsedRealtime()
            val details = withTimeout(20_000) { engine.getBookDetail(book) }
            val detailsMs = SystemClock.elapsedRealtime() - detailsStart
            val catalogStart = SystemClock.elapsedRealtime()
            val chapters = withTimeout(25_000) { engine.getCatalog(details.detailUrl, details.sourceId) }
            val catalogMs = SystemClock.elapsedRealtime() - catalogStart
            val bodyStart = SystemClock.elapsedRealtime()
            val body = withTimeout(20_000) { engine.getChapterContent(chapters.first().url, details.sourceId) }
            val bodyMs = SystemClock.elapsedRealtime() - bodyStart
            assertTrue(chapters.size > 100 && body.length > 500)
            val beforeRouteWarm = requests.size
            val warmRouteStart = SystemClock.elapsedRealtime()
            val repeat = engine.resolveCatalogBook(work)
            val repeatMs = SystemClock.elapsedRealtime() - warmRouteStart
            assertTrue(ReaderBookIdentity.matches(work, repeat))
            assertEquals(book.sourceId, repeat.sourceId); assertEquals(beforeRouteWarm, requests.size)
            val result = buildJsonObject {
                put("environment", "Independent Android35 emulator, real public network, no physical phone")
                put("store_first_section_ms", first); put("store_complete_ms", total); put("store_warm_ms", warmMs)
                put("store_sections", home.sections.size); put("store_books", home.sections.sumOf { it.books.size })
                put("reading_title", work.title); put("match_cold_ms", routeMs); put("match_warm_ms", repeatMs)
                put("source_library_load_ms", sourceLoadMs); put("source_library_records", sourceCount)
                put("detail_ms", detailsMs); put("catalog_ms", catalogMs); put("catalog_chapters", chapters.size)
                put("first_body_ms", bodyMs); put("body_characters", body.length)
                put("warm_store_network_requests", 0); put("warm_route_network_requests", 0)
                put("requests", JsonArray(requests))
            }
            File(base.filesDir, "reader-qa").mkdirs()
            File(base.filesDir, "reader-qa/reader10-latency.json").writeText(result.toString())
        } finally { dir.deleteRecursively() }
    }
}
