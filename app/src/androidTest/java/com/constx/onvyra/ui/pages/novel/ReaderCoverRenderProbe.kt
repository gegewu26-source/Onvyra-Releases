package com.constx.onvyra.ui.pages.novel

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.network.ConnectivityChecker
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real network and actual Coil image decoder/render verification. */
class ReaderCoverRenderProbe {
    @get:Rule val compose = createComposeRule()

    @Test fun actualSourceCoverDecodesAndRenders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val builder = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS)
        args.getString("proxyHost")?.let { builder.proxy(Proxy(Proxy.Type.HTTP,
            InetSocketAddress(it, args.getString("proxyPort", "17897").toInt()))) }
        val client = builder.build()
        val book = runBlocking {
            NovelSourceEngine(client).search("诡秘之主", "bqgnovels").first { it.title == "诡秘之主" }
        }
        val decodedWidth = AtomicInteger()
        val imageLoader = ImageLoader.Builder(instrumentation.targetContext).components {
            // ATD has no active Android network, although the explicit proxy is reachable.
            // Override connectivity only in this opt-in test, never in the product loader.
            add(OkHttpNetworkFetcherFactory(callFactory = { client },
                connectivityChecker = { ConnectivityChecker.ONLINE }))
        }.build()
        SingletonImageLoader.setUnsafe(imageLoader)
        val result = runBlocking {
            imageLoader.execute(ImageRequest.Builder(instrumentation.targetContext)
                .data(book.coverUrl).build())
        }
        assertTrue("Actual image decoder result: $result", result is SuccessResult)
        compose.setContent {
            MaterialExpressiveTheme {
                Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                    Text("真实封面加载验证")
                    NovelNetworkImage(book.coverUrl, book.coverReferer, book.title,
                        modifier = Modifier.size(160.dp, 220.dp),
                        onImageSize = { width, _ -> decodedWidth.set(width) })
                    Text(book.title)
                }
            }
        }
        compose.waitUntil(45_000) { decodedWidth.get() > 0 }
        assertTrue(decodedWidth.get() > 0)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(instrumentation.targetContext.filesDir, "reader-qa").apply { mkdirs() }
        File(dir, "05-live-cover-render.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
