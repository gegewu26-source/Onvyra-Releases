package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.Collections

class SkinSearchTest {
    private fun client(requested: MutableList<String>, failLast: Boolean = false) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            requested += path
            val body = when (path) {
                "/data/search-meta.json" -> """{"chunks":3,"chunkSize":5000}"""
                "/data/search-docs-0.json" -> """[{"id":1,"t":"其他作品","a":"甲"}]"""
                "/data/search-docs-1.json" -> """[{"id":2,"t":"目标退役作品","a":"乙","s":"41nr"}]"""
                "/data/search-docs-2.json" -> """[{"id":3,"t":"目标作品","a":"真实作者","ch":730,"c":"奇幻","cv":"https://cdn.example.com/cover.jpg"}]"""
                else -> throw AssertionError(path)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (failLast && path.endsWith("-2.json")) 503 else 200).message("fixture")
                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
        }.build()

    @Test fun scansLaterShardsFiltersRetiredAndReusesDiskCache(): Unit = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val directory = Files.createTempDirectory("skin-search-qa").toFile()
        try {
            val first = SkinNovelSource(client(calls), directory).searchBooks("目标")
            assertEquals(1, first.size)
            assertEquals("目标作品", first.single().title)
            assertEquals(730, first.single().chapterCount)
            assertEquals("https://www.biquge.skin/novel?id=3", first.single().detailUrl)
            assertEquals(4, calls.size)
            val cached = SkinNovelSource(client(calls), directory).searchBooks("真实作者")
            assertEquals("目标作品", cached.single().title)
            assertEquals("No network fetch after fresh disk cache", 4, calls.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedShardDoesNotBecomeSuccessfulEmptyOrPartialSearch(): Unit = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        try {
            SkinNovelSource(client(calls, true)).searchBooks("目标")
            fail("Missing shards must surface a failure")
        } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("503")) }
    }
}
