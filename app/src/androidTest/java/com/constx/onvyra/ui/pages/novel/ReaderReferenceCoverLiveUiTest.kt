package com.constx.onvyra.ui.pages.novel

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.EventListener
import okhttp3.Call
import java.io.IOException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real public metadata + CDN requests through the same visible cover widget as the bookstore. */
class ReaderReferenceCoverLiveUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun actualBundledBooksFromReportedScreenshotLoadVisibleArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("reader_live") == "true")
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "CodexReaderQA")
        val context = instrumentation.targetContext
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { println("COVER_PUBLIC_START ${call.request().url.host}") }
            override fun callFailed(call: Call, ioe: IOException) { println("COVER_PUBLIC_ERROR ${call.request().url.host} ${ioe.javaClass.simpleName}: ${ioe.message}") }
        }).build()
        val engine = NovelSourceEngine(client, skinIndexSeed = { context.assets.open(SkinSearchIndex.ASSET_PATH) })
        val books = runBlocking { engine.getDiscoverBooks(NovelSourceEngine.SOURCE_SKIN) }
        val expected = listOf("开局地摊卖大力江南夏瑶", "藏夏", "七世悟道", "都市渡鬼人")
        val selected = expected.map { title -> books.first { it.title == title } }
        val resolver = NovelCoverResolver(client, referenceProviders = true)
        compose.setContent {
            MaterialExpressiveTheme {
                NovelBookCollection(selected, grid = true, query = "", onOpen = {}, coverResolver = resolver)
            }
        }
        try { compose.waitUntil(60_000) {
            selected.all { book -> compose.onAllNodesWithContentDescription(book.title).fetchSemanticsNodes()
                .any { it.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "图片已加载" } }
        } } catch (error: Throwable) {
            selected.forEach { println("COVER_VISIBLE_STATE ${it.title}: " + compose.onAllNodesWithContentDescription(it.title).fetchSemanticsNodes()
                .map { n -> n.config.getOrElse(SemanticsProperties.StateDescription) { "missing" } }) }
            throw error
        }
        selected.forEach { book -> compose.onNodeWithContentDescription(book.title).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "图片已加载")) }
        runBlocking { selected.forEach { book ->
            val artwork = resolver.resolve(book)!!
            assertTrue("The reference primary metadata must provide this book's artwork", artwork.sourcePage.startsWith("https://pre-api.tuishujun.com/"))
            assertFalse(NovelCoverResolver.needsCover(book.copy(coverUrl = artwork.url)))
        } }
        val destination = File(context.filesDir, "reader-qa/reader7-real-screenshot-covers.png")
        destination.parentFile?.mkdirs()
        val bitmap = compose.onRoot().captureToImage()
        val decoded = bitmap.asAndroidBitmap()
        destination.outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

