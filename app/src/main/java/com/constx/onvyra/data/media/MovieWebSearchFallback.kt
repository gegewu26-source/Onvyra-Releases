package com.constx.onvyra.data.media

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import com.constx.onvyra.data.ai.tools.validateWebUrl
import okhttp3.HttpUrl

internal data class MovieWebCandidate(val title: String, val url: String, val text: String = "")

internal data class MovieWebPage(
    val url: String,
    val title: String,
    val description: String = "",
    val text: String = "",
    val links: List<MovieWebCandidate> = emptyList(),
)

internal data class MovieWebSearchSelection(
    val originalUrl: String,
    val source: VideoPlaybackSource,
    val durationKnown: Boolean = source.durationSeconds?.let { it.isFinite() && it > 0 } == true,
    val contentTypeVerified: Boolean = false,
)

internal data class MovieWebSearchOutcome(
    val selection: MovieWebSearchSelection? = null,
    val candidates: List<MovieWebCandidate> = emptyList(),
    val status: String,
)

/** A bounded programmatic continuation, independent of whether the model calls search_web next. */
internal class MovieWebSearchFallback(
    private val searchWeb: suspend (String) -> List<MovieWebCandidate>,
    private val readPage: suspend (String) -> MovieWebPage,
    private val resolveVideo: suspend (String) -> VideoPlaybackSource,
    private val timeoutMillis: Long = 90_000L,
) {
    init { require(timeoutMillis > 0) }

    suspend fun search(title: String, year: Int?, episode: Int): MovieWebSearchOutcome =
        search(title, year, episode, MovieContentType.GENERAL_VIDEO)

    suspend fun search(title: String, year: Int?, episode: Int,
                       contentType: MovieContentType): MovieWebSearchOutcome {
        val requested = title.trim().removeSurrounding("《", "》").trim()
        require(requested.isNotEmpty() && requested.length <= 160)
        require(year == null || year in 1..9999)
        require(episode > 0)
        val run = SearchRun(requested, year, episode, contentType)
        return withTimeoutOrNull(timeoutMillis) { run.execute() } ?: run.outcome("timeout")
    }

    private inner class SearchRun(val title: String, val year: Int?, val episode: Int, val contentType: MovieContentType) {
        private val episodeWork = contentType in setOf(MovieContentType.ANIME, MovieContentType.SERIES)
        private val visited = HashSet<String>()
        private val pageAddresses = HashSet<String>()
        private val resolvedAddresses = HashSet<String>()
        private val hostAttempts = HashMap<String, Int>()
        private val reported = LinkedHashMap<String, MovieWebCandidate>()
        private var candidateCount = 0
        private var pageCount = 0
        private var resolutionCount = 0
        private var successfulSearch = false

        suspend fun execute(): MovieWebSearchOutcome {
            val detail = buildString {
                year?.let { append(" $it") }
                if (episodeWork || episode > 1) {
                    if (contentType == MovieContentType.ANIME) append(" 动漫")
                    append(" 第${episode}集 正片")
                } else append(" 电影 正片")
            }
            val queries = listOf("\"$title\"$detail 在线观看", "\"$title\"$detail 播放地址")
            for (query in queries.distinct().take(2)) {
                if (candidateCount >= 6 || pageCount >= 4 && resolutionCount >= 4) break
                val candidates = try {
                    withTimeoutOrNull(15_000) { searchWeb(query) }?.also { successfulSearch = true }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                for (candidate in candidates.orEmpty().take(30)) {
                    examine(candidate)?.let { return outcome("ready", it) }
                }
            }
            return outcome(when {
                candidateCount > 0 -> "unverified_sources"
                successfulSearch -> "no_results"
                else -> "search_unavailable"
            })
        }

        fun outcome(status: String, selection: MovieWebSearchSelection? = null) =
            MovieWebSearchOutcome(selection, reported.values.take(4), status)

        private suspend fun examine(
            candidate: MovieWebCandidate,
            parent: MovieWebPage? = null,
            parentEpisode: Boolean = false,
            depth: Int = 0,
        ): MovieWebSearchSelection? {
            if (candidateCount >= 6 || depth > 1) return null
            val address = publicAddress(candidate.url) ?: return null
            if (isSearchPage(address) || hasClipLabel(candidate.title) || hasClipLabel(candidate.text.take(400))) return null
            if (parent == null && (!matchesRequestedTitle(candidate.title, title) ||
                    hasWrongYear(candidate.title, candidate.text.take(400), title, year))) return null
            val labelEpisodes = episodeNumbers(candidate.title)
            if (labelEpisodes.isNotEmpty() && episode !in labelEpisodes) return null
            val canonical = address.toString()
            if (!visited.add(canonical)) return null
            if ((hostAttempts[address.host] ?: 0) >= 2) return null
            hostAttempts[address.host] = (hostAttempts[address.host] ?: 0) + 1
            candidateCount++

            // A real media URL taken from a checked page needs no second HTML fetch. Its
            // signed URL is never used as the model-visible original_url or a candidate.
            if (parent != null && isDirectMediaAddress(address)) {
                val episodeMatches = (!episodeWork && episode <= 1) || episode in labelEpisodes || parentEpisode
                return if (episodeMatches) resolve(canonical, parent, episodeMatches) else null
            }
            if (pageCount >= 4 || !pageAddresses.add(canonical)) return null
            pageCount++
            val page = try {
                withTimeoutOrNull(12_000) { readPage(canonical) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } ?: return null
            val finalAddress = publicAddress(page.url) ?: return null
            if (isSearchPage(finalAddress) || isDirectMediaAddress(finalAddress) || hasClipLabel(page.title) ||
                hasClipLabel(page.description.take(500))) return null
            if (finalAddress.toString() != canonical && !pageAddresses.add(finalAddress.toString())) return null
            visited.add(finalAddress.toString())
            val inheritedTitle = parent != null && genericVideoTitle(page.title)
            if (!inheritedTitle && !matchesRequestedTitle(page.title, title)) return null
            if (hasWrongYear(page.title, page.description + "\n" + page.text.take(600), title, year)) return null
            val pageEpisodes = episodeNumbers(page.title)
            if (pageEpisodes.isNotEmpty() && episode !in pageEpisodes) return null
            val episodeMatches = (!episodeWork && episode <= 1) || episode in pageEpisodes || episode in labelEpisodes ||
                inheritedTitle && parentEpisode
            val originalAddress = if (finalAddress.queryParameterNames.any { sensitiveParameter.containsMatchIn(it) })
                address.takeUnless { it.queryParameterNames.any { key -> sensitiveParameter.containsMatchIn(key) } }
                    ?: return null else finalAddress
            val checkedPage = page.copy(url = originalAddress.toString(),
                title = if (inheritedTitle) parent!!.title else page.title)
            report(checkedPage)

            // On watch pages, try the actual page first. On an index/detail page, prefer
            // its explicit media/play links instead of spending an extraction on a summary.
            if (episodeMatches && isPlaybackPage(finalAddress)) {
                resolve(checkedPage.url, checkedPage, episodeMatches)?.let { return it }
            }
            if (depth == 0) {
                checkedPage.links.asSequence().filter { isPlaybackLink(it.title) }
                    .filter { !episodeWork && episode <= 1 || episodeNumbers(it.title).let { labels ->
                        episode in labels || labels.isEmpty() && episodeMatches
                    } }
                    .sortedByDescending { publicAddress(it.url)?.let(::isDirectMediaAddress) == true }
                    .take(6).forEach { child ->
                        examine(child, checkedPage, episodeMatches, depth + 1)?.let { return it }
                    }
            }
            return if (episodeMatches && !isPlaybackPage(finalAddress))
                resolve(checkedPage.url, checkedPage, episodeMatches) else null
        }

        private suspend fun resolve(url: String, page: MovieWebPage, episodeMatches: Boolean): MovieWebSearchSelection? {
            if (!episodeMatches || resolutionCount >= 4 || !resolvedAddresses.add(url)) return null
            resolutionCount++
            val source = try {
                withTimeoutOrNull(20_000) { resolveVideo(url) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } ?: return null
            if (source.kind != VideoPlaybackKind.DIRECT || publicAddress(source.url) == null ||
                source.audioUrl?.let { publicAddress(it) == null } == true) return null
            val sourceTitle = source.title.orEmpty()
            if (hasClipLabel(sourceTitle)) return null
            val generic = genericVideoTitle(sourceTitle)
            if (!generic && (!matchesRequestedTitle(sourceTitle, title) ||
                    hasWrongYear(sourceTitle, "", title, year))) return null
            val sourceEpisodes = episodeNumbers(sourceTitle)
            if (sourceEpisodes.isNotEmpty() && episode !in sourceEpisodes) return null
            val knownDuration = source.durationSeconds?.takeIf { it.isFinite() && it > 0 }
            // Short anime/series episodes exist. Episode metadata, rather than a ten-minute
            // movie cutoff, establishes the requested episode; commentary stays excluded.
            if (!episodeWork && knownDuration != null && knownDuration < 600.0) return null
            return MovieWebSearchSelection(page.url,
                if (generic) source.copy(title = page.title) else source,
                durationKnown = knownDuration != null,
                contentTypeVerified = verifiesMovieContentType(contentType, page.title + " " + page.description))
        }

        private fun report(page: MovieWebPage) {
            val address = publicAddress(page.url) ?: return
            if (reported.size >= 4 || isDirectMediaAddress(address) ||
                address.queryParameterNames.any { sensitiveParameter.containsMatchIn(it) }) return
            // Snippets can contain extracted media URLs or upstream state. Only checked
            // webpage titles and their original page addresses may leave this coordinator.
            reported.putIfAbsent(address.toString(), MovieWebCandidate(page.title.take(512), address.toString()))
        }
    }
}

private fun publicAddress(value: String): HttpUrl? = runCatching { validateWebUrl(value) }.getOrNull()

private val clipLabel = Regex(
    "预告片|预告(?!犯)|片花|花絮|片段|混剪|剪辑|(?:电影|影视)?解说|影评|幕后|抢先看|" +
        "\\b(?:trailer|teaser|commentary|review|reaction|highlights?|clips?)\\b",
    RegexOption.IGNORE_CASE,
)
private fun hasClipLabel(value: String) = clipLabel.containsMatchIn(value) || hasNonWorkLabel(value)
private val sensitiveParameter = Regex("token|sign|auth|key|secret|cookie|session|jwt|hmac|policy", RegexOption.IGNORE_CASE)
private val yearNumber = Regex("(?<!\\d)(?:18|19|20|21)\\d{2}(?!\\d)")
private val releaseYear = Regex("(?:上映|首播|年份|年代|发行|release(?:d)?|year)\\s*[:：]?\\s*((?:18|19|20|21)\\d{2})", RegexOption.IGNORE_CASE)

private fun hasWrongYear(label: String, description: String, requestedTitle: String, year: Int?): Boolean {
    if (year == null) return false
    // A film title can itself contain a year (for example 1917 or 2001). It is not release metadata.
    val withoutTitle = label.replace(requestedTitle, "", ignoreCase = true)
    val labelYears = yearNumber.findAll(withoutTitle).mapNotNull { it.value.toIntOrNull() }.toList()
    if (labelYears.isNotEmpty()) return year !in labelYears
    val described = releaseYear.findAll(description).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
    return described.isNotEmpty() && year !in described
}

private fun compactTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{P}]+"), "")

private val titlePrefix = Regex("(?:(?:电影|电视剧|剧集|影视|综艺|动漫|高清|超清|蓝光|正片|hd|bd)|(?:(?:18|19|20|21)\\d{2}))*")
private val titleSuffix = Regex(
    "^(?:免费在线观看|在线观看|在线播放|在线观看|播放|免费观看|观看|电影|电视剧|剧集|动漫|动画|番剧|全集|全季|视频|正片|" +
        "高清|超清|蓝光|完整版|国语|粤语|中字|修复版|第[零〇一二两三四五六七八九十百\\d]+[季集话期]|" +
        "(?:18|19|20|21)\\d{2}|(?:2160|1080|720|480)p|4k|8k|uhd|hdr|hd|bd|episode\\d+|ep\\d+)",
)

private fun matchesRequestedTitle(actual: String, requested: String): Boolean {
    val reqCode = extractJavCode(requested)
    val actCode = extractJavCode(actual)
    if (reqCode != null && actCode != null && reqCode.equals(actCode, ignoreCase = true)) return true
    val expected = compactTitle(requested)
    if (expected.isEmpty()) return false
    val sections = actual.split(Regex("[|｜_—-]")).plus(actual)
    return sections.any { section ->
        val value = compactTitle(section)
        val at = value.indexOf(expected)
        if (at < 0) false else {
            val before = value.substring(0, at)
            val after = value.substring(at + expected.length)
            titlePrefix.matches(before) && (after.isEmpty() || titleSuffix.containsMatchIn(after))
        }
    }
}

private fun genericVideoTitle(value: String): Boolean = compactTitle(value).let {
    it.isEmpty() || it in setOf("video", "movie", "视频", "视频网页", "网页视频", "电影", "正片", "播放", "视频播放", "高清播放", "播放视频", "内嵌视频", "hd", "bd")
}

private fun isDirectMediaAddress(address: HttpUrl) =
    Regex("\\.(?:m3u8|mpd|mp4|webm|mov|m4v)(?:$)", RegexOption.IGNORE_CASE).containsMatchIn(address.encodedPath)

private fun isSearchPage(address: HttpUrl): Boolean {
    val path = address.encodedPath.lowercase(Locale.ROOT)
    return Regex("(?:^|/)(?:search|searching|vodsearch|so)(?:[/_.-]|$)").containsMatchIn(path) ||
        path.matches(Regex(".*/s/?")) ||
        address.queryParameterNames.any { it.lowercase(Locale.ROOT) in setOf("wd", "keyword", "searchword", "search_query", "q", "query") }
}

private fun isPlaybackPage(address: HttpUrl) =
    identifyExternalVideoSite(address.toString()) != null ||
        Regex("(?:^|[/_-])(?:play|vodplay|watch|video|videos|player)(?:[/_.-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(address.encodedPath)

private fun isPlaybackLink(label: String) = !hasClipLabel(label) &&
    (Regex("播放|正片|第[零〇一二两三四五六七八九十百\\d]+[集话期]|内嵌视频|嵌入视频|" +
        "^(?:hd|bd|4k|1080p|720p|episode\\s*\\d+|ep\\s*\\d+)$", RegexOption.IGNORE_CASE).containsMatchIn(label))

private fun episodeNumbers(value: String): Set<Int> = buildSet {
    Regex("第\\s*([零〇一二两三四五六七八九十百\\d]+)\\s*[集话期]").findAll(value).forEach {
        chineseEpisodeNumber(it.groupValues[1])?.let(::add)
    }
    Regex("(?<![A-Za-z0-9])(?:episode|ep)\\s*0*(\\d+)(?!\\d)", RegexOption.IGNORE_CASE).findAll(value).forEach {
        it.groupValues[1].toIntOrNull()?.let(::add)
    }
}

private fun chineseEpisodeNumber(value: String): Int? {
    value.toIntOrNull()?.let { return it }
    val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    var total = 0
    var digit = 0
    for (char in value) when (char) {
        '十' -> { total += (if (digit == 0) 1 else digit) * 10; digit = 0 }
        '百' -> { total += (if (digit == 0) 1 else digit) * 100; digit = 0 }
        else -> digit = digits[char] ?: return null
    }
    return (total + digit).takeIf { it > 0 }
}
