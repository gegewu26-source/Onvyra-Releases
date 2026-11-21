package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Uses the same anonymous, read-only requests as biquge.skin/assets/data.js. */
internal class SkinNovelSource(private val client: OkHttpClient, private val cacheDir: File? = null,
    private val seedIndex: (() -> InputStream)? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val metadata = ConcurrentHashMap<Long, JsonObject>()
    private val source = NovelHtmlSource("biquge_skin", "笔趣阁 Skin", BASE, "/", "/search", "q")
    private val searchMutex = Mutex()
    private val diskLock = Any()
    private data class SearchResult(val at: Long, val books: List<NovelBook>)
    private val searchResults = object : LinkedHashMap<String, SearchResult>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchResult>): Boolean =
            size > MAX_CACHED_SEARCHES
    }

    @Volatile private var mappedIndex: SkinSearchIndex? = null
    private var indexRefresh: Job? = null
    private val refreshScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private var browseGeneration: String? = null
    private var browseBooks: List<NovelBook> = emptyList()

    /** Real source metadata is ready locally before any homepage or cover request. */
    suspend fun discoverBooks(): List<NovelBook> = withContext(Dispatchers.IO) {
        searchMutex.withLock {
            if (mappedIndex == null) mappedIndex = loadLocalIndex()
            if (mappedIndex == null) throw IOException("本地书库尚未准备好，请稍后重试")
            val index = currentIndex()
            if (browseGeneration == index.generation && browseBooks.isNotEmpty()) return@withLock browseBooks
            val job = currentCoroutineContext()
            index.browse(36) { job.ensureActive() }.map(::indexedBook).also {
                browseGeneration = index.generation
                browseBooks = it
            }
        }
    }

    private suspend fun currentIndex(): SkinSearchIndex {
        val index = mappedIndex ?: loadLocalIndex()?.also { mappedIndex = it }
            ?: buildSearchIndex().also { mappedIndex = it }
        if (!fresh(index.createdAt) && indexRefresh?.isActive != true) {
            indexRefresh = refreshScope.launch {
                try {
                    val updated = buildSearchIndex()
                    searchMutex.withLock {
                        mappedIndex = updated; searchResults.clear(); browseGeneration = null; browseBooks = emptyList()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { /* Keep the complete previous index when offline. */ }
            }
        }
        return index
    }

    private fun indexedBook(row: SkinSearchIndex.Row): NovelBook {
        val url = "$BASE/novel?id=${row.id}"
        metadata.putIfAbsent(row.id, JsonObject(mapOf(
            "id" to JsonPrimitive(row.id), "title" to JsonPrimitive(row.title),
            "author" to JsonPrimitive(row.author), "cover_url" to JsonPrimitive(row.cover),
            "category" to JsonPrimitive(row.category), "chapter_count" to JsonPrimitive(row.chapters),
            "source_book_id" to JsonPrimitive(row.sourceBookId.takeIf { it > 0 }?.toString().orEmpty()),
            "_search_index" to JsonPrimitive(1),
        )))
        return NovelBook(id = NovelHtmlParser.stableId(source.id, url), title = row.title, author = row.author,
            coverUrl = row.cover, detailUrl = url, sourceId = source.id, category = row.category,
            chapterCount = row.chapters, description = row.description, coverReferer = url,
            status = "", latestChapter = "", updateTime = "")
    }

    /** Uses a compact local metadata index; a new keyword never re-parses all source shards. */
    suspend fun searchBooks(keyword: String): List<NovelBook> = withContext(Dispatchers.IO) {
        val query = keyword.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return@withContext emptyList()
        searchMutex.withLock {
            currentCoroutineContext().ensureActive()
            searchResults[query]?.takeIf { fresh(it.at) }?.let { return@withLock it.books.toList() }
            val index = currentIndex()
            val job = currentCoroutineContext()
            val rows = index.search(query, MAX_SEARCH_RESULTS) { job.ensureActive() }
            val books = rows.map(::indexedBook)
                .distinctBy { it.title.trim() + "|" + it.author.substringBefore('，').substringBefore(',').trim() }
            searchResults[query] = SearchResult(System.currentTimeMillis(), books)
            books
        }
    }

    private fun loadLocalIndex(): SkinSearchIndex? {
        val target = cacheDir?.let { File(it, SkinSearchIndex.FILE_NAME) }
        target?.let { SkinSearchIndex.open(it) }?.let { return it }
        val seed = seedIndex ?: return null
        return try {
            if (target == null) {
                val bytes = seed().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        if (output.size() > SkinSearchIndex.MAX_BYTES) throw IOException("Skin 内置索引过大")
                    }
                    output.toByteArray()
                }
                SkinSearchIndex.decode(ByteBuffer.wrap(bytes))
            } else {
                target.parentFile?.mkdirs()
                val temporary = File.createTempFile("skin-index-seed-", ".tmp", target.parentFile)
                try {
                    seed().use { input -> temporary.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > SkinSearchIndex.MAX_BYTES) throw IOException("Skin 内置索引过大")
                            output.write(buffer, 0, count)
                        }
                    } }
                    // A mapped temporary file cannot be renamed on Windows.
                    val header = temporary.inputStream().use { input ->
                        val bytes = ByteArray(128)
                        val count = input.read(bytes)
                        if (count <= 0) null else SkinSearchIndex.decode(ByteBuffer.wrap(bytes, 0, count))
                    }
                    if (header == null) throw IOException("Skin 内置索引无效")
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    SkinSearchIndex.open(target)
                } finally { temporary.delete() }
            }
        } catch (_: IOException) { null }
    }

    private suspend fun buildSearchIndex(): SkinSearchIndex {
        val rawMeta = searchAsset("search-meta.json", "search-meta.json")
        val meta = rawMeta.value as? JsonObject ?: throw IOException("Skin 搜索配置格式异常")
        val chunks = meta.text("chunks").toIntOrNull()?.takeIf { it in 1..MAX_SEARCH_CHUNKS }
            ?: throw IOException("Skin 搜索分片配置无效")
        val chunkSize = meta.text("chunkSize").toIntOrNull()?.takeIf { it in 1..MAX_SEARCH_ROWS }
            ?: throw IOException("Skin 搜索分片大小无效")
        val generation = MessageDigest.getInstance("SHA-256").digest(rawMeta.raw.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        val target = cacheDir?.let { File(it, SkinSearchIndex.FILE_NAME) }
        target?.parentFile?.mkdirs()
        val temporary = target?.let { File.createTempFile("skin-index-build-", ".tmp", it.parentFile) }
        val memory = if (temporary == null) ByteArrayOutputStream() else null
        try {
            var count = 0
            SkinSearchIndex.Writer(temporary?.outputStream() ?: memory!!, generation, System.currentTimeMillis()).use { writer ->
                for (start in 0 until chunks step SEARCH_CONCURRENCY) {
                    currentCoroutineContext().ensureActive()
                    val batches = coroutineScope {
                        (start until minOf(chunks, start + SEARCH_CONCURRENCY)).map { index -> async {
                            val name = "search-docs-$index.json"
                            indexRows(searchAsset(name, "$generation-$name").value, chunkSize)
                        } }.awaitAll()
                    }
                    for (batch in batches) for (row in batch) writer.add(row)
                }
                count = writer.finish()
            }
            if (count == 0) throw IOException("Skin 搜索索引没有可用作品")
            if (temporary != null && target != null) {
                SkinSearchIndex.setCount(temporary, count)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return SkinSearchIndex.open(target) ?: throw IOException("Skin 搜索索引无法读取")
            }
            val bytes = memory!!.toByteArray()
            SkinSearchIndex.setCount(bytes, count)
            return SkinSearchIndex.decode(ByteBuffer.wrap(bytes)) ?: throw IOException("Skin 搜索索引无法读取")
        } finally { temporary?.delete() }
    }

    private suspend fun indexRows(root: JsonElement, expectedRows: Int): List<SkinSearchIndex.Row> {
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> sequenceOf("items", "docs", "rows", "novels").mapNotNull { root[it] as? JsonArray }.firstOrNull()
            else -> null
        } ?: throw IOException("Skin 搜索分片未返回作品列表")
        if (rows.size > expectedRows || rows.size > MAX_SEARCH_ROWS) throw IOException("Skin 搜索分片超出声明大小")
        return rows.mapIndexedNotNull { index, row ->
            if (index % 128 == 0) currentCoroutineContext().ensureActive()
            val data = row as? JsonObject ?: return@mapIndexedNotNull null
            val id = data.text("id").toLongOrNull()?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            if (data.pick("source_site", "s").trim().equals("41nr", true)) return@mapIndexedNotNull null
            val title = data.pick("title", "t").trim()
            if (title.isBlank()) return@mapIndexedNotNull null
            SkinSearchIndex.Row(id, title, data.pick("author", "a").trim(), searchCover(data.pick("cover_url", "cv")),
                data.pick("primary_category", "c").trim(), data.pick("chapter_count", "ch").toIntOrNull()?.coerceAtLeast(0) ?: 0,
                data.text("source_book_id").toLongOrNull() ?: 0, data.text("description"))
        }
    }

    private fun JsonObject.pick(long: String, short: String): String =
        (this[long] as? JsonPrimitive)?.contentOrNull
            ?: (this[short] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun searchCover(raw: String): String {
        val url = raw.trim().toHttpUrlOrNull() ?: return ""
        if (url.host in RETIRED_COVER_HOSTS ||
            ((url.host == "41nr.com" || url.host == "www.41nr.com") && url.encodedPath.startsWith("/image/"))) return ""
        // Preserve an actual source URL, including its default cover. Dropping those URLs
        // left every search hit blank even when the source supplied a loadable image.
        return url.toString()
    }

    private fun fresh(at: Long): Boolean = (System.currentTimeMillis() - at) in 0 until SEARCH_CACHE_TTL

    private data class SearchAsset(val raw: String, val value: JsonElement)
    private suspend fun searchAsset(name: String, cacheName: String): SearchAsset {
        currentCoroutineContext().ensureActive()
        readSearchCache(cacheName)?.let { raw ->
            try {
                return SearchAsset(raw, json.parseToJsonElement(raw))
            } catch (_: IllegalArgumentException) {
                // A truncated/corrupt cache is retried from the same official origin.
            }
        }
        val raw = getSearchAsset("$BASE/data/$name")
        val parsed = try {
            json.parseToJsonElement(raw)
        } catch (e: IllegalArgumentException) {
            throw IOException("Skin 搜索接口未返回有效 JSON，可能需要验证", e)
        }
        currentCoroutineContext().ensureActive()
        writeSearchCache(cacheName, raw)
        return SearchAsset(raw, parsed)
    }

    private fun readSearchCache(name: String): String? = synchronized(diskLock) {
        val directory = cacheDir ?: return@synchronized null
        try {
            val file = File(directory, name)
            if (!file.isFile || !fresh(file.lastModified()) || file.length() > MAX_BYTES) return@synchronized null
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    private fun writeSearchCache(name: String, raw: String) = synchronized(diskLock) {
        val directory = cacheDir ?: return@synchronized
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            temporary = File.createTempFile("skin-search-", ".tmp", directory)
            temporary.writeText(raw, Charsets.UTF_8)
            Files.move(temporary.toPath(), File(directory, name).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temporary = null
            // Only this adapter's fixed-format metadata files are eligible for eviction.
            val files = directory.listFiles()?.filter {
                it.name == "search-meta.json" || SEARCH_CACHE_FILE.matches(it.name)
            }.orEmpty().sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= MAX_SEARCH_CACHE_BYTES) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        } catch (_: IOException) {
            // Cache failure must not turn a successfully fetched official response into failure.
        } finally {
            temporary?.delete()
        }
    }

    private suspend fun getSearchAsset(url: String): String = readUrl(url, true)

    private suspend fun readUrl(url: String, searchAsset: Boolean): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Accept", "application/json").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val raw = response.use {
                        if (!it.isSuccessful) throw SkinHttpException(it.code)
                        val expectedHost = url.toHttpUrl().host
                        if (it.request.url.host != expectedHost || it.request.url.scheme != "https") {
                            throw IOException("Skin 接口重定向到其他来源，已停止加载")
                        }
                        val body = it.body ?: throw IOException("Skin 返回空搜索响应")
                        val stream = body.source()
                        stream.request(MAX_BYTES + 1)
                        if (stream.buffer.size > MAX_BYTES) throw IOException("Skin 响应过大，已停止加载")
                        stream.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                    }
                    if (continuation.isActive) continuation.resume(raw)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    suspend fun getBookDetail(book: NovelBook): NovelBook = withContext(Dispatchers.IO) {
        val id = bookId(book.detailUrl)
        val cachedMetadata = cachedCatalogData(id, false)?.get("metadata") as? JsonObject
        if (cachedMetadata?.text("id")?.toLongOrNull() == id && cachedMetadata.text("title") == book.title) {
            metadata[id] = cachedMetadata
        }
        val (doc, baseData) = metadata[id]?.let {
            NovelHtmlParser.document("", book.detailUrl) to it
        } ?: loadMetadata(id)
        // The complete search metadata already identifies this book. Do not block
        // the first chapter on an extra book-info round trip; the catalog endpoint
        // resolves a declared directory alias only when one is actually needed.
        val data = if (baseData.text("_search_index") == "1") baseData else withBookInfo(id, baseData)
        val canonical = NovelHtmlParser.bookUrl(doc.location(), source) ?: "$BASE/novel?id=$id"
        val title = data.text("title")
        if (title.isBlank()) throw IOException("Skin 未返回书籍信息，请重试或换源")
        val cover = data.text("cover_url").ifBlank { book.coverUrl }
        book.copy(
            id = NovelHtmlParser.stableId(source.id, canonical),
            title = title,
            author = data.text("author"),
            detailUrl = canonical,
            sourceId = source.id,
            coverUrl = cover,
            coverReferer = canonical,
            category = data.text("category").ifBlank { data.text("primary_category") },
            description = doc.selectFirst(".desc")?.let { section ->
                section.select("p").joinToString("\n\n") { it.text() }
            }.orEmpty().ifBlank { data.text("description") }.ifBlank { book.description },
            chapterCount = data.text("chapter_count").toIntOrNull() ?: 0,
            status = data.text("full").ifBlank { book.status },
            latestChapter = data.text("lastchapter").ifBlank { book.latestChapter },
            updateTime = data.text("lastupdate").ifBlank { book.updateTime },
        )
    }

    suspend fun getCatalog(bookUrl: String): List<NovelChapter> = withContext(Dispatchers.IO) {
        val id = bookId(bookUrl)
        readCatalog(id)?.let { return@withContext it }
        var data = knownMetadata(id)
        var result = api("booklist", linkedMapOf("id" to apiBookId(id, data)))
        if (result["list"] !is JsonArray) {
            // Some entries are aliases: the book endpoint explicitly returns dirid.
            // For example public book 77228 has its directory under 583.
            data = withBookInfo(id, data)
            result = api("booklist", linkedMapOf("id" to apiBookId(id, data)))
        }
        // The public endpoint returns the entire list. The website uses each list index + 1
        // as its chapter number; never manufacture a catalog from chapter_count.
        val list = result["list"] as? JsonArray
            ?: throw IOException("Skin 未返回完整目录，网页接口可能已变化")
        if (list.isEmpty()) throw IOException("Skin 章节目录为空，请重试或换源")
        val chapters = list.mapIndexed { index, item ->
            val title = (item as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) throw IOException("Skin 第 ${index + 1} 项目录缺少章节名")
            NovelChapter(index, title, "$BASE/reader?novelId=$id&chapter=${index + 1}")
        }
        writeCatalog(id, metadata[id] ?: data, list)
        chapters
    }

    fun invalidateCatalog(bookUrl: String) {
        val id = runCatching { bookId(bookUrl) }.getOrNull() ?: return
        synchronized(diskLock) { cacheDir?.let { File(it, "catalog-$id.json").delete() } }
    }

    private fun cachedCatalogData(id: Long, freshOnly: Boolean): JsonObject? = synchronized(diskLock) {
        val file = cacheDir?.let { File(it, "catalog-$id.json") } ?: return@synchronized null
        try {
            if (!file.isFile || file.length() !in 1..8L * 1024 * 1024) return@synchronized null
            val age = System.currentTimeMillis() - file.lastModified()
            if (age < 0 || age > 7L * 24 * 60 * 60 * 1000 || (freshOnly && age > 300_000L)) return@synchronized null
            val data = json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: return@synchronized null
            if (data.text("id").toLongOrNull() != id || data.text("schema") != "1") return@synchronized null
            data
        } catch (_: Exception) { null }
    }

    private fun readCatalog(id: Long): List<NovelChapter>? {
        val data = cachedCatalogData(id, true) ?: return null
        val cachedMetadata = data["metadata"] as? JsonObject ?: return null
        if (cachedMetadata.text("id").toLongOrNull() != id || cachedMetadata.text("title").isBlank()) return null
        val list = data["list"] as? JsonArray ?: return null
        if (list.isEmpty()) return null
        val chapters = list.mapIndexed { index, item ->
            val title = (item as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (title.isBlank()) return null
            NovelChapter(index, title, "$BASE/reader?novelId=$id&chapter=${index + 1}")
        }
        metadata[id] = cachedMetadata
        return chapters
    }

    private fun writeCatalog(id: Long, book: JsonObject, list: JsonArray) = synchronized(diskLock) {
        val directory = cacheDir ?: return@synchronized
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            val payload = JsonObject(mapOf("schema" to JsonPrimitive(1), "id" to JsonPrimitive(id),
                "metadata" to book, "list" to list)).toString()
            if (payload.toByteArray(Charsets.UTF_8).size > 8L * 1024 * 1024) return@synchronized
            temporary = File.createTempFile("skin-catalog-", ".tmp", directory)
            temporary.writeText(payload, Charsets.UTF_8)
            Files.move(temporary.toPath(), File(directory, "catalog-$id.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            temporary = null
            val files = directory.listFiles()?.filter { Regex("catalog-[0-9]+\\.json").matches(it.name) }
                .orEmpty().sortedByDescending { it.lastModified() }
            var bytes = 0L
            files.forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= 24 || bytes > 32L * 1024 * 1024) file.delete()
            }
        } catch (_: IOException) { /* Disk cache failure never hides real source content. */ }
        finally { temporary?.delete() }
    }

    suspend fun getChapterContent(chapterUrl: String): String = withContext(Dispatchers.IO) {
        val url = chapterUrl.toHttpUrlOrNull()
            ?: throw IOException("Skin 章节地址无效")
        if (!source.owns(chapterUrl) || url.encodedPath != "/reader") {
            throw IOException("章节地址不属于 Skin")
        }
        val id = url.queryParameter("novelId")?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Skin 章节缺少书籍编号")
        val chapter = url.queryParameter("chapter")?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Skin 章节编号无效")
        val providerId = apiBookId(id, knownMetadata(id))
        val data = api("chapter", linkedMapOf("id" to providerId, "chapterid" to chapter))
        if (data.text("id").toLongOrNull() != providerId ||
            data.text("chapterid").toLongOrNull() != chapter) {
            throw IOException("Skin 返回的章节编号与请求不一致")
        }
        val raw = data.text("txt")
        if (raw.length < 50) throw IOException("Skin 正文为空或过短，请重试或换源")
        NovelHtmlParser.cleanText(raw).also {
            if (it.isBlank()) throw IOException("Skin 未返回可阅读正文")
        }
    }

    private fun bookId(url: String): Long {
        if (!source.owns(url)) throw IOException("书籍地址不属于 Skin")
        return NovelHtmlParser.skinId(url)?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Skin 作品链接缺少有效书籍编号")
    }

    private suspend fun knownMetadata(id: Long): JsonObject = metadata[id] ?: run {
        val cached = cachedCatalogData(id, false)?.get("metadata") as? JsonObject
        if (cached?.text("id")?.toLongOrNull() == id && cached.text("title").isNotBlank()) {
            metadata[id] = cached
            cached
        } else loadMetadata(id).second
    }

    private fun apiBookId(id: Long, data: JsonObject): Long =
        data.text("_directory_id").toLongOrNull()?.takeIf { it > 0 }
            ?: data.text("source_book_id").toLongOrNull()?.takeIf { it > 0 } ?: id

    private suspend fun withBookInfo(id: Long, data: JsonObject): JsonObject {
        if (data.text("_directory_id").toLongOrNull()?.takeIf { it > 0 } != null) return data
        val requested = data.text("source_book_id").toLongOrNull()?.takeIf { it > 0 } ?: id
        val info = api("book", linkedMapOf("id" to requested))
        val responseId = info.text("id").toLongOrNull()
        if (responseId != requested || info.text("title").isBlank()) {
            throw IOException("Skin 返回的作品信息与请求不一致")
        }
        val directory = info.text("dirid").toLongOrNull()?.takeIf { it > 0 } ?: requested
        val cover = sequenceOf("cover_url", "cover", "pic", "imgUrl", "image", "img")
            .map { info.text(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val updated = JsonObject(data.toMutableMap().apply {
            put("_directory_id", JsonPrimitive(directory))
            if (data.text("author").isBlank()) put("author", JsonPrimitive(info.text("author")))
            if (data.text("category").isBlank()) put("category", JsonPrimitive(info.text("sortname")))
            if (data.text("description").isBlank()) put("description", JsonPrimitive(info.text("intro")))
            if (data.text("cover_url").isBlank() && cover.isNotBlank())
                put("cover_url", JsonPrimitive(NovelHtmlParser.absolute(BASE + "/", cover)))
            for (name in listOf("full", "lastchapter", "lastupdate")) put(name, JsonPrimitive(info.text(name)))
        })
        metadata[id] = updated
        return updated
    }

    private suspend fun loadMetadata(id: Long): Pair<org.jsoup.nodes.Document, JsonObject> {
        val url = "$BASE/book/$id/"
        val doc = try {
            NovelHtmlParser.document(get(url), url)
        } catch (e: SkinHttpException) {
            if (e.status != 404 && e.status != 410) throw e
            // Most new books have a dynamic /novel?id=N page. That page reads one
            // same-origin detail bucket; it is not an absent or fabricated book.
            NovelHtmlParser.document("", "$BASE/novel?id=$id")
        }
        NovelHtmlParser.checkPage(doc, source)
        val raw = doc.selectFirst("script#book-data[type=application/json]")?.data()
            ?.takeIf { it.isNotBlank() }
        val data = if (raw != null) parseObject(raw) else bucketMetadata(id)
        if (data.text("id").toLongOrNull() != id || data.text("title").isBlank()) {
            throw IOException("Skin 返回的书籍信息与请求不一致")
        }
        if (data.text("source_site").equals("41nr", ignoreCase = true)) {
            throw IOException("此作品已从 Skin 书源移除")
        }
        metadata[id] = data
        return doc to data
    }

    private suspend fun bucketMetadata(id: Long): JsonObject {
        val meta = parseObject(get("$BASE/data/novel-detail-meta.json"))
        val size = meta.text("bucketSize").toLongOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Skin 详情分桶配置无效")
        val max = meta.text("maxId").toLongOrNull()
        if (max != null && id > max) throw IOException("Skin 未收录这本书")
        val bucket = (id - 1) / size
        val buckets = meta["buckets"] as? JsonArray
        if (buckets != null && buckets.none { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == bucket }) {
            throw IOException("Skin 未收录这本书")
        }
        val root = try {
            json.parseToJsonElement(get("$BASE/data/novel-details-$bucket.json"))
        } catch (e: IllegalArgumentException) {
            throw IOException("Skin 详情数据格式异常", e)
        }
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> sequenceOf("items", "docs", "rows", "novels")
                .mapNotNull { root[it] as? JsonArray }.firstOrNull()
            else -> null
        } ?: throw IOException("Skin 详情分桶未返回书籍列表")
        return rows.filterIsInstance<JsonObject>().firstOrNull { it.text("id").toLongOrNull() == id }
            ?: throw IOException("Skin 未收录这本书")
    }

    private suspend fun api(path: String, params: LinkedHashMap<String, Long>): JsonObject {
        val payload = JsonObject(params.mapValues { JsonPrimitive(it.value) }).toString()
        val token = publicToken(payload)
        val url = "https://apibi.cc/api/$path".toHttpUrl().newBuilder()
            .addQueryParameter("token", token).build()
        return parseObject(get(url.toString()))
    }

    private suspend fun get(url: String): String = readUrl(url, false)

    private fun parseObject(raw: String): JsonObject = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        throw IOException("Skin 接口返回格式异常，可能需要验证或网页规则已变化", e)
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private class SkinHttpException(val status: Int) : IOException("Skin 请求失败：HTTP $status")

    companion object {
        private const val BASE = "https://www.biquge.skin"
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val SEARCH_CONCURRENCY = 4
        private const val MAX_SEARCH_CHUNKS = 512
        private const val MAX_SEARCH_ROWS = 10000
        private const val MAX_SEARCH_RESULTS = 120
        private const val MAX_CACHED_SEARCHES = 8
        private const val SEARCH_CACHE_TTL = 24L * 60 * 60 * 1000
        private const val MAX_SEARCH_CACHE_BYTES = 64L * 1024 * 1024
        private val SEARCH_CACHE_FILE = Regex("[a-f0-9]{24}-search-docs-[0-9]+\\.json")
        private val RETIRED_COVER_HOSTS = setOf("telegraph-image-7qx.pages.dev", "img.yplbbs.lol", "aixs.us.ci",
            "img.bqg41.cc.cd", "img.novel41.cc.cd", "img11.ccwu.cc", "novel.ccwu.cc")
        private val PLACEHOLDER_COVER_HASHES = setOf(
            "d21ca920e63ba5e820f07ad60a3b82855680512a4c70ad683d0f6ee2e85ac972",
            "c0eb794e9a029833ceba1c53e5815dbfe67b7b3d7f59cddbe73a92833f17e3d8",
            "9d72f71ddf698ba7ef9f46798c77c573c41c7725b11ee81b403361e2bc841e75",
            "0d471edb90dcd178a3698cd43a02ace0a13c9b98e6feb9de906217fd37c7d901",
            "18a70d88dd2f8ad0fbb26fcf231b4c4cde3ae203b6b0ed68455edd4682038f5a",
            "8b39e830333ae3f3d791a29c53e3a9f52f9b2b39e048736af846af4eaf22b2e5",
        )
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
        /** Public frontend transport encoding from assets/data.js; no credentials involved. */
        internal fun publicToken(payload: String): String {
            val digest = "394c2c3202da6270a3dc22cf70418a51"
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(digest.substring(16).toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(digest.substring(0, 16).toByteArray(Charsets.UTF_8)),
            )
            return Base64.getEncoder().encodeToString(cipher.doFinal(payload.toByteArray(Charsets.UTF_8)))
        }
    }
}
