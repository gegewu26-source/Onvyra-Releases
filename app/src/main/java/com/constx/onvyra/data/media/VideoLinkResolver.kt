package com.constx.onvyra.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.constx.onvyra.security.PrivateMediaAccess

private const val MAX_VIDEO_LINK_REDIRECTS = 6
private const val VIDEO_LINK_TIMEOUT_MS = 15_000L
private const val SHORT_LINK_CACHE_NS = 5L * 60L * 1_000_000_000L
private const val MAX_SHORT_LINK_CACHE = 64
private const val VIDEO_LINK_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/** Resolves public player addresses; never downloads a video to discover its final URL. */
class VideoLinkResolver(httpClient: OkHttpClient) {
    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(VIDEO_LINK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .cache(null)
        .build()

    private data class ResolvedLink(val link: VideoLink, val mimeType: String? = null)
    private data class CachedSource(val target: ResolvedLink, val createdAt: Long)
    private val cache = object : LinkedHashMap<String, CachedSource>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSource>?): Boolean =
            size > MAX_SHORT_LINK_CACHE
    }

    suspend fun resolve(link: VideoLink): VideoPlaybackSource {
        val resolved = resolveTarget(link)
        return playbackSource(resolved.link)?.let { source ->
            if (resolved.mimeType != null) source.copy(mimeType = resolved.mimeType) else source
        }?.also {
            requireAllowedVideoLink(link)
            requireAllowedPlaybackSource(it)
        } ?: throw IOException("分享链接没有返回可识别的视频地址，可尝试增强解析")
    }

    /** Verify an explicit media link using headers only, then play it through the native player. */
    suspend fun verifyDirect(link: VideoLink): VideoPlaybackSource = withContext(Dispatchers.IO) {
        requireAllowedVideoLink(link)
        val canonical = parseVideoLink(link.url)?.takeIf { it.platform == VideoPlatform.DIRECT }
            ?: throw IOException("这不是可识别的视频直链")
        withVideoLinkTimeout {
            var target = canonical.url.toHttpUrlOrNull() ?: throw IOException("视频链接无效")
            val visited = HashSet<String>()
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                PrivateMediaAccess.requireAllowed(url = target.toString())
                if (!visited.add(target.toString())) throw IOException("视频链接出现循环跳转")
                val response = readHeaders(target)
                if (response.code in 300..399 && response.code != 304) {
                    if (redirects >= MAX_VIDEO_LINK_REDIRECTS) throw IOException("视频链接跳转次数过多")
                    val destination = response.location?.let(target::resolve)
                        ?: throw IOException("视频链接缺少有效的跳转地址")
                    if (destination.username.isNotEmpty() || destination.password.isNotEmpty()) {
                        throw IOException("视频跳转地址包含不支持的登录信息")
                    }
                    PrivateMediaAccess.requireAllowed(url = destination.toString())
                    target = destination
                    redirects++
                    continue
                }
                if (response.code !in 200..299) throw IOException("视频直链暂时不可用（HTTP ${response.code}）")
                val receivedMime = response.contentType?.substringBefore(';')?.trim()?.lowercase()
                    ?.takeIf(String::isNotEmpty)
                val mime = when {
                    receivedMime in HLS_CONTENT_TYPES -> "application/x-mpegURL"
                    receivedMime == "application/dash+xml" -> receivedMime
                    receivedMime?.startsWith("video/") == true -> receivedMime
                    receivedMime == null || receivedMime == "application/octet-stream" ->
                        videoMimeType(target.toString()) ?: videoMimeType(canonical.url)
                    else -> throw IOException("视频直链返回了网页或其他非视频内容")
                }
                return@withVideoLinkTimeout VideoPlaybackSource(
                    url = target.toString(), kind = VideoPlaybackKind.DIRECT, mimeType = mime,
                    headers = mapOf("User-Agent" to VIDEO_LINK_USER_AGENT),
                ).also {
                    requireAllowedVideoLink(link)
                    requireAllowedPlaybackSource(it)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable direct video verification state")
        }
    }

    /** Resolve sharing redirects to the actual video webpage before local enhanced extraction. */
    suspend fun resolveLink(link: VideoLink): VideoLink = resolveTarget(link).link.also {
        requireAllowedVideoLink(link)
        requireAllowedVideoLink(it)
    }

    private suspend fun resolveTarget(link: VideoLink): ResolvedLink = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        requireAllowedVideoLink(link)
        // Re-parse rather than trusting caller-supplied embed addresses or platform labels.
        val canonical = parseVideoLink(link.url) ?: throw IOException("这不是可识别的视频链接")
        playbackSource(canonical)?.let { return@withContext ResolvedLink(canonical) }
        val cached = synchronized(cache) {
            cache[canonical.url]?.takeIf { System.nanoTime() - it.createdAt < SHORT_LINK_CACHE_NS }?.target
        }
        if (cached != null) {
            requireAllowedVideoLink(link)
            requireAllowedVideoLink(cached.link)
            return@withContext cached
        }

        val resolved = withVideoLinkTimeout {
            var target = canonical.url.toHttpUrlOrNull() ?: throw IOException("视频链接无效")
            val visited = HashSet<String>()
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                PrivateMediaAccess.requireAllowed(url = target.toString())
                if (!visited.add(target.toString())) throw IOException("视频分享链接出现循环跳转")
                parseVideoLink(target.toString())?.let { parsed ->
                    playbackSource(parsed)?.let { return@withVideoLinkTimeout ResolvedLink(parsed) }
                }
                val response = readHeaders(target)
                if (response.code in 300..399 && response.code != 304) {
                    if (redirects >= MAX_VIDEO_LINK_REDIRECTS) throw IOException("视频分享链接跳转次数过多")
                    val destination = response.location?.let(target::resolve)
                        ?: throw IOException("视频分享链接缺少有效的跳转地址")
                    if (destination.username.isNotEmpty() || destination.password.isNotEmpty()) {
                        throw IOException("视频跳转地址包含不支持的登录信息")
                    }
                    PrivateMediaAccess.requireAllowed(url = destination.toString())
                    target = destination
                    redirects++
                    continue
                }
                if (response.code !in 200..299) throw IOException("视频分享链接暂时不可用（HTTP ${response.code}）")
                val mime = response.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (mime != null && (mime.startsWith("video/") || mime in STREAM_CONTENT_TYPES)) {
                    return@withVideoLinkTimeout ResolvedLink(VideoLink(target.toString(), VideoPlatform.DIRECT), mime)
                }
                // Some share services use a script in the final HTML page. Preserve that final
                // webpage for the embedded player/local extractor without reading or running it here.
                if (mime in setOf("text/html", "application/xhtml+xml")) {
                    return@withVideoLinkTimeout ResolvedLink(VideoLink(target.toString(), VideoPlatform.OTHER))
                }
                throw IOException("分享链接没有返回可识别的视频地址，可尝试粘贴视频完整链接")
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable video resolution state")
        }
        requireAllowedVideoLink(link)
        requireAllowedVideoLink(resolved.link)
        synchronized(cache) { cache[canonical.url] = CachedSource(resolved, System.nanoTime()) }
        resolved
    }

    private suspend fun <T> withVideoLinkTimeout(block: suspend () -> T): T = try {
        withTimeout(VIDEO_LINK_TIMEOUT_MS) { block() }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("视频链接验证超时，可重试或使用原平台播放", error)
    }

    private fun playbackSource(link: VideoLink): VideoPlaybackSource? = when {
        link.platform == VideoPlatform.DIRECT -> VideoPlaybackSource(
            url = link.url, kind = VideoPlaybackKind.DIRECT, mimeType = videoMimeType(link.url),
        )
        link.embedUrl != null -> VideoPlaybackSource(
            url = link.embedUrl, kind = VideoPlaybackKind.EMBEDDED, title = link.platform.displayName,
        )
        link.platform == VideoPlatform.OTHER -> VideoPlaybackSource(
            url = link.url, kind = VideoPlaybackKind.EMBEDDED, title = link.platform.displayName,
        )
        else -> null
    }

    /** Cancellation closes the socket; response bodies are closed without reading media bytes. */
    private suspend fun readHeaders(url: HttpUrl): VideoLinkHeaders = suspendCancellableCoroutine { continuation ->
        PrivateMediaAccess.requireAllowed(url = url.toString())
        val request = Request.Builder().url(url)
            .header("User-Agent", VIDEO_LINK_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,video/*;q=0.9,*/*;q=0.8")
            .header("Range", "bytes=0-0")
            .get().build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val headers = response.use {
                        VideoLinkHeaders(it.code, it.header("Location"), it.header("Content-Type"))
                    }
                    PrivateMediaAccess.requireAllowed(url = url.toString())
                    if (continuation.isActive) continuation.resume(headers)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

private fun requireAllowedVideoLink(link: VideoLink) {
    PrivateMediaAccess.requireAllowed(url = link.url)
    link.embedUrl?.let { PrivateMediaAccess.requireAllowed(url = it) }
}

internal fun requireAllowedPlaybackSource(source: VideoPlaybackSource) {
    PrivateMediaAccess.requireAllowed(url = source.url)
    source.audioUrl?.let { PrivateMediaAccess.requireAllowed(url = it) }
    source.headers.forEach { (name, value) ->
        if (name.equals("Referer", true) || name.equals("Origin", true)) PrivateMediaAccess.requireAllowed(url = value)
    }
}

private data class VideoLinkHeaders(val code: Int, val location: String?, val contentType: String?)

private val HLS_CONTENT_TYPES = setOf(
    "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/mpegurl",
    "audio/mpegurl", "audio/x-mpegurl",
)
private val STREAM_CONTENT_TYPES = HLS_CONTENT_TYPES + "application/dash+xml"
