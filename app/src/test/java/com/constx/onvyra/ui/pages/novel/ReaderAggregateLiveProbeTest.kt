package com.constx.onvyra.ui.pages.novel

import java.io.File
import java.io.ByteArrayInputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real metadata only: no catalog or chapter bodies, no physical-phone claims. */
class ReaderAggregateLiveProbeTest {
    @Test fun realSourcesPublishProgressAndCountOnlyTheirExactMatches(): Unit = runBlocking {
        assumeTrue(System.getenv("ONVYRA_READER_AGGREGATE_LIVE") == "1")
        val project = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
        val assets = File(project, "app/src/main/assets/reader")
        val library = ReaderSourceLibrary { File(assets, "book-sources-489.json").readText() }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
        val engine = NovelSourceEngine(client, File(project, "work/reader6-live-cache"), library,
            skinIndexSeed = { File(assets, "skin-search-v1.bin").inputStream() })
        val query = System.getenv("ONVYRA_READER_AGGREGATE_QUERY") ?: "封神进化"
        val started = System.nanoTime()
        var firstResultMs: Long? = null
        var final: ReaderSearchProgress? = null
        val emissions = mutableListOf<JsonObject>()
        val books = engine.searchAggregate(query, 0,
            continuous = System.getenv("ONVYRA_READER_AGGREGATE_FULL_LIVE") == "1") { progress ->
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (progress.books.isNotEmpty() && firstResultMs == null) firstResultMs = elapsed
            final = progress
            emissions += buildJsonObject {
                put("ms", elapsed); put("completed", progress.completedSources)
                put("results", progress.books.size); put("finished", progress.finished)
            }
        }
        val output = File(project, "outputs/reader6-aggregate-live.json")
        val sourceNames = library.all().associate { it.id to it.name }
        val coverChecks = books.filterNot(NovelCoverResolver::needsCover).distinctBy { it.coverUrl }.take(3).map { book ->
            val safeHeaders = book.artworkHeaders.filterKeys { it.equals("User-Agent", true) || it.equals("Referer", true) }
                .toMutableMap().apply { if (keys.none { it.equals("Referer", true) }) put("Referer", book.coverReferer.ifBlank { book.detailUrl }) }
            var code = 0
            var mime = ""
            var width = 0
            var height = 0
            var byteCount = 0
            var failure = ""
            try {
                val request = Request.Builder().url(book.coverUrl).apply {
                    safeHeaders.forEach { (key, value) -> header(key, value) }
                }.build()
                client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    code = response.code; mime = response.header("Content-Type").orEmpty()
                    if (code == 200) {
                        val source = response.body.source()
                        source.request(2L * 1024 * 1024 + 1)
                        if (source.buffer.size > 2L * 1024 * 1024) error("封面超过2MiB探测上限")
                        val bytes = source.readByteArray(); byteCount = bytes.size
                        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("JVM ImageIO无法解码此响应格式")
                        width = image.width; height = image.height; image.flush()
                    }
                }
            } catch (e: Exception) { failure = e.message.orEmpty().take(200) }
            buildJsonObject {
                put("title", book.title); put("author", book.author); put("actualCoverUrl", book.coverUrl)
                put("headers", JsonObject(safeHeaders.mapValues { JsonPrimitive(it.value) }))
                put("http", code); put("mime", mime); put("bytes", byteCount)
                put("decodedWidth", width); put("decodedHeight", height)
                put("decoded", width > 0 && height > 0); put("error", failure)
            }
        }
        output.parentFile.mkdirs()
        output.writeText(buildJsonObject {
            put("network", "Desktop JVM Proxy.NO_PROXY; standard TLS; real source search metadata only")
            put("query", query); put("firstResultMs", firstResultMs?.let(::JsonPrimitive) ?: JsonNull)
            put("completed", final?.completedSources ?: 0); put("scheduled", final?.scheduledSources ?: 0)
            put("eligible", final?.eligibleSources ?: 0); put("emissions", JsonArray(emissions))
            put("coverChecks", JsonArray(coverChecks)); put("coverDecoder", "Desktop JVM ImageIO; WebP support may differ from Android Coil")
            put("books", JsonArray(books.take(25).map { book -> buildJsonObject {
                put("title", book.title); put("author", book.author); put("sourceCount", book.sourceCount)
                put("coverUrl", book.coverUrl); put("coverReferer", book.coverReferer)
                put("sourceId", book.sourceId); put("detailUrl", book.detailUrl)
                put("artworkHeaders", JsonObject(book.artworkHeaders.filterKeys {
                    it.equals("User-Agent", true) || it.equals("Referer", true)
                }.mapValues { JsonPrimitive(it.value) }))
                put("sources", JsonArray(book.sources.map { hit -> buildJsonObject {
                    put("id", hit.sourceId); put("name", sourceNames[hit.sourceId] ?: hit.sourceId)
                    put("detail", hit.detailUrl); put("hasActualCoverUrl", !NovelCoverResolver.needsCover(hit.asBook()))
                    put("title", hit.title); put("author", hit.author)
                    put("coverUrl", hit.coverUrl); put("coverReferer", hit.coverReferer)
                    put("artworkHeaders", JsonObject(hit.asBook().artworkHeaders.filterKeys {
                        it.equals("User-Agent", true) || it.equals("Referer", true)
                    }.mapValues { JsonPrimitive(it.value) }))
                } })); put("hasActualCoverUrl", !NovelCoverResolver.needsCover(book))
            } })); put("errors", JsonObject(final?.errors.orEmpty().mapValues { JsonPrimitive(it.value) }))
            put("bodySaved", false)
        }.toString())
        assertTrue("No actual search results; see $output", books.isNotEmpty())
        assertTrue(books.all { !it.isComic && !it.isAudio })
        assertTrue(books.all { book -> book.sources.all { ReaderBookIdentity.matches(book, it.asBook()) } })
        assertTrue("No multi-source identity confirmed in this network round; see $output", books.any { it.sourceCount > 1 })
        assertTrue(final!!.finished)
    }
}
