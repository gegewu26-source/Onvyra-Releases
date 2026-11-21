package com.constx.onvyra.data.media

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.constx.onvyra.security.PrivateMediaAccess
import com.constx.onvyra.security.PrivateMediaAccessDeniedException

private const val EXTRACTOR_TIMEOUT_MILLIS = 40_000L
private const val PINNED_EXTRACTOR_ASSET = "video_extractor/yt-dlp"
private const val MAX_INFO_CHARS = 8 * 1024 * 1024
private val extractorInitialization = Mutex()
@Volatile private var extractorInitialized = false

/** Optional, on-demand local URL extraction; no media download, automatic update, or separate app. */
internal class EnhancedVideoExtractor(context: Context) {
    private val appContext = context.applicationContext

    suspend fun resolve(link: VideoLink, cookies: String? = null): VideoPlaybackSource {
        PrivateMediaAccess.requireAllowed(url = link.url)
        val url = canonicalExtractorUrl(link)
        PrivateMediaAccess.requireAllowed(url = url)
        require(url.toHttpUrlOrNull() != null) { "无效的视频链接" }
        val processId = "video-preview-${UUID.randomUUID()}"
        var cookieFile: File? = null
        try {
            return withTimeout(EXTRACTOR_TIMEOUT_MILLIS) {
                initializeExtractor()
                runInterruptible(Dispatchers.IO) {
                    PrivateMediaAccess.requireAllowed(url = url)
                    cookieFile = cookies?.takeIf(String::isNotBlank)?.let {
                        createOriginCookieFile(appContext.cacheDir, url, it)
                    }
                    val request = YoutubeDLRequest(url)
                        .addOption("--dump-single-json")
                        .addOption("--skip-download")
                        .addOption("--no-playlist")
                        .addOption("--playlist-items", "1")
                        .addOption("--ignore-config")
                        .addOption("--no-warnings")
                        .addOption("--socket-timeout", "12")
                        .addOption("--retries", "1")
                        .addOption("--extractor-retries", "1")
                        .addOption("--format", "best[acodec!=none][vcodec!=none]/bestvideo+bestaudio/best")
                    cookieFile?.let { request.addOption("--cookies", it.absolutePath) }
                    val result = YoutubeDL.execute(request, processId = processId, callback = null)
                    PrivateMediaAccess.requireAllowed(url = url)
                    parseYtDlpVideoInfo(result.out).also(::requireAllowedPlaybackSource)
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("增强解析超时，可重试或使用平台播放器。")
        } catch (error: CancellationException) {
            throw error
        } catch (denied: PrivateMediaAccessDeniedException) {
            throw denied
        } catch (error: LinkageError) {
            // A missing/incompatible optional runtime must fail this source, rather than
            // terminate the chat process. Do not swallow cancellation or VM failures.
            throw IOException("增强解析组件暂不可用，可尝试其他片源或使用平台播放器。", error)
        } catch (error: Exception) {
            // Upstream stderr can contain signed URLs or session data; don't put it in UI/log output.
            val details = error.message.orEmpty().lowercase()
            throw IOException(when {
                details.contains("cookie") || details.contains("sign in") || details.contains("login") ->
                    "平台要求网页验证或登录状态，可尝试平台播放器。"
                details.contains("private") || details.contains("premium") || details.contains("drm") ->
                    "该视频受播放权限限制，请使用原平台播放。"
                else -> "增强解析暂未取得可播放的视频，可重试或使用平台播放器。"
            })
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { YoutubeDL.destroyProcessById(processId) }
                cookieFile?.delete()
            }
        }
    }

    private suspend fun initializeExtractor() = extractorInitialization.withLock {
        if (extractorInitialized) return@withLock
        runInterruptible(Dispatchers.IO) {
            YoutubeDL.init(appContext)
            installPinnedExtractorIfPresent(appContext)
        }
        extractorInitialized = true
    }
}

internal fun canonicalExtractorUrl(link: VideoLink): String {
    // The upstream Douyin extractor expects this full URL, not the embedded-player route.
    if (link.platform == VideoPlatform.DOUYIN && link.videoId?.matches(Regex("\\d+")) == true) {
        return "https://www.douyin.com/video/${link.videoId}"
    }
    return link.url
}

/** The APK pins its extractor bytes; initialization never fetches executable code from the network. */
private fun installPinnedExtractorIfPresent(context: Context) {
    val assets = context.assets.list("video_extractor").orEmpty().toSet()
    if ("yt-dlp" !in assets && "yt-dlp.sha256" !in assets) {
        // A build without an override can use the library's bundled offline version.
        return
    }
    check("yt-dlp" in assets && "yt-dlp.sha256" in assets) { "Bundled extractor or checksum is missing" }
    val expected = context.assets.open("$PINNED_EXTRACTOR_ASSET.sha256").bufferedReader().use { it.readText() }
        .trim().substringBefore(' ').lowercase()
    check(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundled extractor checksum" }
    val directory = context.noBackupFilesDir.resolve("${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}")
    check(directory.exists() || directory.mkdirs()) { "Cannot prepare bundled extractor directory" }
    val target = directory.resolve("yt-dlp")
    if (target.isFile && sha256(target) == expected) return
    val staging = directory.resolve(".yt-dlp-pinned-${UUID.randomUUID()}.tmp")
    try {
        context.assets.open(PINNED_EXTRACTOR_ASSET).use { input ->
            staging.outputStream().use { output -> input.copyTo(output) }
        }
        check(sha256(staging) == expected) { "Bundled extractor checksum does not match" }
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            check(staging.renameTo(target)) { "Cannot install bundled extractor" }
        }
    } finally {
        staging.delete()
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Exact-host session cookies are private and temporary, rather than a global Cookie HTTP header. */
internal fun createOriginCookieFile(directory: File, url: String, cookies: String): File? {
    val origin = url.toHttpUrlOrNull() ?: return null
    val entries = cookies.split(';').mapNotNull { pair ->
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
        if ('=' !in pair || !name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) ||
            value.any { it == '\r' || it == '\n' || it == '\t' } ||
            name.lowercase() in setOf("domain", "path", "expires", "max-age", "samesite", "secure", "httponly")) null
        else name to value
    }
    if (entries.isEmpty()) return null
    val file = File.createTempFile("video-origin-", ".cookies", directory)
    file.setReadable(false, false)
    file.setWritable(false, false)
    file.setReadable(true, true)
    file.setWritable(true, true)
    val expires = System.currentTimeMillis() / 1000L + 3600L
    try {
        file.bufferedWriter().use { writer ->
            writer.appendLine("# Netscape HTTP Cookie File")
            entries.forEach { (name, value) ->
                writer.appendLine("${origin.host}\tFALSE\t/\t${if (origin.isHttps) "TRUE" else "FALSE"}\t$expires\t$name\t$value")
            }
        }
        return file
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

/** Raw JSON retains selected separate video/audio formats omitted by simpler URL-only adapters. */
internal fun parseYtDlpVideoInfo(content: String): VideoPlaybackSource {
    require(content.length <= MAX_INFO_CHARS) { "Video metadata is too large" }
    val root = Json.parseToJsonElement(content) as? JsonObject ?: error("Video metadata must be an object")
    return parseVideoInfoObject(root, depth = 0)
}

private fun parseVideoInfoObject(root: JsonObject, depth: Int): VideoPlaybackSource {
    require(depth <= 2) { "Video playlist nesting is not supported" }
    listOf("webpage_url", "original_url").forEach { key ->
        root.text(key)?.let { PrivateMediaAccess.requireAllowed(url = it) }
    }
    val entries = root["entries"] as? JsonArray
    if (entries != null) {
        require(entries.size == 1) { "Provide a single video instead of a playlist" }
        return parseVideoInfoObject(entries.single() as? JsonObject ?: error("Missing video entry"), depth + 1)
    }
    check(root.text("has_drm") != "true") { "DRM protected video" }
    val downloads = root["requested_downloads"] as? JsonArray
    if (root["requested_formats"] == null && downloads?.size == 1) {
        val download = downloads.single() as? JsonObject
        if (download?.get("requested_formats") is JsonArray) {
            return parseVideoInfoObject(JsonObject(root.toMutableMap().apply {
                remove("requested_downloads")
                putAll(download)
            }), depth + 1)
        }
    }
    val requested = ((root["requested_formats"] ?: root["requested_downloads"]) as? JsonArray)
        .orEmpty().mapNotNull { it as? JsonObject }
    val formats = (root["formats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val selected = requested.takeIf { it.isNotEmpty() } ?: listOf(root)
    val selectedVideos = selected.filter(JsonObject::playableVideo)
    val video = selectedVideos.firstOrNull { it.text("acodec") != "none" }
        ?: selectedVideos.firstOrNull()
        // If the extractor did not make a selection, favor a muxed stream over a silent
        // high-resolution video-only track. Separate tracks are still supported below.
        ?: formats.filter { it.playableVideo() && it.text("acodec") != "none" }.bestVideo()
        ?: formats.filter(JsonObject::playableVideo).bestVideo()
        ?: error("No playable video track")
    val audioCandidates = if (requested.isNotEmpty()) requested else formats
    val audio = if (video.text("acodec") == "none") {
        audioCandidates.filter {
            it !== video && it.audioOnly() && it.mediaUrl() != null && it.text("has_drm") != "true"
        }.maxByOrNull { it.number("abr") + it.number("tbr") }
    } else null
    val headers = LinkedHashMap<String, String>()
    headers.putAll(playbackHeaders(root))
    headers.putAll(playbackHeaders(video))
    return VideoPlaybackSource(
        url = video.mediaUrl()!!,
        kind = VideoPlaybackKind.DIRECT,
        audioUrl = audio?.mediaUrl(),
        title = root.text("title")?.take(512),
        headers = headers,
        mimeType = video.mediaMimeType(),
        durationSeconds = root.number("duration").takeIf { it.isFinite() && it > 0 },
    ).also(::requireAllowedPlaybackSource)
}

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(name: String): Double = (get(name) as? JsonPrimitive)?.doubleOrNull ?: 0.0
private fun JsonObject.mediaUrl(): String? = text("url")?.let { value ->
    value.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
}
private fun JsonObject.audioOnly(): Boolean = text("vcodec") == "none" ||
    text("ext")?.lowercase() in setOf("m4a", "mp3", "aac", "opus", "ogg", "flac", "wav")

private fun JsonObject.playableVideo(): Boolean = mediaUrl() != null && !audioOnly() && text("has_drm") != "true"
private fun List<JsonObject>.bestVideo(): JsonObject? = maxByOrNull { it.number("height") * 1_000_000 + it.number("tbr") }

private fun JsonObject.mediaMimeType(): String? {
    val url = mediaUrl().orEmpty().substringBefore('?')
    val protocol = text("protocol").orEmpty()
    return when {
        protocol.startsWith("m3u8") || url.endsWith(".m3u8", true) -> "application/x-mpegURL"
        url.endsWith(".mpd", true) -> "application/dash+xml"
        text("ext") in setOf("mp4", "m4v") -> "video/mp4"
        text("ext") == "webm" -> "video/webm"
        else -> null
    }
}

private fun playbackHeaders(source: JsonObject): Map<String, String> {
    val allowed = mapOf("user-agent" to "User-Agent", "referer" to "Referer", "origin" to "Origin",
        "accept" to "Accept", "accept-language" to "Accept-Language")
    val values = source["http_headers"] as? JsonObject ?: return emptyMap()
    return values.mapNotNull { (name, value) ->
        val canonical = allowed[name.lowercase()] ?: return@mapNotNull null
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        if (text.any { it == '\r' || it == '\n' }) null else canonical to text
    }.toMap()
}
