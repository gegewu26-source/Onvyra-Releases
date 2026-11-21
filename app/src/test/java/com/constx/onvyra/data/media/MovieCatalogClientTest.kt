package com.constx.onvyra.data.media

import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MovieCatalogClientTest {
    private val source = MovieCatalogSource("fixture", "Fixture", "https://example.org/api")
    private val separator = '$'
    private val boundary = "$separator$separator$separator"

    private fun metadata(play: String, name: String = "让子弹飞", year: String = "2010", category: String = "电影"): String =
        buildJsonObject { put("list", buildJsonArray { add(buildJsonObject {
            put("vod_id", 8136); put("vod_name", name); put("vod_year", year); put("type_name", category)
            put("vod_play_from", "web${boundary}hls"); put("vod_play_url", play); put("vod_duration", "127分钟")
        }) }) }.toString()

    @Test fun parsesAllLinesAndKeepsSignedParametersAndDollarInMediaUrl() {
        val json = metadata("正片${separator}https://example.org/page${boundary}正片${separator}https://cdn.example.org/a.m3u8?token=x%2By&part=a${separator}b")
        val item = parseMovieCatalogItems(json, source).single()
        assertEquals(2, item.lines.size)
        assertEquals("hls", item.lines[1].name)
        assertTrue(item.lines[1].episodes.single().url.contains("part=a${separator}b"))
        assertEquals(2010, item.year)
        assertEquals(7620.0, item.durationSeconds!!, 0.1)
        assertEquals("fixture", item.sourceId)
        assertTrue(item.detailUrl!!.contains("ids=8136"))
    }

    @Test fun invalidEntriesDoNotShiftEpisodeNumbersOrPermitFileAndCredentialUrls() {
        val json = metadata("1${separator}javascript:bad#2${separator}https://cdn.example.org/2.mp4#3${separator}file:///private#4${separator}https://user:password@example.org/a.mp4")
        val episode = parseMovieCatalogItems(json, source).single().lines.single().episodes.single()
        assertEquals("2", episode.name)
        assertEquals(2, episode.index)
    }

    @Test fun rankingKeepsExactMovieAheadOfVariantsAndExcludesWrongKnownYear() {
        val original = MovieCatalogItem("1", "让子弹飞", 2010, sourceId = "a")
        val fourK = original.copy(id = "2", title = "让子弹飞4K")
        val wrongYear = original.copy(id = "3", year = 2025)
        val clip = original.copy(id = "4", title = "让子弹飞解说", isClip = true)
        val ranked = rankMovieCatalogItems(listOf(clip, wrongYear, fourK, original), "让子弹飞", 2010)
        assertEquals(listOf(original, fourK, clip), ranked)
        assertTrue(isMatchingMovieTitle("《让子弹飞》", "让子弹飞4K"))
        assertFalse(isMatchingMovieTitle("让子弹飞", "让子弹飞预告片"))
    }

    @Test fun commentaryCategoryIsNotAFullFilmEvenWhenItsTitleMatches() {
        val item = parseMovieCatalogItems(metadata("正片${separator}https://cdn.example.org/a.mp4", category = "电影解说"), source).single()
        assertTrue(item.isClip)
    }

    @Test fun searchSurvivesBadSourceAndReadsJsonLabelledAsHtml() = runBlocking {
        TestServer { path -> if (path.startsWith("/bad")) "not JSON" else metadata("正片${separator}https://cdn.example.org/a.m3u8") }.use { server ->
            val sources = listOf(MovieCatalogSource("bad", "Bad", "${server.url}/bad"),
                MovieCatalogSource("good", "Good", "${server.url}/good"))
            val client = MovieCatalogClient(OkHttpClient(), sources)
            val item = client.search("让子弹飞", 2010).single()
            assertEquals("good", item.sourceId)
            assertEquals(item, client.getItem("good", "8136"))
        }
    }

    @Test fun suggestResultFetchesRealDetailInsteadOfTreatingTitleAsPlayback() = runBlocking {
        TestServer { path ->
            if (path.startsWith("/suggest")) """{"list":[{"id":8136,"name":"让子弹飞"}]}"""
            else metadata("正片${separator}https://cdn.example.org/a.m3u8")
        }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("good", "Good", "${server.url}/api", "${server.url}/suggest")))
            assertEquals("https://cdn.example.org/a.m3u8", client.search("让子弹飞").single().lines.single().episodes.single().url)
        }
    }

    @Test fun failedPreferredCatalogFallsBackAndReportsTheActualFailureWithoutDroppingOtherSources() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        TestServer { path ->
            calls += path.substringBefore('?')
            if (path.startsWith("/preferred")) "not JSON" else metadata("正片${separator}https://cdn.example.org/a.m3u8")
        }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(
                MovieCatalogSource("fallback", "Fallback", "${server.url}/fallback"),
                MovieCatalogSource("wsyzy", "Preferred", "${server.url}/preferred"),
            ))
            val result = client.searchWithReport("让子弹飞", 2010)
            assertEquals("/preferred", calls.first())
            assertEquals(1, calls.count { it == "/preferred" })
            assertEquals("fallback", result.items.single().sourceId)
            assertEquals(setOf("wsyzy", "fallback"), result.report.attemptedSourceIds)
            assertEquals(1, result.report.failures.size)
            assertEquals("wsyzy", result.report.failures.single().sourceId)
        }
    }

    @Test fun failedSuggestionEntranceUsesTheSameProvidersJsonApiOnce() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        TestServer { path ->
            calls += path.substringBefore('?')
            if (path.startsWith("/suggest")) "not JSON" else metadata("正片${separator}https://cdn.example.org/a.m3u8")
        }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(
                MovieCatalogSource("wsyzy", "Preferred", "${server.url}/api", "${server.url}/suggest"),
            ))
            assertEquals("wsyzy", client.search("让子弹飞").single().sourceId)
            assertEquals(listOf("/suggest", "/api"), calls.toList())
        }
    }

    @Test fun failedSuggestionAndEmptySameProviderApiDoNotRepeatTheFallbackOrInventAHop() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        TestServer { path -> calls += path.substringBefore('?'); if (path.startsWith("/suggest")) "not JSON" else """{"list":[]}""" }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(
                MovieCatalogSource("wsyzy", "Preferred", "${server.url}/api", "${server.url}/suggest"),
            ))
            assertTrue(client.search("让子弹飞").isEmpty())
            assertEquals(listOf("/suggest", "/api"), calls.toList())
        }
    }

    private class TestServer(private val body: (String) -> String) : AutoCloseable {
        private val server = ServerSocket(0)
        private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        val url = "http://127.0.0.1:${server.localPort}"
        init { executor.execute {
            while (!server.isClosed) {
                val connection = runCatching { server.accept() }.getOrNull() ?: break
                executor.execute { runCatching { connection.use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = body(path).toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type:text/html\r\nContent-Length:${bytes.size}\r\nConnection:close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } } }
            }
        } }
        override fun close() { server.close(); executor.shutdownNow() }
    }
}
