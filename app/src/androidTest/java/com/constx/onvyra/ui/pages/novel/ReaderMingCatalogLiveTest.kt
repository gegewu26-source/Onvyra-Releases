package com.constx.onvyra.ui.pages.novel

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in actual homepage-to-first-chapter check. Chapter bodies remain in memory only. */
class ReaderMingCatalogLiveTest {
    @Test fun reportedMingHomeEntryReadsAndANewEngineReusesItsPersistedVerifiedRoute() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_live") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        // All temporary source/store/route data is isolated from the user's reader library.
        val directory = File(base.cacheDir, "reader13-ming-live-${System.nanoTime()}").apply { mkdirs() }
        val routeDirectory = File(directory, "files/reader-reading-routes-v1")
        val requests = CopyOnWriteArrayList<JsonObject>()
        val requestCount = AtomicInteger()
        val unexpectedRestartRequests = AtomicInteger()
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).addInterceptor { chain ->
                val request = chain.request()
                check(request.url.queryParameter("token") == null && request.url.queryParameter("userid") == null)
                check(request.url.host !in setOf("biquge.skin", "www.biquge.skin", "apibi.cc"))
                requestCount.incrementAndGet()
                val start = SystemClock.elapsedRealtime()
                var status: Int? = null
                var failureType: String? = null
                try {
                    chain.proceed(request).also { status = it.code }
                } catch (error: Exception) {
                    failureType = error.javaClass.simpleName
                    throw error
                } finally {
                    // Never export headers, query strings, errors containing URLs, or body text.
                    requests += buildJsonObject {
                        put("host", request.url.host); put("path", request.url.encodedPath)
                        put("ms", SystemClock.elapsedRealtime() - start)
                        status?.let { put("http", it) }; failureType?.let { put("error_type", it) }
                    }
                }
            }.build()
        val offlineClient = OkHttpClient.Builder().addInterceptor {
            unexpectedRestartRequests.incrementAndGet()
            throw IOException("Restarted route resolution must use disk metadata")
        }.build()
        val evidence = linkedMapOf<String, JsonObject>()
        var stage = "home"
        var passed = false
        var failureType: String? = null
        try {
            val repository = ReaderStoreRepository(client, File(directory, "store"))
            val homeStart = SystemClock.elapsedRealtime()
            val home = withTimeout(55_000) { repository.home("male", force = true) }
            val section = home.sections.firstOrNull { section -> section.books.any { it.id == CATALOG_ID } }
                ?: error("Actual male home no longer contains the reported catalog ID")
            // Use the untouched API object, rather than constructing a book from the canonical title.
            val work = section.books.first { it.id == CATALOG_ID }
            assertEquals("大明铁血监国：我爹朱棣，杀穿瓦剌", work.title)
            assertEquals("朕闻上古", ReaderBookIdentity.normalizeAuthor(work.author))
            assertEquals(NovelSourceEngine.SOURCE_CATALOG, work.sourceId)
            assertEquals("大明：家父永乐，永镇山河", ReaderBookIdentity.searchTitle(work))
            evidence["home"] = buildJsonObject {
                put("ms", SystemClock.elapsedRealtime() - homeStart); put("sections", home.sections.size)
                put("section", section.category.title); put("catalog_id", work.id)
                put("title", work.title); put("author", work.author)
                put("canonical_title", ReaderBookIdentity.searchTitle(work)); put("source", work.sourceId)
            }

            val sources = ReaderSourceLibrary {
                base.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
            }
            val engine = NovelSourceEngine(client, File(directory, "sources"), sourceLibrary = sources)
            engine.useReadingRouteDirectory(routeDirectory)
            stage = "resolve_original_home_entry"
            val routeStart = SystemClock.elapsedRealtime()
            val resolved = withTimeout(20_000) { engine.resolveCatalogBook(work) }
            val routeMs = SystemClock.elapsedRealtime() - routeStart
            assertTrue(ReaderBookIdentity.matches(work, resolved))
            assertEquals("大明：家父永乐，永镇山河", resolved.title)
            assertEquals("朕闻上古", ReaderBookIdentity.normalizeAuthor(resolved.author))
            assertTrue(resolved.sourceId.startsWith("legado_"))
            assertTrue(sources.searchable(0).any { it.id == resolved.sourceId })
            assertEquals(work.coverUrl, resolved.coverUrl)
            evidence["route"] = buildJsonObject {
                put("ms", routeMs); put("title", resolved.title); put("author", resolved.author)
                put("normalized_author", ReaderBookIdentity.normalizeAuthor(resolved.author))
                put("source", resolved.sourceId); put("detail_url", resolved.detailUrl)
                put("original_cover_preserved", resolved.coverUrl == work.coverUrl)
            }

            stage = "verify_source_detail"
            val detailStart = SystemClock.elapsedRealtime()
            val detail = withTimeout(25_000) { engine.getBookDetail(resolved) }
            assertTrue(ReaderBookIdentity.matches(work, detail))
            assertEquals("朕闻上古", ReaderBookIdentity.normalizeAuthor(detail.author))
            assertEquals(resolved.sourceId, detail.sourceId)
            evidence["detail"] = buildJsonObject {
                put("ms", SystemClock.elapsedRealtime() - detailStart)
                put("title", detail.title); put("author", detail.author); put("source", detail.sourceId)
            }

            stage = "full_catalog"
            val catalogStart = SystemClock.elapsedRealtime()
            val chapters = withTimeout(45_000) { engine.getCatalog(detail.detailUrl, detail.sourceId) }
            assertTrue("The actual completed work must expose its full long catalog", chapters.size >= 800)
            assertEquals(chapters.size, chapters.map { it.url }.distinct().size)
            assertEquals(chapters.indices.toList(), chapters.map { it.index })
            evidence["catalog"] = buildJsonObject {
                put("ms", SystemClock.elapsedRealtime() - catalogStart); put("chapters", chapters.size)
                put("first_chapter", chapters.first().title); put("last_chapter", chapters.last().title)
                put("distinct_urls", true); put("sequential_indexes", true)
            }

            stage = "first_chapter"
            val bodyStart = SystemClock.elapsedRealtime()
            val bodyCharacters = withTimeout(25_000) {
                engine.getChapterContent(chapters.first().url, detail.sourceId).length
            }
            assertTrue("The first chapter must contain actual source text", bodyCharacters > 500)
            engine.recordReadingRouteSuccess(detail)
            evidence["first_chapter"] = buildJsonObject {
                put("ms", SystemClock.elapsedRealtime() - bodyStart); put("body_characters", bodyCharacters)
                put("chapter_index", chapters.first().index); put("chapter_title", chapters.first().title)
                put("body_saved", false)
            }

            stage = "recreate_engine_from_disk"
            val persistedFiles = routeDirectory.listFiles { file -> file.name.startsWith("route-") && file.name.endsWith(".json") }.orEmpty()
            assertTrue("The first engine must have written its verified route to disk", persistedFiles.isNotEmpty())
            // A separate library and engine have no shared readingRoutes or aggregate memory.
            // Their HTTP client rejects every request, so a false cache hit cannot silently rescan.
            val restartedSources = ReaderSourceLibrary {
                base.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
            }
            val restarted = NovelSourceEngine(offlineClient, File(directory, "restarted-sources"), restartedSources)
            restarted.useReadingRouteDirectory(routeDirectory)
            val restartStart = SystemClock.elapsedRealtime()
            val restored = withTimeout(5_000) { restarted.resolveCatalogBook(work) }
            assertTrue(ReaderBookIdentity.matches(work, restored))
            assertEquals("朕闻上古", ReaderBookIdentity.normalizeAuthor(restored.author))
            assertEquals(detail.sourceId, restored.sourceId)
            assertEquals(detail.detailUrl, restored.detailUrl)
            assertEquals(detail.sourceVariables, restored.sourceVariables)
            assertEquals(0, unexpectedRestartRequests.get())
            evidence["restart"] = buildJsonObject {
                put("ms", SystemClock.elapsedRealtime() - restartStart)
                put("source", restored.sourceId); put("detail_url", restored.detailUrl)
                put("persisted_route_files", persistedFiles.size); put("new_engine", true); put("new_source_library", true)
                put("network_requests", unexpectedRestartRequests.get()); put("same_source_and_work", true)
            }
            stage = "complete"
            passed = true
        } catch (error: Throwable) {
            failureType = error.javaClass.simpleName
            throw error
        } finally {
            withContext(NonCancellable) {
                val result = buildJsonObject {
                    put("environment", "Android instrumentation; real anonymous public metadata/catalog/first-chapter requests; no visible UI assertion")
                    put("ok", passed); put("stage", stage); failureType?.let { put("error_type", it) }
                    evidence.forEach { (key, value) -> put(key, value) }
                    put("network_requests", requestCount.get()); put("requests", JsonArray(requests.toList()))
                    put("restart_network_requests", unexpectedRestartRequests.get()); put("chapter_text_saved", false)
                }
                File(base.filesDir, "reader-qa").apply { mkdirs() }
                    .resolve("reader13-ming-catalog-live.json").writeText(result.toString())
                client.dispatcher.cancelAll(); client.connectionPool.evictAll()
                offlineClient.dispatcher.cancelAll(); offlineClient.connectionPool.evictAll()
                directory.deleteRecursively()
            }
        }
    }

    companion object {
        private const val CATALOG_ID = "catalog_62df4ecb31aff99597da907c"
    }
}
