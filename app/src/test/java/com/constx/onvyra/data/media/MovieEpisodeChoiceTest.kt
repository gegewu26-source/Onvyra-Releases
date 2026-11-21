package com.constx.onvyra.data.media

import org.junit.Assert.*
import org.junit.Test

class MovieEpisodeChoiceTest {
    private fun item(vararg lines: MoviePlaybackLine) = MovieCatalogItem("series", "测试剧集", 2026,
        lines = lines.toList(), sourceId = "same-provider", sourceName = "同一来源")
    private fun episode(number: Int?, label: String = number?.let { "第${it}集" }.orEmpty()) =
        MovieEpisode(label, "https://example.org/part-${number}.m3u8", number)

    @Test fun sparseNumberedSeriesDoesNotInventMissingEpisodesOrUseOtherLines() {
        val work = item(MoviePlaybackLine("高清", listOf(episode(1), episode(3))),
            MoviePlaybackLine("蓝光", listOf(episode(1), episode(2), episode(3))))
        assertEquals(listOf(1, 3), movieEpisodeChoices(work, "高清").map { it.number })
        assertEquals(listOf(1, 2, 3), movieEpisodeChoices(work, "蓝光").map { it.number })
    }

    @Test fun mixedNumberingDoesNotTreatUnnumberedTrailerAsEpisodeTwo() {
        val work = item(MoviePlaybackLine("main", listOf(episode(1), episode(null, "预告"), episode(8))))
        assertEquals(listOf(1, 8), movieEpisodeChoices(work, "main").map { it.number })
    }

    @Test fun positionalCatalogKeepsItsRealLabelsAndStableNumbers() {
        val work = item(MoviePlaybackLine("main", listOf(episode(null, "上篇"), episode(null, "下篇"))))
        assertEquals(listOf(MovieEpisodeChoice(1, "上篇"), MovieEpisodeChoice(2, "下篇")), movieEpisodeChoices(work, "main"))
    }

    @Test fun duplicateLineNamesUseTheSelectedIndexForTheirEpisodeMenu() {
        val work = item(MoviePlaybackLine("hls", listOf(episode(1), episode(2))),
            MoviePlaybackLine("hls", listOf(episode(1), episode(8))))
        assertEquals(listOf(1, 8), movieEpisodeChoices(work, "hls", 1).map { it.number })
    }

    @Test fun sameProviderQualityLinesRemainSeparateAndRetainTheirActualEpisode() {
        val work = item(MoviePlaybackLine("高清", listOf(episode(1), episode(2))),
            MoviePlaybackLine("超清", listOf(episode(1), episode(2))),
            MoviePlaybackLine("蓝光", listOf(episode(1), episode(2))))
        val choices = buildMovieSourceOptions(emptyList(), work, 2, "高清", contentType = MovieContentType.SERIES)
        assertEquals(3, choices.size)
        assertEquals(setOf("高清", "超清", "蓝光"), choices.map { it.qualityHint }.toSet())
        assertEquals(3, choices.map { it.id }.distinct().size)
        assertTrue(choices.all { it.sourceId == "same-provider" && it.episode == 2 })
        assertEquals("高清", choices.single { it.selected }.lineName)
    }
}
