package com.constx.onvyra.ui.pages.novel

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class ReaderBookmark(val id: String, val bookKey: String, val sourceId: String,
    val chapter: NovelChapter, val paragraphIndex: Int, val charOffset: Int = 0,
    val quote: String = "", val createdAt: Long = System.currentTimeMillis())

@Serializable
data class ReaderDownloadState(val total: Int = 0, val completed: Int = 0, val failed: Int = 0,
    val running: Boolean = false, val message: String = "")

data class ReaderContentHit(val chapter: NovelChapter, val paragraphIndex: Int, val matchOffset: Int,
    val excerpt: String)
data class ReaderContentSearchState(val query: String = "", val hits: List<ReaderContentHit> = emptyList(),
    val completed: Int = 0, val total: Int = 0, val failed: Int = 0, val running: Boolean = false,
    val truncated: Boolean = false, val message: String = "")

/** Atomic, route-specific clean chapter storage, shared by reading, downloads and in-book search. */
class ReaderLibraryRepository(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    @Serializable private data class Body(val sourceId: String, val url: String, val text: String, val version: Int = 9)
    @Serializable private data class Catalog(val book: NovelBook, val chapters: List<NovelChapter>)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun bodyFile(source: String, url: String) = File(directory, "chapters/${hash("$source\u0000$url")}.json")
    private fun catalogFile(book: NovelBook) = File(directory, "catalogs/${hash(book.sourceId + "\u0000" + book.detailUrl)}.json")
    private fun write(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile("reader-", ".tmp", file.parentFile)
        try {
            temp.writeText(value)
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
    suspend fun content(source: String, url: String): String? = withContext(Dispatchers.IO) {
        lock.withLock { runCatching {
            val file = bodyFile(source, url)
            if (!file.isFile || file.length() > 4_000_000) return@runCatching null
            json.decodeFromString<Body>(file.readText()).takeIf { it.version == 9 && it.sourceId == source && it.url == url }?.text
        }.getOrNull() }
    }
    suspend fun putContent(source: String, url: String, text: String) = withContext(Dispatchers.IO) {
        require(text.isNotBlank() && text.length <= 1_000_000) { "章节正文为空或过大，未写入离线缓存" }
        lock.withLock { write(bodyFile(source, url), json.encodeToString(Body(source, url, text))) }
    }
    suspend fun catalog(book: NovelBook): List<NovelChapter>? = withContext(Dispatchers.IO) { lock.withLock {
        runCatching { catalogFile(book).takeIf { it.isFile && it.length() < 12_000_000 }?.let {
            json.decodeFromString<Catalog>(it.readText()).takeIf { saved -> saved.book.sourceId == book.sourceId &&
                saved.book.detailUrl == book.detailUrl }?.chapters
        } }.getOrNull()
    } }
    suspend fun putCatalog(book: NovelBook, chapters: List<NovelChapter>) = withContext(Dispatchers.IO) { lock.withLock {
        require(chapters.isNotEmpty()) { "目录为空，无法缓存" }
        write(catalogFile(book), json.encodeToString(Catalog(book, chapters)))
    } }
    suspend fun bookmarks(): List<ReaderBookmark> = withContext(Dispatchers.IO) { lock.withLock {
        runCatching { File(directory, "bookmarks.json").takeIf { it.isFile }?.let {
            json.decodeFromString<List<ReaderBookmark>>(it.readText())
        }.orEmpty() }.getOrDefault(emptyList())
    } }
    suspend fun saveBookmarks(values: List<ReaderBookmark>) = withContext(Dispatchers.IO) { lock.withLock {
        write(File(directory, "bookmarks.json"), json.encodeToString(values))
    } }
    suspend fun downloads(): Map<String, ReaderDownloadState> = withContext(Dispatchers.IO) { lock.withLock {
        runCatching { File(directory, "downloads.json").takeIf { it.isFile }?.let {
            json.decodeFromString<Map<String, ReaderDownloadState>>(it.readText())
        }.orEmpty().mapValues { (_, state) -> if (state.running) state.copy(running = false, message = "缓存已暂停，可继续下载") else state }
        }.getOrDefault(emptyMap())
    } }
    suspend fun saveDownloads(values: Map<String, ReaderDownloadState>) = withContext(Dispatchers.IO) { lock.withLock {
        write(File(directory, "downloads.json"), json.encodeToString(values))
    } }
}

class ReaderBookLibrary(directory: File, private val engine: NovelSourceEngine, private val scope: CoroutineScope) {
    val repository = ReaderLibraryRepository(directory)
    private val _bookmarks = MutableStateFlow<List<ReaderBookmark>>(emptyList())
    val bookmarks = _bookmarks.asStateFlow()
    private val _downloads = MutableStateFlow<Map<String, ReaderDownloadState>>(emptyMap())
    val downloads = _downloads.asStateFlow()
    private val _search = MutableStateFlow(ReaderContentSearchState())
    val searchState = _search.asStateFlow()
    private val changes = Mutex()
    private val initial = scope.launch { _bookmarks.value = repository.bookmarks(); _downloads.value = repository.downloads() }
    private val downloadJobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null
    private var searchGeneration = 0
    internal fun workKey(book: NovelBook) = ReaderBookIdentity.key(book)
    fun downloadKey(book: NovelBook) = workKey(book) + "\u0000" + book.sourceId
    suspend fun cachedContent(source: String, url: String) = repository.content(source, url)
    suspend fun rememberContent(book: NovelBook, chapter: NovelChapter, text: String) = repository.putContent(book.sourceId, chapter.url, text)
    suspend fun cachedCatalog(book: NovelBook) = repository.catalog(book)
    suspend fun rememberCatalog(book: NovelBook, chapters: List<NovelChapter>) = repository.putCatalog(book, chapters)
    fun bookmarksFor(book: NovelBook) = _bookmarks.value.filter { it.bookKey == workKey(book) }.sortedByDescending { it.createdAt }
    suspend fun addBookmark(book: NovelBook, chapter: NovelChapter, paragraph: Int, offset: Int, quote: String) {
        initial.join(); changes.withLock {
            val key = workKey(book)
            val same = _bookmarks.value.firstOrNull { it.bookKey == key && it.chapter.url == chapter.url && it.paragraphIndex == paragraph && it.charOffset == offset }
            if (same == null) {
                val next = listOf(ReaderBookmark(UUID.randomUUID().toString(), key, book.sourceId, chapter, paragraph, offset, quote.take(160))) + _bookmarks.value
                repository.saveBookmarks(next); _bookmarks.value = next
            }
        }
    }
    fun removeBookmark(id: String) { scope.launch { initial.join(); changes.withLock {
        val next = _bookmarks.value.filterNot { it.id == id }; repository.saveBookmarks(next); _bookmarks.value = next
    } } }
    fun cancelDownload(book: NovelBook) { downloadJobs[downloadKey(book)]?.cancel() }
    fun download(book: NovelBook, catalog: List<NovelChapter>) {
        if (book.isComic || book.isAudio || catalog.isEmpty()) return
        val key = downloadKey(book)
        if (downloadJobs[key]?.isActive == true) return
        downloadJobs[key] = scope.launch {
            initial.join()
            val count = AtomicInteger(); val failed = AtomicInteger()
            suspend fun update(running: Boolean, message: String) { changes.withLock {
                _downloads.value = _downloads.value + (key to ReaderDownloadState(catalog.size, count.get(), failed.get(), running, message))
                repository.saveDownloads(_downloads.value)
            } }
            try {
                rememberCatalog(book, catalog); update(true, "正在缓存正文")
                val next = AtomicInteger()
                coroutineScope { repeat(3.coerceAtMost(catalog.size)) { launch(Dispatchers.IO) {
                    while (isActive) {
                        val chapter = catalog.getOrNull(next.getAndIncrement()) ?: break
                        try {
                            if (cachedContent(book.sourceId, chapter.url) == null) {
                                val text = engine.getChapterContent(chapter.url, book.sourceId)
                                ensureActive(); rememberContent(book, chapter, text)
                            }
                            count.incrementAndGet()
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { failed.incrementAndGet() }
                        update(true, "已缓存 ${count.get()} / ${catalog.size} 章")
                    }
                } } }
                update(false, if (failed.get() == 0) "已缓存全书 ${catalog.size} 章，可离线阅读" else "${failed.get()} 章未能缓存，点击重试缺章")
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) { update(false, "缓存已暂停，已完成的章节已保留") }; throw cancel
            } catch (error: Exception) { update(false, error.message ?: "缓存失败") }
        }
    }
    fun cancelSearch() { searchGeneration++; searchJob?.cancel(); _search.value = _search.value.copy(running = false, message = "搜索已停止") }
    fun search(book: NovelBook, catalog: List<NovelChapter>, keyword: String) {
        cancelSearch(); val query = keyword.trim(); val generation = searchGeneration
        if (query.isBlank() || book.isComic || book.isAudio) { _search.value = ReaderContentSearchState(); return }
        _search.value = ReaderContentSearchState(query = query, total = catalog.size, running = true)
        searchJob = scope.launch(Dispatchers.IO) {
            val next = AtomicInteger(); val completed = AtomicInteger(); val failures = AtomicInteger()
            val hits = ArrayList<ReaderContentHit>(); val hitLock = Mutex()
            try {
                coroutineScope { repeat(3.coerceAtMost(catalog.size)) { launch {
                    while (isActive) {
                        val chapter = catalog.getOrNull(next.getAndIncrement()) ?: break
                        try {
                            val text = cachedContent(book.sourceId, chapter.url) ?: engine.getChapterContent(chapter.url, book.sourceId).also { rememberContent(book, chapter, it) }
                            val found = mutableListOf<ReaderContentHit>()
                            readerParagraphs(text).forEachIndexed { index, paragraph ->
                                var at = paragraph.indexOf(query, ignoreCase = true)
                                while (at >= 0 && found.size < 2_000) {
                                    found += ReaderContentHit(chapter, index, at, paragraph.substring((at - 26).coerceAtLeast(0), (at + query.length + 65).coerceAtMost(paragraph.length)))
                                    at = paragraph.indexOf(query, at + query.length, ignoreCase = true)
                                }
                            }
                            hitLock.withLock { hits.addAll(found.take((2_000 - hits.size).coerceAtLeast(0))) }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { failures.incrementAndGet() }
                        val done = completed.incrementAndGet()
                        hitLock.withLock { if (generation == searchGeneration) _search.value = ReaderContentSearchState(query,
                            hits.sortedWith(compareBy<ReaderContentHit> { it.chapter.index }.thenBy { it.paragraphIndex }.thenBy { it.matchOffset }),
                            done, catalog.size, failures.get(), true, hits.size >= 2_000) }
                    }
                } } }
                if (generation == searchGeneration) _search.value = _search.value.copy(running = false,
                    message = if (failures.get() > 0) "${failures.get()} 章未能检索，可重试" else "已搜索完整目录")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (generation == searchGeneration) _search.value = _search.value.copy(running = false, message = error.message ?: "搜索失败") }
        }
    }
}
