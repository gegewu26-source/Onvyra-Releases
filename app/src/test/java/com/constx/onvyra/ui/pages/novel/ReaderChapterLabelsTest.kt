package com.constx.onvyra.ui.pages.novel

import org.junit.Assert.*
import org.junit.Test

class ReaderChapterLabelsTest {
    private fun book(lastRead: String = "", latest: String = "") = NovelBook(
        id = "test", title = "作品", detailUrl = "https://example.test/book", sourceId = "test",
        lastReadChapterTitle = lastRead, latestChapter = latest,
        lastReadChapterUrl = "https://example.test/chapter/9", lastReadChapterIndex = 9,
    )

    @Test fun internalRoutesAndLocalFileUrisAreHidden() {
        listOf("/novel_afa3d04f", "/novel_afa3d04f/chapter/9", "/novel_afa3d04f.html", "novel_afa3d04f?chapter=9",
            "file:///data/user/0/app/chapter.txt", "content://app/chapter/9", "/storage/emulated/0/book.txt")
            .forEach { assertEquals("", chapterTitleForDisplay(it)) }
    }

    @Test fun keepsActualChapterTitlesAndReadingProgress() {
        val saved = book(lastRead = " 第九章 山路 / 归途 ", latest = "第十章 /novel_旧事")
        assertEquals("第九章 山路 / 归途", saved.readingChapterForDisplay)
        assertEquals(9, saved.lastReadChapterIndex)
        assertEquals("https://example.test/chapter/9", saved.lastReadChapterUrl)
        assertEquals(" 第九章 山路 / 归途 ", saved.lastReadChapterTitle)
    }

    @Test fun invalidReadLabelFallsBackToActualLatestChapter() {
        assertEquals("第十章 归来", book("/novel_afa3d04f", "第十章 归来").readingChapterForDisplay)
        assertEquals("第九章 山路", book("第九章 山路", "/novel_afa3d04f").readingChapterForDisplay)
    }

    @Test fun leavesNoPathLabelWhenBothSourcesAreInternal() {
        assertEquals("", book("/novel_afa3d04f", "/novel_other").readingChapterForDisplay)
        assertEquals("第十章 /novel_旧事", chapterTitleForDisplay("第十章 /novel_旧事"))
    }
}
