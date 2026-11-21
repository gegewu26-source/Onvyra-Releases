package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.constx.onvyra.ui.context.Navigator
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderShelfResumeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shelfTapAfterRecreatingViewModelOpensSavedChapterDirectlyFromOfflineLibrary() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "reader13-shelf-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val sourceJson = """[{"bookSourceName":"书架恢复验证源","bookSourceUrl":"https://shelf.fixture.test","bookSourceType":0,"enabled":true}]"""
        val sources = ReaderSourceLibrary { sourceJson }
        val sourceId = runBlocking { sources.all().single().id }
        val chapters = (0..2).map { NovelChapter(it, "第${it + 1}章 书架恢复", "https://shelf.fixture.test/chapter/$it") }
        val saved = NovelBook("shelf_1", "书架续读验证", "验证作者", detailUrl = "https://shelf.fixture.test/book/1",
            sourceId = sourceId, preferredSourceId = sourceId, lastReadChapterIndex = 1,
            lastReadChapterTitle = chapters[1].title, lastReadChapterUrl = chapters[1].url)
        File(context.filesDir, "novel_bookshelf.json").writeText(Json.encodeToString(listOf(saved)))
        runBlocking {
            val repository = ReaderLibraryRepository(File(context.filesDir, "reader-library-v8"))
            repository.putCatalog(saved, chapters)
            chapters.forEach { chapter -> repository.putContent(sourceId, chapter.url,
                "这是离线书架第${chapter.index + 1}章正文。\n上次阅读位置已保存，可以直接继续。") }
        }
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet(); throw IOException("Offline: cached shelf reading must not scan sources")
        }.build()
        lateinit var vm: NovelViewModel
        compose.runOnIdle { vm = NovelViewModel(context, NovelSourceEngine(client, sourceLibrary = sources)) }
        try {
            compose.waitUntil(5_000) { vm.bookshelf.value.size == 1 }
            compose.runOnIdle { vm.viewModelScope.cancel(); vm = NovelViewModel(context,
                NovelSourceEngine(client, sourceLibrary = ReaderSourceLibrary { sourceJson })) }
            compose.setContent { NovelMainPage(Navigator(mutableStateListOf<NavKey>()), vm) }
            compose.waitUntil(5_000) { vm.bookshelf.value.size == 1 }
            compose.onNodeWithText(saved.title).performClick()
            compose.waitUntil(10_000) { vm.isCatalogComplete.value && !vm.isLoadingChapter.value && vm.currentChapterIndex.value == 1 }
            compose.onNodeWithTag("reader-book-detail").assertDoesNotExist()
            compose.onNodeWithTag("continuous-paragraph-1-0", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(saved.lastReadChapterUrl, vm.catalog.value[vm.currentChapterIndex.value].url)
            assertEquals(sourceId, vm.currentBook.value!!.preferredSourceId)
            assertNull(vm.errorMessage.value)
            assertEquals("Opening a saved cached work must make zero source requests", 0, requests.get())
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() }; directory.deleteRecursively() }
    }
}
