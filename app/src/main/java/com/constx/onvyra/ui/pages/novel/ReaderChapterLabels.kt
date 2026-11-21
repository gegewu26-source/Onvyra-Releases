package com.constx.onvyra.ui.pages.novel

private val internalNovelPath = Regex("""(?i)^/?novel_[a-z0-9_-]+(?:[./?#].*)?$""")

/** Some source rules return a storage route in a chapter-title field. Keep reading state intact. */
internal fun chapterTitleForDisplay(value: String): String {
    val title = value.trim()
    if (internalNovelPath.matches(title) || title.startsWith("file://", ignoreCase = true) ||
        title.startsWith("content://", ignoreCase = true) ||
        listOf("/data/", "/storage/", "/sdcard/").any { title.startsWith(it) }
    ) return ""
    return title
}

internal val NovelBook.readingChapterForDisplay: String
    get() = chapterTitleForDisplay(lastReadChapterTitle).ifBlank { chapterTitleForDisplay(latestChapter) }
