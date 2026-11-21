package com.constx.onvyra.data.media

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class VideoPlatform(val displayName: String) {
    BILIBILI("哔哩哔哩"), DOUYIN("抖音"), YOUTUBE("YouTube"), TIKTOK("TikTok"),
    VIMEO("Vimeo"), DIRECT("视频"), OTHER("视频网页"),
}

data class VideoLink(
    val url: String,
    val platform: VideoPlatform,
    val videoId: String? = null,
    val embedUrl: String? = null,
)

enum class VideoPlaybackKind { DIRECT, EMBEDDED }

data class VideoPlaybackSource(
    val url: String,
    val kind: VideoPlaybackKind,
    val audioUrl: String? = null,
    val title: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val durationSeconds: Double? = null,
)

private val videoUrl = Regex("https?://[^\\s<>\"'`，。！？；、【】《》「」『』]+", RegexOption.IGNORE_CASE)
private val biliId = Regex("BV[A-Za-z0-9]{10}")
private val avId = Regex("av([0-9]{1,20})", RegexOption.IGNORE_CASE)
private val numericId = Regex("[0-9]{1,32}")
private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
private val directExtensions = setOf("mp4", "m4v", "mov", "webm", "m3u8", "mpd")

/** Recognize shared video links in prose/Markdown without treating code examples as previews. */
fun extractVideoLinks(text: String): List<VideoLink> {
    val visible = withoutCodeExamples(text)
    val found = LinkedHashMap<String, VideoLink>()
    videoUrl.findAll(visible).forEach { match ->
        parseVideoLink(trimLinkPunctuation(match.value))?.let { link ->
            found.putIfAbsent(link.embedUrl ?: link.url, link)
        }
    }
    return found.values.toList()
}

/** HTTP(S) links only. Platform roots/profiles and lookalike domains remain ordinary links. */
fun parseVideoLink(url: String): VideoLink? {
    val parsed = trimLinkPunctuation(url.trim()).toHttpUrlOrNull() ?: return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    val normalized = parsed.toString()
    if (parsed.pathSegments.lastOrNull().orEmpty().substringAfterLast('.', "").lowercase() in directExtensions) {
        return VideoLink(normalized, VideoPlatform.DIRECT)
    }
    val host = parsed.host
    val path = parsed.pathSegments.filter(String::isNotEmpty)
    if (host.isDomain("bilibili.com")) {
        if (path.take(2) == listOf("bangumi", "play") &&
            path.getOrNull(2)?.matches(Regex("(?:ep|ss)[0-9]{1,20}")) == true) {
            // Episode/season IDs are not BV IDs. Keep the real playback page rather than
            // inventing an official iframe URL that cannot accept these identifiers.
            return VideoLink(normalized, VideoPlatform.OTHER, videoId = path[2])
        }
        val pathId = path.getOrNull(1).takeIf { path.firstOrNull() == "video" }
        val bvid = (pathId ?: parsed.queryParameter("bvid"))?.takeIf { biliId.matches(it) }
        val aid = pathId?.let { avId.matchEntire(it)?.groupValues?.get(1) }
            ?: parsed.queryParameter("aid")?.takeIf(numericId::matches)
        if (bvid != null || aid != null) {
            val embed = "https://player.bilibili.com/player.html".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter(if (bvid != null) "bvid" else "aid", bvid ?: aid)
            parsed.queryParameter("p")?.toIntOrNull()?.takeIf { it > 0 }?.let { embed.addQueryParameter("p", it.toString()) }
            timestampSeconds(parsed)?.let { embed.addQueryParameter("t", it.toString()) }
            embed.addQueryParameter("autoplay", "0")
            return VideoLink(normalized, VideoPlatform.BILIBILI, bvid ?: "av$aid", embed.build().toString())
        }
        return null
    }
    if (host == "b23.tv" || host == "www.b23.tv") {
        return path.takeIf { it.isNotEmpty() }?.let { VideoLink(normalized, VideoPlatform.BILIBILI) }
    }
    if (host.isDomain("douyin.com") || host.isDomain("iesdouyin.com")) {
        val videoIndex = path.indexOf("video")
        val id = parsed.queryParameter("modal_id")?.takeIf(numericId::matches)
            ?: parsed.queryParameter("vid")?.takeIf { host == "open.douyin.com" && numericId.matches(it) }
            ?: path.getOrNull(videoIndex + 1)?.takeIf { videoIndex >= 0 && numericId.matches(it) }
        if (id != null) {
            return VideoLink(normalized, VideoPlatform.DOUYIN, id,
                "https://open.douyin.com/player/video?vid=$id&autoplay=0")
        }
        return if (host == "v.douyin.com" && path.isNotEmpty()) VideoLink(normalized, VideoPlatform.DOUYIN) else null
    }
    if (host.isDomain("tiktok.com")) {
        val videoIndex = path.indexOf("video")
        val id = path.getOrNull(videoIndex + 1)?.takeIf { videoIndex >= 0 && numericId.matches(it) }
            ?: path.getOrNull(2)?.takeIf { path.take(2) == listOf("player", "v1") && numericId.matches(it) }
        if (id != null) {
            return VideoLink(normalized, VideoPlatform.TIKTOK, id, "https://www.tiktok.com/player/v1/$id?autoplay=0")
        }
        return if ((host in setOf("vm.tiktok.com", "vt.tiktok.com") && path.isNotEmpty()) ||
            (path.firstOrNull() == "t" && path.size >= 2)) VideoLink(normalized, VideoPlatform.TIKTOK) else null
    }
    if (host.isDomain("youtube.com") || host.isDomain("youtube-nocookie.com") || host == "youtu.be" || host == "www.youtu.be") {
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" -> path.firstOrNull()
            path.firstOrNull() == "watch" -> parsed.queryParameter("v")
            path.firstOrNull() in setOf("embed", "shorts", "live") -> path.getOrNull(1)
            else -> null
        }?.takeIf(youtubeId::matches) ?: return null
        val embed = "https://www.youtube-nocookie.com/embed/$id".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("enablejsapi", "1").addQueryParameter("autoplay", "0")
        timestampSeconds(parsed)?.let { embed.addQueryParameter("start", it.toString()) }
        return VideoLink(normalized, VideoPlatform.YOUTUBE, id, embed.build().toString())
    }
    if (host.isDomain("vimeo.com")) {
        val id = when {
            path.size in 1..2 && numericId.matches(path[0]) -> path[0]
            path.firstOrNull() == "video" -> path.getOrNull(1)
            path.firstOrNull() == "channels" && path.size == 3 -> path.last()
            path.firstOrNull() == "groups" && path.getOrNull(2) == "videos" && path.size == 4 -> path.last()
            else -> null
        }?.takeIf(numericId::matches) ?: return null
        val embed = "https://player.vimeo.com/video/$id".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("autoplay", "0")
        val privateHash = parsed.queryParameter("h") ?: path.getOrNull(1)?.takeIf { path.firstOrNull() == id }
        privateHash?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,64}")) }?.let { embed.addQueryParameter("h", it) }
        return VideoLink(normalized, VideoPlatform.VIMEO, id, embed.build().toString())
    }
    val otherVideoPage = when {
        host.isDomain("kuaishou.com") -> (host == "v.kuaishou.com" && path.isNotEmpty()) ||
            (path.firstOrNull() == "short-video" && path.size >= 2)
        host.isDomain("weibo.com") -> path.take(2) == listOf("tv", "show") && path.size >= 3 ||
            host == "video.weibo.com" && (parsed.queryParameter("fid") != null || path.firstOrNull() == "show")
        host.isDomain("instagram.com") -> path.firstOrNull() in setOf("reel", "reels", "tv") && path.size >= 2
        host.isDomain("x.com") || host.isDomain("twitter.com") -> path.size >= 5 && path[1] == "status" &&
            numericId.matches(path[2]) && path[3] == "video" && path[4].toIntOrNull() != null
        identifyExternalVideoSite(normalized) != null -> true
        else -> false
    }
    return if (otherVideoPage) VideoLink(normalized, VideoPlatform.OTHER) else null
}

