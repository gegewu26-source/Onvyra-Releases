package com.constx.onvyra.ui.pages.novel

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import com.constx.onvyra.ui.context.Navigator
import okhttp3.OkHttpClient
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Actual keyword search and reading with no host proxy or synthetic response. */
class ReaderDirectFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultSourceKeywordSearchAndReadWithoutProxy() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val data = File(base.cacheDir, "reader-direct-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = data
            override fun getCacheDir(): File = File(data, "cache").apply { mkdirs() }
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(base).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client },
                connectivityChecker = { ConnectivityChecker.ONLINE }))
        }.build())
        val vm = NovelViewModel(context, NovelSourceEngine(client, context.cacheDir),
            coverResolver = NovelCoverResolver(client))
        val records = mutableListOf<JsonObject>()
        try {
            compose.runOnUiThread {
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            assertEquals("biquge_skin", vm.selectedSourceId.value)
            compose.setContent {
                MaterialExpressiveTheme {
                    NovelMainPage(Navigator(mutableStateListOf<NavKey>()), vm)
                }
            }
            compose.onNodeWithContentDescription("搜索").performClick()
            val input = compose.onNode(hasSetTextAction())
            input.performTextInput("诡秘之主")
            val begin = System.nanoTime()
            input.performImeAction()
            compose.activityRule.scenario.onActivity { activity ->
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            compose.waitUntil(60_000) { !vm.isSearching.value }
            assertTrue("Search failed: ${vm.errorMessage.value}", vm.searchResults.value.isNotEmpty())
            val searchMs = (System.nanoTime() - begin) / 1_000_000
            compose.waitUntil(15_000) { vm.searchResults.value.any { !NovelCoverResolver.needsCover(it) } }
            compose.waitUntil(15_000) {
                runCatching { compose.onAllNodes(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "图片已加载"))
                    .fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            val covers = vm.searchResults.value.count { !NovelCoverResolver.needsCover(it) }
            compose.waitForIdle()
            val searchImage = compose.onRoot().captureToImage().asAndroidBitmap()
            val screenshots = File(base.filesDir, "reader-qa").apply { mkdirs() }
            File(screenshots, "reader2-direct-search-cover.png").outputStream().use {
                assertTrue(searchImage.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            searchImage.recycle()
            val firstBook = vm.searchResults.value.first { it.title == "诡秘之主" && it.author == "爱潜水的乌贼" }
            compose.onNodeWithTag("reader-results").performScrollToNode(hasTestTag("reader-book-${firstBook.id}"))
            compose.onNodeWithTag("reader-book-${firstBook.id}").performClick()
            compose.waitUntil(45_000) { !vm.isLoadingChapter.value && vm.chapterContent.value.isNotBlank() }
            assertTrue("Chapter failed: ${vm.errorMessage.value}", vm.chapterContent.value.length > 100)
            compose.waitUntil(60_000) { !vm.isLoadingCatalog.value }
            assertTrue("Catalog incomplete: ${vm.errorMessage.value}", vm.isCatalogComplete.value)
            assertTrue(vm.catalog.value.size > 100)
            records += buildJsonObject {
                put("query", "诡秘之主"); put("source", vm.currentBook.value!!.sourceId)
                put("search_ms", searchMs); put("search_count", vm.searchResults.value.size)
                put("resolved_covers", covers)
                put("catalog_count", vm.catalog.value.size); put("first_text_characters", vm.chapterContent.value.length)
            }
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val output = File(base.filesDir, "reader-qa").apply { mkdirs() }
            File(output, "reader2-direct-keyword-reading.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.onNode(hasSetTextAction()).performTextInput("我的职业太有个性")
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.waitUntil(60_000) { !vm.isSearching.value }
            val book = vm.searchResults.value.firstOrNull { it.title == "我的职业太有个性" }
            assertNotNull("Second search failed: ${vm.errorMessage.value}", book)
            compose.onNodeWithTag("reader-results").performScrollToNode(hasTestTag("reader-book-${book!!.id}"))
            compose.onNodeWithTag("reader-book-${book!!.id}").performClick()
            compose.waitUntil(45_000) { !vm.isLoadingChapter.value && vm.chapterContent.value.isNotBlank() }
            assertTrue(vm.chapterContent.value.length > 100)
            compose.waitUntil(60_000) { !vm.isLoadingCatalog.value }
            assertTrue(vm.isCatalogComplete.value)
            records += buildJsonObject {
                put("query", "我的职业太有个性"); put("source", vm.currentBook.value!!.sourceId)
                put("catalog_count", vm.catalog.value.size); put("first_text_characters", vm.chapterContent.value.length)
            }
            File(base.filesDir, "reader2-direct-flow.json").writeText(Json { prettyPrint = true }.encodeToString(
                JsonObject.serializer(), buildJsonObject {
                    put("network", "Proxy.NO_PROXY; no adb reverse; actual Android keyword UI")
                    put("checks", JsonArray(records)); put("ok", true)
                }))
        } catch (error: Throwable) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot != null) {
                val output = File(base.filesDir, "reader-qa").apply { mkdirs() }
                File(output, "reader2-direct-failure.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            throw error
        } finally {
            compose.runOnIdle { vm.viewModelScope.cancel() }
            data.deleteRecursively()
        }
    }
}
