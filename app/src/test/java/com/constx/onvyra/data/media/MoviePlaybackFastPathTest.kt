package com.constx.onvyra.data.media

import java.io.IOException
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient

class MoviePlaybackFastPathTest {
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
    private fun metadata(year: String = "2019", path: String = "fresh", title: String = "流浪地球", id: String = "1") =
        """{"list":[{"vod_id":"$id","vod_name":"$title","vod_year":"$year","type_name":"科幻片","vod_play_from":"sd${'$'}${'$'}${'$'}hd","vod_play_url":"正片${'$'}https://cdn.example.org/$path-sd.m3u8${'$'}${'$'}${'$'}正片${'$'}https://cdn.example.org/$path-hd.m3u8"}]}"""

    @Test fun noYearPreferredExactKnownEditionReturnsWithoutWaitingForTheOtherCatalog() = runBlocking {
        val otherCalls = AtomicInteger()
        Server { path -> if (path.contains("/other")) { otherCalls.incrementAndGet(); Thread.sleep(4_000) }; metadata() }.use { server ->
            val catalog = MovieCatalogClient(http, listOf(
                MovieCatalogSource("other", "Other", "${server.url}/other"),
                MovieCatalogSource("wsyzy", "Preferred", "${server.url}/preferred"),
            ))
            val resolver = MoviePlaybackResolver(catalog) { VideoPlaybackSource(it, VideoPlaybackKind.DIRECT) }
            val selected = withTimeout(1_500) { resolver.searchAndResolve("流浪地球") }
            assertEquals("wsyzy", selected.item.sourceId)
            assertEquals(2019, selected.item.year)
            assertEquals(0, otherCalls.get())
            assertEquals(setOf("wsyzy"), selected.searchReport!!.attemptedSourceIds)
        }
    }

    @Test fun preferredSameTitleWithMultipleYearsStillRequiresAnEdition() = runBlocking {
        val twoYears = metadata().removeSuffix("]}") + "," + metadata("2023", id = "2").substringAfter("[")
        Server { twoYears }.use { server ->
            val resolver = MoviePlaybackResolver(MovieCatalogClient(http, listOf(MovieCatalogSource("wsyzy", "Preferred", server.url)))) {
                error("Ambiguous editions must not enter media resolution")
            }
            try { resolver.searchAndResolve("流浪地球"); fail("Multiple editions were accepted") }
            catch (_: MovieTitleAmbiguousException) { }
        }
    }

    @Test fun listedMetadataResolvesTheExactOtherLineWithoutRepeatingTheDirectoryRequest() = runBlocking {
        Server { metadata() }.use { server ->
            val resolver = MoviePlaybackResolver(MovieCatalogClient(http, listOf(MovieCatalogSource("a", "A", server.url)))) {
                VideoPlaybackSource(it, VideoPlaybackKind.DIRECT)
            }
            val options = resolver.listOptions("a", "1", 1, "sd", "流浪地球", 2019)
            val before = server.requests.get()
            val selected = resolver.resolveOption(options.single { it.lineName == "hd" })
            assertEquals("https://cdn.example.org/fresh-hd.m3u8", selected.source.url)
            assertEquals(before, server.requests.get())
        }
    }

    @Test fun failedCachedUrlRefreshesOnlyTheChosenLocatorAndLineOnce() = runBlocking {
        var fresh = false
        val mediaCalls = mutableListOf<String>()
        Server { metadata(path = if (fresh) "fresh" else "old") }.use { server ->
            val resolver = MoviePlaybackResolver(MovieCatalogClient(http, listOf(MovieCatalogSource("a", "A", server.url)))) {
                mediaCalls += it
                if (it.contains("/old-")) throw IOException("expired")
                VideoPlaybackSource(it, VideoPlaybackKind.DIRECT)
            }
            val options = resolver.listOptions("a", "1", 1, "sd", "流浪地球", 2019)
            val before = server.requests.get()
            fresh = true
            val selected = resolver.resolveOption(options.single { it.lineName == "hd" })
            assertEquals(listOf("https://cdn.example.org/old-hd.m3u8", "https://cdn.example.org/fresh-hd.m3u8"), mediaCalls)
            assertEquals(before + 1, server.requests.get())
            assertEquals("hd", selected.lineName)
        }
    }

    @Test fun missingYearAlternativesArePublishedOnlyAfterTheExactYearIsFetched() = runBlocking {
        Server { path ->
            if (path.contains("ac=detail")) metadata() else metadata("")
        }.use { server ->
            val catalog = MovieCatalogClient(http, listOf(MovieCatalogSource("unknown", "Unknown", server.url)))
            val current = MovieCatalogItem("current", "流浪地球", 2019, sourceId = "current", sourceName = "Current", lines = listOf(
                MoviePlaybackLine("hls", listOf(MovieEpisode("正片", "https://cdn.example.org/current.m3u8", 1))),
            ))
            val resolver = MoviePlaybackResolver(catalog) { error("Menus do not request media") }
            val options = resolver.optionUpdates("current", "current", 1, "hls", "流浪地球", 2019,
                currentItem = current).lastOrNull().orEmpty()
            assertTrue(options.any { it.sourceId == "current" })
            assertTrue(options.any { it.sourceId == "unknown" && it.year == 2019 })
            assertTrue(server.requests.get() >= 2)
        }
    }

    @Test fun cachedMetadataCannotAuthorizeADifferentTitleOrEdition() = runBlocking {
        var mediaCalls = 0
        Server { metadata() }.use { server ->
            val resolver = MoviePlaybackResolver(MovieCatalogClient(http, listOf(MovieCatalogSource("a", "A", server.url)))) {
                mediaCalls++; VideoPlaybackSource(it, VideoPlaybackKind.DIRECT)
            }
            val option = resolver.listOptions("a", "1", 1, "hd", "流浪地球", 2019).first()
            try { resolver.resolveOption(option.copy(title = "流浪地球2", year = 2023)); fail("Wrong edition accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, mediaCalls)
        }
    }

    private class Server(private val body: (String) -> String) : AutoCloseable {
        val requests = AtomicInteger()
        private val server = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        val url = "http://127.0.0.1:${server.localPort}/api"
        init { workers.execute {
            while (!server.isClosed) {
                val connection = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { runCatching { connection.use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    val payload = body(path).toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type:application/json\r\nContent-Length:${payload.size}\r\nConnection:close\r\n\r\n".toByteArray())
                        write(payload); flush()
                    }
                } } }
            }
        } }
        override fun close() { server.close(); workers.shutdownNow() }
    }
}
