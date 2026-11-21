package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SkinNovelSourceTest {
    private val detail = """<html><script id="book-data" type="application/json">{"id":7,"title":"测试作品","author":"作者","category":"奇幻","chapter_count":205,"source_book_id":"9"}</script></html>"""

    private fun adapter(block: (Interceptor.Chain) -> Pair<Int, String>): SkinNovelSource =
        SkinNovelSource(OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = block(chain)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build())

    private fun tokenPayload(chain: Interceptor.Chain): JsonObject {
        val digest = "394c2c3202da6270a3dc22cf70418a51"
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE,
            SecretKeySpec(digest.substring(16).toByteArray(), "AES"),
            IvParameterSpec(digest.substring(0, 16).toByteArray()))
        val bytes = Base64.getDecoder().decode(chain.request().url.queryParameter("token"))
        return Json.parseToJsonElement(cipher.doFinal(bytes).toString(Charsets.UTF_8)).jsonObject
    }

    @Test
    fun catalogReadsEntireReturnedListAndKeepsPublicBookId() = runBlocking {
        val source = adapter { chain ->
            if (chain.request().url.host == "www.biquge.skin") 200 to detail else {
                assertEquals("/api/booklist", chain.request().url.encodedPath)
                assertEquals("9", tokenPayload(chain)["id"]!!.jsonPrimitive.content)
                200 to JsonObject(mapOf("list" to JsonArray((1..205).map { JsonPrimitive("真实章节 $it") }))).toString()
            }
        }
        val catalog = source.getCatalog("https://www.biquge.skin/novel?id=7")
        assertEquals(205, catalog.size)
        assertEquals("真实章节 205", catalog.last().title)
        assertEquals("https://www.biquge.skin/reader?novelId=7&chapter=205", catalog.last().url)
        assertEquals(204, catalog.last().index)
    }

    @Test
    fun contentUsesDeclaredSourceIdAndChecksReturnedChapter(): Unit = runBlocking {
        val source = adapter { chain ->
            if (chain.request().url.host == "www.biquge.skin") 200 to detail else {
                val payload = tokenPayload(chain)
                assertEquals("9", payload["id"]!!.jsonPrimitive.content)
                assertEquals("2", payload["chapterid"]!!.jsonPrimitive.content)
                200 to JsonObject(mapOf("id" to JsonPrimitive(9), "chapterid" to JsonPrimitive(2),
                    "txt" to JsonPrimitive("这是来源返回的真实段落。".repeat(10)))).toString()
            }
        }
        assertTrue(source.getChapterContent("https://www.biquge.skin/reader?novelId=7&chapter=2").contains("来源返回"))
        val wrong = adapter { chain ->
            if (chain.request().url.host == "www.biquge.skin") 200 to detail
            else 200 to """{"id":9,"chapterid":3,"txt":"${"错误章节".repeat(30)}"}"""
        }
        assertThrows(IOException::class.java) {
            runBlocking { wrong.getChapterContent("https://www.biquge.skin/reader?novelId=7&chapter=2") }
        }
    }

    @Test
    fun blockedMetadataDoesNotGuessSourceIdOrGenerateCatalog() {
        var count = 0
        val source = adapter { count++; 403 to "Forbidden" }
        assertThrows(IOException::class.java) {
            runBlocking { source.getCatalog("https://www.biquge.skin/book/7/") }
        }
        assertEquals(1, count)
    }

    @Test
    fun dynamicBookReadsOnlyItsDeclaredDetailBucket() = runBlocking {
        val paths = mutableListOf<String>()
        val source = adapter { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            when (path) {
                "/book/7/" -> 404 to "Not Found"
                "/data/novel-detail-meta.json" -> 200 to """{"bucketSize":3,"maxId":9,"buckets":[0,1,2]}"""
                "/data/novel-details-2.json" -> 200 to """[{"id":7,"title":"动态作品","author":"真实作者","primary_category":"奇幻","description":"真实简介","chapter_count":1,"source_book_id":"9"}]"""
                "/api/book" -> 200 to """{"id":9,"title":"动态作品","dirid":9}"""
                "/api/booklist" -> {
                    assertEquals("9", tokenPayload(chain)["id"]!!.jsonPrimitive.content)
                    200 to """{"list":["真正的第一章"]}"""
                }
                else -> throw AssertionError("Unexpected request: $path")
            }
        }
        val book = source.getBookDetail(NovelBook("initial", "", detailUrl = "https://www.biquge.skin/novel?id=7", sourceId = "biquge_skin"))
        assertEquals("动态作品", book.title)
        assertEquals("https://www.biquge.skin/novel?id=7", book.detailUrl)
        assertEquals("真实简介", book.description)
        assertEquals("奇幻", book.category)
        assertEquals("真正的第一章", source.getCatalog(book.detailUrl).single().title)
        assertEquals(listOf("/book/7/", "/data/novel-detail-meta.json", "/data/novel-details-2.json", "/api/book", "/api/booklist"), paths)
    }

    @Test
    fun nullAliasCatalogUsesOnlyDirectoryIdDeclaredByBookEndpoint(): Unit = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val source = adapter { chain ->
            val path = chain.request().url.encodedPath
            if (path == "/book/77228/") 200 to """<script id="book-data" type="application/json">{"id":77228,"title":"诡秘之主","author":"爱潜水的乌贼"}</script>"""
            else {
                val id = tokenPayload(chain)["id"]!!.jsonPrimitive.content
                calls += path to id
                when (path to id) {
                    "/api/booklist" to "77228" -> 200 to """{"list":null}"""
                    "/api/book" to "77228" -> 200 to """{"id":77228,"title":"诡秘之主","dirid":583}"""
                    "/api/booklist" to "583" -> 200 to """{"list":["第一章 绯红","第二章 情况"]}"""
                    "/api/chapter" to "583" -> 200 to """{"id":583,"chapterid":1,"txt":"${"这是明确目录映射后读取的真实章节段落。".repeat(5)}"}"""
                    else -> throw AssertionError("Unexpected $path $id")
                }
            }
        }
        val catalog = source.getCatalog("https://www.biquge.skin/novel?id=77228")
        assertEquals(2, catalog.size)
        assertTrue(catalog.first().url.contains("novelId=77228"))
        assertTrue(source.getChapterContent(catalog.first().url).contains("明确目录映射"))
        assertEquals(listOf("/api/booklist" to "77228", "/api/book" to "77228", "/api/booklist" to "583", "/api/chapter" to "583"), calls)
    }

    @Test
    fun detailKeepsSearchCoverWhenMetadataAndApiDoNotHaveOne(): Unit = runBlocking {
        val source = adapter { chain ->
            if (chain.request().url.host == "www.biquge.skin") 200 to detail
            else 200 to """{"id":9,"title":"测试作品","dirid":9,"intro":"真实详情"}"""
        }
        val book = source.getBookDetail(NovelBook("initial", "测试作品", coverUrl = "https://cdn.example.com/real.jpg",
            detailUrl = "https://www.biquge.skin/book/7/", sourceId = "biquge_skin"))
        assertEquals("https://cdn.example.com/real.jpg", book.coverUrl)
        assertEquals("真实详情", book.description)
    }

    @Test
    fun foreignChapterUrlDoesNotReachNetwork() {
        var count = 0
        val source = adapter { count++; 200 to "{}" }
        assertThrows(IOException::class.java) {
            runBlocking { source.getChapterContent("https://example.com/reader?novelId=7&chapter=2") }
        }
        assertEquals(0, count)
    }
}