internal fun String.isDomain(domain: String): Boolean = this == domain || endsWith(".$domain")

internal fun videoMimeType(url: String): String? = when (url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.substringAfterLast('.', "")?.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "m3u8" -> "application/x-mpegURL"
    "mpd" -> "application/dash+xml"
    else -> null
}

private fun timestampSeconds(url: HttpUrl): Int? {
    val raw = url.queryParameter("start") ?: url.queryParameter("t")
        ?: url.fragment?.substringAfter("t=", "")?.substringBefore('&')
        ?: return null
    raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE }?.let { return it.toInt() }
    val match = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?", RegexOption.IGNORE_CASE).matchEntire(raw) ?: return null
    if (match.value.isEmpty()) return null
    val values = match.groupValues.drop(1).map { value ->
        // An omitted unit is zero; an overflowing number is invalid, not a zero-length seek.
        if (value.isEmpty()) 0L else value.toLongOrNull() ?: return null
    }
    if (values.any { it > Int.MAX_VALUE }) return null
    return (values[0] * 3600 + values[1] * 60 + values[2]).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
}

private fun trimLinkPunctuation(value: String): String {
    var result = value
    val always = "，。！？；：、）】》」』.,!?;:"
    while (result.isNotEmpty()) {
        val last = result.last()
        val excessClosing = when (last) {
            ')' -> result.count { it == ')' } > result.count { it == '(' }
            ']' -> result.count { it == ']' } > result.count { it == '[' }
            '}' -> result.count { it == '}' } > result.count { it == '{' }
            else -> false
        }
        if (last in always || excessClosing) result = result.dropLast(1) else break
    }
    return result
}

/** Preserve line breaks/length while masking fenced code, then matching backtick spans. */
private fun withoutCodeExamples(text: String): String {
    val output = text.toCharArray()
    var fence: Char? = null
    var fenceLength = 0
    var lineStart = 0
    while (lineStart < text.length) {
        val end = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, end).trimEnd('\r')
        val marker = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
        val wasInside = fence != null
        if (fence == null && marker != null &&
            !(marker.groupValues[1][0] == '`' && marker.groupValues[2].contains('`'))) {
            fence = marker.groupValues[1][0]
            fenceLength = marker.groupValues[1].length
        } else if (fence != null && marker != null && marker.groupValues[1][0] == fence &&
            marker.groupValues[1].length >= fenceLength && marker.groupValues[2].isBlank()) fence = null
        if (wasInside || fence != null) for (i in lineStart until end) if (output[i] != '\r') output[i] = ' '
        lineStart = end + 1
    }
    var i = 0
    while (i < output.size) {
        if (output[i] != '`') { i++; continue }
        val start = i
        while (i < output.size && output[i] == '`') i++
        val length = i - start
        var end = i
        var closing = -1
        while (end < output.size) {
            if (output[end] != '`') { end++; continue }
            val runStart = end
            while (end < output.size && output[end] == '`') end++
            if (end - runStart == length) { closing = end; break }
        }
        if (closing >= 0) {
            for (j in start until closing) if (output[j] != '\n' && output[j] != '\r') output[j] = ' '
            i = closing
        }
    }
    return String(output)
}
