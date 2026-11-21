package com.constx.onvyra.ui.pages.novel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.Proxy
import java.net.InetSocketAddress

/** Explicit opt-in network probe, invoked separately from the offline UI test. */
@RunWith(AndroidJUnit4::class)
class ReaderSourceLiveProbe {
    @Test fun verifyRealSourceMetadataAndReadingBoundaries() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val proxyHost = arguments.getString("proxyHost")
        val builder = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
        if (!proxyHost.isNullOrBlank()) builder.proxy(Proxy(Proxy.Type.HTTP,
            InetSocketAddress(proxyHost, arguments.getString("proxyPort", "7897").toInt())))
        val client = builder.build()
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = NovelSourceEngine(client, base.cacheDir)
        val checks = mutableListOf<JsonObject>()
        var novelOk = false
        var comicOk = false
        var skinOk = false
        var piqugeOk = false
        try {
            val books = engine.search("诡秘之主", "bqgnovels")
            val book = books.first { it.title == "诡秘之主" }
            val detailed = engine.getBookDetail(book)
            val chapters = engine.getCatalog(detailed.detailUrl, detailed.sourceId)
            val first = engine.getChapterContent(chapters.first().url, detailed.sourceId)
            val last = engine.getChapterContent(chapters.last().url, detailed.sourceId)
            var coverStatus = 0
            var coverMime = ""
            client.newCall(Request.Builder().url(book.coverUrl)
                .header("Referer", book.coverReferer).build()).execute().use {
                coverStatus = it.code
                coverMime = it.header("Content-Type").orEmpty()
            }
            novelOk = chapters.size > 100 && first.isNotBlank() && last.isNotBlank() && coverStatus == 200 && coverMime.startsWith("image/")
            checks += buildJsonObject {
                put("type", "novel"); put("source", book.sourceId); put("title", book.title)
                put("search_count", books.size); put("catalog_count", chapters.size)
                put("first_chapter", chapters.first().title); put("last_chapter", chapters.last().title)
                put("unique_chapter_urls", chapters.map { it.url }.distinct().size)
                put("first_text_characters", first.length); put("last_text_characters", last.length)
                put("cover_http", coverStatus); put("cover_mime", coverMime); put("ok", novelOk)
            }
        } catch (e: Exception) {
            checks += buildJsonObject { put("type", "novel"); put("ok", false); put("error", e.message.orEmpty().take(240)) }
        }
        try {
            val search = engine.searchComics("非人哉", "dmmh")
            val url = "https://www.dongmanmanhua.cn/BOY/jiyiwanwudetiancaiwuzhe/list?title_no=2937"
            val catalog = engine.getComicCatalog(url, "dmmh")
            val images = engine.getComicChapterPages(catalog.first().url, "dmmh")
            var imageStatus = 0
            var mime = ""
            client.newCall(Request.Builder().url(images.first()).header("Referer", catalog.first().url).build()).execute().use {
                imageStatus = it.code; mime = it.header("Content-Type").orEmpty()
            }
            comicOk = search.isNotEmpty() && catalog.size == 3 && images.isNotEmpty() && imageStatus == 200 && mime.startsWith("image/")
            checks += buildJsonObject {
                put("type", "comic"); put("source", "dmmh"); put("search_count", search.size)
                put("catalog_count", catalog.size); put("image_count", images.size)
                put("first_image_http", imageStatus); put("first_image_mime", mime); put("ok", comicOk)
            }
        } catch (e: Exception) {
            checks += buildJsonObject { put("type", "comic"); put("ok", false); put("error", e.message.orEmpty().take(240)) }
        }
        try {
            val search = engine.search("海贼之银狐大将", "biquge_skin")
            val book = engine.getBookDetail(search.first { it.title == "海贼之银狐大将" })
            val chapters = engine.getCatalog(book.detailUrl, book.sourceId)
            val first = engine.getChapterContent(chapters.first().url, book.sourceId)
            val last = engine.getChapterContent(chapters.last().url, book.sourceId)
            skinOk = chapters.size > 100 && first.isNotBlank() && last.isNotBlank()
            checks += buildJsonObject {
                put("type", "skin_novel"); put("title", book.title); put("catalog_count", chapters.size)
                put("search_count", search.size)
                put("first_chapter", chapters.first().title); put("last_chapter", chapters.last().title)
                put("first_text_characters", first.length); put("last_text_characters", last.length); put("ok", skinOk)
            }
        } catch (e: Exception) {
            checks += buildJsonObject { put("type", "skin_novel"); put("ok", false); put("error", e.message.orEmpty().take(240)) }
        }
        try {
            val book = engine.search("https://www.piquge.com/190/190260/", "piquge").single()
            val chapters = engine.getCatalog(book.detailUrl, book.sourceId)
            val first = engine.getChapterContent(chapters.first().url, book.sourceId)
            val last = engine.getChapterContent(chapters.last().url, book.sourceId)
            piqugeOk = chapters.size > 100 && first.isNotBlank() && last.isNotBlank()
            checks += buildJsonObject {
                put("type", "piquge_novel"); put("title", book.title); put("catalog_count", chapters.size)
                put("unique_chapter_urls", chapters.map { it.url }.distinct().size)
                put("first_chapter", chapters.first().title); put("last_chapter", chapters.last().title)
                put("first_text_characters", first.length); put("last_text_characters", last.length); put("ok", piqugeOk)
            }
        } catch (e: Exception) {
            checks += buildJsonObject { put("type", "piquge_novel"); put("ok", false); put("error", e.message.orEmpty().take(240)) }
        }
        val report = buildJsonObject {
            put("environment", "Android 15 emulator; real application source engines; HTTPS verification enabled")
            put("network", if (proxyHost.isNullOrBlank()) "direct" else "existing host proxy; probe-only argument")
            put("checks", JsonArray(checks)); put("ok", novelOk && comicOk && skinOk && piqugeOk)
        }
        File(base.filesDir, "reader-source-live.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        assertTrue("Live source probe incomplete; inspect reader-source-live.json", novelOk && comicOk && skinOk && piqugeOk)
    }
}
