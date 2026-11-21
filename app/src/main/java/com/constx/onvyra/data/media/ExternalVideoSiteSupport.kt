package com.constx.onvyra.data.media

import com.constx.onvyra.security.PrivateMediaAccess

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal const val SITE_ID_JABLE = "jable"
internal const val SITE_ID_MISSAV = "missav"
internal const val SITE_ID_SUPJAV = "supjav"
internal const val SITE_ID_HANIME1 = "hanime1"

internal val EXTERNAL_VIDEO_SITE_IDS = setOf(
    SITE_ID_JABLE,
    SITE_ID_MISSAV,
    SITE_ID_SUPJAV,
    SITE_ID_HANIME1,
)

internal val EXTERNAL_VIDEO_CATALOG_SOURCES = listOfNotNull(
    MovieCatalogSource(SITE_ID_MISSAV, "MissAV", "https://${ExternalVideoEndpointConfig.host("missav_primary")}/cn/search/").takeIf { ExternalVideoEndpointConfig.configuredHost("missav_primary") != null },
    MovieCatalogSource(SITE_ID_JABLE, "JableTV", "https://${ExternalVideoEndpointConfig.host("jable_primary")}/search/").takeIf { ExternalVideoEndpointConfig.configuredHost("jable_primary") != null },
    MovieCatalogSource(SITE_ID_HANIME1, "Hanime1", "https://${ExternalVideoEndpointConfig.host("hanime1_primary")}/search").takeIf { ExternalVideoEndpointConfig.configuredHost("hanime1_primary") != null },
    MovieCatalogSource(SITE_ID_SUPJAV, "SupJav", "https://${ExternalVideoEndpointConfig.host("supjav_primary")}/zh/").takeIf { ExternalVideoEndpointConfig.configuredHost("supjav_primary") != null },
)

internal val JABLE_MIRRORS = ExternalVideoEndpointConfig.mirrors("jable_primary", "jable_mirror")

/**
 * All six mirrors declared by MissAV's own anti-tamper script (verified 2026-10-31, all serve
 * identical video pages with the packed `configured missav stream` playlist). Failover order puts the fastest
 * full-featured mirror first; dead hosts (`configured missav legacy 1`/`configured missav legacy 2`/`configured missav legacy 4`) are rewritten away.
 */
internal val MISSAV_MIRRORS = ExternalVideoEndpointConfig.mirrors("missav_primary", "missav_mirror_1", "missav_mirror_2", "missav_mirror_3", "missav_mirror_4", "missav_mirror_5")
internal val MISSAV_KNOWN_HOSTS = setOf(
    ExternalVideoEndpointConfig.host("missav_primary"), ExternalVideoEndpointConfig.host("missav_mirror_1"), ExternalVideoEndpointConfig.host("missav_mirror_2"), ExternalVideoEndpointConfig.host("missav_mirror_3"), ExternalVideoEndpointConfig.host("missav_mirror_4"), ExternalVideoEndpointConfig.host("missav_mirror_5"),
    ExternalVideoEndpointConfig.host("missav_legacy_1"), ExternalVideoEndpointConfig.host("missav_legacy_2"), ExternalVideoEndpointConfig.host("missav_legacy_3"), ExternalVideoEndpointConfig.host("missav_legacy_4"),
)
internal val SUPJAV_MIRRORS = ExternalVideoEndpointConfig.mirrors("supjav_primary")
internal val HANIME1_MIRRORS = ExternalVideoEndpointConfig.mirrors("hanime1_primary", "hanime1_mirror_1", "hanime1_mirror_2")
internal val HANIME1_ALL_HOSTS = setOf(ExternalVideoEndpointConfig.host("hanime1_primary"), ExternalVideoEndpointConfig.host("hanime1_mirror_1"), ExternalVideoEndpointConfig.host("hanime1_mirror_2"), ExternalVideoEndpointConfig.host("hanime1_alternate"))

/**
 * Standard User-Agent aligned with Han1meViewer (`Chrome/149.0.0.0 Mobile Safari/537.36`),
 * which passes Hanime1, MissAV123, and Jable (configured jable primary) directly over OkHttp.
 */
private const val DEFAULT_SITE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

private val HANIME_PRIMARY_CDN = ExternalVideoEndpointConfig.host("hanime1_stream_primary")
private val HANIME_BACKUP_CDN = ExternalVideoEndpointConfig.host("hanime1_stream_backup")
private val hanimeAvCdnRegex = Regex("^(\\s*(?:https?:)?//)t\\d+\\.${Regex.escape(ExternalVideoEndpointConfig.host("hanime1_restricted_2"))}(?=[/:]|\\z)", RegexOption.IGNORE_CASE)

private const val SITE_HTML_LIMIT = 2 * 1024 * 1024
private const val SITE_CALL_TIMEOUT_MS = 10_000L
private const val PACKER_MAX_KEYS = 200_000
private const val PACKER_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
private val webViewGate = Semaphore(1)

internal fun isExternalVideoSiteSource(sourceId: String): Boolean = sourceId in EXTERNAL_VIDEO_SITE_IDS

/**
 * Rewrites dead MissAV domains (`configured missav legacy 1`, `configured missav legacy 2`, `configured missav legacy 4`, `configured missav legacy 3`) to `configured missav primary`,
 * and rewrites `configured jable mirror` to `configured jable primary` for primary requests.
 */
internal fun rewriteToActiveMirror(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    val host = parsed.host.lowercase(Locale.ROOT).removePrefix("www.")
    return when {
        host in MISSAV_KNOWN_HOSTS && host !in MISSAV_MIRRORS ->
            parsed.newBuilder().scheme("https").host(ExternalVideoEndpointConfig.host("missav_primary")).build().toString()
        else -> url
    }
}

internal fun identifyExternalVideoSite(url: String): String? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    val host = parsed.host.lowercase(Locale.ROOT).removePrefix("www.")
    val path = parsed.encodedPath
    return when {
        JABLE_MIRRORS.any { host == it || host.endsWith(".$it") } &&
            Regex("^/videos/[A-Za-z0-9_-]+/?$", RegexOption.IGNORE_CASE).matches(path) -> SITE_ID_JABLE
        MISSAV_KNOWN_HOSTS.any { host == it || host.endsWith(".$it") } &&
            extractMissAvSlug(parsed) != null -> SITE_ID_MISSAV
        SUPJAV_MIRRORS.any { host == it || host.endsWith(".$it") } &&
            Regex("^/(?:(?:zh|ja|en)/)?\\d+\\.html$", RegexOption.IGNORE_CASE).matches(path) -> SITE_ID_SUPJAV
        HANIME1_ALL_HOSTS.any { host == it || host.endsWith(".$it") } &&
            path in setOf("/watch", "/download") &&
            parsed.queryParameter("v")?.all(Char::isDigit) == true -> SITE_ID_HANIME1
        else -> null
    }
}

internal fun extractMissAvSlug(url: HttpUrl): String? {
    val segments = url.pathSegments.filter(String::isNotEmpty)
    if (segments.isEmpty()) return null
    val excludedPrefixes = setOf(
        "search", "tags", "tag", "categories", "category",
        "actresses", "actors", "makers", "genres", "login", "register", "playlists",
    )
    if (segments.any { it.lowercase(Locale.ROOT) in excludedPrefixes }) return null
    val slug = segments.last()
    if (!slug.any(Char::isDigit)) return null
    if (Regex("^dm\\d+$", RegexOption.IGNORE_CASE).matches(slug)) return null
    if (!Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$").matches(slug)) return null
    return slug
}

private val javCodeFc2 = Regex("(?i)(?<![A-Z0-9])FC2[-_ ]*(?:PPV[-_ ]*)?(\\d{5,10})(?![A-Z0-9])")
private val javCodeNumber = Regex("(?i)(?<![A-Z0-9])(\\d{6})[-_](\\d{2,4})(?![A-Z0-9])")
private val javCodeStandard = Regex("(?i)(?<![A-Z0-9])([A-Z]{2,10})[-_ ]?(\\d{2,6})(?:[-_ ]?[C|U])?(?![A-Z0-9])")
private val nonJavPrefixes = setOf("HTTP", "HTTPS", "HTML", "MP4", "M3U8", "H264", "H265", "HEVC", "UTF", "FPS")

internal fun extractJavCode(vararg texts: String?): String? {
    for (text in texts) {
        if (text.isNullOrBlank()) continue
        javCodeFc2.find(text)?.let { return "FC2-PPV-${it.groupValues[1]}" }
        javCodeNumber.find(text)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}" }
        for (match in javCodeStandard.findAll(text)) {
            val prefix = match.groupValues[1].uppercase(Locale.ROOT)
            if (prefix in nonJavPrefixes) continue
            val digits = match.groupValues[2]
            return "$prefix-$digits"
        }
    }
    return null
}

private val SIMPLIFIED_TO_TRADITIONAL = mapOf(
    '结' to '結', '泽' to '澤', '苍' to '蒼', '枫' to '楓', '樱' to '櫻', '爱' to '愛',
    '纱' to '紗', '亚' to '亞', '桥' to '橋', '实' to '實', '华' to '華', '绪' to '緒',
    '圣' to '聖', '宫' to '宮', '岛' to '島', '园' to '園', '怜' to '憐', '咏' to '詠',
    '鹫' to '鷲', '凉' to '涼', '铃' to '鈴', '斋' to '齋', '户' to '戶', '书' to '書',
    '无' to '無', '码' to '碼', '里' to '裏', '师' to '師', '护' to '護', '丝' to '絲',
    '袜' to '襪', '办' to '辦', '温' to '溫', '电' to '電', '车' to '車', '时' to '時',
    '间' to '間', '学' to '學', '义' to '義', '优' to '優', '内' to '內', '伦' to '倫',
    '乱' to '亂', '妇' to '婦', '妈' to '媽', '继' to '繼', '亲' to '親', '邻' to '鄰',
    '纯' to '純', '恋' to '戀', '后' to '後', '开' to '開', '药' to '藥', '汉' to '漢',
    '颜' to '顏', '溃' to '潰', '猎' to '獵', '捆' to '綑', '绑' to '綁', '调' to '調',
    '异' to '異', '寻' to '尋', '欢' to '歡', '隶' to '隸', '强' to '強', '轮' to '輪',
    '奸' to '姦', '样' to '樣', '轨' to '軌', '摄' to '攝', '机' to '機', '虫' to '蟲',
    '转' to '轉', '换' to '換', '兽' to '獸', '发' to '髮', '双' to '雙', '贫' to '貧',
    '肤' to '膚', '晒' to '曬', '镜' to '鏡', '齿' to '齒', '阴' to '陰', '装' to '裝',
    '体' to '體', '库' to '庫', '围' to '圍', '队' to '隊', '带' to '帶', '热' to '熱',
    '裤' to '褲', '紧' to '緊', '纹' to '紋', '写' to '寫', '图' to '圖', '馆' to '館',
    '场' to '場', '宾' to '賓', '医' to '醫', '厕' to '廁', '众' to '眾', '战' to '戰',
    '游' to '遊', '营' to '營', '帐' to '帳', '滩' to '灘', '监' to '監', '狱' to '獄',
    '处' to '處', '萝' to '蘿', '灵' to '靈', '剑' to '劍', '骑' to '騎', '仆' to '僕',
    '恶' to '惡', '堕' to '墮', '娇' to '嬌', '伪' to '偽', '触' to '觸', '宁' to '寧',
)

