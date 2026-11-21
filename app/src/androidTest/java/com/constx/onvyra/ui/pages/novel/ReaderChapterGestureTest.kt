package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test

/** Local Compose fixtures: genuine joined bodies and stable position, without device network. */
class ReaderChapterGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun jumpingFromFirstToHundredthChapterRemainsStationaryWhilePreviousBodiesArrive() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "reader8-idle-jump-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val number = Regex("/190/190260/(\\d+)\\.html").matchEntire(chain.request().url.encodedPath)?.groupValues?.get(1)?.toInt()
            val body = if (number != null) {
                if (number == 99 || number == 98) Thread.sleep(180)
                "<div id='content'>" + (1..12).joinToString("") { "<p>第${number}章第${it}段：保持正在阅读的位置，不操作时不允许自己倒退。</p>" } + "</div>"
            } else "<meta property='og:novel:book_name' content='百章定位验证'><div class='chapterlist'><div class='all'><dl>" +
                (1..140).joinToString("") { "<dd><a href='/190/190260/$it.html'>第${it}章</a></dd>" } + "</dl></div></div>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }.build()
        val vm = NovelViewModel(isolated, NovelSourceEngine(client))
        try {
            compose.setContent { MaterialTheme { NovelReaderPage(vm) {} } }
            compose.runOnIdle { vm.openBook(NovelBook("idle-jump", "百章定位验证", detailUrl = "https://www.piquge.com/190/190260/", sourceId = NovelSourceEngine.SOURCE_PIQUGE)) }
            compose.waitUntil(10_000) { vm.catalog.value.size == 140 && !vm.isLoadingChapter.value }
            compose.runOnIdle { vm.loadChapter(99) }
            compose.waitUntil(10_000) { vm.continuousSections.value.any { it.chapter.index == 99 && it.ready } && !vm.isLoadingChapter.value }
            compose.waitForIdle()
            val heading = compose.onNodeWithTag("continuous-heading-99", useUnmergedTree = true)
            heading.assertIsDisplayed()
            val top = heading.fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("reader-current-chapter").assertTextEquals("第100章")
            repeat(20) {
                Thread.sleep(250)
                compose.waitForIdle()
                assertEquals("Idle preload must not move the chapter backwards", 99, vm.currentChapterIndex.value)
                assertEquals("Idle preload must not move the viewport", top, heading.fetchSemanticsNode().boundsInRoot.top, 1f)
            }
            assertTrue(vm.continuousSections.value.any { it.chapter.index == 98 && it.ready })
        } finally { vm.viewModelScope.cancel(); dir.deleteRecursively() }
    }

    @Test fun actualReaderPageMenusOverlayWithoutMovingOrRepaginatingVisibleText() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "reader7-menu-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val chapter = chain.request().url.encodedPath.endsWith("1.html")
            val html = if (chapter) "<h1>第一章</h1><div id='content'>" +
                (1..40).joinToString("") { "<p>第${it}段菜单覆盖测试。正文必须在原位置，展开和关闭菜单不改变布局。</p>" } + "</div>"
            else "<meta property='og:novel:book_name' content='固定阅读布局'><div class='chapterlist'><div class='all'><dl><dd><a href='/190/190260/1.html'>第一章</a></dd></dl></div></div>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(html.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val vm = NovelViewModel(context, NovelSourceEngine(client))
        try {
            compose.setContent { NovelReaderPage(vm) {} }
            compose.runOnIdle { vm.openBook(NovelBook("fixed-layout", "固定阅读布局", detailUrl = "https://www.piquge.com/190/190260/",
                sourceId = NovelSourceEngine.SOURCE_PIQUGE)) }
            compose.waitUntil(10_000) { vm.continuousSections.value.any { it.ready } }
            compose.onNodeWithTag("reader-body").performScrollToIndex(8)
            val paragraph = compose.onNodeWithTag("continuous-paragraph-0-7", useUnmergedTree = true)
            val bounds = paragraph.fetchSemanticsNode().boundsInRoot
            repeat(4) {
                compose.onNodeWithTag("reader-body").performClick()
                compose.waitForIdle()
                assertEquals("Menu toggle must preserve paragraph top", bounds.top, paragraph.fetchSemanticsNode().boundsInRoot.top, 1f)
                assertEquals("Menu toggle must preserve paragraph width", bounds.width, paragraph.fetchSemanticsNode().boundsInRoot.width, 1f)
            }
        } finally { vm.viewModelScope.cancel(); directory.deleteRecursively() }
    }

    private fun section(index: Int): ContinuousReaderSection {
        val paragraphs = (0..11).map { paragraph ->
            "第${index + 1}章第${paragraph + 1}段：" + "前后章节应当在同一页中连续排布，滚动时保留正在阅读的位置。".repeat(2)
        }
        return ContinuousReaderSection(NovelChapter(index, "第${index + 1}章 连续验证", "fixture://chapter/$index"),
            paragraphs.joinToString("\n"), paragraphs)
    }

    private fun capture(name: String) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(base.filesDir, "reader-qa").apply { mkdirs() }
        val target = if (name.contains("color-wheel")) compose.onNodeWithTag("reader-color-wheel") else compose.onRoot()
        val bitmap = target.captureToImage().asAndroidBitmap()
        try { File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun chapterNineToThirteenRemainOneDocumentAndBoundaryShowsBothBodies() {
        val sections = (8..12).map(::section)
        compose.setContent {
            MaterialTheme {
                ContinuousNovelReader(sections, ContinuousScrollAnchor(1, 8), NovelReaderSettings(), Color.Black,
                    controls = false, onTap = {}, onViewport = { _, _ -> }, onRetry = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("continuous-heading-8", useUnmergedTree = true).assertIsDisplayed()
        // Each chapter has heading + twelve paragraphs + spacer. Start in its last paragraph.
        compose.onNodeWithTag("reader-body").performScrollToIndex(12)
        compose.onNodeWithTag("continuous-paragraph-8-11", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("continuous-heading-9", useUnmergedTree = true).assertIsDisplayed()
        capture("reader4-continuous-boundary.png")
        // A slow drag crosses the boundary in the existing list, without an edge callback replacing it.
        compose.onNodeWithTag("reader-body").performTouchInput {
            swipe(Offset(center.x, center.y + 90f), Offset(center.x, center.y - 90f), durationMillis = 600)
        }
        compose.onNodeWithTag("continuous-heading-9", useUnmergedTree = true).assertIsDisplayed()
        // Reach chapter 13, then reverse through the same document to chapter 9.
        compose.onNodeWithTag("reader-body").performScrollToIndex(56)
        compose.onNodeWithTag("continuous-heading-12", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("reader-body").performScrollToIndex(0)
        compose.onNodeWithTag("continuous-heading-8", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun addingPreviousBodyAndAppendingNextBodyPreservesVisibleParagraphPosition() {
        var sections by mutableStateOf((8..10).map(::section))
        compose.setContent {
            MaterialTheme {
                ContinuousNovelReader(sections, ContinuousScrollAnchor(1, 8), NovelReaderSettings(), Color.Black,
                    controls = false, onTap = {}, onViewport = { _, _ -> }, onRetry = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("reader-body").performScrollToIndex(5)
        val marker = compose.onNodeWithTag("continuous-paragraph-8-4", useUnmergedTree = true)
        marker.assertIsDisplayed()
        val top = marker.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { sections = listOf(section(7)) + sections + section(11) }
        compose.waitForIdle()
        marker.assertIsDisplayed()
        assertEquals("Prepending must keep the same paragraph at the same pixel", top,
            marker.fetchSemanticsNode().boundsInRoot.top, 1f)
        // Removing a distant chapter also must not replace or reposition the visible body.
        compose.runOnIdle { sections = sections.filter { it.chapter.index != 11 } }
        compose.waitForIdle()
        assertEquals(top, marker.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun explicitlyOpenedShortFinalChapterStaysAtTopWhenPreviousChapterArrives() {
        fun short(index: Int) = ContinuousReaderSection(NovelChapter(index, "第${index + 1}章", "fixture://short/$index"),
            "短章正文$index", listOf("短章正文$index"))
        var sections by mutableStateOf(listOf(short(729)))
        var firstVisible = -1
        compose.setContent {
            MaterialTheme {
                ContinuousNovelReader(sections, ContinuousScrollAnchor(1, 729), NovelReaderSettings(), Color.Black,
                    controls = false, onTap = {}, onViewport = { first, _ -> firstVisible = first }, onRetry = {})
            }
        }
        compose.waitForIdle()
        val title = compose.onNodeWithTag("continuous-heading-729", useUnmergedTree = true)
        title.assertIsDisplayed()
        val top = title.fetchSemanticsNode().boundsInRoot.top
        assertEquals(729, firstVisible)
        compose.runOnIdle { sections = listOf(short(728)) + sections }
        compose.waitForIdle()
        title.assertIsDisplayed()
        assertEquals("Last chapter must keep its own heading at the same position", top,
            title.fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals("Short previous chapters must not move reading progress backwards", 729, firstVisible)
    }

    @Test fun viewModelLoadsAdjacentBodiesAndKeepsSessionWhenScrollingInBothDirections() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "reader4-flow-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val fixture = Interceptor { chain ->
            val request = chain.request()
            val number = Regex("/190/190260/(\\d+)\\.html").matchEntire(request.url.encodedPath)
                ?.groupValues?.get(1)?.toIntOrNull()
            val fail = number == 15
            val html = if (number != null) {
                "<html><body><h1>第${number}章</h1><div id='content'><p>章节${number}正文验证段落。</p>" +
                    "<p>同一文档中的正文内容必须完整保留，不因视口变化被替换。</p></div></body></html>"
            } else buildString {
                append("<html><head><meta property='og:novel:book_name' content='连续阅读验证'>")
                append("<meta property='og:novel:author' content='QA'></head><body>")
                append("<div class='book'><h1>连续阅读验证</h1></div><div class='chapterlist'><div class='all'><dl>")
                for (chapter in 1..16) append("<dd><a href='/190/190260/$chapter.html'>第${chapter}章</a></dd>")
                append("</dl></div></div></body></html>")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (fail) 503 else 200).message(if (fail) "Unavailable" else "OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()
        }
        val engine = NovelSourceEngine(OkHttpClient.Builder().addInterceptor(fixture).build())
        val vm = NovelViewModel(context, engine)
        try {
            compose.runOnIdle {
                vm.openBook(NovelBook("continuous-qa", "连续阅读验证", detailUrl = "https://www.piquge.com/190/190260/",
                    sourceId = NovelSourceEngine.SOURCE_PIQUGE, lastReadChapterIndex = 8))
            }
            compose.waitUntil(10_000) { !vm.isLoadingChapter.value && vm.catalog.value.size == 16 &&
                vm.continuousSections.value.filter { it.ready }.map { it.chapter.index }.containsAll(listOf(7, 8, 9, 10)) }
            val revision = vm.continuousAnchor.value.revision
            compose.runOnIdle { vm.onContinuousViewport(10, 10) }
            compose.waitUntil(5_000) { vm.continuousSections.value.any { it.chapter.index == 12 && it.ready } }
            compose.runOnIdle { vm.onContinuousViewport(12, 12) }
            compose.waitUntil(5_000) { vm.continuousSections.value.any { it.chapter.index == 14 && it.error != null } }
            assertEquals(12, vm.currentChapterIndex.value)
            assertEquals("Adjacent HTTP errors must not replace the visible document", null, vm.errorMessage.value)
            assertTrue(!vm.isLoadingChapter.value)
            assertTrue(vm.continuousSections.value.any { it.chapter.index == 8 && it.ready })
            compose.runOnIdle { vm.onContinuousViewport(8, 8) }
            compose.waitUntil(5_000) { vm.continuousSections.value.any { it.chapter.index == 7 && it.ready } }
            assertEquals(8, vm.currentChapterIndex.value)
            assertEquals("Ordinary scrolling must not reset the scroll anchor", revision, vm.continuousAnchor.value.revision)
            assertTrue("Window must remain bounded", vm.continuousSections.value.size <= 13)
        } finally { vm.viewModelScope.cancel(); directory.deleteRecursively() }
    }

    @Test fun customWheelDragChangesColorLiveAndPersistsAfterRecreatingReader() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "reader4-color-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = directory }
        val vm = NovelViewModel(context)
        var chosen by mutableStateOf(0xFFE6DFC8L)
        var open by mutableStateOf(true)
        var changes = 0
        try {
            compose.setContent {
                MaterialTheme {
                    if (open) ReaderColorPickerDialog(chosen, onColorChange = {
                        chosen = it; changes++; vm.selectCustomBackground(it)
                    }, onDismiss = { open = false })
                }
            }
            compose.onNodeWithTag("reader-color-wheel").performTouchInput {
                down(Offset(center.x + width * .25f, center.y))
                moveTo(Offset(center.x, center.y + height * .25f), delayMillis = 250)
                up()
            }
            compose.waitForIdle()
            assertTrue("Dragging must emit live changes", changes >= 2)
            assertNotEquals(0xFFE6DFC8L, chosen)
            compose.waitUntil(5_000) { vm.readerSettings.value.customBackgroundColor == chosen }
            capture("reader4-color-wheel.png")
            compose.onNodeWithText("完成").performClick()
            compose.waitUntil(5_000) {
                runCatching {
                    Json.decodeFromString<NovelReaderSettings>(File(directory, "novel_settings.json").readText())
                        .customBackgroundColor == chosen
                }.getOrDefault(false)
            }
            val recreated = NovelViewModel(context)
            try {
                compose.waitUntil(5_000) { recreated.readerSettings.value.customBackgroundColor == chosen }
                assertEquals(ReaderColorMath.opaque(chosen), recreated.readerSettings.value.readerBackgroundColor())
            } finally { recreated.viewModelScope.cancel() }
        } finally { vm.viewModelScope.cancel(); directory.deleteRecursively() }
    }
}
