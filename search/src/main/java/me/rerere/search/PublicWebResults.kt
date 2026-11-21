package me.rerere.search

import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import me.rerere.search.SearchResult.SearchResultItem
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal data class PublicSearchSource(val name: String, val fetch: suspend () -> List<SearchResultItem>)
private data class SourceResult(val items: List<SearchResultItem>,val problem: String?=null)

/** Primary stays a single request when useful. Weak/empty results widen to independent public sources. */
internal suspend fun collectPublicSearchResults(query: String,requested: Int,
    primary: suspend () -> List<SearchResultItem>,fallbacks: List<PublicSearchSource>,
    sourceTimeoutMs: Long=6_200,totalTimeoutMs: Long=14_000): List<SearchResultItem> = coroutineScope {
    val limit=requested.coerceIn(1,50)
    val collected=mutableListOf<SearchResultItem>()
    val failures=mutableListOf<String>()
    suspend fun attempt(name: String,fetch: suspend ()->List<SearchResultItem>): SourceResult = try {
        val items=withTimeoutOrNull(sourceTimeoutMs) { fetch() }
        SourceResult(items.orEmpty(),if(items==null) "$name timeout" else if(items.isEmpty()) "$name empty" else null)
    } catch(cancelled: CancellationException) { throw cancelled }
    catch(error: Exception) {
        // Never put challenge pages, response bodies or a configured transport's secrets in errors.
        val http=Regex("HTTP ([0-9]{3})").find(error.message.orEmpty())?.groupValues?.get(1)
        val problem=when {
            http!=null -> "$name HTTP $http"
            error.message=="Search response exceeded its size limit" -> "$name size limit"
            else -> "$name ${error.javaClass.simpleName}"
        }
        SourceResult(emptyList(),problem)
    }
    fun accept(result: SourceResult) { collected+=result.items;result.problem?.let(failures::add) }
    withTimeoutOrNull(totalTimeoutMs) {
        accept(attempt("Bing",primary))
        if(!needsPublicSearchFallback(query,rankPublicSearchResults(query,collected,limit),limit)) return@withTimeoutOrNull
        // Two sources at once bounds mobile sockets/DOM allocations. Later sources are
        // contacted only if the first pair is still empty, sparse or off topic.
        for(wave in fallbacks.chunked(2)) {
            val completed=Channel<SourceResult>(Channel.UNLIMITED)
            val jobs=wave.map { source -> launch { completed.send(attempt(source.name,source.fetch)) } }
            try {
                repeat(jobs.size) {
                    accept(completed.receive())
                    if(!needsPublicSearchFallback(query,rankPublicSearchResults(query,collected,limit),limit)) return@withTimeoutOrNull
                }
            } finally {
                jobs.forEach { it.cancel() }
                withContext(NonCancellable) { jobs.forEach { it.join() } }
                completed.close()
            }
        }
    }
    val result=rankPublicSearchResults(query,collected,limit)
    if(result.isEmpty()) error("No readable web results; providers may be unavailable or require verification (${failures.distinct().joinToString("; ").take(400)})")
    result
}

internal fun needsPublicSearchFallback(query: String,items: List<SearchResultItem>,requested: Int): Boolean {
    if(items.size < minOf(requested.coerceIn(1,50),5)) return true
    val terms=queryTerms(query)
    return terms.size>=2 && items.take(5).none { matchingTerms(it,terms)>=2 }
}

internal fun rankPublicSearchResults(query: String,items: List<SearchResultItem>,limit: Int): List<SearchResultItem> {
    val terms=queryTerms(query)
    val domains=Regex("(?i)(?:^|\\s)(-?)site:([a-z0-9.-]+)").findAll(query).map {
        it.groupValues[1] to it.groupValues[2].trim('.').lowercase(Locale.ROOT)
    }.filter { it.second.isNotEmpty() }.toList()
    val unique=LinkedHashMap<String,SearchResultItem>()
    for(item in items) {
        val url=publicResultUrl(item.url.toHttpUrlOrNull() ?: continue) ?: continue
        val host=url.toHttpUrlOrNull()!!.host
        fun matches(domain: String)=host==domain || host.endsWith(".$domain")
        if(domains.any { it.first.isEmpty() } && domains.none {it.first.isEmpty() && matches(it.second)}) continue
        if(domains.any {it.first=="-" && matches(it.second)}) continue
        val clean=item.copy(url=url,title=item.title.trim().take(512),text=item.text.trim().take(4_000))
        if(clean.title.isEmpty()) continue
        val previous=unique[url]
        if(previous==null) unique[url]=clean
        else if(clean.text.length>previous.text.length || (previous.publishedDate==null && clean.publishedDate!=null))
            unique[url]=previous.copy(text=if(clean.text.length>previous.text.length) clean.text else previous.text,
                publishedDate=previous.publishedDate ?: clean.publishedDate)
    }
    return unique.values.sortedByDescending { matchingTerms(it,terms) }.take(limit.coerceIn(1,50))
}

