package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Engine-only retitling checks cannot detect a UI which rejects its successful result. */
class ReaderVerifiedAliasUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reopeningVerifiedRetitledWorkShowsItsBodyInsteadOfAnUnownedSpinner() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "reader18-alias-ui-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val library = ReaderSourceLibrary {
            """[{"bookSourceName":"已验证来源","bookSourceUrl":"https://alias.fixture.test","bookSourceType":0,"enabled":true,"searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":".book","name":"a@text","bookUrl":"a@href"}}]"""
        }
        val source = runBlocking { library.all().single().id }
        val requested = NovelBook("catalog-poster", "新海报上的作品名称", "同一作品作者",
            detailUrl = "https://catalog.fixture.test/poster", sourceId = NovelSourceEngine.SOURCE_CATALOG)
        val resolved = NovelBook("actual-book", "独立验证过的旧作品名称", requested.author,
            detailUrl = "https://alias.fixture.test/book", sourceId = source,
            canonicalTitle = "独立验证过的旧作品名称")
        assertFalse("The regression requires distinct display identities", ReaderBookIdentity.matches(requested, resolved))
        val chapters = listOf(NovelChapter(0, "第一章 验证后的作品", "https://alias.fixture.test/chapter/1"))
        File(context.filesDir, "novel_bookshelf.json").writeText(Json.encodeToString(listOf(requested)))
        runBlocking {
            ReaderReadingRouteCache(File(context.filesDir, "reader-reading-routes-v1")).putResolved(requested, resolved)
            val repository = ReaderLibraryRepository(File(context.filesDir, "reader-library-v8"))
            repository.putCatalog(resolved, chapters)
            repository.putContent(source, chapters.single().url,
                "这是已验证同一作品的离线正文，书城海报名称与阅读来源名称不同。\n页面必须接收本次打开请求的结果，不能一直空白转圈。")
        }
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet(); throw IOException("A verified cached route must not search again")
        }.build()
        lateinit var vm: NovelViewModel
        compose.runOnIdle { vm = NovelViewModel(context, NovelSourceEngine(client, sourceLibrary = library)) }
        try {
            compose.setContent { NovelMainPage(Navigator(mutableStateListOf<NavKey>()), vm) }
            compose.waitUntil(5_000) { vm.bookshelf.value.size == 1 }
            compose.onNodeWithText(requested.title).performClick()
            compose.waitUntil(10_000) { vm.isCatalogComplete.value && !vm.isLoadingChapter.value }
            assertEquals(resolved.title, vm.currentBook.value!!.title)
            assertEquals(requested.id, vm.bookOpenRequestId.value)
            assertNull(vm.errorMessage.value)
            compose.onNodeWithTag("continuous-paragraph-0-0", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(0, requests.get())
            // An internal reload receives the canonical route, but must retain
            // the original presentation request even if that network call fails.
            compose.runOnIdle { vm.refreshCatalog() }
            compose.waitUntil(20_000) { !vm.isLoadingCatalog.value && !vm.isLoadingChapter.value }
            assertEquals(requested.id, vm.bookOpenRequestId.value)
            compose.onNodeWithTag("reader-opening-page").assertDoesNotExist()
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
            directory.deleteRecursively()
        }
    }
}
