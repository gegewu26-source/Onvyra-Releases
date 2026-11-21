package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in public HTTP + real decoded cover and visible details page for the reported work. */
class ReaderExactBookLiveUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reportedFengShenJinHuaHasExactAuthorArtworkAndCleanBodiesFromActualSource() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "CodexReaderQA")
        val base = instrumentation.targetContext
        val dir = File(base.cacheDir, "reader8-live-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val sources = ReaderSourceLibrary { base.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() } }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host !in setOf("www.biquge.skin","biquge.skin","apibi.cc")) { "Removed Skin source was contacted" }
            chain.proceed(chain.request())
        }.build()
        val engine = NovelSourceEngine(client, sourceLibrary = sources)
        val resolver = NovelCoverResolver(client, referenceProviders = true)
        resolver.useSourceLookup(engine::resolveSourceCover)
        val checks = mutableListOf<JsonObject>()
        val book = runBlocking { withTimeout(70_000) {
            val rule = sources.all().first { it.url == "http://www.yetianlian.info" }
            val hit = engine.search("封神进化", rule.id).first { it.title == "封神进化" && it.author.contains("工笔画") }
            val detail = engine.getBookDetail(hit)
            val chapters = engine.getCatalog(detail.detailUrl, detail.sourceId)
            assertTrue(chapters.size > 100)
            assertEquals("楔子一：初入神界",chapters.first().title)
            assertEquals("第一百四十二章：绝对正义",chapters.last().title)
            assertEquals(chapters.size,chapters.map { it.url }.distinct().size)
            for (chapter in listOf(chapters.first(), chapters[7], chapters.last())) {
                val text = engine.getChapterContent(chapter.url, detail.sourceId)
                assertTrue(text.length > 300)
                assertFalse("Website footer in ${chapter.title}", Regex("最新网址|本章未完|请记住本书首发|<script").containsMatchIn(text))
                checks += buildJsonObject { put("source", detail.sourceId); put("chapter", chapter.title); put("characters", text.length); put("website_footer", false) }
            }
            val cover = resolver.resolve(detail) ?: error("Exact title + author artwork not resolved")
            val request = Request.Builder().url(cover.url).header("Referer", cover.referer)
                .apply { cover.requestHeaders.forEach { (key,value) -> header(key,value) } }.build()
            client.newCall(request).execute().use {
                assertEquals(200, it.code)
                val bytes = it.body.bytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Artwork was not a decoded image")
                assertTrue(bitmap.width >= 100 && bitmap.height >= 100); bitmap.recycle()
            }
            checks += buildJsonObject { put("title", detail.title); put("author", detail.author); put("chapters", chapters.size); put("cover", cover.url); put("cover_source", cover.sourcePage) }
            assertFalse(NovelSourceEngine.AVAILABLE_SOURCES.any { it.id == NovelSourceEngine.SOURCE_SKIN })
            val home = engine.getDiscoverBooks()
            assertTrue(home.isNotEmpty() && home.none { it.sourceId == NovelSourceEngine.SOURCE_SKIN })
            checks += buildJsonObject { put("source",rule.name);put("homepage_books",home.size);put("removed_skin_contacted",false) }
            detail.copy(coverUrl = cover.url,coverReferer = cover.referer,coverRequestHeaders = cover.requestHeaders)
        } }
        lateinit var vm: NovelViewModel
        compose.runOnIdle { vm = NovelViewModel(context, engine, coverResolver = resolver); vm.openBook(book) }
        compose.setContent { MaterialExpressiveTheme { NovelBookDetailPage(vm, {}, {}, {}, {}) } }
        try {
            compose.waitUntil(45_000) { compose.onAllNodesWithContentDescription("封神进化封面").fetchSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "图片已加载"
            } && vm.catalog.value.size > 100 }
            compose.onNodeWithTag("reader-book-detail").assertIsDisplayed()
            compose.onAllNodesWithText("工笔画", substring = true).onFirst().assertIsDisplayed()
            compose.onNodeWithContentDescription("封神进化封面").assertIsDisplayed()
            val destination = File(base.filesDir,"reader-qa").apply { mkdirs() }
            destination.resolve("reader8-exact-book-checks.json").writeText(JsonArray(checks).toString())
            destination.resolve("reader8-exact-book-detail.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
        } finally { compose.runOnIdle { vm.viewModelScope.cancel() }; dir.deleteRecursively() }
    }
}
