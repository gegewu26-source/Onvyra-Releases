package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public catalog metadata is independent of the user's selected reading sources. */
class ReaderStoreRepository(client: OkHttpClient = OkHttpClient(), private val cacheDir: File? = null) {
    private val client = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(16, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val homeGates = mapOf("male" to Mutex(), "female" to Mutex())
    private val memory = mutableMapOf<String, CachedHome>()
    private val permits = Semaphore(4)
    private val bookGates = List(32) { Mutex() }
    private val bookMemory = object : LinkedHashMap<String, CachedBooks>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBooks>) = size > 128
    }
    @Serializable private data class CachedHome(val at: Long, val home: ReaderStoreHome, val schema: Int = 1)
    @Serializable private data class CachedBooks(val at: Long, val books: List<NovelBook>, val more: Boolean, val schema: Int = 1)

    /** Publish the first usable books without waiting for unrelated lower sections. */
    suspend fun home(channel: String, force: Boolean = false,
        onUpdate: (suspend (ReaderStoreHome) -> Unit)? = null,
    ): ReaderStoreHome = withContext(Dispatchers.IO) {
        require(channel == "male" || channel == "female") { "未知书城分类" }
        homeGates.getValue(channel).withLock {
            val cached = cachedHome(channel)
            if (cached != null) {
                val fresh = System.currentTimeMillis() - cached.at in 0 until CACHE_TTL
                val visible = cached.home.copy(stale = !fresh,
                    notice = if (fresh) cached.home.notice else "正在更新书城，先显示上次保存的书目")
                onUpdate?.invoke(visible)
                if (!force && fresh) return@withLock visible
            }
            try {
                val categories = categories(channel)
                if (categories.isEmpty()) throw IOException("书城没有返回当前频道的分类")
                val results = arrayOfNulls<List<NovelBook>>(categories.size)
                val featured = mutableListOf<NovelBook>()
                fun snapshot(final: Boolean): ReaderStoreHome {
                    val sections = categories.mapIndexedNotNull { index, category ->
                        results[index]?.takeIf { it.isNotEmpty() }?.let {
                            ReaderStoreSection(category, it, horizontal = index % 2 == 0)
                        }
                    }
                    // Keep the initial hero stable while later categories arrive.
                    sections.mapNotNull { it.books.firstOrNull() }.forEach { book ->
                        if (featured.size < 6 && featured.none { it.id == book.id }) featured += book
                    }
                    val missing = categories.size - sections.size
                    return ReaderStoreHome(channel, featured.toList(), sections,
                        notice = if (final && missing > 0) "$missing 个分区暂未加载，点击刷新重试" else null,
                        categories = categories)
                }
                coroutineScope {
                    val completed = Channel<Pair<Int, List<NovelBook>>>(categories.size)
                    try {
                        categories.forEachIndexed { index, category -> async {
                            val books = permits.withPermit {
                                try { requestBooks(category, 0, HOME_LIMIT, force).first }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (_: IOException) { emptyList() }
                            }
                            completed.send(index to books)
                        } }
                        repeat(categories.size) {
                            val (index, books) = completed.receive()
                            results[index] = books
                            if (cached == null && results.any { !it.isNullOrEmpty() }) onUpdate?.invoke(snapshot(false))
                        }
                    } finally { completed.close() }
                }
                val home = snapshot(true)
                if (home.sections.isEmpty()) throw IOException("书城分类书目暂时未能加载")
                val stored = CachedHome(System.currentTimeMillis(), home)
                // A partial response is visible now but must retry missing sections on the next open.
                if (home.sections.size == categories.size) {
                    synchronized(memory) { memory[channel] = stored }
                    saveHome(channel, stored)
                }
                home.also { onUpdate?.invoke(it) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: IOException) {
                cached?.home?.copy(stale = true, notice = "书城连接失败，正在显示上次保存的书目；可刷新重试")
                    ?.also { onUpdate?.invoke(it) } ?: throw error
            }
        }
    }

    suspend fun category(category: ReaderStoreCategory, page: Int, force: Boolean = false): ReaderStoreCategoryPage = withContext(Dispatchers.IO) {
        require(page > 0 && page <= 10_000) { "书目页码无效" }
        require(category.channel == "male" || category.channel == "female") { "未知书城分类" }
        val start = (page - 1) * PAGE_LIMIT
        val (books, more) = requestBooks(category, start, PAGE_LIMIT, force)
        ReaderStoreCategoryPage(category, books, page, more)
    }

    private suspend fun categories(channel: String): List<ReaderStoreCategory> {
        val url = "$BASE/tag/statics".toHttpUrl().newBuilder()
            .addQueryParameter("packageName", PACKAGE).addQueryParameter("type", "")
            .addQueryParameter("sex", channel).build().toString()
        val data = fetch(url)
        val first = data["firstTags"] as? JsonArray ?: throw IOException("书城分类格式不正确")
        return first.filterIsInstance<JsonObject>().filter { it.text("tagChannel").substringBefore('_') == channel }
            .flatMap { item ->
                val groups = (item["secondTags"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val primary = groups.firstOrNull { it.text("secondTagName") == "热门" } ?: groups.firstOrNull()
                (primary?.get("thirdTags") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { tag ->
                    val name = tag.text("name"); val alias = tag.text("alias")
                    if (name.isBlank() || alias.isBlank()) null else ReaderStoreCategory(alias, name, channel)
                }
            }.distinctBy { it.id }.take(24)
    }

    private suspend fun requestBooks(category: ReaderStoreCategory, start: Int, limit: Int, force: Boolean = false): Pair<List<NovelBook>, Boolean> {
        val key = "${category.channel}-${category.id}-$start-$limit"
        return bookGates[(key.hashCode() and Int.MAX_VALUE) % bookGates.size].withLock {
            if (!force) cachedBooks(key)?.let { return@withLock it.books to it.more }
            val result = fetchBooks(category, start, limit)
            // An empty home section may be a transient upstream response: retry it next time.
            if (result.first.isNotEmpty() || limit == PAGE_LIMIT) {
                val cached = CachedBooks(System.currentTimeMillis(), result.first, result.second)
                synchronized(bookMemory) { bookMemory[key] = cached }
                saveBooks(key, cached)
            }
            result
        }
    }

    private suspend fun fetchBooks(category: ReaderStoreCategory, start: Int, limit: Int): Pair<List<NovelBook>, Boolean> {
        val url = "$BASE/tag/fuzzy-search".toHttpUrl().newBuilder()
            .addQueryParameter("query", category.title).addQueryParameter("packageName", PACKAGE)
            .addQueryParameter("sort", "1").addQueryParameter("start", start.toString())
            .addQueryParameter("limit", limit.toString()).addQueryParameter("alias", category.id)
            .addQueryParameter("gender", category.channel).addQueryParameter("isTagConditionAnd", "true")
            .build().toString()
        val data = fetch(url)
        val rows = data["books"] as? JsonArray ?: throw IOException("书城没有返回分类书目")
        val books = rows.filterIsInstance<JsonObject>().mapNotNull { row ->
            val id = row.text("_id"); val title = row.text("title"); val author = row.text("author")
            if (id.isBlank() || title.isBlank() || author.isBlank()) return@mapNotNull null
            NovelBook(id = "catalog_$id", title = title, author = author,
                coverUrl = absoluteCover(row.text("cover")), detailUrl = "$BASE/book/$id",
                sourceId = NovelSourceEngine.SOURCE_CATALOG, latestChapter = row.text("lastChapter"),
                category = row.text("minorCate").ifBlank { row.text("majorCate") }.ifBlank { category.title },
                status = when ((row["isSerial"] as? JsonPrimitive)?.booleanOrNull) { true -> "连载中"; false -> "已完结"; null -> "" },
                description = row.text("longIntro").ifBlank { row.text("shortIntro") }, coverReferer = "http://statics.zhuishushenqi.com/",
                canonicalTitle = originalTitle(row),
                sourceVariables = mapOf("catalog.id" to id, "catalog.channel" to category.channel,
                    "catalog.category" to category.id))
        }.distinctBy { it.id }
        val total = (data["total"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
        val more = rows.isNotEmpty() && (if (total != null) start + rows.size < total else rows.size >= limit)
        return books to more
    }

    private fun originalTitle(row: JsonObject): String {
        val explicit = listOf("originalTitle", "originalName", "originTitle", "originName")
            .asSequence().map { row.text(it).trim() }.firstOrNull { it.isNotBlank() && it.length <= 300 }
        if (explicit != null) return explicit
        // The actual publisher API uses aliases as a string (often empty).
        // A single explicit alias is usable metadata; an ambiguous list must not
        // silently pick a different work identity.
        val aliases = when (val value = row["aliases"]) {
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotBlank() && it.length <= 300 && '\n' !in it && '\r' !in it }
            .distinctBy(ReaderBookIdentity::normalize)
        return aliases.singleOrNull().orEmpty()
    }

    private fun absoluteCover(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        // Keep /agent/http%3A... encoded; decoding it corrupts the catalog image proxy path.
        return "http://statics.zhuishushenqi.com/".toHttpUrl().resolve(value)?.toString().orEmpty()
    }

    private suspend fun fetch(url: String): JsonObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("书城连接失败", e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) throw IOException("书城返回 HTTP ${it.code}")
                        val body = it.body
                        val source = body.source(); source.request(MAX_BYTES + 1)
                        if (source.buffer.size > MAX_BYTES) throw IOException("书城响应过大")
                        val root = json.parseToJsonElement(source.readUtf8()) as? JsonObject
                            ?: throw IOException("书城返回格式不正确")
                        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull == false) throw IOException("书城未能返回书目")
                        root
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(if (e is IOException) e else IOException("书城返回格式不正确",e))
                }
            }
        })
    }

    private fun cacheFile(channel: String) = cacheDir?.let { File(it, "storefront-$channel-v1.json") }
    private fun cachedHome(channel: String): CachedHome? {
        synchronized(memory) { memory[channel] }?.let { return it }
        return runCatching {
            cacheFile(channel)?.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.let {
                json.decodeFromString<CachedHome>(it.readText()).takeIf { value ->
                    value.schema == 1 && value.home.channel == channel && value.home.sections.isNotEmpty() &&
                        System.currentTimeMillis() - value.at in 0 until MAX_STALE
                }
            }
        }.getOrNull()?.also { synchronized(memory) { memory[channel] = it } }
    }
    private fun saveHome(channel: String, value: CachedHome) {
        val destination = cacheFile(channel) ?: return
        try {
            destination.parentFile?.mkdirs()
            val temporary = File.createTempFile("store-", ".tmp", destination.parentFile)
            try {
                temporary.writeText(json.encodeToString(value))
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        } catch (_: Exception) { /* A cache write failure does not hide a successful catalog response. */ }
    }
    private fun booksFile(key: String) = cacheDir?.let {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        File(it, "category-$digest-v1.json")
    }
    private fun cachedBooks(key: String): CachedBooks? {
        synchronized(bookMemory) { bookMemory[key] }?.let {
            if (System.currentTimeMillis() - it.at in 0 until PAGE_TTL) return it
        }
        return runCatching {
            booksFile(key)?.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.let {
                json.decodeFromString<CachedBooks>(it.readText()).takeIf { value ->
                    value.schema == 1 && System.currentTimeMillis() - value.at in 0 until PAGE_TTL
                }
            }
        }.getOrNull()?.also { synchronized(bookMemory) { bookMemory[key] = it } }
    }
    private fun saveBooks(key: String, value: CachedBooks) {
        val destination = booksFile(key) ?: return
        try {
            destination.parentFile?.mkdirs()
            val temporary = File.createTempFile("store-category-", ".tmp", destination.parentFile)
            try {
                temporary.writeText(json.encodeToString(value))
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            // Category paging should not grow an unbounded disk cache.
            destination.parentFile?.listFiles { f -> f.name.startsWith("category-") && f.name.endsWith("-v1.json") }
                ?.sortedByDescending { it.lastModified() }?.drop(128)?.forEach { it.delete() }
        } catch (_: Exception) { /* Cache persistence must not hide a usable category. */ }
    }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    companion object {
        private const val BASE = "http://b02.zhuishushenqi.com"
        private const val PACKAGE = "com.ushaqi.zhuishushenqi"
        private const val PAGE_LIMIT = 20
        private const val HOME_LIMIT = 6
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val PAGE_TTL = 10L * 60 * 1000
        private const val CACHE_TTL = 24L * 60 * 60 * 1000
        private const val MAX_STALE = 7L * CACHE_TTL
    }
}
