package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class SkinSearchIndexTest {
    private fun seed(vararg rows: SkinSearchIndex.Row): ByteArray {
        val output = ByteArrayOutputStream()
        SkinSearchIndex.Writer(output, "0123456789abcdef01234567", System.currentTimeMillis()).use { writer ->
            rows.forEach(writer::add); writer.finish()
        }
        return output.toByteArray().also { SkinSearchIndex.setCount(it, rows.size) }
    }

    @Test fun exactTitleRanksBeforeLongerCoverRichAndHigherChapterCountResults() {
        val data = seed(
            SkinSearchIndex.Row(9, "诡秘之主同人", "同人作者", "https://cdn.example/cover.jpg", "奇幻", 9000, 0),
            SkinSearchIndex.Row(583, "诡秘之主", "爱潜水的乌贼", "", "奇幻", 1435, 0),
            SkinSearchIndex.Row(10, "我在诡秘之主", "其他作者", "https://cdn.example/c.jpg", "奇幻", 8000, 0),
        )
        val index = SkinSearchIndex.decode(ByteBuffer.wrap(data))!!
        assertEquals(listOf("诡秘之主", "诡秘之主同人", "我在诡秘之主"), index.search("诡秘之主", 120) {}.map { it.title })
        assertEquals("诡秘之主", index.search("爱潜水", 120) {}.single().title)
    }

    @Test fun completeSeedServesNewKeywordsAndBookDetailsWithoutSourceRequests(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.request().url.encodedPath
            assertEquals("/api/booklist", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"list":["第一章 绯红","第二章 情况"]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val bytes = seed(SkinSearchIndex.Row(583, "诡秘之主", "爱潜水的乌贼", "", "奇幻", 1435, 0))
        val directory = Files.createTempDirectory("skin-compact-qa").toFile()
        try {
            val source = SkinNovelSource(client, directory) { ByteArrayInputStream(bytes) }
            val book = source.searchBooks("诡秘之主").single()
            assertEquals(book.title, source.getBookDetail(book).title)
            assertEquals(book.title, source.searchBooks("爱潜水").single().title)
            assertEquals(emptyList<String>(), calls)
            assertEquals(2, source.getCatalog(book.detailUrl).size)
            assertEquals(listOf("/api/booklist"), calls)
            val reopened = SkinNovelSource(client, directory)
            assertEquals(book.title, reopened.searchBooks("诡秘").single().title)
            assertEquals(1, calls.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun bundledSeedFindsThreeDifferentTitlesWithoutNetworkOrJsonShardParsing(): Unit = runBlocking {
        val project = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }
        val asset = File(project, "app/src/main/assets/" + SkinSearchIndex.ASSET_PATH)
        assertTrue("The APK must include its complete metadata seed", asset.isFile)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw AssertionError("Seed search must remain local") }.build()
        val directory = Files.createTempDirectory("skin-bundled-qa").toFile()
        val records = mutableListOf<JsonObject>()
        try {
            // This checks local decoding/search, independently of the asset's age.
            // Keep its real records but make this test fixture fresh, so tomorrow's
            // date cannot turn the local benchmark into a background refresh test.
            val source = SkinNovelSource(client, directory) {
                val input = asset.inputStream()
                val header = ByteArray(16)
                check(input.read(header) == header.size)
                java.nio.ByteBuffer.wrap(header).putLong(8, System.currentTimeMillis())
                java.io.SequenceInputStream(ByteArrayInputStream(header), input)
            }
            for (query in listOf("诡秘之主", "我的职业太有个性", "斗破苍穹")) {
                val started = System.nanoTime()
                val books = source.searchBooks(query)
                assertEquals("An exact title must rank first", query, books.first().title)
                assertTrue(books.none { it.isComic || it.isAudio })
                records += buildJsonObject {
                    put("query", query); put("results", books.size)
                    put("milliseconds", (System.nanoTime() - started) / 1_000_000.0)
                    put("firstTitle", books.first().title); put("chapterMetadata", books.first().chapterCount)
                }
            }
            assertEquals(0, calls.get())
            File(project, "outputs/reader3-index-benchmark.json").writeText(Json { prettyPrint = true }.encodeToString(
                JsonObject.serializer(), buildJsonObject { put("networkRequests", 0); put("checks", JsonArray(records)); put("ok", true) }))
        } finally { directory.deleteRecursively() }
    }
}
