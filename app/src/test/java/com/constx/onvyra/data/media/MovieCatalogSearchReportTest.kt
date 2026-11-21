package com.constx.onvyra.data.media

import java.io.IOException
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Distinguishes a completed empty catalog from incomplete or failed provider queries. */
class MovieCatalogSearchReportTest {
    @Test fun aThreeSourceGroupDoesNotEraseTheRestOfTheEligibleCatalogPool() = runBlocking {
        val visited = ConcurrentHashMap.newKeySet<String>()
        Server { path -> visited.add(path.substringBefore('?')); Response(body = """{"list":[]}""") }.use { server ->
            val sources = (1..24).map { MovieCatalogSource("source$it", "Source $it", "${server.url}/source$it") }
            val selected = setOf("source1", "source2", "source3")
            val result = MovieCatalogClient(OkHttpClient(), sources).searchWithReport("目标影片", sourceIds = selected)
            assertEquals(3, result.report.totalSources)
            assertEquals(24, result.report.eligibleSources)
            assertEquals(sources.mapTo(linkedSetOf()) { it.id }, result.report.eligibleSourceIds)
            assertEquals(selected, result.report.selectedSourceIds)
            assertEquals(selected, result.report.attemptedSourceIds)
            assertEquals(selected, result.report.successfulSourceIds)
            assertEquals(setOf("/source1", "/source2", "/source3"), visited)
            assertTrue("Complete describes only the selected group", result.report.complete)
        }
    }

    @Test fun aValidEmptyCatalogIsCompleteAndBecomesNoMatchOnlyAfterSuccessfulLookup() = runBlocking {
        Server { Response(body = """{"code":1,"list":[]}""") }.use { server ->
            val catalog = catalog(server, "empty")
            val result = catalog.searchWithReport("目标影片")
            assertTrue(result.items.isEmpty())
            assertEquals(1, result.report.totalSources)
            assertEquals(1, result.report.attemptedSources)
            assertEquals(1, result.report.successfulSources)
            assertEquals(1, result.report.emptySources)
            assertEquals(0, result.report.unqueriedSources)
            assertTrue(result.report.failures.isEmpty())
            assertTrue(result.report.complete)

            val resolver = MoviePlaybackResolver(catalog) { error("Empty metadata must not resolve media") }
            val error = lookupFailure { resolver.searchAndResolve("目标影片") }
            assertTrue(error is MovieCatalogNoMatchException)
            assertEquals("no_match", error.catalogStatus)
            assertTrue(requireNotNull(error.report).complete)
        }
    }

    @Test fun malformedAndErrorPayloadsAreFailuresRatherThanSuccessfulEmptyResponses() = runBlocking {
        Server { path ->
            Response(body = when (path.substringBefore('?')) {
                "/missing" -> """{"code":1,"msg":"ok"}"""
                "/malformed" -> "{invalid JSON"
                "/wrong_list" -> """{"list":"not an array"}"""
                else -> """{"success":false,"error":"private-token=https://private.example/secret","list":[]}"""
            })
        }.use { server ->
            val result = catalog(server, "missing", "malformed", "wrong_list", "api_error")
                .searchWithReport("目标影片")
            assertTrue(result.items.isEmpty())
            assertEquals(4, result.report.attemptedSources)
            assertEquals(0, result.report.successfulSources)
            assertEquals(0, result.report.emptySources)
            assertFalse(result.report.complete)
            assertEquals(mapOf(
                "missing" to MovieCatalogFailureCode.INVALID_RESPONSE,
                "malformed" to MovieCatalogFailureCode.INVALID_RESPONSE,
                "wrong_list" to MovieCatalogFailureCode.INVALID_RESPONSE,
                "api_error" to MovieCatalogFailureCode.API_ERROR,
            ), result.report.failures.associate { it.sourceId to it.code })
            assertFalse(result.report.toString().contains("private-token"))
            assertFalse(result.report.toString().contains("private.example"))
        }
    }

