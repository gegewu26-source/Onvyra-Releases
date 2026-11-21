package com.constx.onvyra.ui.pages.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
internal data class ReaderSourceAvailability(
    val sourceId: String, val detailUrl: String,
    val status: String = MATCHED, val checkedAt: Long = 0,
    val chapterTitle: String = "", val chapterUrl: String = "", val chapterIndex: Int = -1,
    val reason: String = "", val retryAfter: Long = 0,
) {
    val routeKey: String get() = "$sourceId\u0000$detailUrl"
    companion object {
        const val MATCHED = "matched"
        const val USABLE = "usable"
        const val FAILED = "failed"
        fun key(sourceId: String, detailUrl: String) = "$sourceId\u0000$detailUrl"
    }
}

internal data class ReaderSourceDiscoveryState(val book: NovelBook, val progress: ReaderSearchProgress,
    val availability: Map<String, ReaderSourceAvailability>, val hasSnapshot: Boolean = false)

/** Saves real candidate routes and actual attempted source IDs, never just a progress counter. */
internal class ReaderSourceDiscoveryStore(private var directory: File?, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class Snapshot(val identity: String, val at: Long, val scanAt: Long,
        val candidates: List<NovelBookSource>, val eligibleIds: Set<String>, val searchedIds: Set<String>,
        val startedIds: Set<String>, val availability: List<ReaderSourceAvailability>, val version: Int = 1)
    private val memory = object : LinkedHashMap<String, Snapshot>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>) = size > 32
    }
    private val lastWrites = mutableMapOf<String, Long>()
    private var lastPruneAt = 0L
    @Synchronized fun useDirectory(value: File) { directory = value }
    private fun file(key: String) = directory?.let {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b) }
        File(it, "discovery-$hash.json")
    }
    private fun read(key: String): Snapshot? = runCatching {
        (memory[key] ?: file(key)?.takeIf { it.isFile && it.length() in 1..2_000_000 }
            ?.let { json.decodeFromString<Snapshot>(it.readText()) })?.takeIf {
            it.version == 1 && it.identity == key && now() - it.at in 0 until MAX_AGE
        }?.also { memory[key] = it }
    }.getOrNull()
    @Synchronized fun restore(book: NovelBook, eligibleIds: Set<String>, force: Boolean = false): ReaderSourceDiscoveryState {
        val key = ReaderBookIdentity.key(book)
        val saved = read(key)
        val candidates = (listOf(book.asSource()) + saved?.candidates.orEmpty() + book.sources)
            .filter { it.sourceId in eligibleIds && ReaderBookIdentity.matches(book, it.asBook()) }
            .distinctBy { it.sourceId }.take(640)
        val routes = candidates.mapTo(hashSetOf()) { ReaderSourceAvailability.key(it.sourceId, it.detailUrl) }
        val availability = saved?.availability.orEmpty().filter { it.routeKey in routes }.associate { value ->
            value.routeKey to if (force || value.status == ReaderSourceAvailability.USABLE && now() - value.checkedAt !in 0 until VERIFIED_TTL)
                value.copy(status = ReaderSourceAvailability.MATCHED, reason = "", retryAfter = 0) else value
        }
        val scanned = if (!force && saved != null && now() - saved.scanAt in 0 until SCAN_TTL)
            saved.searchedIds.intersect(eligibleIds) else emptySet()
        // Keep the user's primary route and saved chapter; cached results only add candidates.
        val restored = book.copy(sources = candidates)
        return ReaderSourceDiscoveryState(restored, ReaderSearchProgress(listOf(restored), scanned.size,
            eligibleIds.size, eligibleIds.size, 0, scanned.size == eligibleIds.size, scanned), availability, saved != null)
    }
    @Synchronized fun save(book: NovelBook, progress: ReaderSearchProgress, eligibleIds: Set<String>,
        availability: Map<String, ReaderSourceAvailability>, reset: Boolean = false, flush: Boolean = true) {
        val key = ReaderBookIdentity.key(book)
        val old = read(key)
        val sameScan = !reset && old != null && now() - old.scanAt in 0 until SCAN_TTL
        val candidates = (listOf(book.asSource()) + book.sources + progress.books.flatMap { listOf(it.asSource()) + it.sources } +
            if (sameScan) old!!.candidates else emptyList())
            .filter { it.sourceId in eligibleIds && ReaderBookIdentity.matches(book, it.asBook()) }
            .distinctBy { it.sourceId }.take(640)
        val routes = candidates.mapTo(hashSetOf()) { ReaderSourceAvailability.key(it.sourceId, it.detailUrl) }
        val statuses = if (reset) availability.values.toList() else (old?.availability.orEmpty() + availability.values)
            .groupBy { it.routeKey }.map { (_, values) -> values.maxBy { it.checkedAt } }
        val snapshot = Snapshot(key, now(), if (reset || old == null || now() - old.scanAt !in 0 until SCAN_TTL) now() else old.scanAt,
            candidates, eligibleIds, (progress.searchedSourceIds + if (sameScan) old!!.searchedIds else emptySet()).intersect(eligibleIds),
            (progress.startedSourceIds + if (sameScan) old!!.startedIds else emptySet()).intersect(eligibleIds), statuses.filter { it.routeKey in routes })
        memory[key] = snapshot
        // Every event updates memory. Metadata-only writes are coalesced; terminal
        // flush, reset and actual verification changes always reach disk now.
        val statusChanged = old?.availability != snapshot.availability
        if (!flush && !reset && !statusChanged && now() - (lastWrites[key] ?: Long.MIN_VALUE / 2) in 0 until 400) return
        val destination = file(key) ?: return
        runCatching {
            destination.parentFile?.mkdirs()
            val temporary = File.createTempFile("discovery-", ".tmp", destination.parentFile)
            try {
                temporary.writeText(json.encodeToString(snapshot))
                try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                lastWrites[key] = now()
            } finally { temporary.delete() }
            if (old == null || now() - lastPruneAt !in 0 until 60_000) {
                destination.parentFile?.listFiles { f -> f.name.startsWith("discovery-") && f.name.endsWith(".json") }
                    ?.sortedByDescending { it.lastModified() }?.drop(96)?.forEach { it.delete() }
                lastPruneAt = now()
                lastWrites.keys.retainAll(memory.keys)
            }
        }
    }
    /** Availability writers never replay an older metadata scan across an explicit reset. */
    @Synchronized fun recordAvailability(book: NovelBook, eligibleIds: Set<String>, value: ReaderSourceAvailability) {
        val current = restore(book, eligibleIds)
        save(current.book, current.progress, eligibleIds, current.availability + (value.routeKey to value))
    }
    companion object {
        private const val SCAN_TTL = 24L * 60 * 60 * 1000
        private const val VERIFIED_TTL = 6L * 60 * 60 * 1000
        private const val MAX_AGE = 7L * SCAN_TTL
    }
}
