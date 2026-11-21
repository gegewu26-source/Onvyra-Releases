package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun detailsBodySearchPreciseJumpBookmarkAndWholeBookCacheSurviveOfflineReopen() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "reader8-library-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val offline = AtomicBoolean(false); val requests = AtomicInteger()
        val source = """[{"bookSourceName":"验证书源","bookSourceUrl":"https://library.fixture.test","bookSourceType":0,"enabled":true,
            "searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":".book","name":"a@text","author":".author@text","bookUrl":"a@href"},
            "ruleBookInfo":{"name":"h1@text","author":".author@text","intro":".intro@text"},
            "ruleToc":{"chapterList":".chapters a","chapterName":"text","chapterUrl":"href"},
            "ruleContent":{"content":"#content@html","replaceRegex":"##网站广告请勿显示"}}]"""
        val sourceLibrary = ReaderSourceLibrary { source }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            if (offline.get()) throw IOException("Offline fixture")
            val path = chain.request().url.encodedPath
            val body = when {
                path == "/search" -> "<div class='book'><a href='/book/1'>书库验证</a><b class='author'>测试作者</b></div>"
                path == "/book/1" -> "<h1>书库验证</h1><p class='author'>测试作者</p><p class='intro'>可离线阅读与检索的小说。</p><div class='chapters'>" +
                    (1..3).joinToString("") { "<a href='/chapter/$it'>第${it}章</a>" } + "</div>"
                path.startsWith("/chapter/") -> "<div id='content'>" + (0..39).joinToString("") {
                    "<p>第${path.substringAfterLast('/')}章第${it}段，" +
                        (if (path == "/chapter/3" && it == 25) "少年拿起书签，记住这一刻。" else "林间微风吹过，故事还在继续。") + "</p>"
                } + "<p>网站广告请勿显示</p></div>"
                else -> throw IOException("Unexpected route")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val engine = NovelSourceEngine(client, sourceLibrary = sourceLibrary)
        val book = runBlocking { engine.search("书库验证", sourceLibrary.all().single().id).single() }
        lateinit var vm: NovelViewModel
        var reading by mutableStateOf(false); var tool by mutableStateOf<ReaderBookTool?>(null)
        compose.runOnIdle { vm = NovelViewModel(context, engine); vm.openBook(book) }
        compose.setContent { MaterialTheme {
            if (reading) NovelReaderPage(vm, onShowDetails = { reading = false }) {}
            else NovelBookDetailPage(vm, {}, { reading = true }, { tool = it }, { vm.loadChapter(it); reading = true })
            tool?.let { selected -> ReaderBookToolDialog(selected, vm, { tool = null }) { chapter, paragraph, offset, query ->
                vm.jumpToText(chapter, paragraph, offset, query); tool = null; reading = true
            } }
        } }
        try {
            compose.waitUntil(10_000) { vm.isCatalogComplete.value && !vm.isLoadingChapter.value }
            compose.onNodeWithTag("reader-book-detail").assertIsDisplayed()
            compose.onNodeWithTag("detail-tool-SEARCH").performClick()
            compose.onNodeWithTag("reader-tool-query").performTextInput("少年")
            compose.onNodeWithTag("reader-search-submit").performClick()
            compose.waitUntil(10_000) { !vm.library.searchState.value.running && vm.library.searchState.value.completed == 3 }
            val hit = vm.library.searchState.value.hits.single()
            assertEquals(2, hit.chapter.index)
            compose.onNodeWithTag("reader-search-hit-${hit.chapter.index}-${hit.paragraphIndex}-${hit.matchOffset}").performClick()
            compose.waitUntil(10_000) { !vm.isLoadingChapter.value && vm.currentChapterIndex.value == 2 }
            compose.onNodeWithTag("continuous-paragraph-2-${hit.paragraphIndex}", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { vm.addCurrentBookmark() }
            compose.waitUntil(5_000) { vm.library.bookmarks.value.isNotEmpty() }
            val mark = vm.library.bookmarks.value.single()
            assertEquals(2, mark.chapter.index); assertEquals(hit.paragraphIndex, mark.paragraphIndex)
            compose.runOnIdle { reading = false }
            compose.onNodeWithTag("detail-tool-DOWNLOAD").performClick()
            compose.onNodeWithTag("reader-download-start").performClick()
            compose.waitUntil(10_000) { vm.library.downloads.value.values.any { !it.running && it.completed == 3 } }
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("detail-tool-BOOKMARKS").performClick()
            compose.onNodeWithTag("reader-bookmark-${mark.id}").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("continuous-paragraph-2-${hit.paragraphIndex}", useUnmergedTree = true).assertIsDisplayed()
            offline.set(true)
            val offlineAt = requests.get()
            lateinit var reopened: NovelViewModel
            compose.runOnIdle { vm.leaveReader(); vm.viewModelScope.cancel(); reopened = NovelViewModel(context, NovelSourceEngine(client, sourceLibrary = sourceLibrary)); reopened.openBook(book) }
            try {
                compose.waitUntil(10_000) { !reopened.isLoadingChapter.value && reopened.catalog.value.size == 3 }
                compose.runOnIdle { reopened.loadChapter(2) }
                compose.waitUntil(5_000) { !reopened.isLoadingChapter.value && reopened.chapterContent.value.contains("少年") }
                assertEquals(offlineAt, requests.get())
                compose.waitUntil(5_000) { reopened.library.bookmarks.value.size == 1 }
                assertEquals(mark, reopened.library.bookmarks.value.single())
            } finally { compose.runOnIdle { reopened.viewModelScope.cancel() } }
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() }; dir.deleteRecursively() }
    }
}
