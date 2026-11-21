package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val SEARCH_BODY_LIMIT = 2 * 1024 * 1024
private const val SOURCE_TIMEOUT_MS = 6_000L

/** Keeps the persisted Bing option compatible; no-key fallbacks never use configured API keys. */
object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name = "Automatic Web Search"
    @Composable override fun Description() { Text(stringResource(R.string.bing_desc)) }
    override fun parameters(options: SearchServiceOptions.BingLocalOptions): InputSchema = InputSchema.Obj(
        properties = buildJsonObject { put("query",buildJsonObject {
            put("type","string")
            put("description","Focused web search keywords; site:example.com restricts returned domains. Use separate focused searches for different questions.")
        }) }, required = listOf("query"))
    override fun scrapingParameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? = null

    override suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions): Result<SearchResult> = withContext(Dispatchers.IO) {
        val query = (params["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty() || query.length > 1_000) return@withContext Result.failure(
            IllegalArgumentException("query must contain 1..1000 characters"))
        try {
            val client = httpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
                .connectTimeout(SOURCE_TIMEOUT_MS,TimeUnit.MILLISECONDS)
                .readTimeout(SOURCE_TIMEOUT_MS,TimeUnit.MILLISECONDS)
                .callTimeout(SOURCE_TIMEOUT_MS,TimeUnit.MILLISECONDS)
                .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                    .removeHeader("Authorization").build()) }.build()
            val locale = Locale.getDefault()
            val language = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: "en-US"
            suspend fun fetch(base: String, key: String = "q", rss: Boolean = false,
                cookie: Boolean = false, parser: (String,okhttp3.HttpUrl,Int)->List<SearchResultItem>): List<SearchResultItem> {
                val builder = base.toHttpUrl().newBuilder().addQueryParameter(key,query)
                if (rss) builder.addQueryParameter("format","rss").addQueryParameter("count","30")
                val url = builder.build()
                val request = Request.Builder().url(url)
                    .header("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept",if(rss) "application/rss+xml, application/xml, text/xml, */*" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language","$language,${locale.language};q=0.8,en;q=0.6")
                    .apply { if(cookie) header("Cookie","SRCHHPGUSR=ULSR=1") }.build()
                val call = client.newCall(request)
                val body = coroutineScope {
                    // Call.await only owns cancellation until headers arrive. Keep cancelling the
                    // call during body reads too, so a losing fallback cannot keep a socket alive.
                    val closer = launch(start=CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try { readSearchBody(call.await()) }
                    finally { withContext(NonCancellable) { closer.cancelAndJoin() } }
                }
                return parser(body,url,50)
            }
            val items = collectPublicSearchResults(query,commonOptions.resultSize,
                primary = { fetch("https://www.bing.com/search",cookie=true,parser=::parseBingSearchResults) },
                fallbacks = listOf(
                    PublicSearchSource("Sogou") { fetch("https://www.sogou.com/web",key="query",parser=::parseSogouSearchResults) },
                    PublicSearchSource("360") { fetch("https://www.so.com/s",parser=::parse360SearchResults) },
                    PublicSearchSource("Bing RSS") { fetch("https://www.bing.com/search",rss=true,parser=::parseBingRssResults) },
                    PublicSearchSource("DuckDuckGo") { fetch("https://lite.duckduckgo.com/lite/",parser=::parseDuckDuckGoResults) },
                ))
            Result.success(SearchResult(items=items))
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { Result.failure(error) }
    }

    override suspend fun scrape(params: JsonObject,commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions): Result<ScrapedResult> =
        Result.failure(UnsupportedOperationException("Use the built-in page fetch tool for URL contents"))
}

private suspend fun readSearchBody(response: okhttp3.Response): String = response.use { resp ->
    check(resp.isSuccessful) { "Search failed: HTTP ${resp.code}" }
    val body=resp.body
    val bytes=ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    body.byteStream().use { input ->
        while(bytes.size() <= SEARCH_BODY_LIMIT) {
            currentCoroutineContext().ensureActive()
            val count=input.read(buffer,0,minOf(buffer.size,SEARCH_BODY_LIMIT+1-bytes.size()))
            if(count<0) break
            bytes.write(buffer,0,count)
        }
    }
    check(bytes.size() <= SEARCH_BODY_LIMIT) { "Search response exceeded its size limit" }
    bytes.toString((body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8).name())
}
