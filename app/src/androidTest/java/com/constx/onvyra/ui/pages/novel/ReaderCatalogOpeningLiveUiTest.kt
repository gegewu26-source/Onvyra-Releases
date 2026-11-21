package com.constx.onvyra.ui.pages.novel

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.constx.onvyra.ui.context.Navigator
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Covers the complete UI hand-off after public catalogue metadata resolves to another valid title. */
class ReaderCatalogOpeningLiveUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun publicStoreClickShowsDetailAndBodyThenReopensWithPersistedRouteAndContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("reader_live") == "true")
        check(arguments.getString("lab_avd_name") == "CodexReaderBarsQA")
        check(UiDevice.getInstance(instrumentation)
            .executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "CodexReaderBarsQA")

        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "reader18-opening-live-${System.nanoTime()}").apply { mkdirs() }
        val isolatedFiles = File(directory, "files").apply { mkdirs() }
        val isolatedCache = File(directory, "cache").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = isolatedFiles
            override fun getCacheDir() = isolatedCache
        }
        val networkRequests = AtomicInteger()
        val restartNetworkAttempts = AtomicInteger()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectTimeout(7, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.queryParameter("token") == null && request.url.queryParameter("userid") == null)
                check(request.url.host !in setOf("biquge.skin", "www.biquge.skin", "apibi.cc"))
                networkRequests.incrementAndGet()
                chain.proceed(request)
            }.build()
        // Only the reconstructed source engine is offline. Its public store repository may read
        // its already-populated metadata cache; automatic background discovery cannot fetch bodies.
        val offline = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor {
            restartNetworkAttempts.incrementAndGet()
            throw IOException("QA restart source engine is offline")
        }.build()
        fun sourceLibrary() = ReaderSourceLibrary {
            base.assets.open(ReaderSourceLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
        }
        fun engine(client: OkHttpClient, name: String) =
            NovelSourceEngine(client, File(isolatedCache, name), sourceLibrary())
        fun repository() = ReaderStoreRepository(client, File(isolatedCache, "public-store"))
        val viewModels = mutableListOf<NovelViewModel>()
        val activeViewModel = mutableStateOf<NovelViewModel?>(null)
        var stage = "public_store_metadata"
        var ok = false
        var requestedTitle = ""
        var resolvedTitle = ""
        var resolvedSource = ""
        var chapters = 0
        var bodyCharacters = 0
        var firstOpenMs = 0L
        var reopenMs = 0L
        var persistedRouteFiles = 0
        var restartedBodyVisible = false
        val started = SystemClock.elapsedRealtime()
        val evidence = File(base.filesDir, "reader-qa").apply { mkdirs() }
        fun capture(name: String) {
            evidence.resolve(name).outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        fun present(matcher: SemanticsMatcher): Boolean =
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        fun clickWorkFromStore(vm: NovelViewModel, work: NovelBook) {
            compose.onNodeWithText("书城").performClick()
            compose.waitUntil(12_000) { vm.storeHome.value != null && !vm.storeLoading.value }
            val home = checkNotNull(vm.storeHome.value)
            val section = home.sections.firstOrNull { section -> section.books.any { it.id == work.id } }
                ?: error("The selected real homepage work is no longer available in a rendered section")
            compose.onNodeWithTag("store-home").performScrollToNode(hasTestTag("store-section-${section.category.id}"))
            if (section.horizontal) {
                compose.onNodeWithTag("store-section-${section.category.id}").onChildren()
                    .filter(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("store-book-${work.id}"))
            }
            compose.onNodeWithTag("store-book-${work.id}").assertIsDisplayed().performClick()
        }

        try {
            val store = repository()
            val home = runBlocking { withTimeout(55_000) { store.home("male", force = true) } }
            val work = home.sections.flatMap { it.books }.firstOrNull { it.title.startsWith("三天被打七次") }
                ?: error("The requested regression sample is absent from the current public homepage")
            requestedTitle = work.title
            assertEquals(NovelSourceEngine.SOURCE_CATALOG, work.sourceId)
            val first = NovelViewModel(context, engine(client, "fresh-sources"), suppliedStoreRepository = store)
            viewModels += first
            activeViewModel.value = first
            compose.setContent {
                activeViewModel.value?.let { vm ->
                    key(vm) {
                        val navigator = remember { Navigator(mutableStateListOf<NavKey>()) }
                        NovelMainPage(navigator, vm)
                    }
                }
            }

            stage = "first_public_store_ui_click"
            val openStart = SystemClock.elapsedRealtime()
            clickWorkFromStore(first, work)
            compose.waitUntil(50_000) {
                first.errorMessage.value?.let { error("Public catalogue UI opening failed before deadline") }
                val actual = first.currentBook.value
                actual != null && actual.sourceId != NovelSourceEngine.SOURCE_CATALOG &&
                    first.catalog.value.isNotEmpty() && !first.isLoadingCatalog.value &&
                    present(hasTestTag("reader-book-detail"))
            }
            firstOpenMs = SystemClock.elapsedRealtime() - openStart
            compose.onNodeWithTag("reader-book-detail").assertIsDisplayed()
            compose.onNodeWithText("正在打开作品…").assertDoesNotExist()
            compose.onNodeWithTag("detail-start-reading").assertIsEnabled()
            resolvedTitle = checkNotNull(first.currentBook.value).title
            resolvedSource = checkNotNull(first.currentBook.value).sourceId
            chapters = first.catalog.value.size
            assertEquals(work.id, first.bookOpenRequestId.value)
            capture("reader18-live-opening-detail.png")

            stage = "first_reading_ui_body"
            compose.onNodeWithTag("detail-start-reading").performClick()
            compose.waitUntil(25_000) {
                first.errorMessage.value?.let { error("Actual first chapter UI failed before deadline") }
                first.chapterContent.value.length > 300 && !first.isLoadingChapter.value &&
                    present(hasTestTag("reader-body"))
            }
            compose.onNodeWithTag("reader-body").assertIsDisplayed()
            bodyCharacters = first.chapterContent.value.length
            val originalBody = first.chapterContent.value
            val originalRoute = checkNotNull(first.currentBook.value)
            capture("reader18-live-opening-body.png")

            stage = "verify_persisted_library_and_route"
            val cached = runBlocking {
                withTimeout(5_000) {
                    val catalog = first.library.cachedCatalog(originalRoute)
                    val chapter = first.catalog.value.first()
                    val body = first.library.cachedContent(originalRoute.sourceId, chapter.url)
                    catalog to body
                }
            }
            assertTrue("The real catalogue must be persisted before reconstruction", !cached.first.isNullOrEmpty())
            assertEquals("The visible chapter must also be persisted", originalBody, cached.second)
            persistedRouteFiles = File(isolatedFiles, "reader-reading-routes-v1")
                .listFiles { file -> file.isFile && file.extension == "json" }?.size ?: 0
            assertTrue("A UI reconstruction must not reuse only engine memory", persistedRouteFiles > 0)
            compose.runOnIdle { first.leaveReader(); first.viewModelScope.cancel(); activeViewModel.value = null }
            compose.waitForIdle()

            stage = "restart_public_store_ui_click_offline_source_engine"
            val restarted = NovelViewModel(context, engine(offline, "new-engine-no-source-cache"),
                suppliedStoreRepository = repository())
            viewModels += restarted
            compose.runOnIdle { activeViewModel.value = restarted }
            val reopenStart = SystemClock.elapsedRealtime()
            clickWorkFromStore(restarted, work)
            compose.waitUntil(10_000) {
                restarted.errorMessage.value?.let { error("Persisted-route UI reconstruction failed before deadline") }
                restarted.currentBook.value?.sourceId == originalRoute.sourceId &&
                    restarted.catalog.value.isNotEmpty() && present(hasTestTag("reader-book-detail"))
            }
            compose.onNodeWithTag("reader-book-detail").assertIsDisplayed()
            compose.onNodeWithText("正在打开作品…").assertDoesNotExist()
            compose.onNodeWithTag("detail-start-reading").assertIsEnabled().performClick()
            compose.waitUntil(10_000) {
                restarted.chapterContent.value == originalBody && !restarted.isLoadingChapter.value &&
                    present(hasTestTag("reader-body"))
            }
            reopenMs = SystemClock.elapsedRealtime() - reopenStart
            assertEquals(originalRoute.detailUrl, restarted.currentBook.value?.detailUrl)
            assertEquals(work.id, restarted.bookOpenRequestId.value)
            compose.onNodeWithTag("reader-body").assertIsDisplayed()
            capture("reader18-live-opening-restarted-body.png")
            restartedBodyVisible = true
            stage = "complete"
            ok = true
        } finally {
            compose.runOnIdle {
                viewModels.forEach { vm -> vm.leaveReader(); vm.viewModelScope.cancel() }
                activeViewModel.value = null
            }
            compose.waitForIdle()
            evidence.resolve("reader18-live-opening.json").writeText(buildJsonObject {
                put("ok", ok); put("stage", stage)
                put("requestedTitle", requestedTitle); put("resolvedTitle", resolvedTitle)
                put("sourceId", resolvedSource); put("catalogChapters", chapters)
                put("bodyCharacters", bodyCharacters); put("firstOpenMs", firstOpenMs); put("reopenMs", reopenMs)
                put("persistedRouteFiles", persistedRouteFiles); put("restartedBodyVisible", restartedBodyVisible)
                put("publicNetworkRequests", networkRequests.get()); put("restartSourceNetworkAttempts", restartNetworkAttempts.get())
                put("restartSourceNetworkEnabled", false); put("uiEntryWasClicked", true)
                put("totalMs", SystemClock.elapsedRealtime() - started)
                put("chapterBodiesWrittenToJson", false); put("userCredentialsProvided", false)
            }.toString())
            client.dispatcher.cancelAll(); offline.dispatcher.cancelAll()
            client.connectionPool.evictAll(); offline.connectionPool.evictAll()
            check(directory.canonicalPath.startsWith(base.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }
}
