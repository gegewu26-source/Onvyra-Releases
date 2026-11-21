package com.constx.onvyra.ui.pages.novel

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Focused, opt-in native-rule diagnostics. Never exports chapter bodies or headers. */
class ReaderCatalogSourceDiagnosticLiveTest {
    @Test fun preferredRulesReportTheirRealMetadataAndEachReadingStage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_live") == "true")
        val avd = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
        ).bufferedReader().use { it.readText().trim() }
        check(avd == "CodexReaderBarsQA")
        val context = instrumentation.targetContext
        val library = ReaderSourceLibrary { context.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() } }
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).build()
        val adapter = LegadoSourceAdapter(client, library)
        val engine = NovelSourceEngine(client, sourceLibrary = library)
        val rules = library.searchable(0).filter { it.url.contains("www.rulianshi.org") || it.url.contains("www.yetianlian.info") }
            .distinctBy { it.url.substringBefore('#').trimEnd('/') }
        val samples = listOf("万古神帝" to "飞天鱼", "三天被打七次？现在全校喊我飞哥！" to "抚琴的人")
        val results = mutableListOf<JsonObject>()
        try {
            for ((title, author) in samples) for (rule in rules) {
                for (mode in listOf("title", "author")) {
                    val start = android.os.SystemClock.elapsedRealtime()
                    val rows = mutableListOf<JsonObject>()
                    var errorType: String? = null
                    var hitsCount = 0
                    var leads = emptyList<NovelBook>()
                    try {
                        val hits = withTimeout(7_000) { adapter.search(if (mode == "title") title else author, rule.id) }
                        hitsCount = hits.size
                        leads = hits.take(16)
                        val titleMatches = hits.filter { ReaderBookIdentity.searchTitle(it.title) == title }
                        val exact = titleMatches.ifEmpty { hits.filter { it.author.filterNot(Char::isWhitespace).contains(author) } }
                        val inspect = exact.ifEmpty { hits }.take(3)
                        for (candidate in inspect) {
                            var stage = "search_metadata"
                            var detail: NovelBook? = null
                            var chapters = 0
                            var bodyCharacters = 0
                            var readable = false
                            var failure: String? = null
                            try {
                                stage = "detail"
                                detail = withTimeout(7_000) { adapter.detail(candidate.copy(description = ""), requireIdentityEvidence = true) }
                                stage = "catalog"
                                val catalog = withTimeout(12_000) { engine.getCatalog(detail.detailUrl, detail.sourceId) }
                                chapters = catalog.size
                                if (catalog.isNotEmpty()) {
                                    stage = "body"
                                    val body = withTimeout(7_000) { engine.getChapterContent(catalog.first().url, detail.sourceId) }
                                    bodyCharacters = body.length
                                    ReaderSourceContentValidator.requireReadable(body, catalog.first().title)
                                    readable = true
                                    stage = "complete"
                                }
                            } catch (cancel: CancellationException) {
                                currentCoroutineContext().ensureActive(); failure = cancel.javaClass.simpleName
                            } catch (error: Exception) { failure = error.javaClass.simpleName }
                            rows += buildJsonObject {
                                put("searchTitle", candidate.title); put("searchAuthor", candidate.author)
                                put("searchSynopsisCharacters", candidate.description.length)
                                put("detailTitle", detail?.title.orEmpty()); put("detailAuthor", detail?.author.orEmpty())
                                put("detailSynopsisCharacters", detail?.description?.length ?: 0)
                                put("authorMatches", detail?.let { ReaderBookIdentity.normalizeAuthor(it.author) == ReaderBookIdentity.normalizeAuthor(author) } ?: false)
                                put("stage", stage); put("chapters", chapters); put("bodyCharacters", bodyCharacters); put("readable", readable)
                                failure?.let { put("errorType", it) }
                            }
                        }
                    } catch (cancel: CancellationException) {
                        currentCoroutineContext().ensureActive(); errorType = cancel.javaClass.simpleName
                    } catch (error: Exception) { errorType = error.javaClass.simpleName }
                    results += buildJsonObject {
                        put("workTitle", title); put("mode", mode); put("rule", rule.id)
                        put("host", rule.url.substringAfter("://").substringBefore('/').substringBefore('#'))
                        put("hits", hitsCount); put("rows", JsonArray(rows)); put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start)
                        put("leads", JsonArray(leads.map { lead -> buildJsonObject {
                            put("title", lead.title); put("author", lead.author); put("synopsisCharacters", lead.description.length)
                        } }))
                        errorType?.let { put("errorType", it) }
                    }
                }
            }
        } finally {
            File(context.filesDir, "reader-qa").apply { mkdirs() }.resolve("reader16-source-diagnostic-live.json").writeText(buildJsonObject {
                put("samples", JsonArray(results)); put("chapterBodiesExported", false); put("headersExported", false)
            }.toString())
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        }
    }
}
