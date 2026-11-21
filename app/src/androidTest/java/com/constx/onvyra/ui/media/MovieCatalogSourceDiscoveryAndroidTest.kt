package com.constx.onvyra.ui.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.constx.onvyra.data.media.MovieCatalogSourceDiscovery
import com.constx.onvyra.data.media.MovieCatalogSourceRegistry
import com.constx.onvyra.data.media.MovieDiscoverySeed
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's actual org.json decoder and controlled HTTP responses, not live repositories. */
@RunWith(AndroidJUnit4::class)
class MovieCatalogSourceDiscoveryAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun suppliedDiscoveryAssetContainsThePromised57SeedsAnd20Pages() {
        val root = context.assets.open("movie_catalog/sources.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        assertEquals(57, root.getValue("base_lines").jsonArray.size)
        assertEquals(20, root.getValue("search_pages").jsonArray.size)
    }

    @Test fun newlyFoundPageConfigurationsAreNotQueuedBehindEveryOldSeed() = runBlocking {
        withRegistry { registry ->
            val requestedRoots = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                when (request.url.host) {
                    "page.example.test" -> response(request,
                        """<html><a href="https://fresh.example.test/config.json">新配置</a></html>""")
                    "fresh.example.test" -> {
                        synchronized(requestedRoots) { requestedRoots += "fresh" }
                        response(request, singleConfig)
                    }
                    else -> {
                        synchronized(requestedRoots) { requestedRoots += request.url.encodedPath }
                        Thread.sleep(200)
                        response(request, """{"sites":[]}""")
                    }
                }
            }.build()
            val sources = MovieCatalogSourceDiscovery(context, client, registry,
                initialSeeds = (1..24).map { MovieDiscoverySeed("旧$it", "https://seed.example.test/root$it.json") },
                sourcePages = listOf("https://page.example.test/list.html")).discover()
            assertEquals(2, sources.size)
            assertTrue("New page discoveries must enter the first worker batch after priority roots: $requestedRoots",
                requestedRoots.indexOf("fresh") in 0..6)
        }
    }

    @Test fun realDecoderTraversesNestedRepositoriesAndRegistersOnlyCompatibleHttpProviders() = runBlocking {
        withRegistry { registry ->
            val requests = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                synchronized(requests) { requests.add(chain.request().url.encodedPath) }
                val body = when (chain.request().url.encodedPath) {
                    "/root.json" -> """{"urls":[{"name":"单仓","url":"/single.json"},{"name":"环","url":"/root.json"}]}"""
                    else -> "**" + Base64.getEncoder().encodeToString(singleConfig.toByteArray())
                }
                response(chain.request(), body)
            }.build()
            val discovery = MovieCatalogSourceDiscovery(context, client, registry,
                initialSeeds = listOf(MovieDiscoverySeed("Root", "https://config.example.test/root.json")), sourcePages = emptyList())
            val sources = discovery.discover()
            assertEquals(2, sources.size)
            assertTrue(sources.all { it.apiUrl.startsWith("https://") && it.name.startsWith("发现·") })
            assertTrue(requests.containsAll(listOf("/root.json", "/single.json")))
            assertEquals(1, requests.count { it == "/root.json" })
            val before = requests.size
            assertEquals(sources, discovery.discover())
            assertEquals("Fresh discovery should use the persisted cache", before, requests.size)
        }
    }

    @Test fun sourcePageCanDiscoverARawGithubConfigWithoutRunningItsPluginReferences() = runBlocking {
        withRegistry { registry ->
            val urls = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                synchronized(urls) { urls.add(chain.request().url.toString()) }
                response(chain.request(), if (chain.request().url.host == "page.example.test")
                    """<html><a href="https://raw.githubusercontent.com/example/repo/main/config.json">配置</a>
                        <a href="https://example.test/provider.jar">插件</a></html>""" else singleConfig)
            }.build()
            val result = MovieCatalogSourceDiscovery(context, client, registry, initialSeeds = emptyList(),
                sourcePages = listOf("https://page.example.test/list.html")).discover()
            assertEquals(2, result.size)
            assertTrue(urls.any { it.startsWith("https://raw.githubusercontent.com/") })
            assertFalse(urls.any { it.endsWith(".jar") || it.endsWith(".js") })
        }
    }

    @Test fun cancellationStopsScanningAndRetainsPreviouslyRegisteredCompleteResults() = runBlocking {
        withRegistry { registry ->
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).addInterceptor { chain ->
                if (chain.request().url.encodedPath != "/slow.json") response(chain.request(), singleConfig)
                else {
                    val body = object : ResponseBody() {
                        private val input = object : Source {
                            override fun read(sink: Buffer, byteCount: Long): Long { reading.countDown(); release.await(10, TimeUnit.SECONDS); return -1 }
                            override fun timeout(): Timeout = Timeout.NONE
                            override fun close() { release.countDown() }
                        }.buffer()
                        override fun contentType() = "application/json".toMediaType()
                        override fun contentLength() = -1L
                        override fun source(): BufferedSource = input
                    }
                    response(chain.request(), "").newBuilder().body(body).build()
                }
            }.build()
            val discovery = MovieCatalogSourceDiscovery(context, client, registry,
                initialSeeds = listOf(MovieDiscoverySeed("Fast", "https://config.example.test/fast.json"),
                    MovieDiscoverySeed("Slow", "https://config.example.test/slow.json")), sourcePages = emptyList())
            try {
                val job = launch(Dispatchers.Default) { discovery.discover() }
                assertTrue(reading.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) { while (registry.all().isEmpty()) delay(10) }
                withTimeout(5_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                assertEquals("Completed discoveries survive a short UI timeout", 2, registry.all().size)
                assertFalse("External cancellation must not cache a completed scan", registry.isDiscoveryCacheFresh())
            } finally { release.countDown() }
        }
    }

    @Test fun failedDiscoveryUsesTheShortCacheAndForceCanRetryImmediately() = runBlocking {
        withRegistry { registry ->
            val calls = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet(); response(chain.request(), "unavailable", 503)
            }.build()
            val discovery = MovieCatalogSourceDiscovery(context, client, registry,
                initialSeeds = listOf(MovieDiscoverySeed("Fail", "https://config.example.test/fail.json")), sourcePages = emptyList())
            assertTrue(discovery.discover().isEmpty())
            val before = calls.get()
            assertTrue(discovery.discover().isEmpty())
            assertEquals(before, calls.get())
            discovery.discover(force = true)
            assertTrue(calls.get() > before)
        }
    }

    private suspend fun withRegistry(block: suspend (MovieCatalogSourceRegistry) -> Unit) {
        val directory = File.createTempFile("catalog-discovery-test-", "", context.cacheDir).apply { delete(); mkdirs() }
        try { block(MovieCatalogSourceRegistry(directory)) }
        finally { directory.deleteRecursively() }
    }

    private fun response(request: Request, body: String, status: Int = 200) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body.toResponseBody("text/html; charset=utf-8".toMediaType())).build()

    private val singleConfig = """{"sites":[
        {"name":"独立CMS","type":1,"api":"https://cms.example.test/api.php/provide/vod/"},
        {"name":"原始API扩展","type":3,"api":"csp_Provider","ext":"https://ext.example.test/api.php/provide/vod/"},
        {"name":"不可执行插件","type":3,"api":"csp_Spider","ext":"https://example.test/rules.js"},
        {"name":"本地不可用","type":1,"api":"http://127.0.0.1/api.php/provide/vod/"}
    ]}"""
}
