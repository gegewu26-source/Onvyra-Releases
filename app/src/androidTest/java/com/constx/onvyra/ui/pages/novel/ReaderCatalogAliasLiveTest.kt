package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual home metadata and public reading sources; no physical-phone or visible-UI claim. */
class ReaderCatalogAliasLiveTest {
    @Test fun aliasedWanGuShenDiHomeEntryResolvesAndOpensTheSameWork() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(UiDevice.getInstance(instrumentation)
            .executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "CodexReaderQA") {
            "Dedicated CodexReaderQA emulator only"
        }
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "reader12-catalog-live-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val requests = CopyOnWriteArrayList<JsonObject>()
        val requestCount = AtomicInteger()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).addInterceptor { chain ->
                val request = chain.request()
                check(request.url.queryParameter("token") == null && request.url.queryParameter("userid") == null)
                check(request.url.host !in setOf("biquge.skin", "www.biquge.skin", "apibi.cc"))
                requestCount.incrementAndGet()
                val start = SystemClock.elapsedRealtime()
                var code: Int? = null
                var failure: String? = null
                try {
                    chain.proceed(request).also { code = it.code }
                } catch (error: Exception) {
                    failure = error.javaClass.simpleName
                    throw error
                } finally {
                    // Queries, headers, response bodies and error messages can contain secrets.
                    requests += buildJsonObject {
                        put("host", request.url.host); put("path", request.url.encodedPath)
                        put("ms", SystemClock.elapsedRealtime() - start)
                        code?.let { put("code", it) }; failure?.let { put("error_type", it) }
                    }
                }
            }.build()
        var vm: NovelViewModel? = null
        var stage = "home"
        var passed = false
        var failureType: String? = null
        val evidence = linkedMapOf<String, JsonObject>()
        try {
            val repository = ReaderStoreRepository(client, File(context.cacheDir, "store"))
            val homeStart = SystemClock.elapsedRealtime()
            val home = withTimeout(55_000) { repository.home("male", force = true) }
            val homeMs = SystemClock.elapsedRealtime() - homeStart
            val section = home.sections.firstOrNull { section -> section.books.any {
                ReaderBookIdentity.searchTitle(it.title) == "万古神帝" &&
                    ReaderBookIdentity.normalize(it.author) == "飞天鱼"
            } } ?: error("Actual male home no longer contains the reported title and author")
            val work = section.books.first {
                ReaderBookIdentity.searchTitle(it.title) == "万古神帝" &&
                    ReaderBookIdentity.normalize(it.author) == "飞天鱼"
            }
            assertEquals(NovelSourceEngine.SOURCE_CATALOG, work.sourceId)
            assertTrue("The actual home entry must exercise the reported alias suffix",
                Regex("[（(]\\s*(?:又名|原名)\\s*[:：]").containsMatchIn(work.title))
            assertTrue("The home entry must have original artwork", work.coverUrl.isNotBlank())
            assertFalse(NovelCoverResolver.needsCover(work))
            evidence["home"] = buildJsonObject {
                put("ms", homeMs); put("sections", home.sections.size)
                put("section", section.category.title); put("horizontal", section.horizontal)
                put("title", work.title); put("canonical_title", ReaderBookIdentity.searchTitle(work.title))
                put("author", work.author); put("source", work.sourceId)
            }

            stage = "resolve_catalog_entry"
            val sources = ReaderSourceLibrary {
                base.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
            }
            val engine = NovelSourceEngine(client, File(context.cacheDir, "sources"), sourceLibrary = sources)
            val resolveStart = SystemClock.elapsedRealtime()
            val resolved = withTimeout(20_000) { engine.resolveCatalogBook(work) }
            val resolveMs = SystemClock.elapsedRealtime() - resolveStart
            assertEquals("万古神帝", resolved.title)
            assertEquals("飞天鱼", ReaderBookIdentity.normalize(resolved.author))
            assertTrue(ReaderBookIdentity.matches(work, resolved))
            assertTrue("A catalog entry must resolve to an actual bundled source", resolved.sourceId.startsWith("legado_"))
            assertEquals(work.coverUrl, resolved.coverUrl)
            assertEquals(work.coverReferer, resolved.coverReferer)
            assertTrue("Original artwork request headers changed", work.artworkHeaders == resolved.artworkHeaders)

            stage = "resolve_warm"
            val beforeWarm = requestCount.get()
            val warmStart = SystemClock.elapsedRealtime()
            val repeat = withTimeout(5_000) { engine.resolveCatalogBook(work) }
            val warmMs = SystemClock.elapsedRealtime() - warmStart
            val warmRequests = requestCount.get() - beforeWarm
            assertEquals(resolved.sourceId, repeat.sourceId)
            assertEquals(resolved.detailUrl, repeat.detailUrl)
            assertTrue(ReaderBookIdentity.matches(work, repeat))
            assertEquals("Repeat matching must use the exact-work route cache", 0, warmRequests)
            evidence["route"] = buildJsonObject {
                put("title", resolved.title); put("author", resolved.author); put("source", resolved.sourceId)
                put("cold_ms", resolveMs); put("warm_ms", warmMs); put("warm_network_requests", warmRequests)
                put("original_cover_preserved", true)
            }

            stage = "open_original_home_entry"
            val openStart = SystemClock.elapsedRealtime()
            val reader = withContext(Dispatchers.Main) {
                NovelViewModel(context, engine, suppliedStoreRepository = repository).also {
                    vm = it
                    // Feed the untouched catalog object through the same entry point as a home tap.
                    it.openBook(work)
                }
            }
            var firstBodyMs = -1L
            var completeCatalogMs = -1L
            withTimeout(90_000) {
                while (true) {
                    assertTrue("Opening the original home entry reported an error", reader.errorMessage.value == null)
                    if (firstBodyMs < 0 && reader.chapterContent.value.length > 500)
                        firstBodyMs = SystemClock.elapsedRealtime() - openStart
                    if (completeCatalogMs < 0 && reader.isCatalogComplete.value)
                        completeCatalogMs = SystemClock.elapsedRealtime() - openStart
                    if (reader.isCatalogComplete.value && !reader.isLoadingCatalog.value &&
                        !reader.isLoadingChapter.value && reader.chapterContent.value.length > 500) break
                    delay(100)
                }
            }
            val current = reader.currentBook.value ?: error("Reader lost the home work")
            assertEquals("万古神帝", current.title)
            assertEquals("飞天鱼", ReaderBookIdentity.normalize(current.author))
            assertTrue(ReaderBookIdentity.matches(work, current))
            assertTrue(current.sourceId.startsWith("legado_"))
            assertTrue(reader.isCatalogComplete.value)
            assertTrue("A complete long-work catalog is required", reader.catalog.value.size > 1_000)
            assertTrue(reader.chapterContent.value.length > 500)
            assertTrue(reader.errorMessage.value == null)
            assertEquals(work.coverUrl, current.coverUrl)
            assertEquals(work.coverReferer, current.coverReferer)
            assertTrue("Original artwork request headers changed", work.artworkHeaders == current.artworkHeaders)
            evidence["reader"] = buildJsonObject {
                put("title", current.title); put("author", current.author); put("source", current.sourceId)
                put("open_ms", SystemClock.elapsedRealtime() - openStart)
                put("first_body_ms", firstBodyMs); put("catalog_complete_ms", completeCatalogMs)
                put("catalog_complete", reader.isCatalogComplete.value)
                put("catalog_chapters", reader.catalog.value.size)
                put("body_characters", reader.chapterContent.value.length)
                put("error", JsonNull); put("original_cover_preserved", true)
            }
            stage = "complete"
            passed = true
        } catch (error: Throwable) {
            failureType = error.javaClass.simpleName
            throw error
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
                val result = buildJsonObject {
                    put("environment", "CodexReaderQA emulator; real public network; no physical phone or visible UI")
                    put("ok", passed); put("stage", stage)
                    failureType?.let { put("error_type", it) }
                    evidence.forEach { (key, value) -> put(key, value) }
                    put("network_requests", requestCount.get()); put("requests", JsonArray(requests.toList()))
                }
                File(base.filesDir, "reader-qa").apply { mkdirs() }
                    .resolve("reader12-catalog-live.json").writeText(result.toString())
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                directory.deleteRecursively()
            }
        }
    }
}
