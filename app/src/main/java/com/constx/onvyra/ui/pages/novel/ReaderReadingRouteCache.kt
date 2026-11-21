package com.constx.onvyra.ui.pages.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Durable work-to-source metadata. Catalogs and chapter bodies remain in ReaderBookLibrary. */
internal class ReaderReadingRouteCache(private var directory: File?, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Serializable private data class Route(val identity: String, val at: Long, val book: NovelBook,
        val failures: Int = 0, val version: Int = 2)
    @Serializable private data class Alias(val catalogId: String, val author: String, val kind: Int,
        val at: Long, val book: NovelBook)
    private val aliases = object : LinkedHashMap<String, Alias>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Alias>) = size > 96
    }
    private val memory = object : LinkedHashMap<String, Route>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Route>) = size > 96
    }
    @Synchronized fun useDirectory(directory: File) { this.directory = directory }

    private fun file(identity: String, prefix: String = "route"): File? = directory?.let {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        File(it, "$prefix-$digest.json")
    }

    private fun read(identity: String): Route? = runCatching {
        (memory[identity] ?: file(identity)?.takeIf { it.isFile && it.length() in 1..MAX_BYTES }
            ?.let { json.decodeFromString<Route>(it.readText()) })?.takeIf { route ->
                route.version == 2 && route.identity == identity && ReaderBookIdentity.key(route.book) == identity &&
                    route.book.title.isNotBlank() && route.book.author.isNotBlank() && route.book.detailUrl.isNotBlank() &&
                    now() - route.at in 0 until TTL && route.failures < 2
            }?.also { memory[identity] = it }
    }.getOrNull()

    @Synchronized fun get(book: NovelBook, enabled: Set<String>): NovelBook? {
        val identity = ReaderBookIdentity.key(book)
        val direct = read(identity)
        val route = direct ?: aliasRoute(book) ?: return null
        // Imported IDs are hashes of the complete rule, including enabled/type. A
        // changed, removed or disabled rule must never resurrect a saved route.
        return route.book.takeIf { it.sourceId in enabled && (direct == null || ReaderBookIdentity.matches(book, it)) }
    }

    private fun aliasKey(book: NovelBook) = "${ReaderSearchPolicy.kind(book)}\u0000${book.id}\u0000${ReaderBookIdentity.normalizeAuthor(book.author)}"
    private fun aliasRoute(book: NovelBook): Route? {
        if (book.sourceId != NovelSourceEngine.SOURCE_CATALOG || book.author.isBlank()) return null
        val key = aliasKey(book)
        val alias = runCatching { aliases[key] ?: file(key, "alias")?.takeIf { it.isFile && it.length() in 1..MAX_BYTES }
            ?.let { json.decodeFromString<Alias>(it.readText()) } }.getOrNull() ?: return null
        if (alias.catalogId != book.id || alias.author != ReaderBookIdentity.normalizeAuthor(book.author) ||
            alias.kind != ReaderSearchPolicy.kind(book) || now() - alias.at !in 0 until TTL ||
            alias.book.canonicalTitle.isBlank()) return null
        aliases[key] = alias
        // Failed/removed canonical routes cannot be resurrected by an older alias.
        return read(ReaderBookIdentity.key(alias.book))?.takeIf {
            it.book.sourceId == alias.book.sourceId && it.book.detailUrl == alias.book.detailUrl
        }
    }

    /** Caller proves retitling from detail evidence and a readable chapter before binding. */
    @Synchronized fun putResolved(requested: NovelBook, resolved: NovelBook) {
        put(resolved)
        if (ReaderBookIdentity.matches(requested, resolved)) return
        if (requested.sourceId != NovelSourceEngine.SOURCE_CATALOG || resolved.canonicalTitle.isBlank() ||
            ReaderSearchPolicy.kind(requested) != ReaderSearchPolicy.kind(resolved) || requested.author.isBlank() ||
            ReaderBookIdentity.normalizeAuthor(requested.author) != ReaderBookIdentity.normalizeAuthor(resolved.author)) return
        val key = aliasKey(requested)
        val alias = Alias(requested.id, ReaderBookIdentity.normalizeAuthor(requested.author), ReaderSearchPolicy.kind(requested), now(), resolved)
        aliases[key] = alias
        val destination = file(key, "alias") ?: return
        runCatching {
            destination.parentFile?.mkdirs()
            val temporary = File.createTempFile("alias-", ".tmp", destination.parentFile)
            try {
                temporary.writeText(json.encodeToString(alias))
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            destination.parentFile?.listFiles { it.name.startsWith("alias-") && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.drop(96)?.forEach { it.delete() }
        }
    }

    @Synchronized fun put(book: NovelBook, successfulRead: Boolean = false) {
        if (book.sourceId == NovelSourceEngine.SOURCE_CATALOG || book.sourceId == NovelSourceEngine.SOURCE_SKIN ||
            book.title.isBlank() || book.author.isBlank() || book.detailUrl.isBlank()) return
        val identity = ReaderBookIdentity.key(book)
        val existing = read(identity)?.takeIf { it.book.sourceId == book.sourceId && it.book.detailUrl == book.detailUrl }
        write(Route(identity, if (successfulRead) now() else existing?.at ?: now(), book,
            failures = if (successfulRead) 0 else existing?.failures ?: 0))
    }

    @Synchronized fun failed(book: NovelBook, identityMismatch: Boolean = false) {
        val identity = ReaderBookIdentity.key(book)
        val route = read(identity) ?: return
        if (route.book.sourceId != book.sourceId || route.book.detailUrl != book.detailUrl) return
        if (identityMismatch || route.failures >= 1) { memory.remove(identity); file(identity)?.delete() }
        else write(route.copy(failures = route.failures + 1))
    }

    private fun write(route: Route) {
        memory[route.identity] = route
        val destination = file(route.identity) ?: return
        runCatching {
            destination.parentFile?.mkdirs()
            val temporary = File.createTempFile("route-", ".tmp", destination.parentFile)
            try {
                temporary.writeText(json.encodeToString(route))
                try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { temporary.delete() }
            destination.parentFile?.listFiles { entry -> entry.name.startsWith("route-") && entry.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.drop(192)?.forEach { it.delete() }
        }
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024
        private const val TTL = 7L * 24 * 60 * 60 * 1000
    }
}
