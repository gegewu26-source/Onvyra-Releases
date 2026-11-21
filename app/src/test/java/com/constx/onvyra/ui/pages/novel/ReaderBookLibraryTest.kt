package com.constx.onvyra.ui.pages.novel

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderBookLibraryTest {
    @get:Rule val temp = TemporaryFolder()
    private val book = NovelBook("book", "验证作品", author = "验证作者", detailUrl = "https://one.test/book", sourceId = "one")
    private val chapters = (0..3).map { NovelChapter(it, "第${it+1}章", "https://one.test/chapter/$it") }
    @Test fun cacheIsPersistentAndSourceSpecificAndIncompleteWritesAreIgnored() = runBlocking {
        val dir = temp.newFolder(); val store = ReaderLibraryRepository(dir)
        store.putCatalog(book, chapters); store.putContent("one", chapters[0].url, "缓存的正文")
        val reopened = ReaderLibraryRepository(dir)
        assertEquals(chapters, reopened.catalog(book)); assertEquals("缓存的正文", reopened.content("one", chapters[0].url))
        assertNull(reopened.content("two", chapters[0].url)); assertNull(reopened.catalog(book.copy(sourceId = "two")))
        File(dir, "chapters").listFiles()!!.single().writeText("{incomplete")
        assertNull(reopened.content("one", chapters[0].url))
    }
    @Test fun searchWholeCachedBookFindsExactParagraphOffsetsAndCanRestartWithoutMixingKeywords() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = ReaderBookLibrary(temp.newFolder(), NovelSourceEngine(), scope)
            chapters.forEach { library.rememberContent(book, it, "序言\n少年走出村庄，少年抬头。\n终章") }
            library.search(book, chapters, "少年")
            withTimeout(5_000) { while (library.searchState.value.running) delay(10) }
            assertEquals(8, library.searchState.value.hits.size)
            assertEquals(listOf(0,7), library.searchState.value.hits.take(2).map { it.matchOffset })
            assertTrue(library.searchState.value.hits.all { it.paragraphIndex == 1 })
            library.search(book, chapters, "终章")
            withTimeout(5_000) { while (library.searchState.value.running) delay(10) }
            assertEquals(4, library.searchState.value.hits.size); assertEquals("终章", library.searchState.value.query)
            assertTrue(library.searchState.value.hits.all { it.paragraphIndex == 2 && it.matchOffset == 0 })
        } finally { scope.cancel() }
    }
    @Test fun bookmarksSurviveRestartDeduplicateAndKeepDistinctWorksSeparate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val dir = temp.newFolder()
        try {
            val library = ReaderBookLibrary(dir, NovelSourceEngine(), scope)
            repeat(2) { library.addBookmark(book, chapters[2], 9, 22, "要返回的阅读位置") }
            val saved = ReaderLibraryRepository(dir).bookmarks()
            assertEquals(1, saved.size); assertEquals(9, saved.single().paragraphIndex); assertEquals(22, saved.single().charOffset)
            assertEquals(chapters[2], saved.single().chapter)
            assertTrue(library.bookmarksFor(book.copy(author = "另一作者")).isEmpty())
            library.removeBookmark(saved.single().id)
            withTimeout(5_000) { while (library.bookmarks.value.isNotEmpty()) delay(10) }
            assertTrue(ReaderLibraryRepository(dir).bookmarks().isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun downloadReusesCleanBodiesAndRestoresPausedStateAfterRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val dir = temp.newFolder()
        try {
            val library = ReaderBookLibrary(dir, NovelSourceEngine(), scope)
            chapters.forEach { library.rememberContent(book, it, "本地正文，无需联网") }
            library.download(book, chapters)
            withTimeout(5_000) { while (library.downloads.value[library.downloadKey(book)]?.running != false) delay(10) }
            assertEquals(4, library.downloads.value[library.downloadKey(book)]!!.completed)
            assertEquals(chapters, ReaderLibraryRepository(dir).catalog(book))
            val store = ReaderLibraryRepository(dir)
            store.saveDownloads(mapOf("paused" to ReaderDownloadState(100,42,running = true)))
            assertFalse(store.downloads().getValue("paused").running)
            assertEquals(42, store.downloads().getValue("paused").completed)
        } finally { scope.cancel() }
    }
    @Test fun websiteFooterCleanupPreservesStoryAndRemovesBracketedPaginationAndNewDomainPromotion() {
        val clean = NovelHtmlParser.cleanText("<p>少年指着广告牌说：请记住我的名字。</p><p>（本章未完，请点击下一页继续阅读）</p><p>请记住本书首发域名：www.rulianshi.cc</p><div class='ad'>网站弹窗推广</div><p>真正结尾。</p>")
        assertTrue(clean.contains("广告牌")); assertTrue(clean.contains("真正结尾"))
        assertFalse(clean.contains("本章未完")); assertFalse(clean.contains("首发域名")); assertFalse(clean.contains("弹窗推广"))
        assertTrue(NovelHtmlParser.cleanText("http://story.test/path 然后他关掉了电脑。", "http://story.test/book").contains("然后他关掉"))
    }
}