    @Test fun anEmptyResponseAlongsideAnHttpFailureRemainsPartialSearch() = runBlocking {
        Server { path ->
            if (path.startsWith("/empty?")) Response(body = """{"list":[]}""")
            else Response(503, "upstream unavailable private-token")
        }.use { server ->
            val catalog = catalog(server, "empty", "failed")
            val result = catalog.searchWithReport("目标影片")
            assertEquals(2, result.report.totalSources)
            assertEquals(1, result.report.successfulSources)
            assertEquals(1, result.report.emptySources)
            assertEquals(listOf(MovieCatalogSourceFailure("failed", MovieCatalogFailureCode.HTTP)), result.report.failures)
            assertFalse(result.report.complete)

            val resolver = MoviePlaybackResolver(catalog) { error("No matching metadata") }
            val error = lookupFailure { resolver.searchAndResolve("目标影片") }
            assertTrue(error is MovieCatalogNoMatchException)
            assertEquals("partial_search", error.catalogStatus)
            assertEquals(1, requireNotNull(error.report).successfulSources)
            assertFalse(error.message.orEmpty().contains("private-token"))
        }
    }

    @Test fun allHttpFailuresBecomeUnavailableRatherThanNoMatch() = runBlocking {
        Server { path -> Response(if (path.startsWith("/denied?")) 403 else 503, "private response") }.use { server ->
            val catalog = catalog(server, "denied", "unavailable")
            val result = catalog.searchWithReport("目标影片")
            assertEquals(0, result.report.successfulSources)
            assertEquals(2, result.report.failures.size)
            assertTrue(result.report.failures.all { it.code == MovieCatalogFailureCode.HTTP })
            assertFalse(result.report.complete)
            val resolver = MoviePlaybackResolver(catalog) { error("Must not resolve a failed lookup") }
            val error = lookupFailure { resolver.searchAndResolve("目标影片") }
            assertTrue(error is MovieCatalogSearchException)
            assertEquals("sources_unavailable", error.catalogStatus)
            assertEquals(2, requireNotNull(error.report).attemptedSources)
        }
    }

    @Test fun overlappingQueriesOnOneClientKeepTheirOwnReports() = runBlocking {
        val distinctQueries = ConcurrentHashMap.newKeySet<String>()
        val bothStarted = CountDownLatch(2)
        Server { path ->
            val query = URLDecoder.decode(path.substringAfter("wd=").substringBefore('&'), "UTF-8")
            if (distinctQueries.add(query)) bothStarted.countDown()
            check(bothStarted.await(4, TimeUnit.SECONDS)) { "The two queries did not overlap" }
            if (query == "空片") Response(body = """{"list":[]}""")
            else Response(body = """{"success":false,"error":"fixture API failure","list":[]}""")
        }.use { server ->
            val catalog = catalog(server, "first", "second")
            val emptyQuery = async { catalog.searchWithReport("空片") }
            val failedQuery = async { catalog.searchWithReport("错误片") }
            val empty = withTimeout(10_000) { emptyQuery.await() }
            val failed = withTimeout(10_000) { failedQuery.await() }
            assertEquals(setOf("空片", "错误片"), distinctQueries)
            assertTrue(empty.report.complete)
            assertEquals(2, empty.report.successfulSources)
            assertEquals(2, empty.report.emptySources)
            assertTrue(empty.report.failures.isEmpty())
            assertEquals(0, failed.report.successfulSources)
            assertEquals(0, failed.report.emptySources)
            assertEquals(setOf("first", "second"), failed.report.failures.map { it.sourceId }.toSet())
            assertTrue(failed.report.failures.all { it.code == MovieCatalogFailureCode.API_ERROR })
            assertFalse(failed.report.complete)
            // A later report must not mutate the immutable snapshot returned for the first query.
            assertEquals(2, empty.report.successfulSources)
            assertTrue(empty.report.failures.isEmpty())
        }
    }

