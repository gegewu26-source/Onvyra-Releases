package com.constx.onvyra.data.media

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual HTTP requests verify discovered providers participate before a successful built-in returns. */
class MovieCatalogPoolParticipationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val metadata = """{"list":[{"vod_id":"1","vod_name":"目标影片","vod_year":"2024","type_name":"电影","vod_play_from":"hls","vod_play_url":"正片${'$'}https://cdn.example.org/film.m3u8"}]}"""

    @Test fun successfulBuiltInAndCachedExtraParticipateInTheSameLookup() = runBlocking {
        val visited = ConcurrentHashMap.newKeySet<String>()
        val extraStarted = CountDownLatch(1)
        Server { path ->
            visited.add(path.substringBefore('?'))
            if (path.startsWith("/extra?")) extraStarted.countDown()
            if (path.startsWith("/built?")) check(extraStarted.await(2, TimeUnit.SECONDS)) {
                "The registered extra must be searched alongside a successful built-in"
            }
            metadata
        }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(source("built", server)),
                extraSources = { listOf(source("extra", server)) })
            val result = withTimeout(4_000) { catalog.searchWithReport("目标影片", 2024) }
            assertEquals(setOf("/built", "/extra"), visited)
            assertEquals(setOf("built", "extra"), result.report.attemptedSourceIds)
            assertEquals(setOf("built", "extra"), result.items.mapTo(hashSetOf()) { it.sourceId })
        }
    }

    @Test fun extraCatalogsGetWorkerSlotsBeforeSixSlowBuiltInsFillThePool() = runBlocking {
        val extraStarted = CountDownLatch(1)
        val visited = ConcurrentHashMap.newKeySet<String>()
        Server { path ->
            visited.add(path.substringBefore('?'))
            if (path.startsWith("/extra")) extraStarted.countDown()
            else check(extraStarted.await(2, TimeUnit.SECONDS)) { "All request slots were monopolized by built-ins" }
            metadata
        }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), (0..7).map { source("built$it", server) },
                extraSources = { (0..7).map { source("extra$it", server) } })
            val first = withTimeout(3_000) { catalog.searchUpdates("目标影片", 2024).first { it.isNotEmpty() } }
            assertTrue(first.isNotEmpty())
            assertEquals(0L, extraStarted.count)
            assertTrue(visited.any { it.startsWith("/extra") })
        }
    }

    @Test fun explicitSourceSelectionRemainsExactDespiteTheLargerPool() = runBlocking {
        val visited = ConcurrentHashMap.newKeySet<String>()
        Server { path -> visited.add(path.substringBefore('?')); metadata }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(source("built", server)),
                extraSources = { listOf(source("extra", server)) })
            val result = catalog.searchWithReport("目标影片", 2024, setOf("extra"))
            assertEquals(setOf("/extra"), visited)
            assertEquals(setOf("extra"), result.report.selectedSourceIds)
            assertEquals(2, result.report.eligibleSources)
        }
    }

    @Test fun removedFeedCannotBeCalledReimportedOrRestoredFromOldRegistry() = runBlocking {
        val visited = ConcurrentHashMap.newKeySet<String>()
        Server { path -> visited.add(path.substringBefore('?')); metadata }.use { server ->
            val banned = MovieCatalogSource("baofeng", "Removed feed", "${server.url}/banned")
            val renamed = MovieCatalogSource(discoveredMovieSourceId("https://sub.bfzyapi.com/api.php/provide/vod/"),
                "Renamed feed", "https://sub.bfzyapi.com/api.php/provide/vod/")
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(source("built", server), banned),
                extraSources = { listOf(renamed) })
            val result = catalog.searchWithReport("目标影片", 2024)
            assertEquals(setOf("/built"), visited)
            assertEquals(setOf("built"), result.report.eligibleSourceIds)
            val directory = temporary.newFolder()
            val registry = MovieCatalogSourceRegistry(directory)
            registry.register(listOf(renamed))
            assertTrue(registry.all().isEmpty())
            assertTrue(movieCatalogSourcesFromSites(listOf(MovieDiscoverySite("renamed", renamed.apiUrl, type = 1)),
                "https://config.example.org/config.json").isEmpty())
            java.io.File(directory, "sources.json").writeText("""{"sources":[{"id":"${renamed.id}","name":"old","api_url":"${renamed.apiUrl}"}]}""")
            assertTrue(MovieCatalogSourceRegistry(directory).all().isEmpty())
        }
    }

    @Test fun backgroundDiscoveryIsSingleFlightAndPersistsItsCache() = runBlocking {
        val directory = temporary.newFolder()
        val registry = MovieCatalogSourceRegistry(directory)
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = "https://extra.example.org/api.php/provide/vod/"
        val discovered = MovieCatalogSource(discoveredMovieSourceId(api), "Extra", api)
        val first = MovieCatalogWarmup.start(registry) {
            calls.incrementAndGet(); started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            registry.register(listOf(discovered))
            registry.recordDiscoveryAttempt(true, listOf("https://config.example.org/config.json"))
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            repeat(5) { assertSame(first, MovieCatalogWarmup.start(registry) { fail("Duplicate discovery") }) }
        } finally { release.countDown() }
        withTimeout(3_000) { requireNotNull(first).join() }
        assertEquals(1, calls.get())
        assertNull(MovieCatalogWarmup.start(registry) { fail("Fresh discovery cache must be reused") })
        val restored = MovieCatalogSourceRegistry(directory)
        assertEquals(listOf(discovered), restored.all())
        assertTrue(restored.isDiscoveryCacheFresh())
    }

    @Test fun failedWarmupDoesNotRestartForEveryChatMessage() = runBlocking {
        val registry = MovieCatalogSourceRegistry(temporary.newFolder())
        withTimeout(2_000) { requireNotNull(MovieCatalogWarmup.start(registry) { error("offline fixture") }).join() }
        assertTrue(registry.isDiscoveryCacheFresh())
        assertNull(MovieCatalogWarmup.start(registry) { fail("Failure cache must prevent repeated scans") })
    }

    private fun source(id: String, server: Server) = MovieCatalogSource(id, id, "${server.url}/$id")

    private class Server(private val response: (String) -> String) : Closeable {
        private val socket = ServerSocket(0, 30, java.net.InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "movie-pool-fixture").apply { isDaemon = true } }
        val url = "http://127.0.0.1:${socket.localPort}"
        init {
            workers.execute {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    workers.execute {
                        client.use {
                            runCatching {
                                val reader = it.getInputStream().bufferedReader()
                                val path = reader.readLine().split(' ')[1]
                                while (!reader.readLine().isNullOrEmpty()) { }
                                val payload = response(path).toByteArray(Charsets.UTF_8)
                                it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                it.getOutputStream().write(payload)
                            }
                        }
                    }
                }
            }
        }
        override fun close() { socket.close(); workers.shutdownNow() }
    }
}
