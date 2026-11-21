package com.constx.onvyra.ui.pages.novel

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Coil decoder and visible widget with local HTTP fixtures, not a network claim. */
class ReaderCoverFallbackUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun automaticRefreshAndExplicitRetryBypassUndecodable200DiskEntries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "cover-retry-${System.nanoTime()}").apply { mkdirs() }
        val calls = AtomicInteger()
        val bitmap = Bitmap.createBitmap(39, 57, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF586A9C.toInt()) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val url = "https://recover.invalid/cover.png"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val call = calls.incrementAndGet()
            val broken = call <= 2
            if (call > 1) assertEquals("no-cache", chain.request().header("Cache-Control"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body((if (broken) "<html>temporarily unavailable</html>".toByteArray() else bytes)
                    .toResponseBody((if (broken) "text/html" else "image/png").toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(context).memoryCache(null).diskCache {
            DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(1024L * 1024).build()
        }.components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }, connectivityChecker = { ConnectivityChecker.ONLINE }))
        }.build()
        val book = NovelBook("cache-retry", "缓存封面恢复", coverUrl = url,
            detailUrl = "https://recover.invalid/book/1", sourceId = "fixture")
        val visible = mutableStateOf(true)
        val width = AtomicInteger()
        val resolver = NovelCoverResolver(client)
        try {
            // The HTTP fetch succeeds and is cached before the real image decoder rejects it.
            assertTrue(runBlocking { loader.execute(ImageRequest.Builder(context).data(url).build()) } is ErrorResult)
            assertEquals(1, calls.get())
            compose.setContent {
                MaterialExpressiveTheme {
                    if (visible.value) NovelNetworkImage(url, book.detailUrl, book.title, Modifier.size(100.dp, 150.dp),
                        coverBook = book, imageLoader = loader, coverResolver = resolver,
                        onImageSize = { w, _ -> width.set(w) })
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "图片加载失败"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("The cached decoder error triggers one bounded network refresh", 2, calls.get())
            compose.onNodeWithContentDescription(book.title).performClick()
            compose.waitUntil(10_000) { width.get() == 39 }
            compose.onNodeWithContentDescription(book.title)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "图片已加载"))
            assertEquals(3, calls.get())
        } finally {
            compose.runOnIdle { visible.value = false }
            loader.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test fun lateExactSourceArtworkRecoversTheAlreadyFailedVisibleCover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldCalls = AtomicInteger(); val lookupCalls = AtomicInteger(); val imageCalls = AtomicInteger()
        val bitmap = Bitmap.createBitmap(37, 55, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF56855B.toInt()) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body: ByteArray; val type: String; var status = 200
            when (request.url.host) {
                "early.invalid" -> { oldCalls.incrementAndGet(); status = 404; body = "missing".toByteArray(); type = "text/plain" }
                "m.qidian.com" -> { lookupCalls.incrementAndGet(); body = "<html></html>".toByteArray(); type = "text/html" }
                "late.invalid" -> {
                    imageCalls.incrementAndGet()
                    assertNull("Old source credentials must not reach the new artwork source", request.header("Cookie"))
                    assertEquals("late-artwork", request.header("X-Art"))
                    assertEquals("https://late.invalid/book/7", request.header("Referer"))
                    body = bytes; type = "image/png"
                }
                else -> throw AssertionError("Unexpected fixture host")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(context).diskCache(null).memoryCache(null).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }, connectivityChecker = { ConnectivityChecker.ONLINE }))
        }.build()
        val original = NovelBook("early-7", "稍后返回的封面", "聚合作者", "https://early.invalid/missing.png",
            "https://early.invalid/book/7", "early", requestHeaders = mapOf("Cookie" to "fixture-old-source"))
        val state = mutableStateOf(original)
        val visible = mutableStateOf(true)
        val width = AtomicInteger()
        val resolver = NovelCoverResolver(client)
        try {
            compose.setContent {
                MaterialExpressiveTheme {
                    if (visible.value) NovelNetworkImage(state.value.coverUrl, state.value.detailUrl, state.value.title,
                        Modifier.size(100.dp, 150.dp), requestHeaders = state.value.artworkHeaders,
                        coverBook = state.value, imageLoader = loader, coverResolver = resolver,
                        onImageSize = { w, _ -> width.set(w) })
                }
            }
            compose.waitUntil(10_000) { oldCalls.get() == 1 && lookupCalls.get() == 1 }
            val late = original.copy(id = "late-7", sourceId = "late", coverUrl = "https://late.invalid/cover.png",
                detailUrl = "https://late.invalid/book/7", coverReferer = "https://late.invalid/book/7",
                requestHeaders = emptyMap(), coverRequestHeaders = mapOf("X-Art" to "late-artwork"))
            compose.runOnIdle { state.value = original.copy(sources = listOf(original.asSource(), late.asSource())) }
            compose.waitUntil(10_000) { width.get() == 37 }
            compose.onNodeWithContentDescription(original.title).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "图片已加载"))
            assertEquals(1, oldCalls.get()); assertEquals(1, lookupCalls.get()); assertEquals(1, imageCalls.get())
        } finally {
            compose.runOnIdle { visible.value = false }; loader.shutdown()
        }
    }

    @Test fun existingBrokenCoverFallsBackToExactPublisherMatchAndActuallyRenders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceCalls = AtomicInteger()
        val publisherCalls = AtomicInteger()
        val imageCalls = AtomicInteger()
        val bitmap = Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF407AC0.toInt()) }
        val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body: ByteArray
            val type: String
            var status = 200
            when (request.url.host) {
                "source.invalid" -> { sourceCalls.incrementAndGet(); body = "missing".toByteArray(); type = "text/plain"; status = 404 }
                "m.qidian.com" -> {
                    publisherCalls.incrementAndGet()
                    assertEquals("验证封面作品", request.url.queryParameter("kw"))
                    body = """<a data-bid="12"><h2>验证封面作品</h2><p class="author">另一个作者</p><img data-src="//bookcover.yuewen.com/wrong.png"></a>
                        <a data-bid="13"><h2>验证封面作品</h2><p class="author">验证作者</p><img data-src="//bookcover.yuewen.com/exact.png"></a>""".toByteArray()
                    type = "text/html; charset=utf-8"
                }
                "bookcover.yuewen.com" -> {
                    imageCalls.incrementAndGet()
                    assertEquals("/exact.png", request.url.encodedPath)
                    assertNull("Source cookie must not reach publisher CDN", request.header("Cookie"))
                    assertTrue(request.header("Referer").orEmpty().startsWith("https://m.qidian.com/"))
                    body = image; type = "image/png"
                }
                else -> throw AssertionError("Unexpected image fixture URL")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()
        // Repeatable decoder/network evidence must not reuse a previous fixture's disk cache.
        val loader = ImageLoader.Builder(context).diskCache(null).memoryCache(null).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { client }, connectivityChecker = { ConnectivityChecker.ONLINE }))
        }.build()
        val book = NovelBook("cover-ui-qa", "验证封面作品", "验证作者", "https://source.invalid/broken.png",
            "https://source.invalid/novel/12", "biquge_skin", requestHeaders = mapOf("Cookie" to "fixture-only"))
        val resolver = NovelCoverResolver(client)
        val width = AtomicInteger()
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                MaterialExpressiveTheme {
                    if (visible.value) LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            NovelNetworkImage(book.coverUrl, book.detailUrl, book.title, Modifier.size(100.dp, 150.dp),
                                requestHeaders = book.requestHeaders, coverBook = book, imageLoader = loader,
                                coverResolver = resolver, onImageSize = { w, _ -> width.set(w) })
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { width.get() == 48 }
            compose.onNodeWithContentDescription(book.title).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "图片已加载"))
            assertEquals(1, sourceCalls.get())
            assertEquals(1, publisherCalls.get())
            assertEquals(1, imageCalls.get())
        } finally {
            compose.runOnIdle { visible.value = false }
            loader.shutdown()
        }
    }
}
