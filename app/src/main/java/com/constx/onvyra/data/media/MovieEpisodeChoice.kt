package com.constx.onvyra.data.media

/** A catalog locator, with no signed stream address or model-generated episode. */
internal data class MovieEpisodeChoice(val number: Int, val label: String)

internal fun movieEpisodeChoices(item: MovieCatalogItem, lineName: String?, lineIndex: Int? = null): List<MovieEpisodeChoice> {
    val line = lineIndex?.let { item.lines.getOrNull(it) }?.takeIf { lineName == null || it.name == lineName }
        ?: item.lines.firstOrNull { it.name == lineName }
        ?: item.lines.firstOrNull() ?: return emptyList()
    val positional = line.episodes.all { it.index == null }
    return line.episodes.mapIndexedNotNull { index, part ->
        val number = part.index ?: if (positional) index + 1 else return@mapIndexedNotNull null
        if (number < 1) return@mapIndexedNotNull null
        MovieEpisodeChoice(number, part.name.take(72).ifBlank { "第${number}集" })
    }.distinctBy { it.number }.sortedBy { it.number }
}
