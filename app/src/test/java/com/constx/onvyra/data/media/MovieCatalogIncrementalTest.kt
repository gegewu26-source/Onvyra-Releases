package com.constx.onvyra.data.media

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MovieCatalogIncrementalTest {
    private val metadata = """{"list":[{"vod_id":"1","vod_name":"流浪地球","vod_year":"2019","vod_play_from":"hls","vod_play_url":"正片${'$'}https://cdn.example.org/a.m3u8"}]}"""

    @Test fun typedAnimeSurvivesFirstPlayerPayloadMenuManualSelectionAndRefresh() = runBlocking {
        val anime = metadata.replace("流浪地球", "鬼灭之刃").replace("\"vod_year\"", "\"type_name\":\"日韩动漫\",\"vod_year\"")
        Server { anime }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("anime", "Anime", "${server.url}/anime")))
            val resolver = MoviePlaybackResolver(catalog) { VideoPlaybackSource(it, VideoPlaybackKind.DIRECT, title = "鬼灭之刃 第1集") }
            val selected = resolver.searchAndResolve("鬼灭之刃", 2019, contentType = MovieContentType.ANIME)
            assertEquals(MovieContentType.ANIME, selected.contentType)
            val preview = requireNotNull(parseVideoPreviewPayload(com.constx.onvyra.data.ai.tools.buildCatalogVideoPreview(selected).toString()))
            assertEquals(MovieContentType.ANIME, preview.requestedContentType)
            val options = resolver.optionUpdates("anime", "1", 1, "hls", "鬼灭之刃", 2019,
                currentItem = selected.item, contentType = preview.requestedContentType).lastOrNull().orEmpty()
            assertEquals(MovieContentType.ANIME, options.single().contentType)
            val switched = resolver.resolveOption(options.single())
            assertEquals(MovieContentType.ANIME, switched.contentType)
            val refreshed = resolver.refresh(switched.item.sourceId, switched.item.id, switched.episode, switched.lineName, switched.contentType)
            assertEquals(MovieContentType.ANIME, refreshed.contentType)
            assertTrue(refreshed.contentTypeVerified)
        }
    }

    @Test fun aGeneralMenuCacheCannotLeakLiveActionIntoALaterAnimeMenu() = runBlocking {
        val live = metadata.replace("流浪地球", "鬼灭之刃").replace("\"vod_year\"", "\"type_name\":\"真人电视剧\",\"vod_year\"")
        Server { live }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("same", "Same", "${server.url}/same")))
            val resolver = MoviePlaybackResolver(catalog) { error("Menu loading does not resolve media") }
            assertEquals(1, resolver.listOptions("same", "1", 1, "hls", "鬼灭之刃", 2019).size)
            try {
                resolver.listOptions("same", "1", 1, "hls", "鬼灭之刃", 2019, MovieContentType.ANIME)
                fail("A cached general-video option was reused for anime")
            } catch (_: java.io.IOException) { }
        }
    }

    @Test fun aTypedManualOptionRejectsAnExtractorCommentaryBeforeRenamingItsTitle() = runBlocking {
        val anime = metadata.replace("流浪地球", "鬼灭之刃").replace("\"vod_year\"", "\"type_name\":\"日韩动漫\",\"vod_year\"")
        Server { anime }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("anime", "Anime", "${server.url}/anime")))
            val resolver = MoviePlaybackResolver(catalog) { VideoPlaybackSource(it, VideoPlaybackKind.DIRECT, title = "鬼灭之刃解说") }
            val option = resolver.listOptions("anime", "1", 1, "hls", "鬼灭之刃", 2019, MovieContentType.ANIME).single()
            try { resolver.resolveOption(option); fail("Manual source selection accepted commentary") }
            catch (_: java.io.IOException) { }
            try { resolver.resolveOption(option.copy(contentType = MovieContentType.GENERAL_VIDEO)); fail("Dropping type retained a valid option ID") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun animeCatalogSkipsLiveActionAndAnExtractorCommentaryTitleBeforeTryingTheNextRealLine() = runBlocking {
        val payload = """{"list":[
            {"vod_id":"live","vod_name":"鬼灭之刃","vod_year":"2019","type_name":"真人电视剧","vod_play_from":"hls","vod_play_url":"第1集${'$'}https://cdn.example.org/live.m3u8"},
            {"vod_id":"anime","vod_name":"鬼灭之刃","vod_year":"2019","type_name":"日韩动漫","vod_play_from":"bad${'$'}${'$'}${'$'}good","vod_play_url":"第1集${'$'}https://cdn.example.org/commentary.m3u8${'$'}${'$'}${'$'}第1集${'$'}https://cdn.example.org/episode.m3u8"}
        ]}"""
        val resolved = mutableListOf<String>()
        Server { payload }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("anime", "Anime", "${server.url}/anime")))
            val resolver = MoviePlaybackResolver(catalog) { url ->
                resolved += url
                VideoPlaybackSource(url, VideoPlaybackKind.DIRECT,
                    title = if (url.endsWith("commentary.m3u8")) "鬼灭之刃解说" else "鬼灭之刃 第1集", durationSeconds = 180.0)
            }
            val selected = resolver.searchAndResolve("鬼灭之刃", 2019, 1, contentType = MovieContentType.ANIME)
            assertEquals("anime", selected.item.id)
            assertEquals(listOf("https://cdn.example.org/commentary.m3u8", "https://cdn.example.org/episode.m3u8"), resolved)
            assertEquals("good", selected.lineName)
            assertTrue(selected.contentTypeVerified)
        }
    }

    @Test fun explicitYearCanVerifyAFastSourceWhileAnIndependentCatalogIsStillBlocked() = runBlocking {
        val slowStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        Server { path ->
            if (path.startsWith("/slow?")) { slowStarted.countDown(); release.await(5, TimeUnit.SECONDS) }
            else check(slowStarted.await(3, TimeUnit.SECONDS))
            metadata
        }.use { server ->
            val http = OkHttpClient()
            val catalog = MovieCatalogClient(http, listOf(
                MovieCatalogSource("slow", "Slow", "${server.url}/slow"),
                MovieCatalogSource("fast", "Fast", "${server.url}/fast"),
            ))
            val resolver = MoviePlaybackResolver(catalog) { VideoPlaybackSource(it, VideoPlaybackKind.DIRECT) }
            try {
                val selected = withTimeout(2_000) { resolver.searchAndResolve("流浪地球", 2019) }
                assertEquals("fast", selected.item.sourceId)
                assertEquals(1L, release.count)
                assertEquals(2, selected.searchReport!!.eligibleSources)
                assertEquals(setOf("fast"), selected.searchReport.successfulSourceIds)
            } finally { release.countDown() }
        }
    }

    @Test fun aFailedFirstMediaDoesNotStopALaterCatalogSnapshot() = runBlocking {
        val firstMediaAttempt = CountDownLatch(1)
        val release = CountDownLatch(1)
        Server { path ->
            when {
                path.startsWith("/slow?") -> { release.await(5, TimeUnit.SECONDS); """{"list":[]}""" }
                path.startsWith("/later?") -> { check(firstMediaAttempt.await(3, TimeUnit.SECONDS)); metadata.replace("a.m3u8", "later.m3u8") }
                else -> metadata
            }
        }.use { server ->
            val http = OkHttpClient.Builder().dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build()
            val catalog = MovieCatalogClient(http, listOf("slow", "first", "later").map {
                // The fixture represents independent providers rather than one busy host.
                MovieCatalogSource(it, it, "${server.url.replace("127.0.0.1", "$it.catalog.test")}/$it")
            })
            val resolver = MoviePlaybackResolver(catalog) { url ->
                if (url.endsWith("/a.m3u8")) { firstMediaAttempt.countDown(); throw java.io.IOException("failed first CDN") }
                VideoPlaybackSource(url, VideoPlaybackKind.DIRECT)
            }
            try {
                val selected = withTimeout(2_000) { resolver.searchAndResolve("流浪地球", 2019) }
                assertEquals("later", selected.item.sourceId)
                assertEquals(1L, release.count)
                assertTrue(selected.searchReport!!.successfulSourceIds.containsAll(setOf("first", "later")))
            } finally { release.countDown() }
        }
    }

    @Test fun sequentialSearchAndDetailEachKeepTheirHttpBudget() = runBlocking {
        val completedStages = ConcurrentHashMap.newKeySet<String>()
        Server { path ->
            Thread.sleep(4_500)
            if (path.startsWith("/suggest?")) {
                completedStages.add("suggest")
                """{"list":[{"id":"1","name":"流浪地球"}]}"""
            } else {
                completedStages.add("detail")
                metadata
            }
        }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(
                MovieCatalogSource("two_step", "Two step", "${server.url}/api", "${server.url}/suggest"),
            ))
            val started = System.nanoTime()
            val items = withTimeout(15_000) { catalog.search("流浪地球", 2019) }
            assertEquals(setOf("suggest", "detail"), completedStages)
            assertEquals("https://cdn.example.org/a.m3u8", items.single().lines.single().episodes.single().url)
            assertTrue("A valid two-request lookup must survive the old eight-second aggregate limit",
                System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(8))
        }
    }

    @Test fun fastCatalogPublishesWithoutWaitingForBlockedCatalogAndCancellationStopsCalls() = runBlocking {
        val release = CountDownLatch(1)
        Server { path -> if (path.startsWith("/slow")) release.await(5, TimeUnit.SECONDS); metadata }.use { server ->
            val http = OkHttpClient()
            val catalog = MovieCatalogClient(http, listOf(
                MovieCatalogSource("slow", "Slow", "${server.url}/slow"),
                MovieCatalogSource("fast", "Fast", "${server.url}/fast"),
            ))
            try {
                val first = withTimeout(2_000) { catalog.searchUpdates("流浪地球", 2019).first { it.any { item -> item.sourceId == "fast" } } }
                assertEquals(listOf("fast"), first.map { it.sourceId })
                assertEquals("A first result must not depend on the slow provider", 1L, release.count)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (http.dispatcher.runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(0, http.dispatcher.runningCallsCount())
            } finally { release.countDown() }
        }
    }

    @Test fun aMatchingCatalogAfterTheOldFirstFifteenIsSearched() = runBlocking {
        val visited = ConcurrentHashMap.newKeySet<String>()
        Server { path ->
            visited.add(path.substringBefore('?'))
            if (path.startsWith("/source23?")) metadata else """{"list":[]}"""
        }.use { server ->
            val sources = (0..23).map { MovieCatalogSource("source$it", "Source $it", "${server.url}/source$it") }
            val catalog = MovieCatalogClient(OkHttpClient(), sources)
            val items = withTimeout(5_000) { catalog.search("流浪地球", 2019) }
            assertEquals(listOf("source23"), items.map { it.sourceId })
            assertEquals(24, visited.size)
            assertEquals(24, catalog.sourceIdsForSearch().size)
        }
    }

    @Test fun stalledCurrentCatalogDoesNotBlockAlternativeMenuChoice() = runBlocking {
        val release = CountDownLatch(1)
        Server { path -> if (path.startsWith("/current")) release.await(5, TimeUnit.SECONDS); metadata }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(
                MovieCatalogSource("current", "Current", "${server.url}/current"),
                MovieCatalogSource("other", "Other", "${server.url}/other"),
            ))
            val resolver = MoviePlaybackResolver(catalog) { error("Opening a source menu must not resolve media") }
            try {
                val options = withTimeout(2_000) {
                    resolver.optionUpdates("current", "1", 1, "hls", "流浪地球", 2019)
                        .first { choices -> choices.any { it.sourceId == "other" } }
                }
                assertEquals(listOf("other"), options.map { it.sourceId })
                assertEquals(1L, release.count)
            } finally { release.countDown() }
        }
    }

    @Test fun aLaterMatchingProviderExtendsAnAlreadyPublishedCurrentChoice() = runBlocking {
        val release = CountDownLatch(1)
        Server { path -> if (path.startsWith("/later")) release.await(5, TimeUnit.SECONDS); metadata }.use { server ->
            val catalog = MovieCatalogClient(OkHttpClient(), listOf(
                MovieCatalogSource("current", "Current", "${server.url}/current"),
                MovieCatalogSource("later", "Later", "${server.url}/later"),
            ))
            val resolver = MoviePlaybackResolver(catalog) { error("No media download is needed") }
            val snapshots = withTimeout(3_000) {
                resolver.optionUpdates("current", "1", 1, "hls", "流浪地球", 2019).onEach {
                    if (it.any { option -> option.sourceId == "current" }) release.countDown()
                }.toList()
            }
            assertEquals(setOf("current"), snapshots.first().map { it.sourceId }.toSet())
            assertEquals(setOf("current", "later"), snapshots.last().map { it.sourceId }.toSet())
            assertTrue(snapshots.last().single { it.sourceId == "current" }.selected)
        }
    }

    private class Server(private val content: (String) -> String) : AutoCloseable {
        private val socket = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        val url = "http://127.0.0.1:${socket.localPort}"
        init { workers.execute {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { runCatching { connection.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = content(path).toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type:application/json\r\nContent-Length:${bytes.size}\r\nConnection:close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } } }
            }
        } }
        override fun close() { socket.close(); workers.shutdownNow() }
    }
}