    @Test fun consumingTheFirstSuccessfulSnapshotCancelsTheRemainingNetworkCall() = runBlocking {
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        Server { path ->
            if (path.startsWith("/slow?")) {
                slowStarted.countDown()
                releaseSlow.await(5, TimeUnit.SECONDS)
            } else check(slowStarted.await(3, TimeUnit.SECONDS))
            Response(body = metadata())
        }.use { server ->
            val http = OkHttpClient()
            val catalog = catalog(server, "slow", "fast", http = http)
            try {
                val first = withTimeout(5_000) {
                    catalog.searchReportUpdates("目标影片").first { result -> result.report.successfulSources > 0 }
                }
                assertEquals(listOf("fast"), first.items.map { it.sourceId })
                assertEquals(1, first.report.successfulSources)
                assertFalse(first.report.complete)
                assertEquals(1L, releaseSlow.count)
                awaitNoCalls(http)
            } finally { releaseSlow.countDown() }
        }
    }

    @Test fun userCancellationPropagatesAndClosesTheActiveCall() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        Server {
            requestStarted.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response(body = """{"list":[]}""")
        }.use { server ->
            val http = OkHttpClient()
            val catalog = catalog(server, "slow", http = http)
            val job = async { catalog.searchWithReport("目标影片") }
            try {
                assertTrue(withContext(Dispatchers.IO) { requestStarted.await(3, TimeUnit.SECONDS) })
                job.cancel(CancellationException("user stopped"))
                try { job.await(); fail("Cancellation became a catalog result") }
                catch (_: CancellationException) { }
                awaitNoCalls(http)
            } finally { release.countDown() }
        }
    }

    @Test fun foundMetadataWithAFailedMediaCheckIsPlaybackFailure() = runBlocking {
        Server { Response(body = metadata()) }.use { server ->
            val mediaAttempts = AtomicInteger()
            val resolver = MoviePlaybackResolver(catalog(server, "found")) {
                mediaAttempts.incrementAndGet()
                throw IOException("private stream URL or signed token")
            }
            val error = lookupFailure { resolver.searchAndResolve("目标影片") }
            assertTrue(error is MovieCatalogPlaybackException)
            assertEquals("playback_failed", error.catalogStatus)
            assertEquals(1, mediaAttempts.get())
            assertTrue(requireNotNull(error.report).complete)
            assertFalse(error.message.orEmpty().contains("private stream"))
        }
    }

    @Test fun noConfiguredSourcesCannotBeReportedAsACompleteNoMatch() = runBlocking {
        val catalog = MovieCatalogClient(OkHttpClient(), emptyList())
        val result = catalog.searchWithReport("目标影片")
        assertEquals(0, result.report.totalSources)
        assertEquals(0, result.report.successfulSources)
        assertFalse(result.report.complete)
        val error = lookupFailure { catalog.search("目标影片") }
        assertTrue(error is MovieCatalogSearchException)
        assertEquals("sources_unavailable", error.catalogStatus)
    }

    private fun catalog(server: Server, vararg ids: String, http: OkHttpClient = OkHttpClient()) =
        MovieCatalogClient(http.newBuilder().dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build(),
            ids.map { MovieCatalogSource(it, it, "${server.url.replace("127.0.0.1", "$it.catalog.test")}/$it") })

    private suspend fun lookupFailure(block: suspend () -> Any?): MovieCatalogLookupException {
        try { block() } catch (error: MovieCatalogLookupException) { return error }
        throw AssertionError("Expected a typed movie catalog lookup failure")
    }

    private suspend fun awaitNoCalls(http: OkHttpClient) = withTimeout(3_000) {
        while (http.dispatcher.runningCallsCount() != 0) delay(10)
    }

    private fun metadata() = """{"list":[{"vod_id":"1","vod_name":"目标影片","vod_year":"2026","vod_play_from":"hls","vod_play_url":"正片${'$'}https://cdn.example.org/film.m3u8"}]}"""

    private data class Response(val status: Int = 200, val body: String)

    private class Server(private val response: (String) -> Response) : AutoCloseable {
        private val socket = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        val url = "http://127.0.0.1:${socket.localPort}"

        init { workers.execute {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { runCatching { connection.use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val reply = response(path)
                    val bytes = reply.body.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 ${reply.status} Fixture\r\nContent-Type:application/json\r\nContent-Length:${bytes.size}\r\nConnection:close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } } }
            }
        } }

        override fun close() { socket.close(); workers.shutdownNow() }
    }
}