private val TRADITIONAL_TO_SIMPLIFIED = SIMPLIFIED_TO_TRADITIONAL.entries.associate { (s, t) -> t to s }

internal fun toTraditionalChinese(text: String): String = buildString(text.length) {
    for (ch in text) append(SIMPLIFIED_TO_TRADITIONAL[ch] ?: ch)
}

internal fun toSimplifiedChinese(text: String): String = buildString(text.length) {
    for (ch in text) append(TRADITIONAL_TO_SIMPLIFIED[ch] ?: ch)
}

internal fun normalizeCjkForMatch(value: String): String {
    val shinjitai = mapOf(
        '黒' to '黑', '獣' to '兽', '姫' to '姬', '桜' to '樱', '亜' to '亚', '収' to '收',
        '毎' to '每', '歩' to '步', '絆' to '绊', '楽' to '乐', '転' to '转', '実' to '实',
        '浄' to '净', '剣' to '剑', '変' to '变', '単' to '单', '弾' to '弹', '拡' to '扩',
        '晩' to '晚', '歯' to '齿', '瀬' to '濑', '発' to '发', '厳' to '严', '剤' to '剂',
        '訳' to '译', '読' to '读', '続' to '续', '雑' to '杂', '顔' to '颜', '麗' to '丽',
    )
    fun convert(text: String): String = buildString(text.length) {
        for (ch in text) append(shinjitai[ch] ?: ch)
    }
    return toSimplifiedChinese(convert(Normalizer.normalize(value, Normalizer.Form.NFKC)))
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s\\p{P}]+"), "")
}

/**
 * Determines whether a catalog item matches the user's search query.
 */
internal fun isMatchingCatalogSearchResult(requested: String, item: MovieCatalogItem): Boolean {
    if (isMatchingMovieTitle(requested, item.title)) return true
    val requestedCode = extractJavCode(requested)
    val itemCode = extractJavCode(item.title, item.id)
    if (requestedCode != null && itemCode != null && requestedCode.equals(itemCode, ignoreCase = true)) {
        return true
    }
    if (item.sourceId in EXTERNAL_VIDEO_SITE_IDS) {
        val normReq = normalizeCjkForMatch(requested)
        val normTitle = normalizeCjkForMatch(item.title)
        if (normReq.isNotEmpty() && normTitle.contains(normReq)) return true
        val tokens = Normalizer.normalize(requested, Normalizer.Form.NFKC)
            .trim()
            .split(Regex("[\\s·_\\-/]+"))
            .map(::normalizeCjkForMatch)
            .filter { it.length >= 2 }
        if (tokens.isNotEmpty() && tokens.any { normTitle.contains(it) }) return true
        // When the user searches by actress/tag/genre on MissAV/Jable/Hanime1/SupJav,
        // the site's search engine returns matching videos even if the truncated card title omits the tag.
        if (requestedCode == null && item.lines.isNotEmpty()) return true
    }
    return false
}

/**
 * Unpacks ALL Dean Edwards `eval(function(p,a,c,k,e,d){...}('payload', radix, count, 'dict'.split('|')))`
 * blocks in [scriptText]. MissAV pages often contain multiple `eval(function...)` blocks in the same `<script>`.
 */
internal fun unpackAllDeanEdwardsPackers(scriptText: String): List<String> {
    val results = ArrayList<String>(2)
    val splitMarker = "'.split('|')"
    var searchFrom = 0
    while (searchFrom < scriptText.length) {
        val dictEnd = scriptText.indexOf(splitMarker, searchFrom)
        if (dictEnd < 0) break
        searchFrom = dictEnd + splitMarker.length

        val dictStart = findUnescapedSingleQuoteBefore(scriptText, dictEnd - 1)
        if (dictStart < 0) continue
        val dictStr = scriptText.substring(dictStart + 1, dictEnd)
        val keys = dictStr.split('|')

        val beforeDict = scriptText.substring(0, dictStart).trimEnd()
        if (!beforeDict.endsWith(",")) continue
        val withoutTrailingComma = beforeDict.dropLast(1).trimEnd()

        val countComma = withoutTrailingComma.lastIndexOf(',')
        if (countComma < 0) continue
        val count = withoutTrailingComma.substring(countComma + 1).trim().toIntOrNull() ?: continue

        val beforeCount = withoutTrailingComma.substring(0, countComma).trimEnd()
        val radixComma = beforeCount.lastIndexOf(',')
        if (radixComma < 0) continue
        val radix = beforeCount.substring(radixComma + 1).trim().toIntOrNull() ?: continue

        if (radix !in 2..62 || count !in 0..PACKER_MAX_KEYS) continue

        val beforeRadix = beforeCount.substring(0, radixComma).trimEnd()
        if (!beforeRadix.endsWith("'") && !beforeRadix.endsWith("\"")) continue
        val quoteChar = beforeRadix.last()
        val payloadEnd = beforeRadix.length - 1
        val callOpen = beforeRadix.lastIndexOf("}(")
        val payloadStart = if (callOpen >= 0) {
            beforeRadix.indexOf(quoteChar, callOpen + 2).takeIf { it in 0 until payloadEnd } ?: -1
        } else {
            findUnescapedQuoteBefore(scriptText, payloadEnd - 1, quoteChar)
        }
        if (payloadStart < 0) continue

        val rawPayload = scriptText.substring(payloadStart + 1, payloadEnd)
            .replace("\\\\'", "'")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
        val lookup = HashMap<String, String>(minOf(count, keys.size) + 4)
        for (i in 0 until count) {
            val word = keys.getOrNull(i).orEmpty()
            if (word.isNotEmpty()) {
                lookup[toPackerBase(i, radix)] = word
            }
        }
        val unpacked = Regex("\\b[0-9A-Za-z]+\\b").replace(rawPayload) { match ->
            lookup[match.value] ?: match.value
        }
        results.add(unpacked)
    }
    return results
}

internal fun unpackDeanEdwardsPacker(scriptText: String): String? =
    unpackAllDeanEdwardsPackers(scriptText).firstOrNull {
        it.contains("m3u8", ignoreCase = true) || it.contains("surrit", ignoreCase = true) || it.contains("http", ignoreCase = true)
    } ?: unpackAllDeanEdwardsPackers(scriptText).firstOrNull()

private fun findUnescapedSingleQuoteBefore(text: String, startIndex: Int): Int =
    findUnescapedQuoteBefore(text, startIndex, '\'')

private fun findUnescapedQuoteBefore(text: String, startIndex: Int, quote: Char): Int {
    var i = startIndex
    while (i >= 0) {
        if (text[i] == quote) {
            var backslashes = 0
            var j = i - 1
            while (j >= 0 && text[j] == '\\') {
                backslashes++
                j--
            }
            if (backslashes % 2 == 0) return i
        }
        i--
    }
    return -1
}

private fun toPackerBase(number: Int, radix: Int): String {
    if (number == 0) return "0"
    var n = number
    val sb = StringBuilder()
    while (n > 0) {
        sb.append(PACKER_ALPHABET[n % radix])
        n /= radix
    }
    return sb.reverse().toString()
}

private val escapedUrlSlashes = Regex("\\\\/")
private val m3u8UrlRegex = Regex("https?://[^\\s'\"<>\\\\]+?\\.m3u8(?:\\?[^\\s'\"<>\\\\]*)?", RegexOption.IGNORE_CASE)
private val hlsTxtUrlRegex = Regex("https?://[^\\s'\"<>\\\\]+?/hls[^\\s'\"<>\\\\]*?\\.txt(?:\\?[^\\s'\"<>\\\\]*)?", RegexOption.IGNORE_CASE)
private val mp4UrlRegex = Regex("https?://[^\\s'\"<>\\\\]+?\\.mp4(?:\\?[^\\s'\"<>\\\\]*)?", RegexOption.IGNORE_CASE)

private fun findMediaUrls(text: String, ext: String): List<String> {
    val unescaped = org.jsoup.parser.Parser.unescapeEntities(text, true).replace(escapedUrlSlashes, "/")
    val regexes = if (ext.equals("m3u8", ignoreCase = true)) {
        listOf(m3u8UrlRegex, hlsTxtUrlRegex)
    } else {
        listOf(mp4UrlRegex)
    }
    val results = LinkedHashSet<String>()
    for (regex in regexes) {
        for (match in regex.findAll(unescaped)) {
            val cleaned = match.value.trimEnd(')', ',', ';', ']', '\\', '\'', '"')
            if (cleaned.toHttpUrlOrNull() != null) {
                results.add(cleaned)
            }
        }
    }
    return results.toList()
}

private fun isCloudflareBlocked(statusCode: Int, headers: Map<String, String>, body: String): Boolean {
    if (statusCode in setOf(403, 429, 503)) return true
    if (headers["cf-mitigated"]?.contains("challenge", ignoreCase = true) == true) return true
    val head = body.take(20_000).lowercase(Locale.ROOT)
    return head.contains("<title>just a moment") ||
        head.contains("attention required! | cloudflare") ||
        head.contains("cf-browser-verification") ||
        head.contains("cf_chl_opt") ||
        head.contains("challenges.cloudflare.com/turnstile")
}

