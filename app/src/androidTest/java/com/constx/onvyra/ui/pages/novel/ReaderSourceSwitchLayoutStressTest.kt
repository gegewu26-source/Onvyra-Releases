package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the production reader, source dialog, adapters and source-switch implementation.
 * All HTTP is intercepted locally. Passing this experiment does not rule out a phone-only crash.
 */
@RunWith(AndroidJUnit4::class)
class ReaderSourceSwitchLayoutStressTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun hundredActualSourceSwitchesKeepTheChapterWhilePickerResultsAndReaderRowsChange() {
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "reader13-switch-layout-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        val hosts = listOf("reader-a.fixture.test", "reader-b.fixture.test", "reader-extra.fixture.test")
        val library = ReaderSourceLibrary { JsonArray(hosts.map(::source)).toString() }
        val searchGate = AtomicReference<SearchGate?>(null)
        val searchRequests = AtomicInteger()
        val bodyRequests = AtomicInteger()
        val resetObservations = AtomicInteger()
        val readyObservations = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val path = request.url.encodedPath
            if (host !in hosts) throw IOException("External network is disabled for reader layout QA")
            val body = when {
                path == "/search" -> {
                    searchRequests.incrementAndGet()
                    searchGate.get()?.let { gate ->
                        gate.waiting.incrementAndGet()
                        gate.entered.countDown()
                        try {
                            check(gate.release.await(10, TimeUnit.SECONDS)) { "Fixture search gate was not released" }
                        } finally { gate.waiting.decrementAndGet() }
                    }
                    val work = request.url.queryParameter("q")?.removePrefix("换源布局压力作品")?.toIntOrNull()
                        ?: error("Unexpected fixture search query")
                    "<div class='book'><a href='/book/$work'>${title(work)}</a><b class='author'>布局验证作者</b></div>"
                }
                path.startsWith("/book/") -> {
                    val work = path.substringAfterLast('/').toInt()
                    "<h1>${title(work)}</h1><p class='author'>布局验证作者</p>" +
                        "<div class='chapters'>" + (1..12).joinToString("") {
                            "<a href='/chapter/$work/$it'>第${it}章 同一章节</a>"
                        } + "</div>"
                }
                path.startsWith("/chapter/") -> {
                    bodyRequests.incrementAndGet()
                    // Cold requests complete after the picker starts closing; subsequent
                    // cached switches also exercise the faster production commit path.
                    Thread.sleep(25)
                    "<div id='content'>" + (0..17).joinToString("") { paragraph ->
                        "<p>$host 第${path.substringAfterLast('/')}章第${paragraph}段。" +
                            "换源必须保留章节，前后正文在同一阅读视口完成布局。".repeat(3) + "</p>"
                    } + "</div>"
                }
                else -> throw IOException("Unexpected reader layout fixture route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("layout fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val engine = NovelSourceEngine(client, sourceLibrary = library)
        val ids = runBlocking { library.all().associate { it.name to it.id } }
        lateinit var vm: NovelViewModel
        var initialized = false
        var completedSwitches = 0
        var overlappingSearches = 0
        try {
            instrumentation.runOnMainSync {
                vm = NovelViewModel(isolated, engine)
                initialized = true
                vm.continuousSections.onEach { sections ->
                    if (sections.isEmpty() || sections.any { it.loading }) resetObservations.incrementAndGet()
                    if (sections.any { it.ready }) readyObservations.incrementAndGet()
                }.launchIn(vm.viewModelScope)
            }
            compose.setContent { MaterialExpressiveTheme { NovelReaderPage(vm) {} } }

            repeat(100) { iteration ->
                val targetHost = hosts[if (iteration % 2 == 0) 1 else 0]
                val targetId = ids.getValue(targetHost)
                val gate = if (iteration % 10 == 0) {
                    // force=true retries deferred sources but deliberately reuses successful
                    // aggregate hits. Open a distinct, real fixture work every ten switches
                    // so its aggregate query is uncached, without touching private caches.
                    val work = iteration / 10
                    val seeded = runBlocking {
                        ReaderBookIdentity.merge(title(work), hosts.map { host ->
                            engine.search(title(work), ids.getValue(host)).single()
                        }).single()
                    }
                    SearchGate().also { pending ->
                        searchGate.set(pending)
                        compose.runOnIdle { vm.openBook(seeded.copy(lastReadChapterIndex = 4)) }
                        compose.waitUntil(15_000) {
                            !vm.isLoadingChapter.value && vm.currentChapterIndex.value == 4 &&
                                vm.currentBook.value?.title == title(work) &&
                                vm.currentBook.value?.sourceCount == hosts.size
                        }
                        assertTrue("An uncached adapter search must actually enter the HTTP gate",
                            pending.entered.await(2, TimeUnit.SECONDS))
                    }
                } else null
                if (iteration % 5 == 4) {
                    // Scroll within the current chapter before a source replacement.
                    // Derive its row start from the real sections, rather than guessing
                    // an index that could accidentally select an adjacent chapter.
                    val sections = vm.continuousSections.value
                    val start = sections.takeWhile { it.chapter.index < 4 }.sumOf {
                        if (it.ready) it.paragraphs.size + 2 else 2
                    }
                    compose.onNodeWithTag("reader-body").performScrollToIndex(start + 5)
                    compose.waitForIdle()
                    assertEquals(4, vm.currentChapterIndex.value)
                }

                try {
                    if (compose.onAllNodesWithText("换源").fetchSemanticsNodes().isEmpty()) {
                        compose.onNodeWithTag("reader-body").performClick()
                    }
                    compose.onNodeWithText("换源").performClick()
                    compose.onNodeWithTag("reader-book-source-picker").assertIsDisplayed()
                    compose.onNodeWithTag("reader-candidate-$targetId").assertIsDisplayed()
                    if (gate != null) {
                        assertTrue("The picker must overlap a request still blocked in the real adapter", gate.waiting.get() > 0)
                        assertTrue("The picker must really overlap an outstanding source lookup", vm.isDiscoveringBookSources.value)
                        overlappingSearches++
                    }
                    // Resume the production discovery stream immediately before the
                    // real dialog click closes it and replaces the reading document.
                    searchGate.set(null)
                    gate?.release?.countDown()
                    compose.onNodeWithTag("reader-candidate-$targetId").performClick()
                } finally {
                    searchGate.set(null)
                    gate?.release?.countDown()
                }
                compose.waitUntil(15_000) {
                    !vm.isSwitchingSource.value && !vm.isLoadingChapter.value &&
                        vm.currentBook.value?.sourceId == targetId &&
                        vm.chapterContent.value.contains(targetHost) &&
                        vm.continuousSections.value.any { it.chapter.index == 4 && it.ready }
                }
                compose.waitForIdle()
                assertNull("Source switch $iteration failed", vm.errorMessage.value)
                assertEquals("Source switch $iteration changed the chapter", 4, vm.currentChapterIndex.value)
                assertEquals(targetId, vm.currentBook.value!!.preferredSourceId)
                assertTrue(vm.catalog.value.all { it.url.contains(targetHost) })
                compose.onNodeWithTag("reader-body").assertIsDisplayed()
                compose.onNodeWithTag("continuous-heading-4", useUnmergedTree = true).assertIsDisplayed()
                completedSwitches++
            }

            assertEquals(100, completedSwitches)
            assertEquals(10, overlappingSearches)
            assertTrue("No real adapter body was read", bodyRequests.get() >= 2)
            assertTrue("The fixture did not exercise changing document readiness", resetObservations.get() > 1)
            assertTrue(readyObservations.get() > 1)
            println("READER_SOURCE_LAYOUT_STRESS=PASS; ACTUAL_SWITCHES=$completedSwitches; " +
                "PICKER_SEARCH_OVERLAPS=$overlappingSearches; BODY_REQUESTS=${bodyRequests.get()}; " +
                "SEARCH_REQUESTS=${searchRequests.get()}; READY_OBSERVATIONS=${readyObservations.get()}; " +
                "CHAPTER_RETAINED=4; PHONE_CRASH_RULED_OUT=false")
        } finally {
            searchGate.getAndSet(null)?.release?.countDown()
            instrumentation.runOnMainSync { if (initialized) { vm.leaveReader(); vm.viewModelScope.cancel() } }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            directory.deleteRecursively()
        }
    }

    private class SearchGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = AtomicInteger()
    }

    private fun title(work: Int) = "换源布局压力作品$work"

    private fun source(host: String) = buildJsonObject {
        put("bookSourceName", host); put("bookSourceUrl", "https://$host"); put("enabled", true)
        put("bookSourceType", 0); put("searchUrl", "/search?q={{key}}")
        putJsonObject("ruleSearch") {
            put("bookList", ".book"); put("name", "a@text"); put("author", ".author@text"); put("bookUrl", "a@href")
        }
        putJsonObject("ruleBookInfo") { put("name", "h1@text"); put("author", ".author@text") }
        putJsonObject("ruleToc") { put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href") }
        putJsonObject("ruleContent") { put("content", "#content@html") }
    }
}
