package com.constx.onvyra.data.media

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MovieSourceSwitchingTest {
    @Test fun typedAnimeOptionsRejectSameTitleLiveActionAndCannotReuseGeneralCacheIds() {
        val current = item(title = "鬼灭之刃").copy(category = "日韩动漫")
        val liveAction = current.copy(id = "live", category = "真人电视剧")
        val commentary = current.copy(id = "commentary", remarks = "解说")
        val generic = buildMovieSourceOptions(listOf(liveAction), current, 1, "hls")
        val anime = buildMovieSourceOptions(listOf(liveAction, commentary), current, 1, "hls", contentType = MovieContentType.ANIME)
        assertEquals(listOf("1"), anime.map { it.itemId })
        assertTrue(anime.all { it.contentType == MovieContentType.ANIME })
        assertNotEquals(generic.first { it.itemId == "1" }.id, anime.single().id)
        assertEquals(anime, mergeMovieSourceOptions(generic, anime, "a", "1", 1, "鬼灭之刃", 2019, MovieContentType.ANIME))
    }

    private fun line(name: String, url: String, episode: Int? = 1) =
        MoviePlaybackLine(name, listOf(MovieEpisode("正片", url, episode)))

    private fun item(
        source: String = "a", id: String = "1", title: String = "流浪地球", year: Int? = 2019,
        lines: List<MoviePlaybackLine> = listOf(line("hls", "https://cdn.example.org/a.m3u8")),
        isClip: Boolean = false,
    ) = MovieCatalogItem(id, title, year, lines = lines, sourceId = source, sourceName = "来源$source", isClip = isClip)

    @Test fun menuKeepsProvidersLinesAndCatalogQualityVariantsButRejectsOtherFilms() {
        val current = item(lines = listOf(line("web", "https://example.org/watch"), line("hls", "https://cdn.example.org/a.m3u8")))
        val fourK = item(id = "2", title = "流浪地球4K（国语版）")
        val otherSource = item(source = "b", title = "流浪地球1080P")
        val choices = buildMovieSourceOptions(listOf(
            current, fourK, otherSource, item(source = "c", year = 2025), item(source = "d", year = null),
            item(id = "3", isClip = true), item(id = "4", title = "流浪地球2"),
        ), current, 1, "hls")

        assertEquals(4, choices.size)
        assertEquals(setOf("a", "b"), choices.map { it.sourceId }.toSet())
        assertEquals(1, choices.count { it.selected })
        assertEquals("hls", choices.single { it.selected }.lineName)
        assertEquals("4K", choices.single { it.itemId == "2" }.qualityHint)
        assertTrue(choices.single { it.itemId == "2" }.label.contains("目录标注4K"))
        assertEquals("1080P", choices.single { it.sourceId == "b" }.qualityHint)
        assertTrue(choices.none { it.label.contains("https://") })
        assertTrue(choices.all { it.id.matches(Regex("[a-f0-9]{32}")) })
    }

    @Test fun unknownMovieYearDoesNotMixTwoKnownSameTitleReleases() {
        val current = item(year = null)
        val choices = buildMovieSourceOptions(listOf(item(source = "b", year = 2019), item(source = "c", year = 2025)), current, 1, "hls")
        assertEquals(listOf("a"), choices.map { it.sourceId })
    }

    @Test fun mergingLaterSnapshotsRemovesStaleWrongYearsAndKeepsOnlyTheRequestedFilmAndEpisode() {
        val currentUnknown = buildMovieSourceOptions(emptyList(), item(year = null), 1, "hls").single()
        val oldRelease = buildMovieSourceOptions(emptyList(), item(source = "b", year = 2025), 1, "hls").single()
        val currentKnown = currentUnknown.copy(year = 2019)
        val matching = buildMovieSourceOptions(emptyList(), item(source = "c", year = 2019), 1, "hls").single()
        val wrongFilm = matching.copy(id = "wrongfilm", title = "流浪地球2")
        val wrongEpisode = matching.copy(id = "wrongepisode", episode = 2)
        val merged = mergeMovieSourceOptions(listOf(currentUnknown, oldRelease, wrongFilm, wrongEpisode),
            listOf(currentKnown, matching), "a", "1", 1, "流浪地球", null)
        assertEquals(setOf("a", "c"), merged.map { it.sourceId }.toSet())
        assertTrue(merged.all { it.year == 2019 && it.episode == 1 && it.title == "流浪地球" })

        val ambiguous = mergeMovieSourceOptions(listOf(currentUnknown, oldRelease), listOf(matching),
            "a", "1", 1, "流浪地球", null)
        assertEquals(listOf(currentUnknown), ambiguous)
    }

    @Test fun rejectedEpisodeUrlsDoNotTurnEpisodeTwoIntoEpisodeOne() {
        val current = item(lines = listOf(line("hls", "https://cdn.example.org/2.m3u8", episode = 2)))
        assertTrue(buildMovieSourceOptions(emptyList(), current, 1, "hls").isEmpty())
        assertEquals(2, buildMovieSourceOptions(emptyList(), current, 2, "hls").single().episode)
        assertEquals("https://cdn.example.org/2.m3u8", current.lines.single().episodeAt(2)?.url)
    }

    @Test fun qualityCatalogHintIsNotInventedAndIdsDoNotDependOnSignedUrls() {
        val current = item()
        val first = buildMovieSourceOptions(emptyList(), current, 1, "hls").single()
        val refreshed = current.copy(lines = listOf(line("hls", "https://cdn.example.org/refreshed.m3u8?token=secret")))
        val second = buildMovieSourceOptions(emptyList(), refreshed, 1, "hls").single()
        assertNull(first.qualityHint)
        assertEquals(first.id, second.id)
        assertFalse(second.toString().contains("secret"))
    }

    @Test fun queryingFromAQualityOrDubbedVersionKeepsTheOriginalFilmSearchable() {
        assertEquals("流浪地球", movieCatalogQueryTitle("流浪地球4K（国语版）"))
        assertEquals("Now You See Me 3", movieCatalogQueryTitle("Now You See Me 3 - 1080P"))
        assertEquals("4K", movieCatalogQueryTitle("4K"))
        assertTrue(isMatchingMovieTitle("流浪地球", "流浪地球 4K（国语版）"))
        assertFalse(isMatchingMovieTitle("流浪地球", "流浪地球电影解说"))
    }

    @Test fun manualChoiceRefreshesItsUrlAndNeverFallsBackToAnotherLine() = runBlocking {
        val original = item(lines = listOf(line("sd", "https://cdn.example.org/old-sd.m3u8"), line("hd", "https://cdn.example.org/old-hd.m3u8")))
        val choice = buildMovieSourceOptions(emptyList(), original, 1, "sd").single { it.lineName == "hd" }
        TestServer { metadata(listOf("sd" to "https://cdn.example.org/new-sd.m3u8", "hd" to "https://cdn.example.org/new-hd.m3u8?token=renewed")) }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("a", "来源a", server.url)))
            val calls = mutableListOf<String>()
            val resolver = MoviePlaybackResolver(client) { url -> calls.add(url); throw IOException("chosen CDN unavailable") }
            try { resolver.resolveOption(choice); fail("A failed manual choice must not silently use sd") }
            catch (_: IOException) { }
            assertEquals(listOf("https://cdn.example.org/new-hd.m3u8?token=renewed"), calls)
        }
    }

    @Test fun uniquelyNamedLineCanMoveWithoutChangingTheRequestedSource() = runBlocking {
        val original = item(lines = listOf(line("sd", "https://cdn.example.org/sd.m3u8"), line("hd", "https://cdn.example.org/hd.m3u8")))
        val choice = buildMovieSourceOptions(emptyList(), original, 1, "sd").single { it.lineName == "hd" }
        TestServer { metadata(listOf("hd" to "https://cdn.example.org/refreshed-hd.m3u8", "sd" to "https://cdn.example.org/sd.m3u8")) }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("a", "来源a", server.url)))
            val resolver = MoviePlaybackResolver(client) { url -> VideoPlaybackSource(url, VideoPlaybackKind.DIRECT) }
            val selected = resolver.resolveOption(choice)
            assertEquals("hd", selected.lineName)
            assertEquals("https://cdn.example.org/refreshed-hd.m3u8", selected.source.url)
            assertEquals("流浪地球 · 来源a", selected.source.title)
        }
    }

    @Test fun manualChoiceRejectsChangedMovieAndForgedIdsBeforePlaying() = runBlocking {
        val original = item()
        val choice = buildMovieSourceOptions(emptyList(), original, 1, "hls").single()
        TestServer { metadata(listOf("hls" to "https://cdn.example.org/a.m3u8"), title = "流浪地球2", year = 2023) }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("a", "来源a", server.url)))
            var mediaCalls = 0
            val resolver = MoviePlaybackResolver(client) { url -> mediaCalls++; VideoPlaybackSource(url, VideoPlaybackKind.DIRECT) }
            try { resolver.resolveOption(choice.copy(id = "forged")); fail("Forged choice accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, server.requests.get())
            try { resolver.resolveOption(choice); fail("Different movie was accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, mediaCalls)
        }
    }

    @Test fun repeatedMenuOpenUsesCachedMetadataAndRecalculatesCurrentLine() = runBlocking {
        TestServer { metadata(listOf("sd" to "https://cdn.example.org/sd.m3u8", "hd" to "https://cdn.example.org/hd.m3u8")) }.use { server ->
            val client = MovieCatalogClient(OkHttpClient(), listOf(MovieCatalogSource("a", "来源a", server.url)))
            val resolver = MoviePlaybackResolver(client) { url -> VideoPlaybackSource(url, VideoPlaybackKind.DIRECT) }
            val first = resolver.listOptions("a", "1", 1, "sd")
            val requestsAfterFirst = server.requests.get()
            assertTrue(requestsAfterFirst > 0)
            assertEquals("sd", first.single { it.selected }.lineName)
            val second = resolver.listOptions("a", "1", 1, "hd")
            assertEquals("hd", second.single { it.selected }.lineName)
            assertEquals(requestsAfterFirst, server.requests.get())
        }
    }

    private fun metadata(lines: List<Pair<String, String>>, title: String = "流浪地球", year: Int = 2019): String = buildJsonObject {
        put("list", buildJsonArray { add(buildJsonObject {
            put("vod_id", "1"); put("vod_name", title); put("vod_year", year); put("type_name", "科幻片")
            put("vod_play_from", lines.joinToString("\$\$\$") { it.first })
            put("vod_play_url", lines.joinToString("\$\$\$") { "正片\$${it.second}" })
        }) })
    }.toString()

    private class TestServer(private val content: () -> String) : AutoCloseable {
        val requests = AtomicInteger()
        private val server = ServerSocket(0)
        private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        val url = "http://127.0.0.1:${server.localPort}/api"
        init { workers.execute {
            while (!server.isClosed) {
                val connection = runCatching { server.accept() }.getOrNull() ?: break
                workers.execute { runCatching { connection.use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requests.incrementAndGet()
                    val body = content().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type:application/json\r\nContent-Length:${body.size}\r\nConnection:close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                } } }
            }
        } }
        override fun close() { server.close(); workers.shutdownNow() }
    }
}
