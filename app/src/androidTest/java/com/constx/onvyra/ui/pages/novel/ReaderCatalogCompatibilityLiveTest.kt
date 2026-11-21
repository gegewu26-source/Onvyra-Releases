package com.constx.onvyra.ui.pages.novel

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real public metadata -> several unrelated catalog works -> actual body -> disk route reuse. */
class ReaderCatalogCompatibilityLiveTest {
    @Test fun severalUntouchedStoreEntriesResolveReadAndReuseRoutes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("reader_live") == "true")
        check(args.getString("lab_avd_name") == "CodexReaderBarsQA")
        val avd = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
        ).bufferedReader().use { it.readText().trim() }
        check(avd == "CodexReaderBarsQA")
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "reader16-catalog-live-${System.nanoTime()}").apply { mkdirs() }
        val routes = File(directory, "routes")
        val requests = AtomicInteger()
        val offlineRequests = AtomicInteger()
        val transport = CopyOnWriteArrayList<JsonObject>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    val reason = when {
                        ioe.message?.contains("CRC", ignoreCase = true) == true -> "InvalidGzipCRC"
                        ioe.message?.contains("unexpected end", ignoreCase = true) == true -> "UnexpectedEnd"
                        ioe.message?.contains("reset", ignoreCase = true) == true -> "ConnectionReset"
                        else -> ioe.javaClass.simpleName
                    }
                    transport += buildJsonObject {
                        put("host", call.request().url.host); put("transportFailure", reason)
                    }
                }
            })
            .addInterceptor { chain ->
                check(chain.request().url.host !in setOf("biquge.skin", "www.biquge.skin", "apibi.cc"))
                requests.incrementAndGet()
                val start = SystemClock.elapsedRealtime()
                chain.proceed(chain.request()).also { response ->
                    transport += buildJsonObject {
                        put("host", chain.request().url.host); put("http", response.code)
                        put("headersMs", SystemClock.elapsedRealtime() - start)
                    }
                }
            }.build()
        val offline = OkHttpClient.Builder().addInterceptor {
            offlineRequests.incrementAndGet()
            throw IOException("Restart route lookup must not re-search")
        }.build()
        fun library() = ReaderSourceLibrary {
            context.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
        }
        val results = mutableListOf<JsonObject>()
        var homeFailure: String? = null
        try {
            val home = withTimeout(55_000) { ReaderStoreRepository(client, File(directory, "store")).home("male", force = true) }
            val works = home.sections.flatMap { it.books }.distinctBy { it.id }
            val prefixes = listOf("三天被打七次", "万古神帝", "大明铁血监国")
            val engine = NovelSourceEngine(client, File(directory, "sources"), library())
            engine.useReadingRouteDirectory(routes)
            for (prefix in prefixes) {
                engine.clearSourcePhases()
                val start = SystemClock.elapsedRealtime()
                var stage = "home_metadata"
                var title = prefix
                var source = ""
                var chapters = 0
                var characters = 0
                var routeMs = 0L
                var cacheMs = 0L
                var ok = false
                var errorType: String? = null
                try {
                    val work = works.firstOrNull { it.title.startsWith(prefix) }
                        ?: error("Requested sample is absent from the current live homepage")
                    title = work.title
                    stage = "resolve_original_metadata"
                    val routeStart = SystemClock.elapsedRealtime()
                    val resolved = withTimeout(25_000) { engine.resolveCatalogBook(work) }
                    routeMs = SystemClock.elapsedRealtime() - routeStart
                    source = resolved.sourceId
                    stage = "detail_catalog_body"
                    val detail = withTimeout(20_000) { engine.getBookDetail(resolved) }
                    val catalog = withTimeout(35_000) { engine.getCatalog(detail.detailUrl, detail.sourceId) }
                    check(catalog.isNotEmpty())
                    chapters = catalog.size
                    characters = withTimeout(20_000) { engine.getChapterContent(catalog.first().url, detail.sourceId).length }
                    check(characters > 300)
                    engine.recordReadingRouteSuccess(detail)
                    stage = "restart_offline_route"
                    val restarted = NovelSourceEngine(offline, File(directory, "fresh-sources-$prefix"), library())
                    restarted.useReadingRouteDirectory(routes)
                    val cacheStart = SystemClock.elapsedRealtime()
                    val restored = withTimeout(5_000) { restarted.resolveCatalogBook(work) }
                    cacheMs = SystemClock.elapsedRealtime() - cacheStart
                    check(restored.detailUrl == detail.detailUrl && restored.sourceId == detail.sourceId)
                    check(offlineRequests.get() == 0)
                    stage = "complete"
                    ok = true
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    errorType = cancelled.javaClass.simpleName
                } catch (error: Exception) {
                    errorType = error.javaClass.simpleName
                }
                results += buildJsonObject {
                    put("title", title); put("ok", ok); put("stage", stage)
                    put("source", source); put("chapters", chapters); put("bodyCharacters", characters)
                    put("routeMs", routeMs); put("offlineRouteMs", cacheMs)
                    put("totalMs", SystemClock.elapsedRealtime() - start)
                    errorType?.let { put("errorType", it) }
                    put("phases", JsonArray(engine.sourcePhaseSnapshot().map { phase -> buildJsonObject {
                        put("sourceId", phase.sourceId); put("phase", phase.phase)
                        put("queueMs", phase.queueMs); put("executionMs", phase.executionMs)
                        put("status", phase.status); put("startedAtMs", phase.startedAtMs)
                    } }))
                }
            }
        } catch (error: Exception) {
            homeFailure = error.javaClass.simpleName
        } finally {
            val report = buildJsonObject {
                put("ok", results.size == 3 && results.all { it["ok"].toString() == "true" })
                put("samples", JsonArray(results)); put("networkRequests", requests.get())
                put("transport", JsonArray(transport.toList()))
                put("offlineRouteNetworkRequests", offlineRequests.get())
                put("chapterBodiesExported", false); put("userCredentialsProvided", false)
                homeFailure?.let { put("homeErrorType", it) }
            }
            File(context.filesDir, "reader-qa").apply { mkdirs() }
                .resolve(args.getString("reader_report")?.takeIf { it.matches(Regex("reader[0-9]+-catalog-live\\.json")) }
                    ?: "reader17-catalog-live.json").writeText(report.toString())
            client.dispatcher.cancelAll(); offline.dispatcher.cancelAll()
            client.connectionPool.evictAll(); offline.connectionPool.evictAll()
            check(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
        assertTrue("Actual public metadata/catalog/body or route reuse remains unresolved; see the redacted sample report",
            homeFailure == null && results.size == 3 && results.all { it["ok"].toString() == "true" })
    }
}
