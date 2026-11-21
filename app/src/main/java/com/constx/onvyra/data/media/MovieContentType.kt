package com.constx.onvyra.data.media

internal enum class MovieContentType(val wireValue: String) {
    ANIME("anime"), FILM("film"), SERIES("series"), GENERAL_VIDEO("general_video");

    companion object {
        fun fromWire(value: String): MovieContentType? = entries.firstOrNull { it.wireValue == value }
    }
}

private val nonWorkLabel = Regex(
    "预告(?!犯)|片花|花絮|片段|混剪|剪辑|解说|讲解|盘点|影评|幕后|抢先看|" +
        "\\b(?:trailer|teaser|commentary|review|reaction|highlights?|clips?)\\b",
    RegexOption.IGNORE_CASE,
)
private val animeLabel = Regex("动漫|动画|番剧|日漫|国漫|美漫|\\banime\\b|\\banimation\\b", RegexOption.IGNORE_CASE)
private val liveActionLabel = Regex("真人|电视剧|国产剧|欧美剧|日韩剧|韩剧|日剧|港剧|台剧|纪录片|综艺")

internal fun hasNonWorkLabel(value: String): Boolean = nonWorkLabel.containsMatchIn(value)

/** Unknown metadata stays unknown; a playable stream alone cannot establish the work's type. */
internal fun verifiesMovieContentType(type: MovieContentType, metadata: String): Boolean = when (type) {
    MovieContentType.ANIME -> animeLabel.containsMatchIn(metadata)
    MovieContentType.SERIES -> Regex("电视剧|剧集|连续剧|\\bseries\\b", RegexOption.IGNORE_CASE).containsMatchIn(metadata)
    MovieContentType.FILM -> Regex("电影|动作片|剧情片|喜剧片|科幻片|恐怖片|\\bfilm\\b|\\bmovie\\b", RegexOption.IGNORE_CASE).containsMatchIn(metadata)
    MovieContentType.GENERAL_VIDEO -> false
}

internal fun isEligibleCatalogWork(item: MovieCatalogItem, type: MovieContentType): Boolean {
    val metadata = listOfNotNull(item.title, item.category, item.remarks).joinToString(" ")
    if (item.isClip || hasNonWorkLabel(metadata)) return false
    return type != MovieContentType.ANIME || !liveActionLabel.containsMatchIn(item.category.orEmpty()) ||
        verifiesMovieContentType(type, item.category.orEmpty())
}
