package com.constx.onvyra.ui.pages.novel

import kotlinx.serialization.Serializable

/**
 * 书源定义
 */
@Serializable
data class NovelSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val isAggregated: Boolean = false,
    val description: String = "",
    val enabled: Boolean = true,
)

/**
 * 小说书籍数据结构
 */
@Serializable
data class NovelBook(
    val id: String, // 唯一标识，如 "bqgeee_12345"
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val detailUrl: String,
    val sourceId: String,
    val latestChapter: String = "",
    val updateTime: String = "",
    val category: String = "玄幻修真",
    val status: String = "连载中",
    val description: String = "",
    val isComic: Boolean = false,
    val lastReadChapterTitle: String = "",
    val lastReadChapterUrl: String = "",
    val lastReadChapterIndex: Int = 0,
    val lastReadTime: Long = System.currentTimeMillis(),
    val coverReferer: String = "",
    val chapterCount: Int = 0,
    val isAudio: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(),
    /** Actual search hits for this exact title, author and media type. */
    val sources: List<NovelBookSource> = emptyList(),
    /** Artwork may come from another matching source; its headers stay separate. */
    val coverRequestHeaders: Map<String, String>? = null,
    /** Source rule state, such as book.kind when a provider uses it as its ID. */
    val sourceVariables: Map<String, String> = emptyMap(),
    /** Explicit source choice survives reopening; failures must not silently change it. */
    val preferredSourceId: String? = null,
    /** Original title from publisher metadata, or an independently verified retitling. */
    val canonicalTitle: String = "",
)

@Serializable
data class NovelBookSource(
    val id: String,
    val title: String,
    val author: String,
    val detailUrl: String,
    val sourceId: String,
    val coverUrl: String = "",
    val coverReferer: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val coverRequestHeaders: Map<String, String>? = null,
    val latestChapter: String = "",
    val chapterCount: Int = 0,
    val isComic: Boolean = false,
    val isAudio: Boolean = false,
    val sourceVariables: Map<String, String> = emptyMap(),
    val preferredSourceId: String? = null,
    val canonicalTitle: String = "",
)

internal fun NovelBook.asSource() = NovelBookSource(id, title, author, detailUrl, sourceId,
    coverUrl, coverReferer, requestHeaders, coverRequestHeaders, latestChapter, chapterCount, isComic, isAudio, sourceVariables, preferredSourceId, canonicalTitle)

internal fun NovelBookSource.asBook() = NovelBook(id, title, author, coverUrl, detailUrl, sourceId,
    latestChapter = latestChapter, chapterCount = chapterCount, isComic = isComic, isAudio = isAudio,
    coverReferer = coverReferer, requestHeaders = requestHeaders, coverRequestHeaders = coverRequestHeaders,
    sourceVariables = sourceVariables, preferredSourceId = preferredSourceId, canonicalTitle = canonicalTitle)

internal val NovelBook.sourceCount: Int
    get() = (listOf(sourceId) + sources.map { it.sourceId }).filterNot {
        it == NovelSourceEngine.SOURCE_SKIN || it == NovelSourceEngine.SOURCE_CATALOG }.distinct().size

internal val NovelBook.artworkHeaders: Map<String, String>
    get() = coverRequestHeaders ?: requestHeaders

/**
 * 漫画源定义
 */
@Serializable
data class ComicSource(
    val id: String,
    val name: String,
    val enable: Boolean = true,
    val baseUrl: String = "",
    val description: String = ""
)

/**
 * 漫画正文内容 (图片切片列表)
 */
@Serializable
data class ComicChapterContent(
    val chapterTitle: String,
    val images: List<String> = emptyList()
)

/**
 * 章节数据结构
 */
@Serializable
data class NovelChapter(
    val index: Int,
    val title: String,
    val url: String
)

/**
 * 阅读器配置
 */
@Serializable
data class NovelReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.4f,
    val isVerticalScroll: Boolean = true,
    val theme: NovelReaderTheme = NovelReaderTheme.PARCHMENT,
    val brightness: Float = -1f,
    val comicHorizontalPaging: Boolean = false,
    val comicPageGapDp: Float = 0f,
    val autoScrollSpeed: Float = 0f,
    val customBackgroundColor: Long? = null,
)

/**
 * 阅读器预设护眼主题
 */
@Serializable
enum class NovelReaderTheme(
    val title: String,
    val backgroundColorHex: Long,
    val textColorHex: Long
) {
    WHITE("极简白", 0xFFFFFFFF, 0xFF2B2B2B),
    PARCHMENT("羊皮纸", 0xFFE6DFC8, 0xFF403016),
    GREEN("护眼绿", 0xFFE3EDE1, 0xFF2D3C28),
    BLUE("浅蓝", 0xFFE4EFF7, 0xFF26394B),
    NIGHT("暗夜黑", 0xFF18181A, 0xFF9E9E9E)
}
