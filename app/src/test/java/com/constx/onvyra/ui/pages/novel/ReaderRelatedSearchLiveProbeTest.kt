package com.constx.onvyra.ui.pages.novel

import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional live search metadata comparison, never chapter-body downloads. */
class ReaderRelatedSearchLiveProbeTest {
    @Test fun compareUserPhraseWithItsTitlePrefixOnTheSameSourceBatch(): Unit = runBlocking {
        assumeTrue(System.getenv("ONVYRA_READER_RELATED_LIVE") == "1")
        val project = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
        val assets = File(project, "app/src/main/assets/reader")
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
        val reports = mutableListOf<JsonObject>()
        for (query in listOf("我有一群地球人", "我有一群地球")) {
            val library = ReaderSourceLibrary { File(assets, "book-sources-489.json").readText() }
            val engine = NovelSourceEngine(client, sourceLibrary = library)
            val began = System.nanoTime()
            var firstMs: Long? = null
            var last: ReaderSearchProgress? = null
            val books = engine.searchAggregate(query, 0) { progress ->
                if (firstMs == null && progress.books.isNotEmpty()) firstMs = (System.nanoTime() - began) / 1_000_000
                last = progress
            }
            reports += buildJsonObject {
                put("query", query); put("firstResultMs", firstMs?.let(::JsonPrimitive) ?: JsonNull)
                put("elapsedMs", (System.nanoTime() - began) / 1_000_000)
                put("scheduled", last?.scheduledSources ?: 0); put("eligible", last?.eligibleSources ?: 0)
                put("books", JsonArray(books.take(40).map { book -> buildJsonObject {
                    put("title", book.title); put("author", book.author); put("sourceCount", book.sourceCount)
                } }))
                put("errors", JsonObject(last?.errors.orEmpty().mapValues { JsonPrimitive(it.value) }))
            }
        }
        File(project, "outputs/reader34-related-search-live.json").apply {
            parentFile.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(reports)))
        }
    }
}
