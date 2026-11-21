package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
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

/** First page must precede optional all-source discovery, even when alternatives stall. */
class ReaderLoadingLatencyUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectedChapterIsVisibleBeforeAlternativeSourceRequestsStart() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "reader10-priority-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val rules = (0..11).map { index -> buildJsonObject {
            put("bookSourceName", "priority-$index"); put("bookSourceUrl", "https://priority-$index.fixture.test")
            put("enabled", true); put("bookSourceType", 0); put("searchUrl", "/search?q={{key}}")
            putJsonObject("ruleSearch") { put("bookList", ".book"); put("name", "a@text"); put("author", ".author@text"); put("bookUrl", "a@href") }
            putJsonObject("ruleBookInfo") { put("name", "h1@text"); put("author", ".author@text") }
            putJsonObject("ruleToc") { put("chapterList", ".chapters a"); put("chapterName", "text"); put("chapterUrl", "href") }
            putJsonObject("ruleContent") { put("content", "#content@html") }
        } }
        val sources = ReaderSourceLibrary { JsonArray(rules).toString() }
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath
            if (!request.url.host.endsWith(".fixture.test")) throw IOException("No external network in priority fixture")
            requests += path
            if (path == "/search") Thread.sleep(900)
            val body = when (path) {
                "/search" -> "<div class='book'><a href='/book'>正文优先</a><b class='author'>测试作者</b></div>"
                "/book" -> "<h1>正文优先</h1><b class='author'>测试作者</b><div class='chapters'><a href='/chapter'>第一章 正文</a></div>"
                "/chapter" -> "<div id='content'>正文已经显示，其他来源不应该阻塞这一页。</div>"
                else -> throw IOException("Unexpected fixture path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val engine = NovelSourceEngine(client, sourceLibrary = sources)
        val id = runBlocking { sources.all().first { it.name == "priority-0" }.id }
        val book = NovelBook("priority", "正文优先", author = "测试作者", detailUrl = "https://priority-0.fixture.test/book", sourceId = id)
        lateinit var vm: NovelViewModel
        compose.setContent { Text("Reader first-page priority QA") }
        compose.runOnIdle { vm = NovelViewModel(context, engine); vm.openBook(book) }
        try {
            compose.waitUntil(10_000) { vm.chapterContent.value.contains("正文已经显示") && !vm.isLoadingChapter.value }
            assertTrue("The selected body was actually requested", requests.contains("/chapter"))
            compose.waitUntil(5_000) { requests.contains("/search") }
            assertTrue("Alternative scans must start after the selected chapter", requests.indexOf("/chapter") < requests.indexOf("/search"))
            assertEquals(id, vm.currentBook.value!!.sourceId)
            compose.waitUntil(5_000) { runBlocking { vm.library.cachedContent(id, "https://priority-0.fixture.test/chapter") } != null }
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
            dir.deleteRecursively()
        }
    }
}
