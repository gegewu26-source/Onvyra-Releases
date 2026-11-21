package com.constx.onvyra.ui.pages.novel

/** A chapter is one section of the same scroll document, never a replacement page. */
data class ContinuousReaderSection(
    val chapter: NovelChapter,
    val text: String = "",
    val paragraphs: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean get() = text.isNotBlank()
}

/** Only explicit book/directory/button navigation changes this revision. */
data class ContinuousScrollAnchor(val revision: Int = 0, val chapterIndex: Int = 0, val atEnd: Boolean = false,
    val paragraphIndex: Int? = null, val charOffset: Int = 0, val highlight: String = "")

internal fun readerParagraphs(text: String): List<String> = text.split('\n').flatMap { line ->
    if (line.length > 3000) line.chunked(3000) else listOf(line)
}
