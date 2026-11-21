package com.constx.onvyra.ui.pages.novel

import java.io.File
import java.net.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in desktop network evidence, not a phone/Coil rendering claim. */
class ReaderCoverLiveProbeTest {
    @Test fun matchedPublisherCoversAreActualImages(): Unit = runBlocking {
        assumeTrue(System.getenv("ONVYRA_READER_COVER_LIVE") == "1")
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        val resolver = NovelCoverResolver(client)
        val records = mutableListOf<JsonObject>()
        for ((title, author) in listOf("诡秘之主" to "爱潜水的乌贼", "斗破苍穹" to "天蚕土豆", "我的职业太有个性" to "团又圆")) {
            val book = NovelBook("live-$title", title, author, detailUrl = "https://www.biquge.skin/", sourceId = "biquge_skin")
            val found = resolver.resolve(book)
            assertNotNull("No exact publisher cover for $title / $author", found)
            val cover = found!!
            client.newCall(Request.Builder().url(cover.url).header("Referer", cover.referer).build()).execute().use { response ->
                val bytes = response.body.source().readByteArray(128)
                assertEquals(200, response.code)
                assertTrue(response.header("Content-Type").orEmpty().startsWith("image/"))
                assertTrue("Actual JPEG/PNG data required", ((bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 255) == 216) ||
                    ((bytes[0].toInt() and 255) == 137 && (bytes[1].toInt() and 255) == 80))
                records += buildJsonObject {
                    put("title", title); put("author", author); put("sourcePage", cover.sourcePage)
                    put("imageUrl", cover.url); put("imageHttp", response.code); put("mime", response.header("Content-Type"))
                    put("identity", "exact title and cleaned author from the same publisher card")
                }
            }
        }
        val project = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
        File(project, "outputs/reader5-cover-live.json").writeText(buildJsonObject {
            put("environment", "Desktop JVM, Proxy.NO_PROXY, publisher search and CDN, not physical phone")
            put("records", JsonArray(records)); put("bodySaved", false); put("allPassed", true)
        }.toString())
    }
}