private val STOP_WORDS=setOf("the","and","for","with","what","which","when","where","how","are","does","from","that","this","about","site")
private fun queryTerms(query: String): List<String> {
    val plain=query.replace(Regex("(?i)(?:^|\\s)-?site:[^\\s]+")," ").lowercase(Locale.ROOT)
    return Regex("[\\p{IsHan}]+|[a-z0-9_+.\\-]+").findAll(plain).flatMap { match ->
        val value=match.value
        if(value.first().code in 0x3400..0x9fff && value.length>2) value.windowed(2).asSequence()
        else sequenceOf(value)
    }.filter { it.length>=2 && it !in STOP_WORDS }.distinct().take(32).toList()
}
private fun matchingTerms(item: SearchResultItem,terms: List<String>): Int {
    val text="${item.title} ${item.text} ${item.url}".lowercase(Locale.ROOT)
    return terms.count(text::contains)
}

internal fun parseBingSearchResults(html: String,base: HttpUrl,limit: Int): List<SearchResultItem> {
    val document=Jsoup.parse(html,base.toString())
    if(document.select("#b_captcha, .b_captcha, iframe[src*=captcha]").isNotEmpty()) return emptyList()
    return distinctParsed(document.select("li.b_algo, #b_results .b_algo"),limit) { element ->
        val anchor=element.selectFirst("h2 a[href]") ?: return@distinctParsed null
        val target=publicResultUrl(base.resolve(anchor.attr("href")) ?: return@distinctParsed null) ?: return@distinctParsed null
        SearchResultItem(anchor.text(),target,element.select(".b_caption p, .b_snippet, .b_lineclamp2").text())
    }
}

internal fun parseBingRssResults(xml: String,base: HttpUrl,limit: Int): List<SearchResultItem> {
    val document=Jsoup.parse(xml,base.toString(),Parser.xmlParser())
    return distinctParsed(document.select("item"),limit) { item ->
        val title=item.selectFirst("title")?.text().orEmpty()
        val raw=item.selectFirst("link")?.text()?.trim() ?: return@distinctParsed null
        val target=publicResultUrl(base.resolve(raw) ?: return@distinctParsed null) ?: return@distinctParsed null
        val description=Jsoup.parseBodyFragment(item.selectFirst("description")?.text().orEmpty()).text()
        SearchResultItem(title,target,description,publishedDate=item.selectFirst("pubDate")?.text()?.takeIf(String::isNotBlank))
    }
}

internal fun parseDuckDuckGoResults(html: String,base: HttpUrl,limit: Int): List<SearchResultItem> {
    val document=Jsoup.parse(html,base.toString())
    if(document.select("#challenge-error, form[action*=anomaly]").isNotEmpty()) return emptyList()
    return distinctParsed(document.select("a.result-link[href], a.result__a[href]"),limit) { anchor ->
        val target=publicResultUrl(base.resolve(anchor.attr("href")) ?: return@distinctParsed null) ?: return@distinctParsed null
        val snippet=anchor.closest(".result")?.selectFirst(".result__snippet")?.text() ?: run {
            var row=anchor.closest("tr")?.nextElementSibling()
            var text=""
            repeat(3) { if(text.isEmpty()) {text=row?.selectFirst(".result-snippet")?.text().orEmpty();row=row?.nextElementSibling()} }
            text
        }
        SearchResultItem(anchor.text(),target,snippet)
    }
}