private fun extractHtmlTitle(doc: Document): String {
    val shareTitle = doc.getElementById("shareBtn-title")?.text()?.trim().orEmpty()
    if (shareTitle.isNotEmpty()) return cleanSiteTitle(shareTitle)
    val ogTitle = doc.selectFirst("meta[property=og:title][content]")?.attr("content")?.trim().orEmpty()
    if (ogTitle.isNotEmpty()) return cleanSiteTitle(ogTitle)
    val h3 = doc.selectFirst("h3.video-details-wrapper")?.text()?.trim().orEmpty()
    if (h3.isNotEmpty()) return cleanSiteTitle(h3)
    val h1 = doc.selectFirst("h1, h4")?.text()?.trim().orEmpty()
    if (h1.isNotEmpty()) return cleanSiteTitle(h1)
    return cleanSiteTitle(doc.title())
}

private val siteTitleSuffix = run {
    val labels = listOf(
        ExternalVideoEndpointConfig.host("jable_mirror"),
        "MissAV",
        ExternalVideoEndpointConfig.host("supjav_primary"),
        ExternalVideoEndpointConfig.host("hanime1_primary"),
        ExternalVideoEndpointConfig.host("hanime1_mirror_1"),
    ).joinToString("|") { Regex.escape(it) }
    Regex("\\s+-\\s+(?:$labels).*$", RegexOption.IGNORE_CASE)
}

private fun cleanSiteTitle(raw: String): String =
    raw.replace(siteTitleSuffix, "")
        .replace(Regex("\\s+\\|\\s+.*$"), "")
        .trim()

/**
 * Parses JableTV / configured jable primary search result HTML into [MovieCatalogItem]s.
 */
