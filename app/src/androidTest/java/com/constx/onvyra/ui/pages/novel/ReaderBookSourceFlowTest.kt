package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real ViewModel/adapter/persistence flow, with every HTTP response local to this test. */
class ReaderBookSourceFlowTest {
    @get:Rule val compose = createComposeRule()

    private fun source(host: String) = buildJsonObject {
        put("bookSourceName", host); put("bookSourceUrl", "https://$host"); put("enabled", true)
        put("bookSourceType", 0); put("searchUrl", "/search?q={{key}}")
        putJsonObject("ruleSearch") {
            put("bookList", ".book"); put("name", "a@text"); put("author", ".author@text"); put("bookUrl", "a@href")
        }
        putJsonObject("ruleBookInfo") { put("name", "h1@text"); put("author", ".author@text") }
        putJsonObject("ruleToc") { put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href") }
        putJsonObject("ruleContent") { put("content", "#content@html"); put("replaceRegex", "##网站广告请勿显示") }
    }

    @Test fun onlyThisBooksMatchedSourcesAppearAndManualSwitchPersistsWithoutSilentFallback() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "reader-source-flow-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val library = ReaderSourceLibrary {
            JsonArray(listOf("first.fixture.test", "second.fixture.test", "broken.fixture.test", "other.fixture.test").map(::source)).toString()
        }
        val requests = CopyOnWriteArrayList<String>()
        val secondFailure = AtomicInteger(0) // 1=body failure, 2=catalog/detail failure after reopening.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val host = request.url.host; val path = request.url.encodedPath
            requests += "$host$path"
            if (!host.endsWith(".fixture.test")) throw IOException("No external network in source-flow QA")
            val author = if (host.startsWith("other.")) "另一位作者" else "验证作者"
            val code = if (host.startsWith("broken.") && path.startsWith("/chapter") ||
                host.startsWith("second.") && (secondFailure.get() == 1 && path.startsWith("/chapter") ||
                    secondFailure.get() == 2 && path == "/book/1")) 503 else 200
            val body = when {
                path == "/search" -> "<div class='book'><a href='/book/1'>换源验证作品</a><b class='author'>$author</b></div>"
                path == "/book/1" -> "<h1>换源验证作品</h1><p class='author'>$author</p><div class='chapters'><a href='/chapter/1'>第一章 初见</a><a href='/chapter/2'>第二章 前行</a></div>"
                path.startsWith("/chapter/") -> "<div id='content'><p>$host 的${path.substringAfterLast('/')}章真实夹具正文。</p><p>网站广告请勿显示</p></div>"
                else -> throw IOException("Unexpected source-flow fixture route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("source-flow fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val engine = NovelSourceEngine(client, sourceLibrary = library)
        val ids = runBlocking { library.all().associate { it.name to it.id } }
        val first = runBlocking { engine.search("换源验证作品", ids.getValue("first.fixture.test")).single() }
        lateinit var vm: NovelViewModel
        val pickerBook = mutableStateOf<NovelBook?>(null)
        compose.setContent { MaterialTheme {
            Text("Reader source flow QA")
            pickerBook.value?.let { book -> NovelBookSourcePicker(book, emptyList(), false, "查找完成", false,
                onDismiss = { pickerBook.value = null }, onSelect = { id -> pickerBook.value = null; vm.switchSource(id) }, onRefresh = {}) }
        } }
        compose.runOnIdle { vm = NovelViewModel(isolated, engine); vm.openBook(first) }
        try {
            compose.waitUntil(10_000) { !vm.isLoadingChapter.value && vm.chapterContent.value.contains("first.fixture.test") }
            compose.waitUntil(10_000) { vm.currentBook.value?.sourceCount == 3 && !vm.isDiscoveringBookSources.value }
            assertEquals(setOf("first.fixture.test", "second.fixture.test", "broken.fixture.test").map(ids::getValue).toSet(),
                vm.currentBook.value!!.sources.map { it.sourceId }.toSet())
            assertFalse(vm.chapterContent.value.contains("网站广告"))
            compose.runOnIdle { vm.toggleBookshelf(vm.currentBook.value!!) }
            compose.waitUntil(5_000) { File(directory, "novel_bookshelf.json").isFile }
            val oldCatalog = vm.catalog.value
            val oldContent = vm.chapterContent.value
            compose.runOnIdle { pickerBook.value = vm.currentBook.value }
            for (name in listOf("first.fixture.test", "second.fixture.test", "broken.fixture.test"))
                compose.onNodeWithTag("reader-candidate-${ids.getValue(name)}").assertIsDisplayed()
            compose.onNodeWithTag("reader-candidate-${ids.getValue("other.fixture.test")}").assertDoesNotExist()
            compose.onNodeWithTag("reader-candidate-${ids.getValue("broken.fixture.test")}").performClick()
            compose.waitUntil(10_000) { !vm.isSwitchingSource.value && vm.errorMessage.value?.contains("换源失败") == true }
            assertEquals(ids.getValue("first.fixture.test"), vm.currentBook.value!!.sourceId)
            assertEquals(oldCatalog, vm.catalog.value)
            assertEquals(oldContent, vm.chapterContent.value)
            val successfulSwitchAt = requests.size
            compose.runOnIdle { pickerBook.value = vm.currentBook.value }
            compose.onNodeWithTag("reader-candidate-${ids.getValue("second.fixture.test")}").performClick()
            compose.waitUntil(10_000) { !vm.isSwitchingSource.value && vm.currentBook.value?.sourceId == ids.getValue("second.fixture.test") }
            assertTrue(vm.chapterContent.value.contains("second.fixture.test"))
            assertEquals(ids.getValue("second.fixture.test"), vm.currentBook.value!!.preferredSourceId)
            assertTrue(vm.catalog.value.all { it.url.contains("second.fixture.test") })
            assertEquals("第一章 初见", vm.currentBook.value!!.lastReadChapterTitle)
            assertFalse(requests.drop(successfulSwitchAt).any { it.startsWith("first.fixture.test/chapter/") })
            compose.waitUntil(5_000) {
                runCatching { Json.decodeFromString<List<NovelBook>>(File(directory, "novel_bookshelf.json").readText())
                    .single().sourceId == ids.getValue("second.fixture.test") }.getOrDefault(false)
            }
            compose.runOnIdle { vm.leaveReader(); vm.viewModelScope.cancel() }
            lateinit var reopened: NovelViewModel
            compose.runOnIdle { reopened = NovelViewModel(isolated, engine) }
            try {
                compose.waitUntil(5_000) { reopened.bookshelf.value.size == 1 }
                assertEquals(ids.getValue("second.fixture.test"), reopened.bookshelf.value.single().sourceId)
                assertEquals(ids.getValue("second.fixture.test"), reopened.bookshelf.value.single().preferredSourceId)
                compose.runOnIdle { reopened.openBook(reopened.bookshelf.value.single()) }
                compose.waitUntil(10_000) { !reopened.isLoadingChapter.value && reopened.chapterContent.value.contains("second.fixture.test") }
                assertEquals(ids.getValue("second.fixture.test"), reopened.currentBook.value!!.sourceId)
                // Searching again returns the old site's fresh hit without a pin.
                // The single openBook entry point must still honor the saved choice.
                assertNull(first.preferredSourceId)
                compose.runOnIdle { reopened.openBook(first) }
                compose.waitUntil(10_000) { !reopened.isLoadingChapter.value && reopened.chapterContent.value.contains("second.fixture.test") }
                assertEquals(ids.getValue("second.fixture.test"), reopened.currentBook.value!!.sourceId)
                assertEquals(ids.getValue("second.fixture.test"), reopened.currentBook.value!!.preferredSourceId)
            } finally { compose.runOnIdle { reopened.viewModelScope.cancel() } }
            for (failureMode in 1..2) {
                File(directory, "reader-library-v8").deleteRecursively()
                secondFailure.set(failureMode)
                lateinit var unavailable: NovelViewModel
                // A fresh engine prevents the old successful body/cache from masking the failure.
                compose.runOnIdle { unavailable = NovelViewModel(isolated, NovelSourceEngine(client, sourceLibrary = library)) }
                try {
                    compose.waitUntil(5_000) { unavailable.bookshelf.value.size == 1 }
                    val failureStart = requests.size
                    compose.runOnIdle { unavailable.openBook(first) }
                    compose.waitUntil(10_000) { !unavailable.isLoadingChapter.value && unavailable.errorMessage.value != null }
                    assertEquals("Explicit source must survive restart and upstream failure mode=$failureMode",
                        ids.getValue("second.fixture.test"), unavailable.currentBook.value!!.sourceId)
                    assertEquals(ids.getValue("second.fixture.test"), unavailable.currentBook.value!!.preferredSourceId)
                    assertFalse(unavailable.chapterContent.value.contains("first.fixture.test"))
                    assertFalse("A pinned source cannot request the old source's chapters on failure",
                        requests.drop(failureStart).any { it.startsWith("first.fixture.test/chapter/") })
                } finally { compose.runOnIdle { unavailable.viewModelScope.cancel() } }
            }
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() }; directory.deleteRecursively() }
    }
}
