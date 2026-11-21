package com.constx.onvyra.ui.pages.novel

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.net.Proxy
import java.io.IOException
import okhttp3.Call
import okhttp3.EventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opted-in public source protocol probe; no physical-device execution. */
class ReaderYuluCompatibilityLiveTest {
    @Test fun originalYuluRuleSearchesAndIndependentlyReadsSeveralPublicWorks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("reader_live") == "true")
        check(args.getString("lab_avd_name") == "CodexReaderBarsQA")
        val avd = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
        ).bufferedReader().use { it.readText().trim() }
        check(avd == "CodexReaderBarsQA")
        val context = instrumentation.targetContext
        val isolated = File(context.cacheDir, "reader17-yulu-live-${System.nanoTime()}").apply { mkdirs() }
        val transport = CopyOnWriteArrayList<JsonObject>()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    transport += buildJsonObject { put("host", call.request().url.host); put("errorType", ioe.javaClass.simpleName) }
                }
            })
            .addInterceptor { chain ->
                val start = SystemClock.elapsedRealtime()
                chain.proceed(chain.request()).also { response ->
                    transport += buildJsonObject { put("host", chain.request().url.host); put("http", response.code)
                        put("headersMs", SystemClock.elapsedRealtime() - start) }
                }
            }.build()
        val rows = mutableListOf<JsonObject>()
        var fatal: String? = null
        try {
            // Import the actual asset, including its original jsLib. No source
            // protocol constants, endpoints or rule bodies are exported.
            val library = ReaderSourceLibrary {
                context.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
            }
            val rules = library.searchable(0).filter { it.name == "雨鹿小说" }
            check(rules.isNotEmpty())
            val samples = listOf(
                "三天被打七次？现在全校喊我飞哥！" to "抚琴的人",
                "诡秘之主" to "爱潜水的乌贼"
            )
            for (rule in rules) {
                // One adapter per source keeps only this isolated run's source
                // variables warm across search/detail/catalog/content sessions.
                val phases = ReaderPhaseTrace()
                val adapter = LegadoSourceAdapter(client, library, phases::record)
                for ((title, author) in samples) for (mode in listOf("title", "author")) {
                    phases.clear()
                    val start = SystemClock.elapsedRealtime()
                    var stage = "search"
                    var hits = emptyList<NovelBook>()
                    val readings = mutableListOf<JsonObject>()
                    var failure: String? = null
                    val stageTimes = linkedMapOf<String, Long>()
                    suspend fun <T> timed(name: String, timeout: Long, block: suspend () -> T): T {
                        val began = SystemClock.elapsedRealtime()
                        try { return withTimeout(timeout) { block() } }
                        finally { stageTimes[name] = SystemClock.elapsedRealtime() - began }
                    }
                    try {
                        hits = timed("searchMs", 20_000) { adapter.search(if (mode == "title") title else author, rule.id) }
                        val expected = NovelBook("probe", title, author, detailUrl = "", sourceId = rule.id)
                        val exact = hits.filter { ReaderBookIdentity.matches(expected, it) }
                        val candidates = exact.ifEmpty { hits.filter {
                            ReaderBookIdentity.normalizeAuthor(it.author) == ReaderBookIdentity.normalizeAuthor(author)
                        } }.take(2)
                        stage = if (hits.isEmpty()) "empty_search" else if (candidates.isEmpty()) "no_matching_author" else "metadata"
                        for ((index, candidate) in candidates.withIndex()) {
                            var candidateStage = "detail"
                            var detail: NovelBook? = null
                            var chapterCount = 0
                            var bodyCharacters = 0
                            var readable = false
                            var candidateFailure: String? = null
                            try {
                                val fetchedDetail = timed("detail${index}Ms", 12_000) {
                                    adapter.detail(candidate.copy(description = "", canonicalTitle = ""), requireIdentityEvidence = true)
                                }
                                detail = fetchedDetail
                                check(ReaderBookIdentity.matches(candidate, fetchedDetail) && !fetchedDetail.isComic && !fetchedDetail.isAudio)
                                candidateStage = "catalog"
                                val catalog = timed("catalog${index}Ms", 20_000) { adapter.catalog(fetchedDetail.detailUrl, fetchedDetail.sourceId) }
                                chapterCount = catalog.size
                                check(catalog.isNotEmpty())
                                candidateStage = "body"
                                val body = timed("body${index}Ms", 12_000) { adapter.content(catalog.first().url, fetchedDetail.sourceId) }
                                bodyCharacters = body.length
                                ReaderSourceContentValidator.requireReadable(body, catalog.first().title)
                                readable = true
                                candidateStage = "complete"
                            } catch (cancelled: CancellationException) {
                                currentCoroutineContext().ensureActive(); candidateFailure = cancelled.javaClass.simpleName
                            } catch (error: Exception) { candidateFailure = error.javaClass.simpleName }
                            readings += buildJsonObject {
                                put("title", candidate.title); put("author", candidate.author)
                                put("matchesRequestedTitleAndAuthor", ReaderBookIdentity.matches(expected, candidate))
                                put("independentDetailTitle", detail?.title.orEmpty()); put("independentDetailAuthor", detail?.author.orEmpty())
                                put("stage", candidateStage); put("readable", readable)
                                put("chapters", chapterCount); put("bodyCharacters", bodyCharacters)
                                candidateFailure?.let { put("errorType", it) }
                            }
                        }
                        if (readings.any { it["readable"]?.jsonPrimitive?.booleanOrNull == true }) stage = "readable_candidate"
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive(); failure = cancelled.javaClass.simpleName
                    } catch (error: Exception) { failure = error.javaClass.simpleName }
                    finally {
                        rows += buildJsonObject {
                            put("workTitle", title); put("workAuthor", author); put("queryMode", mode)
                            put("sourceId", rule.id); put("stage", stage); put("hits", hits.size)
                            put("exactHits", hits.count { ReaderBookIdentity.matches(
                                NovelBook("probe", title, author, detailUrl = "", sourceId = rule.id), it) })
                            put("elapsedMs", SystemClock.elapsedRealtime() - start)
                            put("timings", buildJsonObject { stageTimes.forEach { (key, value) -> put(key, value) } })
                            put("metadata", JsonArray(hits.take(16).map { candidate -> buildJsonObject {
                                put("title", candidate.title); put("author", candidate.author)
                                put("synopsisCharacters", candidate.description.length)
                            } }))
                            put("readings", JsonArray(readings))
                            failure?.let { put("errorType", it) }
                            put("phases", JsonArray(phases.snapshot().map { phase -> buildJsonObject {
                                put("sourceId", phase.sourceId); put("phase", phase.phase)
                                put("queueMs", phase.queueMs); put("executionMs", phase.executionMs)
                                put("status", phase.status); put("startedAtMs", phase.startedAtMs)
                            } }))
                        }
                    }
                }
            }
        } catch (error: Exception) { fatal = error.javaClass.simpleName }
        finally {
            File(context.filesDir, "reader-qa").apply { mkdirs() }.resolve("reader17-yulu-live.json").writeText(buildJsonObject {
                put("samples", JsonArray(rows)); put("chapterBodiesExported", false)
                put("transport", JsonArray(transport.takeLast(100)))
                put("protocolReadable", rows.any { row -> (row["readings"] as? JsonArray).orEmpty().any {
                    (it as? JsonObject)?.get("readable")?.jsonPrimitive?.booleanOrNull == true
                } })
                put("urlsExported", false); put("headersExported", false); put("userCredentialsProvided", false)
                put("readingACandidateDoesNotProveAnUnknownRetitling", true)
                fatal?.let { put("fatalErrorType", it) }
            }.toString())
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            check(isolated.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            isolated.deleteRecursively()
        }
    }
}
