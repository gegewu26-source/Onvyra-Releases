package com.constx.onvyra.data.media

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MovieWebSearchFallbackTest {
    @Test fun animeSearchSkipsCommentaryAndWrongEpisodeAndCanAcceptAShortExplicitEpisode() = runBlocking {
        val title = "鬼灭之刃"
        val commentary = MovieWebCandidate("鬼灭之刃解说", "https://review.example.org/watch/one")
        val mix = MovieWebCandidate("鬼灭之刃 混剪", "https://mix.example.org/watch/one")
        val wrong = MovieWebCandidate("鬼灭之刃 第2集", "https://wrong.example.org/watch/two")
        val episode = MovieWebCandidate("鬼灭之刃 第1集", "https://anime.example.org/watch/one")
        val queries = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val resolutions = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            searchWeb = { queries += it; listOf(commentary, mix, wrong, episode) },
            readPage = { reads += it; MovieWebPage(it, episode.title, "类型：日韩动漫") },
            resolveVideo = { resolutions += it; media(title = episode.title, duration = 180.0) },
        )
        val result = fallback.search(title, null, 1, MovieContentType.ANIME)
        assertEquals("ready", result.status)
        assertEquals(episode.url, result.selection!!.originalUrl)
        assertEquals(listOf(episode.url), reads)
        assertEquals(listOf(episode.url), resolutions)
        assertTrue(result.selection.contentTypeVerified)
        assertTrue(queries.single().contains("动漫 第1集 正片"))
        assertFalse(queries.single().contains("电影"))
    }

    @Test fun animeEpisodeOneRequiresActualEpisodeMetadataRatherThanATitleOnlyWatchPage() = runBlocking {
        val c = MovieWebCandidate("鬼灭之刃", "https://anime.example.org/watch/title-only")
        var resolveCalls = 0
        val fallback = MovieWebSearchFallback(
            { listOf(c) }, { MovieWebPage(it, "鬼灭之刃", "日韩动漫 作品介绍") },
            { resolveCalls++; media(title = "鬼灭之刃", duration = 180.0) },
        )
        val result = fallback.search("鬼灭之刃", null, 1, MovieContentType.ANIME)
        assertEquals("unverified_sources", result.status)
        assertNull(result.selection)
        assertEquals(0, resolveCalls)
    }

    @Test fun animeIndexFollowsItsFirstEpisodeAndExtractorCommentaryCannotReplaceTheEpisode() = runBlocking {
        val title = "鬼灭之刃"
        val index = MovieWebCandidate(title, "https://index.example.org/detail/anime")
        val bad = MovieWebCandidate("第1集", "https://bad.example.org/play/1")
        val good = MovieWebCandidate("第1集", "https://good.example.org/play/1")
        val resolved = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            { listOf(index) },
            { if (it == index.url) MovieWebPage(it, title, "日韩动漫", links = listOf(bad, good))
              else MovieWebPage(it, "$title 第1集", "动漫") },
            { resolved += it; media(title = if (it == bad.url) "$title 解说" else "$title 第1集", duration = 180.0) },
        )
        val result = fallback.search(title, null, 1, MovieContentType.ANIME)
        assertEquals("ready", result.status)
        assertEquals(listOf(bad.url, good.url), resolved)
        assertEquals(good.url, result.selection!!.originalUrl)
    }

    private val film = "让子弹飞"
    private fun candidate(host: String, title: String = film, path: String = "watch/film") =
        MovieWebCandidate(title, "https://$host.example.org/$path")
    private fun page(candidate: MovieWebCandidate, title: String = "$film (2010)",
                     description: String = "", text: String = "", links: List<MovieWebCandidate> = emptyList()) =
        MovieWebPage(candidate.url, title, description, text, links)
    private fun media(title: String? = film, duration: Double? = 7_200.0, url: String = "https://cdn.example.org/film.m3u8") =
        VideoPlaybackSource(url, VideoPlaybackKind.DIRECT, title = title, durationSeconds = duration)

    @Test fun failedFirstPageDoesNotPreventAnIndependentVerifiedPlaybackCandidate() = runBlocking {
        val first = candidate("first")
        val second = candidate("second")
        val searched = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            searchWeb = { searched += it; listOf(first, second) },
            readPage = { url -> page(if (url == first.url) first else second) },
            resolveVideo = { url -> resolved += url; if (url == first.url) throw IOException("old source unavailable") else media() },
        )
        val result = fallback.search(film, 2010, 1)
        assertEquals("ready", result.status)
        assertEquals(second.url, result.selection!!.originalUrl)
        assertTrue(result.selection.durationKnown)
        assertEquals(listOf(first.url, second.url), resolved)
        assertEquals(1, searched.size)
    }

    @Test fun duplicatesWrongFilmClipsSearchPagesAndNonPublicUrlsNeverReachThePageReader() = runBlocking {
        val real = candidate("real")
        val seen = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            searchWeb = { listOf(
                candidate("other", "让子弹飞2"), candidate("review", "$film 电影解说"),
                candidate("trailer", "$film 预告片"), candidate("query", path = "search/$film"),
                MovieWebCandidate(film, "https://u:p@credentials.example.org/watch/film"),
                MovieWebCandidate(film, "http://127.0.0.1/watch/film"),
                MovieWebCandidate(film, "http://192.168.1.1/watch/film"),
                MovieWebCandidate(film, "http://localhost/watch/film"),
                MovieWebCandidate(film, "file:///sdcard/film.mp4"), real, real.copy(url = real.url + "#repeated"),
            ) },
            readPage = { seen += it; page(real) },
            resolveVideo = { throw IOException("no playable stream") },
        )
        val result = fallback.search(film, 2010, 1)
        assertEquals("unverified_sources", result.status)
        assertEquals(listOf(real.url), seen)
        assertEquals(listOf(real.url), result.candidates.map { it.url })
        assertEquals("$film (2010)", result.candidates.single().title)
    }

    @Test fun pageTitleAndReleaseMetadataOverrideAConvenientSearchSnippetOrFooter() = runBlocking {
        val wrongTitle = candidate("wrong-title")
        val wrongYear = candidate("wrong-year")
        val right = candidate("right")
        val resolved = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            searchWeb = { listOf(wrongTitle, wrongYear, right) },
            readPage = { when (it) {
                wrongTitle.url -> page(wrongTitle, title = "无关电影", text = "正文完全不同\n页脚推荐让子弹飞 2010")
                wrongYear.url -> page(wrongYear, title = film, description = "上映年份：2025")
                else -> page(right)
            } },
            resolveVideo = { resolved += it; media() },
        )
        val result = fallback.search(film, 2010, 1)
        assertEquals("ready", result.status)
        assertEquals(right.url, result.selection!!.originalUrl)
        assertEquals(listOf(right.url), resolved)
    }

    @Test fun seriesIndexFollowsOnlyTheExplicitRequestedEpisodeRatherThanEpisodeTwenty() = runBlocking {
        val series = "漫长的季节"
        val index = candidate("index", series, "detail/series")
        val twenty = candidate("twenty", "第20集", "play/20")
        val two = candidate("two", "第二集", "play/2")
        val read = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            searchWeb = { listOf(candidate("mismatch", "$series 第20集"), index) },
            readPage = { read += it; if (it == index.url)
                page(index, title = "$series (2023)", links = listOf(twenty, two))
                else page(two, title = "$series 第二集 (2023)") },
            resolveVideo = { resolved += it; media(title = "$series 第2集") },
        )
        val result = fallback.search(series, 2023, 2)
        assertEquals("ready", result.status)
        assertEquals(two.url, result.selection!!.originalUrl)
        assertEquals(listOf(index.url, two.url), read)
        assertEquals(listOf(two.url), resolved)
    }

    @Test fun explicitlyWrongEpisodeIsRejectedEvenWhenDefaultEpisodeOneWasRequested() = runBlocking {
        val c = candidate("wrong", "测试剧 第10集")
        var reads = 0
        val fallback = MovieWebSearchFallback({ listOf(c) }, { reads++; page(c, title = c.title) }, { media(title = c.title) })
        assertEquals("no_results", fallback.search("测试剧", null, 1).status)
        assertEquals(0, reads)
    }

    @Test fun shortClipTrailerAndWrongYearFromTheExtractorDoNotBecomeFullMovieSuccess() = runBlocking {
        val choices = listOf(candidate("short"), candidate("trailer"), candidate("wrong-year"), candidate("full"))
        val resolved = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            searchWeb = { choices }, readPage = { url -> page(choices.first { it.url == url }) },
            resolveVideo = { url ->
                resolved += url
                when (url) {
                    choices[0].url -> media(duration = 120.0)
                    choices[1].url -> media(title = "$film Trailer", duration = 7_200.0)
                    choices[2].url -> media(title = "$film (2025)")
                    else -> media()
                }
            },
        )
        val result = fallback.search(film, 2010, 1)
        assertEquals("ready", result.status)
        assertEquals(choices.last().url, result.selection!!.originalUrl)
        assertEquals(4, resolved.size)
    }

    @Test fun anUnknownDurationStaysExplicitAndUsesTheCheckedPageTitleForGenericExtraction() = runBlocking {
        val c = candidate("valid")
        val source = media(title = "视频网页", duration = null,
            url = "https://cdn.example.org/film.m3u8?token=private-stream").copy(headers = mapOf("Cookie" to "private-cookie"))
        val result = MovieWebSearchFallback({ listOf(c) }, { page(c) }, { source }).search(film, 2010, 1)
        assertEquals("ready", result.status)
        assertFalse(result.selection!!.durationKnown)
        assertEquals("$film (2010)", result.selection.source.title)
        assertEquals(c.url, result.selection.originalUrl)
        assertFalse(result.candidates.toString().contains("private"))
        assertTrue(result.candidates.all { it.text.isEmpty() })
    }

    @Test fun anActualMediaLinkFromTheCheckedPageDoesNotExposeItsSignatureAsOriginalUrl() = runBlocking {
        val c = candidate("detail", path = "detail/film")
        val direct = MovieWebCandidate("正片播放", "https://cdn.example.org/film.mp4?signature=private")
        val read = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        val fallback = MovieWebSearchFallback(
            searchWeb = { listOf(c) }, readPage = { read += it; page(c, links = listOf(direct)) },
            resolveVideo = { resolved += it; media(url = direct.url) },
        )
        val result = fallback.search(film, 2010, 1)
        assertEquals("ready", result.status)
        assertEquals(c.url, result.selection!!.originalUrl)
        assertEquals(listOf(c.url), read)
        assertEquals(listOf(direct.url), resolved)
        assertEquals(listOf(c.url), result.candidates.map { it.url })
    }

    @Test fun cancellationFromAnyNetworkStagePropagatesRatherThanTryingTheNextSource() = runBlocking {
        for (stage in 0..2) {
            val c = candidate("cancelled")
            var calls = 0
            val fallback = MovieWebSearchFallback(
                searchWeb = { calls++; if (stage == 0) throw CancellationException("user stopped"); listOf(c) },
                readPage = { if (stage == 1) throw CancellationException("user stopped"); page(c) },
                resolveVideo = { if (stage == 2) throw CancellationException("user stopped"); media() },
            )
            try { fallback.search(film, 2010, 1); fail("Stage $stage swallowed cancellation") }
            catch (_: CancellationException) { }
            assertEquals(1, calls)
        }
    }

    @Test fun theCoordinatorConvertsOnlyItsOwnDeadlineToATimeoutOutcome() = runBlocking {
        var searches = 0
        val fallback = MovieWebSearchFallback(
            searchWeb = { searches++; delay(1_000); emptyList() },
            readPage = { error("unexpected") }, resolveVideo = { error("unexpected") }, timeoutMillis = 20,
        )
        val result = fallback.search(film, 2010, 1)
        assertEquals("timeout", result.status)
        assertNull(result.selection)
        assertEquals(1, searches)
    }

    @Test fun searchFailureAndSuccessfulEmptyResultsAreDifferentAndUseAtMostTwoQueries() = runBlocking {
        val queries = mutableListOf<String>()
        val unavailable = MovieWebSearchFallback({ queries += it; throw IOException("apikey=do-not-leak") },
            { error("unexpected") }, { error("unexpected") }).search(film, 2010, 1)
        assertEquals("search_unavailable", unavailable.status)
        assertEquals(2, queries.distinct().size)
        assertFalse(unavailable.toString().contains("apikey"))
        var calls = 0
        val empty = MovieWebSearchFallback({ calls++; if (calls == 1) throw IOException("temporary"); emptyList() },
            { error("unexpected") }, { error("unexpected") }).search(film, 2010, 1)
        assertEquals("no_results", empty.status)
        assertEquals(2, calls)
    }

    @Test fun theGlobalCandidatePageAndExtractionBudgetsBoundAnUnplayableResultSet() = runBlocking {
        var searches = 0
        var reads = 0
        var resolutions = 0
        val choices = (1..20).map { candidate("site$it") }
        val result = MovieWebSearchFallback(
            searchWeb = { searches++; choices },
            readPage = { url -> reads++; page(choices.first { it.url == url }) },
            resolveVideo = { resolutions++; throw IOException("unavailable") },
        ).search(film, 2010, 1)
        assertEquals("unverified_sources", result.status)
        assertEquals(1, searches)
        assertEquals(4, reads)
        assertEquals(4, resolutions)
        assertEquals(4, result.candidates.size)
    }

    @Test fun embeddedPlayersAndPrivateResolvedMediaAreNotAcceptedAsVerifiedNativeStreams() = runBlocking {
        val choices = listOf(candidate("embedded"), candidate("private"), candidate("public"))
        val result = MovieWebSearchFallback({ choices }, { url -> page(choices.first { it.url == url }) }, { url ->
            when (url) {
                choices[0].url -> media().copy(kind = VideoPlaybackKind.EMBEDDED)
                choices[1].url -> media(url = "http://10.0.0.1/film.mp4")
                else -> media()
            }
        }).search(film, 2010, 1)
        assertEquals("ready", result.status)
        assertEquals(choices.last().url, result.selection!!.originalUrl)
    }

    @Test fun aYearWithinTheFilmNameIsNotMisreadAsItsReleaseYear() = runBlocking {
        val c = candidate("war", "1917 (2019)")
        val result = MovieWebSearchFallback({ listOf(c) }, { page(c, title = c.title) },
            { media(title = "1917", duration = 7_100.0) }).search("1917", 2019, 1)
        assertEquals("ready", result.status)
        assertEquals(c.url, result.selection!!.originalUrl)
    }
}
