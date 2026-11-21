package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production VM, adapters, source dialog and files; all upstream responses stay local. */
@RunWith(AndroidJUnit4::class)
class ReaderSourceDiscoveryRecreationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recreatedReaderKeepsValidatedRoutesAndOnlySearchesNewIdsWhenAChapterReturns404() =
        exerciseRecreation(noticeBody = false)

    @Test fun recreatedReaderDoesNotTreatAMatchedHttp200NoticeAsReadableOrRetryItAfterRestart() =
        exerciseRecreation(noticeBody = true)

    private fun exerciseRecreation(noticeBody: Boolean) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "reader14-discovery-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val hosts = listOf("discovery-first.fixture.test", "discovery-selected.fixture.test", "discovery-failed.fixture.test")
        val searches = CopyOnWriteArrayList<String>()
        val thirdSourceGate = AtomicReference<CountDownLatch?>(null)
        val enteredThirdSearch = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val path = request.url.encodedPath
            if (host !in hosts) throw IOException("External requests are disabled in discovery recreation QA")
            if (path == "/search") {
                searches += host
                if (host == hosts[2]) {
                    enteredThirdSearch.countDown()
                    thirdSourceGate.get()?.let { gate ->
                        check(gate.await(10, TimeUnit.SECONDS)) { "The source-search fixture gate was not released" }
                    }
                }
            }
            val isBadChapter = host == hosts[2] && path == "/chapter/1"
            val code = if (isBadChapter && !noticeBody) 404 else 200
            val html = when (path) {
                "/search" -> "<div class='book'><a href='/book/1'>持久查源重开作品</a><b class='author'>重开验证作者</b></div>"
                "/book/1" -> "<h1>持久查源重开作品</h1><p class='author'>重开验证作者</p>" +
                    "<div class='chapters'><a href='/chapter/1'>第一章 初见</a></div>"
                "/chapter/1" -> "<div id='content'><p>" +
                    if (isBadChapter) "系统提示：当前章节暂时不可用，请稍后重试。</p></div>"
                    else "$host 的真实故事正文。他走进庭院，在树下等到了故人。</p></div>"
                else -> throw IOException("Unexpected discovery fixture path: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("discovery fixture")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        fun library(includeThird: Boolean) = ReaderSourceLibrary {
            JsonArray(hosts.take(if (includeThird) 3 else 2).map(::source)).toString()
        }
        val initialLibrary = library(false)
        val ids = runBlocking { library(true).all().associate { it.name to it.id } }
        val firstId = ids.getValue(hosts[0])
        val selectedId = ids.getValue(hosts[1])
        val failedId = ids.getValue(hosts[2])
        val selectedRoute = ReaderSourceAvailability.key(selectedId, "https://${hosts[1]}/book/1")
        val failedRoute = ReaderSourceAvailability.key(failedId, "https://${hosts[2]}/book/1")
        val visibleVm = mutableStateOf<NovelViewModel?>(null)
        val instances = mutableListOf<NovelViewModel>()
        compose.setContent { MaterialExpressiveTheme {
            visibleVm.value?.let { vm ->
                val current by vm.currentBook.collectAsState()
                val availability by vm.bookSourceAvailability.collectAsState()
                val searching by vm.isDiscoveringBookSources.collectAsState()
                val status by vm.bookSourceStatus.collectAsState()
                val switching by vm.isSwitchingSource.collectAsState()
                val imported by vm.importedSources.collectAsState()
                current?.let { book -> NovelBookSourcePicker(book, imported, searching, status, switching,
                    onDismiss = {}, onSelect = vm::switchSource, onRefresh = { vm.refreshBookSources(force = true) },
                    availability = availability, onContinue = { vm.refreshBookSources() }) }
            }
        } }
        fun newVm(sourceLibrary: ReaderSourceLibrary): NovelViewModel {
            lateinit var result: NovelViewModel
            compose.runOnIdle { result = NovelViewModel(context, NovelSourceEngine(client, sourceLibrary = sourceLibrary)) }
            instances += result
            return result
        }
        fun stop(vm: NovelViewModel) {
            compose.runOnIdle { visibleVm.value = null; vm.leaveReader(); vm.viewModelScope.cancel() }
        }
        try {
            val firstEngine = NovelSourceEngine(client, sourceLibrary = initialLibrary)
            val firstBook = runBlocking { firstEngine.search("持久查源重开作品", firstId).single() }
            lateinit var first: NovelViewModel
            compose.runOnIdle { first = NovelViewModel(context, firstEngine); first.openBook(firstBook) }
            instances += first
            compose.waitUntil(15_000) {
                !first.isLoadingChapter.value && !first.isDiscoveringBookSources.value &&
                    first.bookSourceAvailability.value[selectedRoute]?.status == ReaderSourceAvailability.USABLE
            }
            compose.runOnIdle { first.switchSource(selectedId) }
            compose.waitUntil(10_000) {
                !first.isSwitchingSource.value && first.currentBook.value?.preferredSourceId == selectedId &&
                    first.chapterContent.value.contains(hosts[1])
            }
            compose.runOnIdle { first.toggleBookshelf(first.currentBook.value!!) }
            val shelfFile = File(context.filesDir, "novel_bookshelf.json")
            compose.waitUntil(5_000) {
                runCatching { Json.decodeFromString<List<NovelBook>>(shelfFile.readText())
                    .single().preferredSourceId == selectedId }.getOrDefault(false)
            }
            stop(first)

            val release = CountDownLatch(1)
            thirdSourceGate.set(release)
            val beforeRecreation = searches.size
            val reopened = newVm(library(true))
            compose.waitUntil(5_000) { reopened.bookshelf.value.size == 1 }
            compose.runOnIdle { reopened.openBook(reopened.bookshelf.value.single()); visibleVm.value = reopened }
            compose.waitUntil(10_000) {
                !reopened.isLoadingChapter.value && reopened.chapterContent.value.contains(hosts[1]) &&
                    reopened.bookSourceAvailability.value[selectedRoute]?.status == ReaderSourceAvailability.USABLE
            }
            // The selected chapter is restored before the newly eligible source returns.
            compose.onNodeWithTag("reader-book-source-picker").assertIsDisplayed()
            compose.onNodeWithTag("reader-candidate-$selectedId").assertIsDisplayed()
            compose.onNodeWithTag("reader-candidate-$firstId").assertIsDisplayed()
            assertTrue(reopened.bookSourceStatus.value, Regex("已查 [1-9][0-9]*/[0-9]+")
                .containsMatchIn(reopened.bookSourceStatus.value))
            assertTrue("An unfinished persisted scan must resume after a cached chapter opens",
                enteredThirdSearch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(hosts[2]), searches.drop(beforeRecreation))
            assertEquals(selectedId, reopened.currentBook.value?.sourceId)
            assertEquals(selectedId, reopened.currentBook.value?.preferredSourceId)
            release.countDown()
            thirdSourceGate.set(null)
            compose.waitUntil(15_000) {
                !reopened.isDiscoveringBookSources.value &&
                    reopened.bookSourceAvailability.value[failedRoute]?.status == ReaderSourceAvailability.FAILED
            }
            compose.onNodeWithTag("reader-candidate-$failedId").assertIsDisplayed()
            compose.onNodeWithText("验证失败，可点击重试", substring = true).assertIsDisplayed()
            assertFalse(reopened.bookSourceAvailability.value[failedRoute]?.reason.isNullOrBlank())
            assertEquals(selectedId, reopened.currentBook.value?.sourceId)
            assertTrue(reopened.chapterContent.value.contains(hosts[1]))
            stop(reopened)

            // A third independent engine rules out an in-memory result or HTTP cache.
            val beforeFinalOpen = searches.size
            val finalVm = newVm(library(true))
            compose.waitUntil(5_000) { finalVm.bookshelf.value.size == 1 }
            compose.runOnIdle { finalVm.openBook(finalVm.bookshelf.value.single()); visibleVm.value = finalVm }
            compose.waitUntil(10_000) {
                !finalVm.isLoadingChapter.value && finalVm.chapterContent.value.contains(hosts[1]) &&
                    finalVm.bookSourceAvailability.value[failedRoute]?.status == ReaderSourceAvailability.FAILED
            }
            compose.runOnIdle { finalVm.refreshBookSources() }
            compose.waitUntil(10_000) { !finalVm.isDiscoveringBookSources.value }
            assertEquals(beforeFinalOpen, searches.size)
            assertEquals(selectedId, finalVm.currentBook.value?.preferredSourceId)
            assertEquals(ReaderSourceAvailability.USABLE, finalVm.bookSourceAvailability.value[selectedRoute]?.status)
            assertEquals(ReaderSourceAvailability.FAILED, finalVm.bookSourceAvailability.value[failedRoute]?.status)
        } finally {
            thirdSourceGate.getAndSet(null)?.countDown()
            compose.runOnIdle { visibleVm.value = null; instances.forEach { it.viewModelScope.cancel() } }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private fun source(host: String) = buildJsonObject {
        put("bookSourceName", host); put("bookSourceUrl", "https://$host"); put("bookSourceType", 0); put("enabled", true)
        put("searchUrl", "/search?q={{key}}")
        putJsonObject("ruleSearch") {
            put("bookList", ".book"); put("name", "a@text"); put("author", ".author@text"); put("bookUrl", "a@href")
        }
        putJsonObject("ruleBookInfo") { put("name", "h1@text"); put("author", ".author@text") }
        putJsonObject("ruleToc") { put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href") }
        putJsonObject("ruleContent") { put("content", "#content@html") }
    }
}