internal fun parseSogouSearchResults(html: String,base: HttpUrl,limit: Int): List<SearchResultItem> {
    val document=Jsoup.parse(html,base.toString())
    if(document.select("#seccodeForm, #captcha_container, form[action*=antispider]").isNotEmpty()) return emptyList()
    return distinctParsed(document.select(".vrwrap, .rb"),limit) { item ->
        if(item.hasAttr("data-ad") || item.hasClass("vr-ad")) return@distinctParsed null
        val anchor=item.selectFirst("h3.vr-title a[href], h3.pt a[href]") ?: return@distinctParsed null
        // Read the real target supplied with this organic result. Never follow opaque redirect
        // URLs or turn the displayed, possibly truncated citation into an invented page URL.
        val metadata=item.selectFirst(".r-sech[data-url], [data-result-url]")
        val raw=metadata?.let { it.attr(if(it.hasAttr("data-url")) "data-url" else "data-result-url") }
            ?.takeIf(String::isNotBlank) ?: anchor.attr("href")
        val target=publicResultUrl(base.resolve(raw) ?: return@distinctParsed null) ?: return@distinctParsed null
        SearchResultItem(anchor.text(),target,item.select(".fz-mid, .str_info, .star-wiki").text())
    }
}

internal fun parse360SearchResults(html: String,base: HttpUrl,limit: Int): List<SearchResultItem> {
    val document=Jsoup.parse(html,base.toString())
    if(document.select("#captcha, #captcha-box, form[action*=captcha]").isNotEmpty()) return emptyList()
    return distinctParsed(document.select("li.res-list"),limit) { item ->
        if(item.attr("data-result-type").lowercase(Locale.ROOT).startsWith("ad")) return@distinctParsed null
        val anchor=item.selectFirst("h3.res-title a[href]") ?: return@distinctParsed null
        val raw=anchor.attr("data-mdurl").takeIf(String::isNotBlank) ?: anchor.attr("href")
        val target=publicResultUrl(base.resolve(raw) ?: return@distinctParsed null) ?: return@distinctParsed null
        SearchResultItem(anchor.text(),target,item.select("[data-result-summary], .res-desc").text())
    }
}

private fun distinctParsed(elements: Iterable<Element>,limit: Int,parse: (Element)->SearchResultItem?): List<SearchResultItem> {
    val items=LinkedHashMap<String,SearchResultItem>()
    for(element in elements) {
        val item=parse(element) ?: continue
        if(item.title.isBlank()) continue
        items.putIfAbsent(item.url,item.copy(title=item.title.trim().take(512),text=item.text.trim().take(4_000)))
        if(items.size>=limit.coerceIn(1,50)) break
    }
    return items.values.toList()
}

/** Unwrap only known public search redirects, reject credentials, then deduplicate tracking variants. */
internal fun publicResultUrl(initial: HttpUrl): String? {
    val target=when {
        initial.host.isDomain("bing.com") && initial.encodedPath.trimEnd('/')=="/ck/a" -> {
            val encoded=initial.queryParameter("u") ?: return null
            (if(encoded.startsWith("a1")) encoded.drop(2).decodeBase64()?.utf8() else encoded)?.toHttpUrlOrNull() ?: return null
        }
        initial.host.isDomain("duckduckgo.com") && initial.encodedPath.trimEnd('/')=="/l" ->
            initial.queryParameter("uddg")?.toHttpUrlOrNull() ?: return null
        else -> initial
    }
    if(target.username.isNotEmpty() || target.password.isNotEmpty() || target.isSearchLocator()) return null
    val builder=target.newBuilder().fragment(null)
    target.queryParameterNames.filter {it.startsWith("utm_",true) || it.lowercase(Locale.ROOT) in setOf("gclid","fbclid","msclkid")}
        .forEach(builder::removeAllQueryParameters)
    return builder.build().toString()
}
private fun String.isDomain(domain: String)=this==domain || endsWith(".$domain")

private fun HttpUrl.isSearchLocator(): Boolean {
    val path=encodedPath.trimEnd('/')
    return when {
        host.isDomain("bing.com") -> path in setOf("/search","/ck/a") || (path.isEmpty() && queryParameter("q")!=null)
        host.isDomain("duckduckgo.com") -> path in setOf("/l","/lite","/html") || (path.isEmpty() && queryParameter("q")!=null)
        host.isDomain("sogou.com") -> path in setOf("/link","/web")
        host.isDomain("so.com") -> path in setOf("/link","/s")
        else -> false
    }
}
