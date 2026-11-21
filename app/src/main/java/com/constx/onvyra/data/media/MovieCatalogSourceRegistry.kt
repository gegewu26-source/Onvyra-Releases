package com.constx.onvyra.data.media

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import com.constx.onvyra.data.ai.tools.validateWebUrl

/** Private, small registry of compatible HTTP catalogs; no executable provider configuration. */
internal class MovieCatalogSourceRegistry internal constructor(
    directory: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val source: MovieCatalogSource, val discoveredAt: Long)
    private val file = File(directory, "sources.json")
    private val lock = Any()
    internal val discoveryMutex = Mutex()
    private var entries = emptyList<Entry>()
    private var successfulConfigs = emptyList<String>()
    private var lastAttemptAt = -1L
    private var lastAttemptFoundSources = false

    init { load() }

    fun all(): List<MovieCatalogSource> = synchronized(lock) {
        entries.sortedByDescending { it.discoveredAt }.map { it.source }.filter(::isAllowedCatalogSource)
    }

    fun register(sources: List<MovieCatalogSource>) = synchronized(lock) {
        val incoming = sources.mapNotNull(::validatedDiscoveredMovieSource).distinctBy { it.id }.take(MAX_ENTRIES)
        if (incoming.isEmpty()) return@synchronized
        val ids = incoming.mapTo(HashSet()) { it.id }
        val now = clockMillis().coerceAtLeast(0)
        entries = (incoming.map { Entry(it, now) } + entries.filterNot { it.source.id in ids })
            .sortedByDescending { it.discoveredAt }.take(MAX_ENTRIES)
        save()
    }

    internal fun recentConfigUrls(): List<String> = synchronized(lock) {
        successfulConfigs.filter { com.constx.onvyra.security.PrivateMediaAccess.isAllowed(url = it) }
    }

    internal fun isDiscoveryCacheFresh(): Boolean = synchronized(lock) {
        val elapsed = clockMillis() - lastAttemptAt
        lastAttemptAt >= 0 && elapsed >= 0 && elapsed < if (lastAttemptFoundSources) SUCCESS_TTL_MS else FAILURE_TTL_MS
    }

    internal fun recordDiscoveryAttempt(foundSources: Boolean, configUrls: List<String>) = synchronized(lock) {
        lastAttemptAt = clockMillis().coerceAtLeast(0)
        lastAttemptFoundSources = foundSources
        successfulConfigs = (configUrls.mapNotNull(::safeDiscoveryConfigUrl) + successfulConfigs).distinct().take(12)
        save()
    }

    private fun load() = synchronized(lock) {
        runCatching {
            if (!file.isFile || file.length() > MAX_REGISTRY_BYTES) return@runCatching
            val json = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: return@runCatching
            val loaded = (json["sources"] as? JsonArray).orEmpty().take(MAX_ENTRIES).mapNotNull { value ->
                val row = value as? JsonObject ?: return@mapNotNull null
                fun string(name: String) = (row[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                val source = MovieCatalogSource(string("id") ?: return@mapNotNull null,
                    string("name") ?: "发现·影片目录", string("api_url") ?: return@mapNotNull null)
                val validated = validatedDiscoveredMovieSource(source) ?: return@mapNotNull null
                val timestamp = (row["discovered_at"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0L
                Entry(validated, timestamp)
            }
            entries = loaded.distinctBy { it.source.id }.sortedByDescending { it.discoveredAt }
            successfulConfigs = (json["recent_configs"] as? JsonArray).orEmpty().take(12)
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::safeDiscoveryConfigUrl) }.distinct()
            lastAttemptAt = (json["last_attempt_at"] as? JsonPrimitive)?.longOrNull ?: -1L
            lastAttemptFoundSources = (json["last_attempt_found_sources"] as? JsonPrimitive)?.contentOrNull == "true"
        }
        Unit
    }

    private fun save() {
        val json = buildJsonObject {
            put("version", 1)
            put("sources", buildJsonArray { entries.forEach { entry -> add(buildJsonObject {
                put("id", entry.source.id); put("name", entry.source.name); put("api_url", entry.source.apiUrl)
                put("discovered_at", entry.discoveredAt)
            }) } })
            put("recent_configs", buildJsonArray { successfulConfigs.forEach { add(JsonPrimitive(it)) } })
            if (lastAttemptAt >= 0) put("last_attempt_at", lastAttemptAt)
            put("last_attempt_found_sources", lastAttemptFoundSources)
        }.toString().toByteArray(Charsets.UTF_8)
        if (json.size > MAX_REGISTRY_BYTES) return
        var temporary: File? = null
        try {
            val directory = requireNotNull(file.parentFile)
            if (!directory.isDirectory && !directory.mkdirs()) return
            temporary = File.createTempFile("movie-sources-", ".tmp", directory)
            FileOutputStream(temporary).use { stream -> stream.write(json); stream.fd.sync() }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            // A full/read-only private volume must not prevent using the in-memory discovered sources.
        } finally { temporary?.takeIf(File::exists)?.delete() }
    }

    companion object {
        private const val MAX_ENTRIES = 128
        private const val MAX_REGISTRY_BYTES = 512 * 1024L
        private val SUCCESS_TTL_MS = TimeUnit.HOURS.toMillis(6)
        private val FAILURE_TTL_MS = TimeUnit.MINUTES.toMillis(5)
        @Volatile private var instance: MovieCatalogSourceRegistry? = null

        fun get(context: Context): MovieCatalogSourceRegistry = instance ?: synchronized(this) {
            instance ?: MovieCatalogSourceRegistry(File(context.applicationContext.filesDir, "movie_catalog"))
                .also { instance = it }
        }
    }
}

internal fun discoveredMovieSourceId(apiUrl: String): String = "discovered_" +
    MessageDigest.getInstance("SHA-256").digest(apiUrl.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun safeDiscoveryConfigUrl(value: String): String? = runCatching {
    validateWebUrl(value.trim()).toString()
}.getOrNull()

internal fun canonicalDiscoveredMovieApi(value: String): String? = runCatching {
    val url = validateWebUrl(value.trim())
    val lowerPath = url.encodedPath.lowercase()
    require(!lowerPath.endsWith(".js") && !lowerPath.endsWith(".jar") && !lowerPath.endsWith(".zip") &&
        !lowerPath.endsWith(".m3u8") && !lowerPath.endsWith(".mp4"))
    url.newBuilder().removeAllQueryParameters("ac").removeAllQueryParameters("wd")
        .removeAllQueryParameters("ids").removeAllQueryParameters("pg").build().toString()
}.getOrNull()

private fun validatedDiscoveredMovieSource(source: MovieCatalogSource): MovieCatalogSource? {
    if (isExcludedMovieCatalogSource(source)) return null
    val api = canonicalDiscoveredMovieApi(source.apiUrl) ?: return null
    if (source.id != discoveredMovieSourceId(api)) return null
    val name = source.name.replace(Regex("[\\s\\u0000-\\u001f]+"), " ").trim().take(100)
        .ifBlank { "发现·影片目录" }
    return MovieCatalogSource(source.id, name, api)
}
