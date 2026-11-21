package com.constx.onvyra.ui.pages.novel

import android.graphics.Bitmap
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test

/** Actual search-content widget and orchestrator; every image response is a local fixture. */
class ReaderAggregateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun earlyResultsStayVisibleWhileSameAuthorSourcesIncreaseAndOtherMediaStayOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF578AA0.toInt()) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(context).diskCache(null).memoryCache(null).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }, connectivityChecker = { ConnectivityChecker.ONLINE }))
        }.build()
        val resolver = NovelCoverResolver(client)
        fun hit(source: String, writer: String, suffix: String) = NovelBook("$source-$suffix", "同名作品", writer,
            "https://fixture.invalid/cover.png", "https://$source.invalid/book/$suffix", source)
        val comic = hit("comic", "漫画作者", "comic").copy(title = "漫画不应出现", isComic = true)
        val releaseSlow = CompletableDeferred<Unit>()
        val state = mutableStateOf(ReaderSearchProgress(emptyList(), 0, 2, 2, 0, false, emptySet()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val search = ReaderAggregateSearch(searchSource = { _, id ->
            if (id == "slow") releaseSlow.await()
            if (id == "fast") listOf(hit(id, "作者甲", "main"), hit(id, "作者乙", "other"), comic.copy(sourceId = id))
            else listOf(hit(id, "作者甲", "main"))
        }, sourceTimeoutMs = 10_000, totalTimeoutMs = 15_000)
        try {
            compose.setContent {
                MaterialExpressiveTheme {
                    NovelSearchResultsContent(state.value.books + comic, !state.value.finished, "同名作品", 0, {},
                        imageLoader = loader, coverResolver = resolver)
                }
            }
            scope.launch {
                search.searchAll("同名作品", 0, listOf(ReaderSearchSource("fast"), ReaderSearchSource("slow"))) { state.value = it }
            }
            compose.waitUntil(5_000) { state.value.books.size == 2 && !state.value.finished }
            compose.onAllNodesWithText("同名作品").assertCountEquals(2)
            compose.onNodeWithText("作者甲", substring = true).assertIsDisplayed()
            compose.onNodeWithText("作者乙", substring = true).assertIsDisplayed()
            compose.onNodeWithText("漫画不应出现").assertDoesNotExist()
            compose.onNodeWithText("正在搜索小说…").assertDoesNotExist()
            compose.onNodeWithTag("reader-search-content").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "继续查找来源"))
            releaseSlow.complete(Unit)
            compose.waitUntil(5_000) { state.value.finished && state.value.books.any { it.sourceCount == 2 } }
            compose.onNodeWithText("2 个来源").assertIsDisplayed()
            compose.onNodeWithText("1 个来源").assertIsDisplayed()
            compose.onAllNodesWithText("同名作品").assertCountEquals(2)
            compose.onNodeWithText("漫画不应出现").assertDoesNotExist()
        } finally {
            releaseSlow.complete(Unit)
            scope.cancel()
            loader.shutdown()
        }
    }
}