internal fun parseJableSearchHtml(html: String, baseUrl: String = "https://${ExternalVideoEndpointConfig.host("jable_primary")}/"): List<MovieCatalogItem> {
    val doc = Jsoup.parse(html, baseUrl)
    val host = baseUrl.toHttpUrlOrNull()?.host ?: ExternalVideoEndpointConfig.host("jable_primary")
    val memberOnlyUrls = doc.select("div.ribbon-top-left")
        .filter { it.text().contains("會員") || it.text().contains("会员") }
        .mapNotNull { it.parents().firstOrNull { p -> p.tagName() == "a" }?.absUrl("href") }
        .toSet()

    val results = LinkedHashMap<String, MovieCatalogItem>()
    val cards = doc.select("div.video-img-box").ifEmpty { doc.select("main a[href*=/videos/], div a[href*=/videos/]") }
    for (card in cards) {
        val anchor = if (card.tagName() == "a") card else {
            card.selectFirst("div.detail h6.title a[href], h6 a[href], a[href*=/videos/]") ?: continue
        }
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (href in memberOnlyUrls) continue
        val parsed = href.toHttpUrlOrNull() ?: baseUrl.toHttpUrlOrNull()?.resolve(href) ?: continue
        val segments = parsed.pathSegments.filter(String::isNotEmpty)
        if (segments.size < 2 || !segments[0].equals("videos", ignoreCase = true)) continue
        val slug = segments[1].takeIf { Regex("^[A-Za-z0-9_-]{1,80}$").matches(it) } ?: continue
        val title = sequenceOf(
            anchor.attr("title"),
            card.selectFirst("div.detail h6.title a, h6.title, .title")?.text().orEmpty(),
            card.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            anchor.text(),
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: continue

        val durationText = card.selectFirst("span.label")?.text()?.trim()
        val canonicalUrl = "https://$host/videos/$slug/"
        results.putIfAbsent(
            slug,
            MovieCatalogItem(
                id = slug,
                title = title,
                detailUrl = canonicalUrl,
                durationSeconds = parseClockDurationSeconds(durationText),
                lines = listOf(
                    MoviePlaybackLine(
                        name = "JableTV",
                        episodes = listOf(MovieEpisode("正片", canonicalUrl, 1)),
                    )
                ),
                sourceId = SITE_ID_JABLE,
                sourceName = "JableTV",
                remarks = durationText,
            )
        )
        if (results.size >= 20) break
    }
    return results.values.toList()
}

/**
 * Parses MissAV (`configured missav primary`) search result HTML into [MovieCatalogItem]s.
 */
internal fun parseMissAvSearchHtml(html: String, baseUrl: String = "https://${ExternalVideoEndpointConfig.host("missav_primary")}/"): List<MovieCatalogItem> {
    val doc = Jsoup.parse(html, baseUrl)
    val results = LinkedHashMap<String, MovieCatalogItem>()
    val cards = doc.select("div.thumbnail, div.video-item, article.video-item")
    for (card in cards) {
        val anchors = card.select("a[href]")
        val matchedAnchor = anchors.firstOrNull { a ->
            val u = a.absUrl("href").toHttpUrlOrNull() ?: baseUrl.toHttpUrlOrNull()?.resolve(a.attr("href"))
            u != null && extractMissAvSlug(u) != null
        } ?: continue
        val url = matchedAnchor.absUrl("href").toHttpUrlOrNull()
            ?: baseUrl.toHttpUrlOrNull()?.resolve(matchedAnchor.attr("href"))
            ?: continue
        val slug = extractMissAvSlug(url) ?: continue
        if (slug.length > 80) continue
        val title = sequenceOf(
            card.selectFirst("div.my-2 a, div.truncate a, a.text-secondary")?.text().orEmpty(),
            card.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            matchedAnchor.attr("title"),
            matchedAnchor.text(),
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: continue

        val enrichedTitle = enrichMissAvTitle(title, slug)
        val durationText = card.selectFirst("span.bottom-1.right-1")?.text()?.trim()
        val badgeText = card.selectFirst("span.bottom-1.left-1")?.text()?.trim()
        val canonicalUrl = "https://${ExternalVideoEndpointConfig.host("missav_primary")}/cn/$slug"
        results.putIfAbsent(
            slug,
            MovieCatalogItem(
                id = slug,
                title = enrichedTitle,
                detailUrl = canonicalUrl,
                durationSeconds = parseClockDurationSeconds(durationText),
                lines = listOf(
                    MoviePlaybackLine(
                        name = "MissAV",
                        episodes = listOf(MovieEpisode("正片", canonicalUrl, 1)),
                    )
                ),
                sourceId = SITE_ID_MISSAV,
                sourceName = "MissAV",
                remarks = listOfNotNull(badgeText, durationText).joinToString(" · ").takeIf { it.isNotEmpty() },
            )
        )
        if (results.size >= 20) break
    }
    return results.values.toList()
}

private fun enrichMissAvTitle(title: String, slug: String): String {
    val cleanTitle = title.trim()
    val code = extractJavCode(slug)
    return if (code != null && !cleanTitle.contains(code, ignoreCase = true) &&
        !cleanTitle.replace("_", "-").contains(code, ignoreCase = true)
    ) {
        "$code $cleanTitle"
    } else {
        cleanTitle
    }
}

/**
 * Parses SupJav search result HTML into [MovieCatalogItem]s.
 */
internal fun parseSupJavSearchHtml(html: String, baseUrl: String = "https://${ExternalVideoEndpointConfig.host("supjav_primary")}/zh/"): List<MovieCatalogItem> {
    val doc = Jsoup.parse(html, baseUrl)
    val results = LinkedHashMap<String, MovieCatalogItem>()
    for (post in doc.select("div.post")) {
        val anchor = post.selectFirst("h3 a[href], a.img[href], a[href*=.html]") ?: continue
        val url = anchor.absUrl("href").toHttpUrlOrNull()
            ?: baseUrl.toHttpUrlOrNull()?.resolve(anchor.attr("href"))
            ?: continue
        val match = Regex("^/(?:(?:zh|ja|en)/)?(\\d+)\\.html$", RegexOption.IGNORE_CASE).find(url.encodedPath)
            ?: continue
        val id = match.groupValues[1]
        val title = sequenceOf(
            anchor.attr("title"),
            post.selectFirst("h3 a")?.text().orEmpty(),
            post.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            anchor.text(),
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: continue

        val canonicalUrl = "https://${ExternalVideoEndpointConfig.host("supjav_primary")}/zh/$id.html"
        results.putIfAbsent(
            id,
            MovieCatalogItem(
                id = id,
                title = title,
                detailUrl = canonicalUrl,
                lines = listOf(
                    MoviePlaybackLine(
                        name = "SupJav",
                        episodes = listOf(MovieEpisode("正片", canonicalUrl, 1)),
                    )
                ),
                sourceId = SITE_ID_SUPJAV,
                sourceName = "SupJav",
            )
        )
        if (results.size >= 20) break
    }
    return results.values.toList()
}

/**
 * Parses Hanime1 (`configured hanime1 primary` / `configured hanime1 mirror 1` / `configured hanime1 mirror 2` / `configured hanime1 alternate`) search result HTML
 * supporting both Hanime1 Ver2 (`div[class^=horizontal-card]` and `div.home-rows-videos-wrapper`)
 * and legacy `.video-item-container` layouts (adapted from `Han1meViewer/Parser.kt`).
 */
internal fun parseHanime1SearchHtml(html: String, baseUrl: String = "https://${ExternalVideoEndpointConfig.host("hanime1_primary")}/"): List<MovieCatalogItem> {
    val doc = Jsoup.parse(html, baseUrl)
    val parsedBase = baseUrl.toHttpUrlOrNull()
    val host = parsedBase?.host?.lowercase(Locale.ROOT) ?: ExternalVideoEndpointConfig.host("hanime1_primary")
    val isJavChu = ExternalVideoEndpointConfig.matchesHost(host, "hanime1_alternate")
    val results = LinkedHashMap<String, MovieCatalogItem>()

    fun addItem(rawHref: String, titleCandidates: Sequence<String>, durationText: String?) {
        if (results.size >= 20) return
        val url = rawHref.toHttpUrlOrNull() ?: parsedBase?.resolve(rawHref) ?: return
        val videoCode = url.queryParameter("v")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return
        val title = titleCandidates.map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return
        val itemId = if (isJavChu) "javchu_$videoCode" else videoCode
        val canonicalUrl = "https://$host/watch?v=$videoCode"
        results.putIfAbsent(
            itemId,
            MovieCatalogItem(
                id = itemId,
                title = title,
                detailUrl = canonicalUrl,
                durationSeconds = parseClockDurationSeconds(durationText),
                lines = listOf(
                    MoviePlaybackLine(
                        name = if (isJavChu) "Hanime1-JAV" else "Hanime1",
                        episodes = listOf(MovieEpisode("正片", canonicalUrl, 1)),
                    )
                ),
                sourceId = SITE_ID_HANIME1,
                sourceName = "Hanime1",
                remarks = durationText,
            )
        )
    }

    // 1. Hanime1 Ver2 normal cards: div[class^=horizontal-card]
    for (card in doc.select("div[class^=horizontal-card]")) {
        val anchor = card.selectFirst("a.video-link[href], a[href*=/watch]") ?: continue
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        val duration = card.selectFirst("div[class^=thumb-container] div[class^=duration], .duration")?.text()?.trim()
        addItem(
            rawHref = href,
            titleCandidates = sequenceOf(
                card.selectFirst("div.title, h4.video-title")?.text().orEmpty(),
                card.attr("title"),
                card.selectFirst("img[alt]")?.attr("alt").orEmpty(),
                anchor.attr("title"),
            ),
            durationText = duration,
        )
    }

    // 2. Legacy cards: .video-item-container
    for (card in doc.select(".video-item-container")) {
        val anchor = card.selectFirst("a.video-link[href], a[href*=/watch]") ?: continue
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        val duration = card.selectFirst(".duration")?.text()?.trim()
        addItem(
            rawHref = href,
            titleCandidates = sequenceOf(
                card.selectFirst(".title, h4.video-title")?.text().orEmpty(),
                card.attr("title"),
                card.selectFirst("img[alt]")?.attr("alt").orEmpty(),
                anchor.text(),
            ),
            durationText = duration,
        )
    }

    // 3. Simplified grid cards (裏番 / 泡麵番): div.home-rows-videos-wrapper > a or .home-rows-videos-div
    for (anchor in doc.select("div.home-rows-videos-wrapper a[href*=/watch], a[href*=/watch]")) {
        if (results.size >= 20) break
        val card: Element? = anchor.selectFirst(".home-rows-videos-div, .search-videos")
            ?: anchor.takeIf { it.parent()?.hasClass("home-rows-videos-wrapper") == true }
        if (card == null) continue
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        addItem(
            rawHref = href,
            titleCandidates = sequenceOf(
                card.selectFirst("div.home-rows-videos-title, div[class$=title], .title")?.text().orEmpty(),
                card.selectFirst("img[alt]")?.attr("alt").orEmpty(),
                anchor.attr("title"),
            ),
            durationText = null,
        )
    }

    return results.values.toList()
}

/**
 * Extracts a playable HLS/MP4 [VideoPlaybackSource] from JableTV detail HTML.
 */
internal fun extractJableVideoFromHtml(html: String, pageUrl: String): VideoPlaybackSource? {
    val doc = Jsoup.parse(html, pageUrl)
    val hlsVar = Regex("var\\s+hlsUrl\\s*=\\s*['\"](https?://[^'\"]+\\.m3u8[^'\"]*)['\"]", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.getOrNull(1)
    val streamUrl = hlsVar ?: findMediaUrls(html, "m3u8").firstOrNull() ?: return null
    val parsedPage = pageUrl.toHttpUrlOrNull()
    val origin = parsedPage?.let { "${it.scheme}://${it.host}" } ?: "https://${ExternalVideoEndpointConfig.host("jable_primary")}"
    val title = extractHtmlTitle(doc).ifBlank { "JableTV" }
    return VideoPlaybackSource(
        url = streamUrl,
        kind = VideoPlaybackKind.DIRECT,
        title = title,
        mimeType = "application/x-mpegURL",
        headers = mapOf(
            "User-Agent" to DEFAULT_SITE_USER_AGENT,
            "Referer" to pageUrl,
            "Origin" to origin,
        ),
    )
}

/**
 * Extracts a playable HLS [VideoPlaybackSource] from MissAV (`configured missav primary`) detail HTML.
 */
internal fun extractMissAvVideoFromHtml(html: String, pageUrl: String): VideoPlaybackSource? {
    val doc = Jsoup.parse(html, pageUrl)
    var streamUrl: String? = null
    for (script in doc.select("script")) {
        val data = script.data()
        if (!data.contains("eval(function")) continue
        for (unpacked in unpackAllDeanEdwardsPackers(data)) {
            val candidates = listOf(
                Regex("source1280\\s*=\\s*[\\\\'\"]*(https?://[^'\"\\\\;\\s]+\\.m3u8[^'\"\\\\;\\s]*)"),
                Regex("source842\\s*=\\s*[\\\\'\"]*(https?://[^'\"\\\\;\\s]+\\.m3u8[^'\"\\\\;\\s]*)"),
                Regex("source\\s*=\\s*[\\\\'\"]*(https?://[^'\"\\\\;\\s]+\\.m3u8[^'\"\\\\;\\s]*)"),
            ).mapNotNull { it.find(unpacked)?.groupValues?.getOrNull(1)?.trimEnd('\\', '\'', '"') }
            val allUnpacked = findMediaUrls(unpacked, "m3u8")
            streamUrl = allUnpacked.firstOrNull { it.contains("playlist.m3u8") }
                ?: candidates.firstOrNull()
                ?: allUnpacked.firstOrNull()
            if (streamUrl != null) break
        }
        if (streamUrl != null) break
    }
    if (streamUrl == null) {
        val normalized = html.replace("\\/", "/")
        val uuid = Regex("https?://[^/\"'\\s]+/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})/seek/")
            .find(normalized)?.groupValues?.getOrNull(1)
        if (uuid != null) {
            streamUrl = "https://${ExternalVideoEndpointConfig.host("missav_stream")}/$uuid/playlist.m3u8"
        }
    }
    if (streamUrl == null) {
        streamUrl = findMediaUrls(html, "m3u8").firstOrNull()
    }
    val resolvedUrl = streamUrl ?: return null
    val parsedPage = pageUrl.toHttpUrlOrNull()
    val host = parsedPage?.host ?: ExternalVideoEndpointConfig.host("missav_primary")
    val title = enrichMissAvTitle(
        extractHtmlTitle(doc).ifBlank { "MissAV" },
        parsedPage?.let(::extractMissAvSlug).orEmpty(),
    )
    return VideoPlaybackSource(
        url = resolvedUrl,
        kind = VideoPlaybackKind.DIRECT,
        title = title,
        mimeType = "application/x-mpegURL",
        headers = mapOf(
            "User-Agent" to DEFAULT_SITE_USER_AGENT,
            "Referer" to "https://$host/",
            "Origin" to "https://$host",
        ),
    )
}

/**
 * Extracts server map `{SERVER_NAME: data_link}` from a SupJav video page.
 */
internal fun extractSupJavServers(html: String): Map<String, String> {
    val doc = Jsoup.parse(html)
    val servers = LinkedHashMap<String, String>()
    for (anchor in doc.select("a.btn-server[data-link], a[data-link]")) {
        val name = anchor.text().trim().uppercase(Locale.ROOT)
        val link = anchor.attr("data-link").trim()
        if (name.isNotEmpty() && link.isNotEmpty() && name !in servers) {
            servers[name] = link
        }
    }
    return servers
}

/**
 * Extracts Streamtape direct MP4 URL from its embed HTML `#robotlink` token script.
 */
internal fun extractStreamtapeDirectUrl(html: String): String? {
    val match = Regex(
        "getElementById\\(\\s*['\"]robotlink['\"]\\s*\\)\\.innerHTML\\s*=\\s*" +
            "['\"]([^'\"]*)['\"]\\s*\\+\\s*(?:['\"]{2}\\s*\\+\\s*)?" +
            "\\(\\s*['\"]([^'\"]*)['\"]\\s*\\)((?:\\.substring\\(\\s*\\d+\\s*\\))+)"
    ).find(html) ?: return null
    val prefix = match.groupValues[1]
    var suffix = match.groupValues[2]
    val operations = match.groupValues[3]
    for (sub in Regex("substring\\(\\s*(\\d+)\\s*\\)").findAll(operations)) {
        val offset = sub.groupValues[1].toIntOrNull() ?: continue
        if (offset in 0..suffix.length) {
            suffix = suffix.substring(offset)
        }
    }
    val link = (prefix + suffix).trimStart('/')
    if (!link.contains("get_video")) return null
    val fullUrl = if (link.startsWith("http://") || link.startsWith("https://")) link else "https://$link"
    return fullUrl.toHttpUrlOrNull()?.toString()
}

/**
 * Decodes VOE player obfuscated JSON configuration into an HLS m3u8 URL.
 */
internal fun extractVoeM3u8Url(html: String): String? {
    val doc = Jsoup.parse(html)
    val noises = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&", "_")
    for (script in doc.select("script[type=application/json]")) {
        try {
            val arr = Json.parseToJsonElement(script.data().trim()) as? JsonArray ?: continue
            val first = (arr.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            var rot13 = buildString(first.length) {
                for (ch in first) {
                    append(
                        when (ch) {
                            in 'a'..'z' -> 'a' + (ch - 'a' + 13) % 26
                            in 'A'..'Z' -> 'A' + (ch - 'A' + 13) % 26
                            else -> ch
                        }
                    )
                }
            }
            for (noise in noises) rot13 = rot13.replace(noise, "")
            val decoded1 = String(Base64.getDecoder().decode(rot13), Charsets.US_ASCII)
            val shiftedReversed = buildString(decoded1.length) {
                for (ch in decoded1) append((ch.code - 3).toChar())
            }.reversed()
            val decoded2 = String(Base64.getDecoder().decode(shiftedReversed), Charsets.UTF_8)
            val obj = Json.parseToJsonElement(decoded2) as? JsonObject ?: continue
            val source = (obj["source"] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/") ?: continue
            if (source.toHttpUrlOrNull() != null && source.contains(".m3u8", ignoreCase = true)) {
                return source
            }
        } catch (_: Exception) {
            continue
        }
    }
    return null
}

private fun fixHanimeAvCdnHost(url: String): String =
    hanimeAvCdnRegex.replace(url) { "${it.groupValues[1]}${ExternalVideoEndpointConfig.host("hanime1_stream_rewrite")}" }

/**
 * Extracts signed MP4 or HLS sources from Hanime1 / JavChu watch/download HTML
 * (adapted from `Han1meViewer/Parser.kt`).
 */
internal fun extractHanime1VideoFromHtml(html: String, pageUrl: String): VideoPlaybackSource? {
    val doc = Jsoup.parse(html, pageUrl)
    val candidates = ArrayList<Pair<String, Int>>()
    val seen = HashSet<String>()

    fun addCandidate(rawUrl: String?, label: String = "") {
        if (rawUrl.isNullOrBlank()) return
        val unescaped = fixHanimeAvCdnHost(
            org.jsoup.parser.Parser.unescapeEntities(rawUrl.trim(), true).replace("\\/", "/")
        )
        val parsed = unescaped.toHttpUrlOrNull() ?: return
        if (parsed.scheme != "https" && parsed.scheme != "http") return
        val lowerPath = parsed.encodedPath.lowercase(Locale.ROOT)
        if (!lowerPath.endsWith(".mp4") && !lowerPath.endsWith(".m3u8")) return
        val normalized = parsed.toString()
        if (!seen.add(normalized)) return
        val height = Regex("(?<!\\d)(\\d{3,4})\\s*p?(?!\\w)", RegexOption.IGNORE_CASE)
            .find("$label $normalized")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in setOf(2160, 1440, 1080, 720, 480, 360, 240) } ?: 0
        candidates.add(normalized to height)
    }

    // 1. Han1meViewer primary path: <video id="player"> children <source>
    val playerVideo = doc.selectFirst("video#player")
    if (playerVideo != null) {
        for (source in playerVideo.children()) {
            val sizeAttr = source.attr("size")
            val src = source.absUrl("src").ifBlank { source.attr("src") }
            addCandidate(src, if (sizeAttr.isNotEmpty()) "${sizeAttr}P" else "")
        }
    }

    // 2. Han1meViewer secondary path: #player-div-wrapper <script> const source = '...'
    val playerDivWrapper = doc.selectFirst("div#player-div-wrapper")
    if (playerDivWrapper != null) {
        for (script in playerDivWrapper.select("script")) {
            val data = script.data()
            if (data.isBlank()) continue
            val match = Regex("""const\s+source\s*=\s*['"]([^'"]+)['"]""").find(data) ?: continue
            addCandidate(match.groupValues[1], "1080P")
        }
    }

    // 3. General <source[src]>, <a[data-url]>, <a[href]> and inline media URLs
    for (el in doc.select("source[src], a[data-url], a[href]")) {
        val raw = el.attr("src").ifBlank { el.attr("data-url") }.ifBlank { el.attr("href") }
        val sizeAttr = el.attr("size").ifBlank { el.text() }
        addCandidate(raw, sizeAttr)
    }
    for (mp4 in findMediaUrls(html, "mp4")) addCandidate(mp4)
    for (m3u8 in findMediaUrls(html, "m3u8")) addCandidate(m3u8)

    val useBackupCdn = pageUrl.contains("cdn=backup", ignoreCase = true)
    val rawSelected = candidates.maxByOrNull { it.second }?.first ?: return null
    val selected = if (useBackupCdn) {
        rawSelected.replace(HANIME_PRIMARY_CDN, HANIME_BACKUP_CDN, ignoreCase = true)
    } else {
        rawSelected
    }
    val cleanPageUrl = pageUrl.substringBefore('#')
    val title = extractHtmlTitle(doc).ifBlank { "Hanime1" }
    val mimeType = if (selected.contains(".m3u8", ignoreCase = true)) "application/x-mpegURL" else "video/mp4"
    return VideoPlaybackSource(
        url = selected,
        kind = VideoPlaybackKind.DIRECT,
        title = title,
        mimeType = mimeType,
        headers = mapOf(
            "User-Agent" to DEFAULT_SITE_USER_AGENT,
            "Referer" to cleanPageUrl,
        ),
    )
}

private fun parseClockDurationSeconds(value: String?): Double? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
    val total = when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> return null
    }
    return total.toDouble().takeIf { it > 0 }
}

/**
 * Client for searching and resolving JableTV, MissAV (`configured missav primary`), SupJav, and Hanime1.
 */
internal class ExternalVideoSiteClient(
    httpClient: OkHttpClient,
    context: Context? = null,
) {
    private val appContext = context?.applicationContext
    private val sessionCookies = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()

    /**
     * Exact system WebView User-Agent (without `; wv`) so Cloudflare `Critical-Ch: Sec-CH-UA-*`
     * and `cf_clearance` cookies match between WebView and OkHttp.
     */
    private val webViewUserAgent: String by lazy {
        val raw = appContext?.let { ctx ->
            runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
        }
        raw?.replace("; wv)", ")")?.replace("Version/4.0 ", "")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SITE_USER_AGENT
    }

    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(SITE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SITE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SITE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .cache(null)
        .addNetworkInterceptor { chain ->
            // Remove duplicate Accept-Language headers added by global app interceptors so
            // Cloudflare and Hanime1 see a clean, single-header browser fingerprint.
            val req = chain.request()
            val langHeaders = req.headers("Accept-Language")
            val cleaned = if (langHeaders.size > 1) {
                req.newBuilder()
                    .removeHeader("Accept-Language")
                    .header("Accept-Language", langHeaders.first())
                    .build()
            } else {
                req
            }
            chain.proceed(cleaned)
        }
        .build()

    suspend fun search(sourceId: String, rawQuery: String): List<MovieCatalogItem> {
        PrivateMediaAccess.requireAccess()
        val query = rawQuery.trim().removeSurrounding("《", "》").trim()
        if (query.isEmpty()) return emptyList()
        val extractedCode = extractJavCode(query)
        val variants = buildList {
            add(query)
            val traditional = toTraditionalChinese(query)
            if (traditional != query) add(traditional)
            if (extractedCode != null && !extractedCode.equals(query, ignoreCase = true)) {
                add(extractedCode)
            }
        }
        val combined = LinkedHashMap<String, MovieCatalogItem>()
        for (q in variants) {
            val items = try {
                when (sourceId) {
                    SITE_ID_JABLE -> searchJable(q)
                    SITE_ID_MISSAV -> searchMissAv(q)
                    SITE_ID_SUPJAV -> searchSupJav(q)
                    SITE_ID_HANIME1 -> searchHanime1(q)
                    else -> emptyList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PrivateMediaAccess.requireAccess()
                emptyList()
            }
            for (item in items) {
                combined.putIfAbsent(item.id, item)
            }
            if (combined.size >= 8) break
        }
        PrivateMediaAccess.requireAccess()
        return combined.values.toList()
    }

    suspend fun getItem(sourceId: String, itemId: String): MovieCatalogItem {
        PrivateMediaAccess.requireAccess()
        return when (sourceId) {
            SITE_ID_JABLE -> {
                val url = "https://${ExternalVideoEndpointConfig.host("jable_primary")}/videos/$itemId/"
                val page = fetchWithMirrors(url, JABLE_MIRRORS, extraCookie = "kt_rt_lang=")
                val doc = Jsoup.parse(page.body, page.finalUrl)
                val title = extractHtmlTitle(doc).ifBlank { itemId.uppercase(Locale.ROOT) }
                MovieCatalogItem(
                    id = itemId,
                    title = title,
                    detailUrl = page.finalUrl,
                    lines = listOf(MoviePlaybackLine("JableTV", listOf(MovieEpisode("正片", page.finalUrl, 1)))),
                    sourceId = SITE_ID_JABLE,
                    sourceName = "JableTV",
                )
            }
            SITE_ID_MISSAV -> {
                val url = "https://${ExternalVideoEndpointConfig.host("missav_primary")}/cn/$itemId"
                val page = fetchWithMirrors(url, MISSAV_MIRRORS)
                val doc = Jsoup.parse(page.body, page.finalUrl)
                val title = enrichMissAvTitle(extractHtmlTitle(doc).ifBlank { itemId.uppercase(Locale.ROOT) }, itemId)
                MovieCatalogItem(
                    id = itemId,
                    title = title,
                    detailUrl = page.finalUrl,
                    lines = listOf(MoviePlaybackLine("MissAV", listOf(MovieEpisode("正片", page.finalUrl, 1)))),
                    sourceId = SITE_ID_MISSAV,
                    sourceName = "MissAV",
                )
            }
            SITE_ID_SUPJAV -> {
                val url = "https://${ExternalVideoEndpointConfig.host("supjav_primary")}/zh/$itemId.html"
                val page = fetchWithMirrors(url, SUPJAV_MIRRORS, preferWebViewUa = true)
                val doc = Jsoup.parse(page.body, page.finalUrl)
                val title = extractHtmlTitle(doc).ifBlank { "SupJav $itemId" }
                val servers = extractSupJavServers(page.body)
                val lines = if (servers.isNotEmpty()) {
                    servers.keys.map { serverName ->
                        MoviePlaybackLine(
                            name = "SupJav-$serverName",
                            episodes = listOf(MovieEpisode("正片", "${page.finalUrl}#server=$serverName", 1)),
                        )
                    }
                } else {
                    listOf(MoviePlaybackLine("SupJav", listOf(MovieEpisode("正片", page.finalUrl, 1))))
                }
                MovieCatalogItem(
                    id = itemId,
                    title = title,
                    detailUrl = page.finalUrl,
                    lines = lines,
                    sourceId = SITE_ID_SUPJAV,
                    sourceName = "SupJav",
                )
            }
            SITE_ID_HANIME1 -> {
                val isJavChu = itemId.startsWith("javchu_")
                val cleanId = itemId.removePrefix("javchu_")
                val host = if (isJavChu) ExternalVideoEndpointConfig.host("hanime1_alternate") else ExternalVideoEndpointConfig.host("hanime1_primary")
                val mirrors = if (isJavChu) listOf(ExternalVideoEndpointConfig.host("hanime1_alternate")) else HANIME1_MIRRORS
                val url = "https://$host/watch?v=$cleanId"
                val page = fetchWithMirrors(url, mirrors, referer = "https://$host/")
                val doc = Jsoup.parse(page.body, page.finalUrl)
                val title = extractHtmlTitle(doc).ifBlank { "Hanime1 $cleanId" }
                val lines = if (isJavChu) {
                    listOf(MoviePlaybackLine("Hanime1-JAV", listOf(MovieEpisode("正片", page.finalUrl, 1))))
                } else {
                    listOf(
                        MoviePlaybackLine("Hanime1-主线路", listOf(MovieEpisode("正片", page.finalUrl, 1))),
                        MoviePlaybackLine("Hanime1-备用CDN", listOf(MovieEpisode("正片", "${page.finalUrl}#cdn=backup", 1))),
                    )
                }
                MovieCatalogItem(
                    id = itemId,
                    title = title,
                    detailUrl = page.finalUrl,
                    lines = lines,
                    sourceId = SITE_ID_HANIME1,
                    sourceName = "Hanime1",
                )
            }
            else -> throw IOException("未知的外部站点: $sourceId")
        }.also { PrivateMediaAccess.requireAccess() }
    }

    suspend fun resolveVideo(rawUrl: String): VideoPlaybackSource {
        PrivateMediaAccess.requireAccess()
        val url = rewriteToActiveMirror(rawUrl)
        val site = identifyExternalVideoSite(url) ?: throw IOException("不支持的外部视频地址")
        val primaryError = try {
            return resolveVideoPrimary(site, url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            PrivateMediaAccess.requireAccess()
            e
        }

        // Cross-site fallback: if the AI guessed a URL (e.g. https://configured jable mirror/videos/wanz-225/ which is 404 on Jable)
        // or if one site is blocked, extract the JAV code from the URL and resolve from MissAV123 / Jable / JavChu.
        val fallbackCode = extractJavCode(url)
        if (fallbackCode != null) {
            val slug = fallbackCode.lowercase(Locale.ROOT)
            val fallbackTargets = listOf(
                SITE_ID_MISSAV to "https://${ExternalVideoEndpointConfig.host("missav_primary")}/cn/$slug",
                SITE_ID_JABLE to "https://${ExternalVideoEndpointConfig.host("jable_primary")}/videos/$slug/",
                SITE_ID_JABLE to "https://${ExternalVideoEndpointConfig.host("jable_primary")}/videos/$slug-c/",
            ).filter { it.first != site || it.second != url }
            for ((targetSite, targetUrl) in fallbackTargets) {
                try {
                    return resolveVideoPrimary(targetSite, targetUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PrivateMediaAccess.requireAccess()
                    // Continue trying next fallback target.
                }
            }
        }
        throw primaryError
    }

    private suspend fun resolveVideoPrimary(site: String, url: String): VideoPlaybackSource {
        PrivateMediaAccess.requireAccess()
        return resolveAuthorizedVideoPrimary(site, url).also { PrivateMediaAccess.requireAccess() }
    }

    private suspend fun resolveAuthorizedVideoPrimary(site: String, url: String): VideoPlaybackSource = when (site) {
        SITE_ID_JABLE -> {
            val page = fetchWithMirrors(url, JABLE_MIRRORS, extraCookie = "kt_rt_lang=") { body ->
                body.contains("m3u8", ignoreCase = true) || body.contains("hlsUrl")
            }
            extractJableVideoFromHtml(page.body, page.finalUrl)
                ?: throw IOException("JableTV 页面未找到可播放的 M3U8 地址")
        }
        SITE_ID_MISSAV -> {
            val page = fetchWithMirrors(url, MISSAV_MIRRORS) { body ->
                body.contains("eval(function") || body.contains("m3u8") || body.contains("/seek/")
            }
            extractMissAvVideoFromHtml(page.body, page.finalUrl)
                ?: throw IOException("MissAV 页面未找到可播放的 M3U8 地址")
        }
        SITE_ID_SUPJAV -> resolveSupJavVideo(url)
        SITE_ID_HANIME1 -> resolveHanime1Video(url)
        else -> throw IOException("不支持的外部视频站点")
    }

    private suspend fun searchJable(query: String): List<MovieCatalogItem> {
        val encoded = encodePathSegment(query)
        val url = "https://${ExternalVideoEndpointConfig.host("jable_primary")}/search/$encoded/"
        val page = fetchWithMirrors(url, JABLE_MIRRORS, extraCookie = "kt_rt_lang=") { body ->
            body.contains("video-img-box") || body.contains("list_videos") || body.contains("site-content")
        }
        return parseJableSearchHtml(page.body, page.finalUrl)
    }

    private suspend fun searchMissAv(query: String): List<MovieCatalogItem> {
        val encoded = encodePathSegment(query)
        val url = "https://${ExternalVideoEndpointConfig.host("missav_primary")}/cn/search/$encoded"
        val page = fetchWithMirrors(url, MISSAV_MIRRORS) { body ->
            body.contains("thumbnail") || body.contains("content-without-search")
        }
        val parsed = parseMissAvSearchHtml(page.body, page.finalUrl)
        val code = extractJavCode(query)
        if (code != null) {
            val slug = code.lowercase(Locale.ROOT)
            if (parsed.none { it.id.equals(slug, ignoreCase = true) }) {
                val directUrl = "https://${ExternalVideoEndpointConfig.host("missav_primary")}/cn/$slug"
                val directItem = MovieCatalogItem(
                    id = slug,
                    title = code.uppercase(Locale.ROOT),
                    detailUrl = directUrl,
                    lines = listOf(MoviePlaybackLine("MissAV", listOf(MovieEpisode("正片", directUrl, 1)))),
                    sourceId = SITE_ID_MISSAV,
                    sourceName = "MissAV",
                )
                return listOf(directItem) + parsed
            }
        }
        return parsed
    }

    private suspend fun searchSupJav(query: String): List<MovieCatalogItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://${ExternalVideoEndpointConfig.host("supjav_primary")}/zh/?s=$encoded"
        val page = fetchWithMirrors(url, SUPJAV_MIRRORS, preferWebViewUa = true) { body ->
            body.contains("class=\"post\"") || body.contains("class='post'") || body.contains("supjav")
        }
        return parseSupJavSearchHtml(page.body, page.finalUrl)
    }

    private suspend fun searchHanime1(query: String): List<MovieCatalogItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val combined = LinkedHashMap<String, MovieCatalogItem>()
        val isCodeQuery = extractJavCode(query) != null

        // If query is NOT a JAV code, search configured hanime1 primary (anime/3DCG) first; otherwise search configured hanime1 alternate first.
        val searchTargets = if (isCodeQuery) {
            listOf(
                "https://${ExternalVideoEndpointConfig.host("hanime1_alternate")}/search?query=$encoded" to listOf(ExternalVideoEndpointConfig.host("hanime1_alternate")),
                "https://${ExternalVideoEndpointConfig.host("hanime1_primary")}/search?query=$encoded" to HANIME1_MIRRORS,
            )
        } else {
            listOf(
                "https://${ExternalVideoEndpointConfig.host("hanime1_primary")}/search?query=$encoded" to HANIME1_MIRRORS,
                "https://${ExternalVideoEndpointConfig.host("hanime1_alternate")}/search?query=$encoded" to listOf(ExternalVideoEndpointConfig.host("hanime1_alternate")),
            )
        }

        for ((targetUrl, mirrors) in searchTargets) {
            val host = targetUrl.toHttpUrlOrNull()?.host ?: ExternalVideoEndpointConfig.host("hanime1_primary")
            val page = runCatching {
                fetchWithMirrors(targetUrl, mirrors, referer = "https://$host/") { body ->
                    body.contains("horizontal-card") || body.contains("home-rows-videos") || body.contains("video-item-container")
                }
            }.getOrNull() ?: continue
            for (item in parseHanime1SearchHtml(page.body, page.finalUrl)) {
                combined.putIfAbsent(item.id, item)
            }
            if (combined.size >= 6) break
        }
        return combined.values.toList()
    }

    private suspend fun resolveSupJavVideo(url: String): VideoPlaybackSource {
        val preferredServer = url.toHttpUrlOrNull()?.fragment
            ?.takeIf { it.startsWith("server=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.uppercase(Locale.ROOT)
        val cleanUrl = url.substringBefore('#')
        val page = fetchWithMirrors(cleanUrl, SUPJAV_MIRRORS, preferWebViewUa = true) { body ->
            body.contains("data-link")
        }
        val doc = Jsoup.parse(page.body, page.finalUrl)
        val title = extractHtmlTitle(doc).ifBlank { "SupJav" }
        val servers = extractSupJavServers(page.body)
        if (servers.isEmpty()) throw IOException("SupJav 页面未找到可用的播放服务器")

        val order = buildList {
            if (preferredServer != null && preferredServer in servers) add(preferredServer)
            for (name in listOf("EVS", "FST", "LUC", "VOE", "VAS", "TV", "ST")) {
                if (name in servers && name !in this) add(name)
            }
            for (name in servers.keys) {
                if (name !in this) add(name)
            }
        }

        for (serverName in order) {
            val token = servers[serverName] ?: continue
            try {
                val resolved = resolveSupJavServer(serverName, token, title, page.finalUrl)
                if (resolved != null) return resolved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Try the next server line on the page.
            }
        }
        throw IOException("SupJav 所有服务器线路暂不可用")
    }

    private suspend fun resolveSupJavServer(
        name: String,
        dataLink: String,
        title: String,
        supjavPageUrl: String,
    ): VideoPlaybackSource? {
        val reversed = URLEncoder.encode(dataLink.reversed(), "UTF-8")
        val bootstrapUrl = "https://${ExternalVideoEndpointConfig.host("supjav_embed")}/supjav.php?c=$reversed"
        val iframeHeaders = mapOf(
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1",
        )
        var response = try {
            fetchHttpWithRedirects(
                bootstrapUrl,
                referer = "https://${ExternalVideoEndpointConfig.host("supjav_primary")}/",
                preferWebViewUa = true,
                extraHeaders = iframeHeaders,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        if (response != null && response.statusCode in 200..299 && !response.body.contains("404 Not Found", ignoreCase = true)) {
            if (name == "VOE") {
                repeat(2) {
                    if (extractVoeM3u8Url(response!!.body) != null) return@repeat
                    val jsRedirect = Regex("window\\.location\\.href\\s*=\\s*['\"](https://[^'\"]+)['\"]")
                        .find(response!!.body)?.groupValues?.getOrNull(1) ?: return@repeat
                    response = runCatching {
                        fetchHttpWithRedirects(jsRedirect, referer = response!!.finalUrl, preferWebViewUa = true)
                    }.getOrNull() ?: return@repeat
                }
            }
            val currentResp = response!!
            val embedUrl = currentResp.finalUrl
            val parsedEmbed = embedUrl.toHttpUrlOrNull()
            if (parsedEmbed != null) {
                val origin = "${parsedEmbed.scheme}://${parsedEmbed.host}"
                val ua = webViewUserAgent
                val headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to embedUrl,
                    "Origin" to origin,
                )

                val streamUrl: String?
                val mimeType: String
                when (name) {
                    "ST" -> {
                        streamUrl = extractStreamtapeDirectUrl(currentResp.body)
                        mimeType = "video/mp4"
                    }
                    "VOE" -> {
                        streamUrl = extractVoeM3u8Url(currentResp.body)
                        mimeType = "application/x-mpegURL"
                    }
                    "VAS" -> {
                        val filecode = parsedEmbed.pathSegments.lastOrNull { it.isNotEmpty() }
                        if (filecode != null && Regex("^[A-Za-z0-9]+$").matches(filecode) && currentResp.body.contains("/api/stream")) {
                            val apiUrl = "$origin/api/stream"
                            val jsonBody = """{"filecode":"$filecode","device":"web","codecs":[]}"""
                            val apiText = runCatching { postJson(apiUrl, jsonBody, headers) }.getOrNull().orEmpty()
                            val obj = runCatching { Json.parseToJsonElement(apiText) as? JsonObject }.getOrNull()
                            streamUrl = (obj?.get("streaming_url") as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")
                        } else {
                            streamUrl = null
                        }
                        mimeType = "application/x-mpegURL"
                    }
                    "TV" -> {
                        val normalized = currentResp.body.replace("\\/", "/")
                        streamUrl = Regex("urlPlay[\\s=:'\"]+(https?://[^\\s'\"\\\\]+\\.m3u8[^\\s'\"\\\\]*)")
                            .find(normalized)?.groupValues?.getOrNull(1)
                            ?: findMediaUrls(normalized, "m3u8").firstOrNull()
                        mimeType = "application/x-mpegURL"
                        if (streamUrl != null) {
                            return VideoPlaybackSource(
                                url = streamUrl,
                                kind = VideoPlaybackKind.DIRECT,
                                title = title,
                                mimeType = mimeType,
                                headers = mapOf("User-Agent" to ua, "Referer" to "https://${ExternalVideoEndpointConfig.host("supjav_primary")}/"),
                            )
                        }
                    }
                    else -> {
                        var unpackedStream: String? = null
                        for (script in Jsoup.parse(currentResp.body).select("script")) {
                            val data = script.data()
                            if (!data.contains("eval(function")) continue
                            for (unpacked in unpackAllDeanEdwardsPackers(data)) {
                                unpackedStream = findMediaUrls(unpacked, "m3u8").firstOrNull()
                                if (unpackedStream != null) break
                            }
                            if (unpackedStream != null) break
                        }
                        streamUrl = unpackedStream ?: findMediaUrls(currentResp.body, "m3u8").firstOrNull()
                        mimeType = "application/x-mpegURL"
                    }
                }
                if (streamUrl != null && streamUrl.toHttpUrlOrNull() != null) {
                    return VideoPlaybackSource(
                        url = streamUrl,
                        kind = VideoPlaybackKind.DIRECT,
                        title = title,
                        mimeType = mimeType,
                        headers = headers,
                    )
                }
            }
        }

        // Android WebView iframe fallback (adapted from javbrowser's SupjavMediaResolver):
        // Loads the wrapper inside an iframe with configured supjav primary as base URL and reads jwplayer/video sources.
        if (appContext != null) {
            return resolveSupJavEmbedViaWebView(supjavPageUrl, bootstrapUrl, title)
        }
        return null
    }

    private suspend fun resolveHanime1Video(url: String): VideoPlaybackSource {
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("Hanime1 视频链接无效")
        val videoId = parsed.queryParameter("v")?.takeIf { it.all(Char::isDigit) }
            ?: throw IOException("Hanime1 视频编号无效")
        val host = parsed.host.lowercase(Locale.ROOT)
        val isJavChu = ExternalVideoEndpointConfig.matchesHost(host, "hanime1_alternate")
        val mirrors = if (isJavChu) listOf(ExternalVideoEndpointConfig.host("hanime1_alternate")) else HANIME1_MIRRORS
        val watchUrl = "https://${if (isJavChu) ExternalVideoEndpointConfig.host("hanime1_alternate") else ExternalVideoEndpointConfig.host("hanime1_primary")}/watch?v=$videoId"
        val fragment = parsed.fragment?.let { "#$it" }.orEmpty()
        val page = fetchWithMirrors(watchUrl, mirrors, referer = "https://${mirrors.first()}/")
        extractHanime1VideoFromHtml(page.body, page.finalUrl + fragment)?.let { return it }

        if (!isJavChu) {
            val downloadUrl = "https://${ExternalVideoEndpointConfig.host("hanime1_primary")}/download?v=$videoId"
            val downloadPage = runCatching {
                fetchWithMirrors(downloadUrl, HANIME1_MIRRORS, referer = watchUrl)
            }.getOrNull()
            if (downloadPage != null) {
                extractHanime1VideoFromHtml(downloadPage.body, watchUrl + fragment)?.let { return it }
            }
        }
        throw IOException("Hanime1 页面未找到可播放的视频源")
    }

    private data class SiteHttpResponse(
        val statusCode: Int,
        val finalUrl: String,
        val body: String,
        val headers: Map<String, String>,
    )

    private suspend fun fetchWithMirrors(
        rawUrl: String,
        mirrors: List<String>,
        referer: String? = null,
        extraCookie: String? = null,
        preferWebViewUa: Boolean = false,
        validator: ((String) -> Boolean)? = null,
    ): SiteHttpResponse {
        PrivateMediaAccess.requireAccess()
        val url = rewriteToActiveMirror(rawUrl)
        val initial = url.toHttpUrlOrNull() ?: throw IOException("无效的站点地址")
        val originalHost = initial.host.lowercase(Locale.ROOT)
        val hosts = buildList {
            for (mirror in mirrors) {
                if (mirror !in this) add(mirror)
            }
            if (originalHost in mirrors && originalHost != mirrors.firstOrNull()) {
                // Keep preferred primary mirror first
            }
        }
        var lastBlockedUrl: String? = null
        for (host in hosts) {
            val targetUrl = initial.newBuilder().scheme("https").host(host).build().toString()
            val targetReferer = referer ?: "https://$host/"
            try {
                val resp = fetchHttpWithRedirects(
                    targetUrl,
                    referer = targetReferer,
                    extraCookie = extraCookie,
                    preferWebViewUa = preferWebViewUa,
                )
                if (isCloudflareBlocked(resp.statusCode, resp.headers, resp.body)) {
                    lastBlockedUrl = targetUrl
                    continue
                }
                if (resp.statusCode in 200..299 && (validator == null || validator(resp.body))) {
                    return resp
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PrivateMediaAccess.requireAccess()
                lastBlockedUrl = targetUrl
            }
        }
        if (appContext != null && lastBlockedUrl != null) {
            PrivateMediaAccess.requireAccess()
            val rendered = fetchViaAndroidWebView(lastBlockedUrl, validator)
            if (rendered != null) return rendered
        }
        throw IOException("目标站点暂时无法访问或受 Cloudflare 保护")
    }

    private suspend fun fetchHttpWithRedirects(
        initialUrl: String,
        referer: String? = null,
        extraCookie: String? = null,
        preferWebViewUa: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ): SiteHttpResponse = withContext(Dispatchers.IO) {
        var current = initialUrl.toHttpUrlOrNull() ?: throw IOException("请求地址无效")
        var currentReferer = referer
        val visited = HashSet<String>()
        val ua = if (preferWebViewUa) webViewUserAgent else DEFAULT_SITE_USER_AGENT
        repeat(6) {
            currentCoroutineContext().ensureActive()
            if (!visited.add(current.toString())) throw IOException("站点请求出现循环跳转")
            val cookieHeader = buildCookieHeader(current, extraCookie)
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .apply {
                    currentReferer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
                    if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
                    extraHeaders.forEach { (k, v) -> header(k, v) }
                }
                .get()
                .build()

            val response = executeCall(request)
            storeCookies(current, response.setCookies)
            val location = response.headers["location"]
            if (response.statusCode in listOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                val next = current.resolve(location) ?: throw IOException("跳转地址无效")
                currentReferer = current.toString()
                current = next
            } else {
                return@withContext SiteHttpResponse(
                    statusCode = response.statusCode,
                    finalUrl = current.toString(),
                    body = response.body,
                    headers = response.headers,
                )
            }
        }
        throw IOException("站点跳转次数过多")
    }

    private suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val target = url.toHttpUrlOrNull() ?: throw IOException("请求地址无效")
            val request = Request.Builder()
                .url(target)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val response = executeCall(request)
            if (response.statusCode !in 200..299) throw IOException("HTTP ${response.statusCode}")
            response.body
        }

    private data class RawCallResult(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, String>,
        val setCookies: List<String>,
    )

    private suspend fun executeCall(request: Request): RawCallResult = suspendCancellableCoroutine { continuation ->
        PrivateMediaAccess.requireAccess()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { resp ->
                        val headerMap = LinkedHashMap<String, String>()
                        for (i in 0 until resp.headers.size) {
                            headerMap[resp.headers.name(i).lowercase(Locale.ROOT)] = resp.headers.value(i)
                        }
                        val setCookies = resp.headers("Set-Cookie")
                        val bodyBytes = ByteArrayOutputStream()
                        resp.body.byteStream().use { input ->
                            val buffer = ByteArray(8_192)
                            while (true) {
                                PrivateMediaAccess.requireAccess()
                                if (call.isCanceled()) throw IOException("请求已取消")
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (bodyBytes.size() + read > SITE_HTML_LIMIT) break
                                bodyBytes.write(buffer, 0, read)
                            }
                        }
                        val charset = resp.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                        RawCallResult(resp.code, bodyBytes.toString(charset.name()), headerMap, setCookies)
                    }
                    PrivateMediaAccess.requireAccess()
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun buildCookieHeader(url: HttpUrl, extraCookie: String?): String {
        val merged = LinkedHashMap<String, String>()
        sessionCookies[url.host.lowercase(Locale.ROOT)]?.let { merged.putAll(it) }
        val webViewCookie = runCatching {
            CookieManager.getInstance().getCookie(url.toString())
        }.getOrNull()
        for (source in listOfNotNull(webViewCookie, extraCookie)) {
            for (pair in source.split(';')) {
                val part = pair.trim()
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq > 0) {
                    merged[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun storeCookies(url: HttpUrl, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val host = url.host.lowercase(Locale.ROOT)
        val map = sessionCookies.computeIfAbsent(host) { java.util.concurrent.ConcurrentHashMap() }
        for (raw in setCookies) {
            val firstPair = raw.substringBefore(';').trim()
            val eq = firstPair.indexOf('=')
            if (eq > 0) {
                map[firstPair.substring(0, eq).trim()] = firstPair.substring(eq + 1).trim()
            }
            runCatching { CookieManager.getInstance().setCookie(url.toString(), raw) }
        }
    }

    /**
     * Loads a Cloudflare-protected page in an offscreen Android WebView using the device's
     * genuine WebView User-Agent and viewport layout so `Sec-CH-UA` and `navigator.userAgentData`
     * match, then syncs the resulting `cf_clearance` cookies into `CookieManager` and `sessionCookies`.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchViaAndroidWebView(
        url: String,
        validator: ((String) -> Boolean)?,
    ): SiteHttpResponse? {
        PrivateMediaAccess.requireAccess()
        val ctx = appContext ?: return null
        if (Looper.getMainLooper() == null) return null
        return webViewGate.withPermit {
            withTimeoutOrNull(30_000L) {
                withContext(Dispatchers.Main) {
                    val view = WebView(ctx)
                    try {
                        view.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            @Suppress("DEPRECATION")
                            databaseEnabled = true
                            loadsImagesAutomatically = true
                            mediaPlaybackRequiresUserGesture = true
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            userAgentString = webViewUserAgent
                        }
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                        )
                        view.layout(0, 0, 1080, 1920)
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(view, true)
                        view.webChromeClient = WebChromeClient()
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                                return !PrivateMediaAccess.hasAccess() || scheme != "http" && scheme != "https"
                            }

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                                if (PrivateMediaAccess.hasAccess()) null else
                                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse,
                            ) {
                                // Keep Cloudflare 403/503 challenge document alive while JS solves the challenge.
                            }
                        }
                        view.loadUrl(url)
                        var lastHtml = ""
                        repeat(56) { step ->
                            delay(500)
                            PrivateMediaAccess.requireAccess()
                            val snapshot = evaluateDomSnapshot(view)
                            if (snapshot.html.isNotBlank()) {
                                lastHtml = snapshot.html
                                val blocked = isCloudflareBlocked(200, emptyMap(), snapshot.html)
                                if (!blocked && (validator == null || validator(snapshot.html)) && (snapshot.loaded || step >= 3)) {
                                    runCatching { cookieManager.flush() }
                                    val finalUrl = view.url ?: url
                                    finalUrl.toHttpUrlOrNull()?.let { parsed ->
                                        val c = runCatching { cookieManager.getCookie(finalUrl) }.getOrNull()
                                        if (!c.isNullOrBlank()) storeCookies(parsed, c.split(';').map { it.trim() })
                                    }
                                    return@withContext SiteHttpResponse(200, finalUrl, snapshot.html, emptyMap())
                                }
                            }
                        }
                        runCatching { cookieManager.flush() }
                        if (lastHtml.isNotBlank() && !isCloudflareBlocked(200, emptyMap(), lastHtml)) {
                            SiteHttpResponse(200, view.url ?: url, lastHtml, emptyMap())
                        } else {
                            null
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            runCatching {
                                view.stopLoading()
                                view.webChromeClient = null
                                view.webViewClient = WebViewClient()
                                view.removeAllViews()
                                view.destroy()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves a SupJav embed iframe (`configured supjav embed/supjav.php?c=...`) inside an Android WebView
     * with `configured supjav primary` as the base URL, inspecting child player frames and `jwplayer().getPlaylist()`.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveSupJavEmbedViaWebView(
        supjavPageUrl: String,
        embedUrl: String,
        title: String,
    ): VideoPlaybackSource? {
        PrivateMediaAccess.requireAccess()
        val ctx = appContext ?: return null
        if (Looper.getMainLooper() == null) return null
        return webViewGate.withPermit {
            withTimeoutOrNull(25_000L) {
                withContext(Dispatchers.Main) {
                    val view = WebView(ctx)
                    var currentDocUrl = embedUrl
                    try {
                        view.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadsImagesAutomatically = true
                            mediaPlaybackRequiresUserGesture = false
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            allowFileAccess = false
                            allowContentAccess = false
                            userAgentString = webViewUserAgent
                        }
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                        )
                        view.layout(0, 0, 1080, 720)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                        var interceptedStream: Pair<String, String>? = null
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(
                                view,
                                """
                                (function(){
                                    function scan(){
                                        var media=[];
                                        function add(u){if(u && /^https?:\/\//i.test(u)) media.push(u);}
                                        try{var e=jwplayer().getPlaylist()[0]||{};add(e.file);(e.sources||[]).forEach(function(s){add(s.file);});}catch(_){}
                                        document.querySelectorAll('video').forEach(function(v){add(v.currentSrc);add(v.src);v.querySelectorAll('source[src]').forEach(function(s){add(s.src);});});
                                        if(media.length) window.__onvyraSupjavMedia = {url:location.href, media:media};
                                    }
                                    setInterval(scan, 400);
                                })();
                                """.trimIndent(),
                                setOf("*"),
                            )
                        }
                        view.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (!url.isNullOrBlank() && url != "about:blank") currentDocUrl = url
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                if (!PrivateMediaAccess.hasAccess())
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                val u = request?.url?.toString().orEmpty()
                                val ref = request?.requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value
                                    ?: currentDocUrl
                                if (interceptedStream == null &&
                                    (u.contains(".m3u8", ignoreCase = true) ||
                                        Regex("/hls[^?]*/master\\.txt", RegexOption.IGNORE_CASE).containsMatchIn(u) ||
                                        u.contains("${ExternalVideoEndpointConfig.host("supjav_stream")}/get_video", ignoreCase = true))
                                ) {
                                    interceptedStream = u to ref
                                }
                                return null
                            }
                        }
                        val safeEmbed = embedUrl.replace("&", "&amp;").replace("\"", "&quot;")
                        view.loadDataWithBaseURL(
                            supjavPageUrl,
                            "<html><body style=\"margin:0\"><iframe id=\"video\" src=\"$safeEmbed\" style=\"width:100%;height:360px;border:0\" allow=\"autoplay; fullscreen\"></iframe></body></html>",
                            "text/html",
                            "UTF-8",
                            null,
                        )
                        val visitedFrames = HashSet<String>()
                        repeat(40) {
                            delay(500)
                            PrivateMediaAccess.requireAccess()
                            interceptedStream?.let { (stream, ref) ->
                                val origin = ref.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://${ExternalVideoEndpointConfig.host("supjav_primary")}"
                                val mime = if (stream.contains("get_video")) "video/mp4" else "application/x-mpegURL"
                                return@withContext VideoPlaybackSource(
                                    url = stream,
                                    kind = VideoPlaybackKind.DIRECT,
                                    title = title,
                                    mimeType = mime,
                                    headers = mapOf("User-Agent" to webViewUserAgent, "Referer" to ref, "Origin" to origin),
                                )
                            }
                            val frameOrMedia = evaluatePlayerState(view)
                            if (frameOrMedia.mediaUrl != null) {
                                val ref = frameOrMedia.docUrl.ifBlank { currentDocUrl }
                                val origin = ref.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://${ExternalVideoEndpointConfig.host("supjav_primary")}"
                                val mime = if (frameOrMedia.mediaUrl.contains("get_video")) "video/mp4" else "application/x-mpegURL"
                                return@withContext VideoPlaybackSource(
                                    url = frameOrMedia.mediaUrl,
                                    kind = VideoPlaybackKind.DIRECT,
                                    title = title,
                                    mimeType = mime,
                                    headers = mapOf("User-Agent" to webViewUserAgent, "Referer" to ref, "Origin" to origin),
                                )
                            }
                            val nextFrame = frameOrMedia.iframeUrl
                            if (!nextFrame.isNullOrBlank() && visitedFrames.add(nextFrame) && visitedFrames.size <= 4) {
                                view.loadUrl(nextFrame, mapOf("Referer" to currentDocUrl))
                            }
                        }
                        null
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            runCatching {
                                view.stopLoading()
                                view.webViewClient = WebViewClient()
                                view.removeAllViews()
                                view.destroy()
                            }
                        }
                    }
                }
            }
        }
    }

    private data class DomSnapshot(val html: String, val loaded: Boolean)
    private data class PlayerPollSnapshot(val mediaUrl: String?, val iframeUrl: String?, val docUrl: String)

    private suspend fun evaluateDomSnapshot(view: WebView): DomSnapshot = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(
            "(function(){return JSON.stringify({html:document.documentElement?document.documentElement.outerHTML:'',loaded:document.readyState==='complete'});})()"
        ) { raw ->
            if (!cont.isActive) return@evaluateJavascript
            val parsed = runCatching {
                val str = (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
                val obj = Json.parseToJsonElement(str) as? JsonObject
                DomSnapshot(
                    html = (obj?.get("html") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    loaded = (obj?.get("loaded") as? JsonPrimitive)?.booleanOrNull == true,
                )
            }.getOrDefault(DomSnapshot("", false))
            cont.resume(parsed)
        }
    }

    private suspend fun evaluatePlayerState(view: WebView): PlayerPollSnapshot = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(
            """
            (function(){
                var media = [];
                function add(u){if(u && /^https?:\/\//i.test(u)) media.push(u);}
                if (window.__onvyraSupjavMedia && window.__onvyraSupjavMedia.media) {
                    window.__onvyraSupjavMedia.media.forEach(add);
                }
                try{var e=jwplayer().getPlaylist()[0]||{};add(e.file);(e.sources||[]).forEach(function(s){add(s.file);});}catch(_){}
                document.querySelectorAll('video').forEach(function(v){add(v.currentSrc);add(v.src);v.querySelectorAll('source[src]').forEach(function(s){add(s.src);});});
                var frame = '';
                document.querySelectorAll('iframe[src]').forEach(function(f){
                    if(!frame && f.src && /^https?:\/\//i.test(f.src) && !/(ads|banner|popup|recaptcha)/i.test(f.src)) frame = f.src;
                });
                return JSON.stringify({media:media[0]||'', frame:frame, url:location.href});
            })()
            """.trimIndent()
        ) { raw ->
            if (!cont.isActive) return@evaluateJavascript
            val parsed = runCatching {
                val str = (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
                val obj = Json.parseToJsonElement(str) as? JsonObject
                PlayerPollSnapshot(
                    mediaUrl = (obj?.get("media") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                    iframeUrl = (obj?.get("frame") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                    docUrl = (obj?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }.getOrDefault(PlayerPollSnapshot(null, null, ""))
            cont.resume(parsed)
        }
    }
}

private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
