package com.constx.onvyra.ui.pages.novel

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import com.constx.onvyra.ui.context.Navigator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** UI plus a device-local HTTP interceptor fixture; this does not validate the live website. */
@RunWith(AndroidJUnit4::class)
class ReaderUiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var files: File
    private lateinit var testContext: Context
    private lateinit var fixture: PagedReaderFixture
    private lateinit var engine: NovelSourceEngine
    private lateinit var viewModel: NovelViewModel
    private val createdViewModels = mutableListOf<NovelViewModel>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        files = File(base.cacheDir, "reader-ui-${System.nanoTime()}").apply { mkdirs() }
        testContext = object : ContextWrapper(base) { override fun getFilesDir(): File = files }
        fixture = PagedReaderFixture()
        engine = NovelSourceEngine(OkHttpClient.Builder().addInterceptor(fixture).build())
        viewModel = createViewModel()
        // This fixture intentionally belongs to Piquge; the product default is Skin.
        viewModel.selectSource(NovelSourceEngine.SOURCE_PIQUGE)
    }

    private fun createViewModel() = NovelViewModel(testContext, engine).also { createdViewModels += it }

    /** Persist fixture-only visual evidence outside the temporary reader data removed below. */
    private fun captureFixture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "reader-qa").apply { mkdirs() }
        val target = if (name.endsWith("settings")) compose.onNodeWithText("阅读设置").onParent()
            else compose.onRoot()
        val screenshot = target.captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { output ->
                assertTrue("Could not encode fixture UI screenshot: $name",
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
    }

    @After fun tearDown() {
        compose.runOnIdle { createdViewModels.forEach { it.viewModelScope.cancel() } }
        files.deleteRecursively()
    }

    @Test fun searchReadAllCatalogPagesPersistPreferencesAndRecoverFromHttpError() {
        compose.setContent {
            MaterialExpressiveTheme {
                NovelMainPage(navController = Navigator(mutableStateListOf<NavKey>()), viewModel = viewModel)
            }
        }
        compose.onNodeWithText("求书").assertDoesNotExist()
        compose.onNodeWithText("我的").assertDoesNotExist()
        // Exercise the actual search field/IME/reader entry, rather than setting UI-only state.
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(PagedReaderFixture.BOOK_URL)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(20_000) { !viewModel.isSearching.value && viewModel.searchResults.value.isNotEmpty() }
        compose.onNodeWithText(PagedReaderFixture.TITLE).assertIsDisplayed()
        captureFixture("01-fixture-search-results")
        compose.onNodeWithText(PagedReaderFixture.TITLE).performClick()
        compose.waitUntil(20_000) { !viewModel.isLoadingChapter.value && viewModel.catalog.value.size == 730 }
        compose.onNodeWithTag("reader-book-detail").assertIsDisplayed()
        compose.onNodeWithTag("detail-start-reading").performClick()
        assertEquals("The catalog must fetch all eight pages", (1..8).toSet(), fixture.catalogPages.toSet())
        assertEquals(730, viewModel.catalog.value.map { it.url }.distinct().size)
        assertEquals("第730章 验证730", viewModel.catalog.value.last().title)
        compose.onNodeWithText("章节验证编号1", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("reader-body").performClick()

        compose.onNodeWithContentDescription("目录").performClick()
        compose.onNodeWithText("共 730 章").assertIsDisplayed()
        compose.onNodeWithText("正序 ↑").performClick()
        compose.onNodeWithText("倒序 ↓").assertIsDisplayed()
        captureFixture("02-fixture-catalog-730")
        compose.onNodeWithContentDescription("关闭目录").performClick()

        compose.runOnIdle { viewModel.loadChapter(729) }
        compose.waitUntil(10_000) { !viewModel.isLoadingChapter.value && viewModel.currentChapterIndex.value == 729 }
        captureFixture("reader4-explicit-last-before-assert")
        compose.onNodeWithText("章节验证编号730", substring = true).assertIsDisplayed()
        // Exercise background text layout on Android as well as virtualized scrolling.
        compose.runOnIdle { viewModel.toggleScrollMode(false) }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("章节验证编号730", substring = true).fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithText("章节验证编号730", substring = true).assertIsDisplayed()
        compose.runOnIdle { viewModel.toggleScrollMode(true) }
        captureFixture("03-fixture-reader-last-chapter")
        compose.onNodeWithText("⋯").performClick()
        compose.onNodeWithText("加入书架").performClick()
        compose.waitUntil(10_000) { viewModel.bookshelf.value.size == 1 && File(files, "novel_bookshelf.json").exists() }

        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("A＋").performClick()
        compose.onNodeWithText("护眼绿").performClick()
        captureFixture("04-fixture-reader-settings")
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(10_000) {
            runCatching {
                val saved = json.decodeFromString<NovelReaderSettings>(File(files, "novel_settings.json").readText())
                saved.fontSizeSp == 19f && saved.theme == NovelReaderTheme.GREEN
            }.getOrDefault(false)
        }
        val restored = createViewModel()
        compose.waitUntil(10_000) { restored.bookshelf.value.size == 1 && restored.readerSettings.value.fontSizeSp == 19f }
        assertEquals(729, restored.bookshelf.value.single().lastReadChapterIndex)
        assertEquals(NovelReaderTheme.GREEN, restored.readerSettings.value.theme)
        assertEquals(PagedReaderFixture.BOOK_URL, restored.searchHistory.value.single())

        // A failed HTTP request must replace stale text with visible recovery UI, never fake prose.
        // Re-read from upstream rather than the adjacent-chapter prefetch cache.
        engine.invalidateCatalog(PagedReaderFixture.BOOK_URL, NovelSourceEngine.SOURCE_PIQUGE)
        fixture.failChapter.set(true)
        File(files, "reader-library-v8/chapters").listFiles()?.forEach { it.delete() }
        compose.runOnIdle { viewModel.loadChapter(1) }
        compose.waitUntil(10_000) { !viewModel.isLoadingChapter.value && viewModel.errorMessage.value != null }
        assertTrue(viewModel.errorMessage.value.orEmpty().contains("503"))
        assertEquals("", viewModel.chapterContent.value)
        assertFalse(viewModel.isLoadingChapter.value)
        compose.onNodeWithText("章节验证编号730", substring = true).assertDoesNotExist()
        compose.onNodeWithText("正文加载失败，请尝试点击顶部换源重试。").assertDoesNotExist()
        compose.onNodeWithText("重新加载").assertIsDisplayed()
        fixture.failChapter.set(false)
        compose.onNodeWithText("重新加载").performClick()
        compose.waitUntil(10_000) { !viewModel.isLoadingChapter.value && viewModel.errorMessage.value == null }
        compose.onNodeWithText("章节验证编号2", substring = true).assertIsDisplayed()
    }

    private class PagedReaderFixture : Interceptor {
        companion object {
            const val BOOK_URL = "https://www.piquge.com/190/190260/"
            const val TITLE = "目录分页验证作品（本地QA）"
            private const val AUTHOR = "验证作者"
        }
        val catalogPages: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
        val failChapter = AtomicBoolean(false)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            val chapter = Regex("^/190/190260/(\\d+)\\.html$").matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
            val page = when {
                path == "/190/190260/" -> 1
                else -> Regex("^/190/190260/(\\d+)/$").matchEntire(path)?.groupValues?.get(1)?.toIntOrNull()
            }
            val failure = chapter != null && failChapter.get()
            val html = when {
                request.url.host != "www.piquge.com" -> "<html><body>Unexpected fixture host</body></html>"
                failure -> "<html><body>Service temporarily unavailable</body></html>"
                chapter != null -> """<!doctype html><html><head><title>第${chapter}章 验证$chapter</title></head>
                    <body><h1>第${chapter}章 验证$chapter</h1><div id="content"><p>章节验证编号$chapter</p>
                    <p>这段正文专为阅读器功能验证而写，确认真实请求、分页目录与错误恢复。</p></div></body></html>"""
                page != null && page in 1..8 -> catalogHtml(page)
                else -> "<html><body></body></html>"
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (failure) 503 else 200).message(if (failure) "Service Unavailable" else "OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }

        private fun catalogHtml(page: Int): String {
            catalogPages += page
            val first = (page - 1) * 100 + 1
            val last = (page * 100).coerceAtMost(730)
            return buildString {
                append("""<!doctype html><html><head><title>$TITLE</title>
                    <meta property="og:novel:book_name" content="$TITLE">
                    <meta property="og:novel:author" content="$AUTHOR">
                    <meta property="og:novel:category" content="验证">
                    <meta property="og:novel:status" content="完本">
                    <link rel="canonical" href="$BOOK_URL"></head><body>
                    <div class="book"><div class="cover"><img src=""></div><h1>$TITLE</h1>
                    <div class="intro">仅用于本地仪器验证，不构成线上作品数据。</div></div>
                    <div class="chapterlist"><div class="all"><dl>""")
                for (chapter in first..last) append("<dd><a href=\"/190/190260/$chapter.html\">第${chapter}章 验证$chapter</a></dd>")
                append("</dl></div></div><div class=\"pagelist page\">")
                for (next in 1..8) append("<a href=\"${if (next == 1) BOOK_URL else "$BOOK_URL$next/"}\">$next</a>")
                if (page < 8) append("<a href=\"$BOOK_URL${page + 1}/\">下一页</a>")
                append("</div></body></html>")
            }
        }
    }
}
