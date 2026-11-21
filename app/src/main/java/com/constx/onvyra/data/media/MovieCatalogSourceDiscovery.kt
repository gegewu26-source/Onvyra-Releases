package com.constx.onvyra.data.media

import android.content.Context
import com.example.configdiscovery.ConfigDecoder
import com.example.configdiscovery.ConfigDiscovery
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.constx.onvyra.data.ai.tools.PublicWebNetwork
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal data class MovieDiscoverySeed(val name: String, val url: String)

/** Discovers plain HTTP catalogs from configuration data; provider JAR/JS code is never loaded. */
internal class MovieCatalogSourceDiscovery(
    context: Context,
    client: OkHttpClient,
    private val registry: MovieCatalogSourceRegistry = MovieCatalogSourceRegistry.get(context),
    private val initialSeeds: List<MovieDiscoverySeed>? = null,
    private val sourcePages: List<String>? = null,
    private val totalTimeoutMs: Long = 35_000,
    private val rootTimeoutMs: Long = 15_000,
) {
    private val appContext = context.applicationContext
    private val network = PublicWebNetwork(client)

    init { require(totalTimeoutMs in 1..120_000 && rootTimeoutMs in 1..60_000) }

    fun warmUp(): kotlinx.coroutines.Job? = MovieCatalogWarmup.start(registry) { discover() }

    fun inventory(catalog: MovieCatalogClient): MovieCatalogSourceInventory {
        val bundled = loadBundledDiscoverySeeds()
        val eligible = catalog.sourceIdsForSearch()
        return MovieCatalogSourceInventory(
            (initialSeeds ?: bundled.first).size, (sourcePages ?: bundled.second).size,
            catalog.builtInSourceIds().size, registry.all().size, eligible.size, eligible,
        )
    }

    suspend fun discover(force: Boolean = false): List<MovieCatalogSource> = registry.discoveryMutex.withLock {
        if (!force && registry.isDiscoveryCacheFresh()) return@withLock registry.all()
        withContext(Dispatchers.IO) {
            val bundled = loadBundledDiscoverySeeds()
            val recent = registry.recentConfigUrls()
            val seeds = (recent.map { MovieDiscoverySeed("最近发现", it) } + (initialSeeds ?: bundled.first))
                .mapNotNull { seed -> safeDiscoveryConfigUrl(seed.url)?.let { seed.copy(url = it) } }
                .distinctBy { it.url }
                .sortedByDescending { discoveryUrlPriority(it.url, it.url in recent) }
                .take(96)
            val pages = (sourcePages ?: bundled.second).mapNotNull(::safeDiscoveryConfigUrl).distinct()
                .sortedByDescending { discoveryUrlPriority(it) }.take(4)
            val found = LinkedHashMap<String, MovieCatalogSource>()
            val successfulConfigs = LinkedHashSet<String>()
            val resultsLock = Any()
            val prefetched = LinkedHashMap<String, ConfigDiscovery.Response>()
            val prefetchLock = Any()
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs)

            fun consumeConfig(bytes: ByteArray, contentType: String?, finalUrl: String) {
                val decoded = ConfigDecoder.decode(bytes, contentType)
                if (!decoded.isConfig || decoded.kind != ConfigDecoder.SINGLE) return
                val sites = decoded.`object`?.optJSONArray("sites") ?: return
                val records = (0 until minOf(sites.length(), 512)).mapNotNull { index ->
                    val site = sites.optJSONObject(index) ?: return@mapNotNull null
                    fun field(name: String) = (site.opt(name) as? String)?.take(8_192).orEmpty()
                    MovieDiscoverySite(field("name"), field("api"), field("ext"), site.opt("type")?.toString()?.toIntOrNull())
                }
                val sources = movieCatalogSourcesFromSites(records, finalUrl)
                if (sources.isNotEmpty()) {
                    val additions = synchronized(resultsLock) {
                        val added = sources.filter { it.id !in found }.take((128 - found.size).coerceAtLeast(0))
                        added.forEach { found[it.id] = it }
                        successfulConfigs.add(finalUrl)
                        added
                    }
                    // A short UI discovery window may be cancelled after finding useful results.
                    // Retain complete, validated HTTP entries as they arrive, not just after all roots finish.
                    if (additions.isNotEmpty()) registry.register(additions)
                }
            }

            withTimeoutOrNull(totalTimeoutMs) {
                coroutineScope {
                    val queue = Channel<MovieDiscoverySeed>(128)
                    launch {
                        try {
                            // Do not place newly found configurations behind every old address.
                            // A fixed deadline would otherwise expire before a page discovery is
                            // ever examined when the bundle contains many stale roots.
                            seeds.take(6).forEach { queue.send(it) }
                            val discoveredFromPages = pages.map { pageUrl -> async {
                                try {
                                    withTimeoutOrNull(minOf(8_000L, totalTimeoutMs)) {
                                        val started = System.nanoTime()
                                        val page = network.read(pageUrl)
                                        if (page.status !in 200..299 || page.sourceTruncated) return@withTimeoutOrNull emptyList()
                                        currentCoroutineContext().ensureActive()
                                        val response = ConfigDiscovery.Response(page.bytes, page.contentType?.toString(),
                                            page.finalUrl, page.status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                                        synchronized(prefetchLock) { prefetched[pageUrl] = response }
                                        consumeConfig(page.bytes, page.contentType?.toString(), page.finalUrl)
                                        val decoded = ConfigDecoder.decode(page.bytes, page.contentType?.toString())
                                        if (decoded.isConfig) listOf(MovieDiscoverySeed("来源页面配置", pageUrl))
                                        else extractMovieDiscoveryConfigLinks(ConfigDecoder.decodeText(page.bytes, page.contentType?.toString()), page.finalUrl)
                                            .map { MovieDiscoverySeed("来源页面发现", it) }
                                    }.orEmpty()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { emptyList() }
                            } }.awaitAll().flatten().distinctBy { it.url }.take(64)
                            discoveredFromPages.filterNot { candidate -> seeds.any { it.url == candidate.url } }.forEach { queue.send(it) }
                            seeds.drop(6).forEach { queue.send(it) }
                        } finally { queue.close() }
                    }
                    repeat(6) {
                        launch(Dispatchers.IO) {
                            for (seed in queue) {
                                currentCoroutineContext().ensureActive()
                                val parent = currentCoroutineContext()
                                lateinit var scanner: ConfigDiscovery
                                val fetcher = ConfigDiscovery.Fetcher { requestedUrl, timeoutMs, maxBytes, scannerDeadline ->
                                    try {
                                        parent.ensureActive()
                                        runBlocking(parent) {
                                            val remainingNanos = minOf(deadlineNanos, scannerDeadline) - System.nanoTime()
                                            if (remainingNanos <= 0) throw IOException("配置发现阶段超时")
                                            withTimeout(minOf(timeoutMs.toLong(), TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1))) {
                                                val cached = synchronized(prefetchLock) { prefetched[requestedUrl] }
                                                val response = cached ?: run {
                                                    val started = System.nanoTime()
                                                    val page = network.read(requestedUrl)
                                                    if (page.sourceTruncated || page.bytes.size > maxBytes) throw IOException("配置响应过大")
                                                    ConfigDiscovery.Response(page.bytes, page.contentType?.toString(), page.finalUrl,
                                                        page.status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                                                }
                                                currentCoroutineContext().ensureActive()
                                                if (response.status in 200..299) consumeConfig(response.body, response.contentType, response.finalUrl)
                                                response
                                            }
                                        }
                                    } catch (cancelled: CancellationException) { scanner.cancel(); throw cancelled }
                                }
                                val options = ConfigDiscovery.Options().apply {
                                    maxDepth = 3; maxChildren = 8; maxNodes = 24; maxPages = 1
                                    deadlineMs = minOf(rootTimeoutMs, totalTimeoutMs)
                                }
                                scanner = ConfigDiscovery(fetcher, options)
                                try { scanner.scan(listOf(ConfigDiscovery.Seed(seed.name, seed.url)), emptyList()) }
                                catch (cancelled: CancellationException) { scanner.cancel(); throw cancelled }
                                catch (_: Exception) { /* A failed configuration does not stop independent roots. */ }
                                finally { scanner.cancel() }
                                // Java ConfigDiscovery catches Exception. Preserve the coroutine's cancellation contract.
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val sources = synchronized(resultsLock) { found.values.toList() }
            registry.recordDiscoveryAttempt(sources.isNotEmpty(), synchronized(resultsLock) { successfulConfigs.toList() })
            registry.all()
        }
    }

    private fun loadBundledDiscoverySeeds(): Pair<List<MovieDiscoverySeed>, List<String>> {
        if (initialSeeds != null && sourcePages != null) return initialSeeds to sourcePages
        return runCatching {
            val bytes = appContext.assets.open("movie_catalog/sources.json").use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (output.size() <= 128 * 1024) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 128 * 1024 + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                require(output.size() <= 128 * 1024)
                output.toByteArray()
            }
            decodeBundledMovieDiscoverySources(bytes.toString(Charsets.UTF_8))
        }.getOrElse { emptyList<MovieDiscoverySeed>() to emptyList() }
    }
}

/** Shared by discovery and inventory, so the tool reports the bundle actually loaded by this APK. */
internal fun decodeBundledMovieDiscoverySources(content: String): Pair<List<MovieDiscoverySeed>, List<String>> {
    val root = Json.parseToJsonElement(content) as JsonObject
    fun text(value: kotlinx.serialization.json.JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val seeds = (root["base_lines"] as? JsonArray).orEmpty().mapNotNull { value ->
        val row = value as? JsonObject ?: return@mapNotNull null
        MovieDiscoverySeed(text(row["name"]).orEmpty(), text(row["url"]) ?: return@mapNotNull null)
    }
    return seeds to (root["search_pages"] as? JsonArray).orEmpty().mapNotNull(::text)
}

internal data class MovieDiscoverySite(val name: String, val api: String, val ext: String = "", val type: Int? = null)

/** Only independent HTTP JSON APIs can enter the native catalog client. */
internal fun movieCatalogSourcesFromSites(sites: List<MovieDiscoverySite>, configUrl: String): List<MovieCatalogSource> {
    val base = safeDiscoveryConfigUrl(configUrl)?.toHttpUrlOrNull() ?: return emptyList()
    val recognizedApi = Regex("(?:/api\\.php/(?:provide|provideo)/vod|/provide/vod|/api\\.php\\?(?:[^#]*&)?ac=)", RegexOption.IGNORE_CASE)
    fun actualApi(value: String): String? {
        if (value.isBlank() || value.length > 4_096 || value.trim().startsWith("csp_", true)) return null
        if (!value.trim().startsWith("http://", true) && !value.trim().startsWith("https://", true) && !value.trim().startsWith('/')) return null
        val resolved = base.resolve(value.trim()) ?: return null
        return canonicalDiscoveredMovieApi(resolved.toString())
    }
    return sites.take(512).mapNotNull { site ->
        val api = when {
            site.type in setOf(1, 4) -> actualApi(site.api)
            recognizedApi.containsMatchIn(site.api) -> actualApi(site.api)
            else -> null
        } ?: site.ext.takeIf { recognizedApi.containsMatchIn(it) }?.let(::actualApi) ?: return@mapNotNull null
        val label = site.name.replace(Regex("[\\s\\u0000-\\u001f]+"), " ").trim().take(80)
            .ifBlank { api.toHttpUrlOrNull()?.host.orEmpty() }
        MovieCatalogSource(discoveredMovieSourceId(api), "发现·$label", api)
    }.filter { isAllowedCatalogSource(it) && !isExcludedMovieCatalogSource(it) }.distinctBy { it.id }.take(128)
}

/** Unlike the package's broad site blacklist, raw GitHub/GitLab configuration URLs remain eligible. */
internal fun extractMovieDiscoveryConfigLinks(text: String, pageUrl: String): List<String> {
    val base = safeDiscoveryConfigUrl(pageUrl)?.toHttpUrlOrNull() ?: return emptyList()
    val candidates = LinkedHashSet<String>()
    fun add(value: String) {
        if (candidates.size >= 48) return
        val raw = Parser.unescapeEntities(value.trim().trimEnd('.', ',', ';', '，', '。', '；', '：', ')', ']', '}', '>', '"'), false)
        val url = base.resolve(raw)?.toString()?.let(::safeDiscoveryConfigUrl) ?: return
        val parsed = url.toHttpUrlOrNull() ?: return
        val path = parsed.encodedPath.lowercase()
        if (Regex("\\.(?:png|jpe?g|gif|webp|svg|ico|css|js|jar|zip|mp4|mp3|m3u8|woff2?|ttf)$").containsMatchIn(path)) return
        val rawRepository = parsed.host == "raw.githubusercontent.com" || "/-/raw/" in path || "/raw/" in path
        val hinted = path.endsWith(".json") || path.endsWith(".txt") ||
            listOf("/tv", "/box", "/api", "/dc", "tvbox", "catvod", "dcang", "配置", "接口").any { it in path }
        if (rawRepository || hinted) candidates.add(url)
    }
    Jsoup.parse(text, base.toString()).select("a[href]").take(128).forEach { add(it.attr("href")) }
    Regex("https?://[^\\s\"'<>()\\[\\]]+", RegexOption.IGNORE_CASE).findAll(text.replace("\\/", "/")).take(128)
        .forEach { add(it.value) }
    return candidates.toList().sortedByDescending { discoveryUrlPriority(it) }
}

private fun discoveryUrlPriority(value: String, recent: Boolean = false): Int {
    val url = value.toHttpUrlOrNull() ?: return 0
    return (if (recent) 1_000 else 0) + (if (url.isHttps) 100 else 0) +
        (if (url.host == "raw.githubusercontent.com") 80 else if ("/-/raw/" in url.encodedPath || "/raw/" in url.encodedPath) 60 else 0)
}
