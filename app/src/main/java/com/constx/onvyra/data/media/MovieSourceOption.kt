package com.constx.onvyra.data.media

import java.security.MessageDigest

/** A local catalog locator. Signed playback addresses are deliberately not part of this model. */
internal data class MovieSourceOption(
    val id: String,
    val label: String,
    val sourceId: String,
    val itemId: String,
    val episode: Int,
    val lineName: String,
    val title: String,
    val year: Int? = null,
    val selected: Boolean = false,
    val qualityHint: String? = null,
    val lineIndex: Int = 0,
    val contentType: MovieContentType = MovieContentType.GENERAL_VIDEO,
)

internal fun movieSourceOptionId(sourceId: String, itemId: String, episode: Int, lineIndex: Int, lineName: String,
                                 contentType: MovieContentType = MovieContentType.GENERAL_VIDEO): String {
    val fields = listOf(sourceId, itemId, episode.toString(), lineIndex.toString(), lineName)
    val locator = (if (contentType == MovieContentType.GENERAL_VIDEO) fields else fields + contentType.wireValue).joinToString("\u0000")
    val digest = MessageDigest.getInstance("SHA-256").digest(locator.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Describes catalog labels, never the measured resolution of the actual stream. */
internal fun movieCatalogQualityHint(item: MovieCatalogItem, line: MoviePlaybackLine): String? {
    val value = listOfNotNull(item.title, item.remarks, line.name).joinToString(" ")
    return listOf(
        "8K" to Regex("(?i)(?<![A-Za-z0-9])(?:8k|4320p)(?![A-Za-z0-9])"),
        "4K" to Regex("(?i)(?<![A-Za-z0-9])(?:4k|2160p)(?![A-Za-z0-9])"),
        "1080P" to Regex("(?i)(?<![A-Za-z0-9])1080p(?![A-Za-z0-9])"),
        "720P" to Regex("(?i)(?<![A-Za-z0-9])720p(?![A-Za-z0-9])"),
        "蓝光" to Regex("蓝光"),
        "超清" to Regex("超清"),
        "高清" to Regex("高清"),
    ).firstOrNull { it.second.containsMatchIn(value) }?.first
}

internal fun MoviePlaybackLine.episodeAt(number: Int): MovieEpisode? = episodes.firstOrNull { it.index == number }
    ?: episodes.takeIf { list -> list.all { it.index == null } }?.getOrNull(number - 1)

/** Merge parallel/remembered snapshots without bringing back another film or release year. */
internal fun mergeMovieSourceOptions(
    previous: List<MovieSourceOption>,
    incoming: List<MovieSourceOption>,
    currentSourceId: String,
    currentItemId: String,
    episode: Int,
    title: String,
    year: Int?,
    contentType: MovieContentType = MovieContentType.GENERAL_VIDEO,
): List<MovieSourceOption> {
    fun MovieSourceOption.isCurrent() = sourceId == currentSourceId && itemId == currentItemId
    fun matchesTarget(option: MovieSourceOption): Boolean {
        if (isMatchingMovieTitle(title, option.title)) return true
        val targetCode = extractJavCode(title, currentItemId)
        val optionCode = extractJavCode(option.title, option.itemId)
        if (targetCode != null && optionCode != null && targetCode.equals(optionCode, ignoreCase = true)) return true
        if (option.sourceId in EXTERNAL_VIDEO_SITE_IDS || currentSourceId in EXTERNAL_VIDEO_SITE_IDS) {
            val normReq = normalizeCjkForMatch(title)
            val normOpt = normalizeCjkForMatch(option.title)
            if (normReq.isNotEmpty() && (normOpt.contains(normReq) || normReq.contains(normOpt))) return true
        }
        return false
    }
    val candidates = (incoming + previous).distinctBy { it.id }
        .filter { it.contentType == contentType && it.episode == episode && matchesTarget(it) }
    val currentYear = candidates.firstOrNull { it.isCurrent() && it.year != null }?.year
    val distinctYears = candidates.mapNotNull { it.year }.distinct()
    val selectedYear = year ?: currentYear ?: distinctYears.singleOrNull()
    return candidates.filter {
        when {
            selectedYear != null -> it.year == selectedYear || it.isCurrent() && it.year == null
            distinctYears.size > 1 -> it.isCurrent()
            else -> true
        }
    }
}

/** Keep all real lines for the same movie/edition, including catalog-labelled high quality variants. */
internal fun buildMovieSourceOptions(
    items: List<MovieCatalogItem>,
    current: MovieCatalogItem,
    episode: Int,
    currentLine: String?,
    title: String = current.title,
    year: Int? = current.year,
    contentType: MovieContentType = MovieContentType.GENERAL_VIDEO,
): List<MovieSourceOption> {
    require(episode > 0)
    val knownYear = year ?: current.year
    val targetCode = extractJavCode(title, current.title, current.id)
    val sameTitle = (listOf(current) + items).filter { candidate ->
        isEligibleCatalogWork(candidate, contentType) && (
            isMatchingMovieTitle(title, candidate.title) ||
                isMatchingCatalogSearchResult(title, candidate) ||
                (targetCode != null && extractJavCode(candidate.title, candidate.id)?.equals(targetCode, ignoreCase = true) == true)
            )
    }
    val distinctYears = sameTitle.mapNotNull { it.year }.distinct()
    val unambiguousYear = knownYear ?: distinctYears.singleOrNull()
    val entries = sameTitle.filter { candidate ->
        val isCurrent = candidate.sourceId == current.sourceId && candidate.id == current.id
        when {
            unambiguousYear != null -> candidate.year == unambiguousYear || isCurrent && candidate.year == null
            distinctYears.size > 1 -> isCurrent
            else -> true
        }
    }.distinctBy { it.sourceId to it.id }
    val currentLineIndex = current.lines.indexOfFirst { it.name == currentLine }
    return entries.flatMap { item ->
        item.lines.mapIndexedNotNull { lineIndex, line ->
            if (line.episodeAt(episode) == null) return@mapIndexedNotNull null
            val hint = movieCatalogQualityHint(item, line)
            val label = buildString {
                append(item.sourceName.ifBlank { item.sourceId })
                append(" · "); append(item.title)
                item.year?.let { append("（"); append(it); append("）") }
                append(" · "); append(line.name)
                hint?.let { append(" · 目录标注"); append(it) }
            }
            MovieSourceOption(
                id = movieSourceOptionId(item.sourceId, item.id, episode, lineIndex, line.name, contentType),
                label = label, sourceId = item.sourceId, itemId = item.id, episode = episode,
                lineName = line.name, title = item.title, year = item.year,
                selected = item.sourceId == current.sourceId && item.id == current.id && lineIndex == currentLineIndex,
                qualityHint = hint, lineIndex = lineIndex, contentType = contentType,
            )
        }
    }.distinctBy { it.id }
}
