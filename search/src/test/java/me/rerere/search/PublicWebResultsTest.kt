package me.rerere.search

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import me.rerere.search.SearchResult.SearchResultItem
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PublicWebResultsTest {
    private fun item(title: String="Kotlin coroutines",url: String="https://kotlinlang.org/docs/coroutines.html",text: String="Kotlin coroutines guide") = SearchResultItem(title,url,text)

    @Test fun strongPrimaryDoesNotSendTheQueryToOtherProviders() = runBlocking {
        var fallbackCalls=0
        val items=collectPublicSearchResults("Kotlin coroutines",1,{listOf(item())},
            listOf(PublicSearchSource("unused") {fallbackCalls++;error("must not run")}))
        assertEquals(1,items.size)
        assertEquals(0,fallbackCalls)
    }

    @Test fun poorAndSparsePrimaryResultsAreEnrichedAndRelevantPagesRankFirst() = runBlocking {
        val unrelated=(1..5).map {item("Android homepage","https://android.example/$it","Android phones")}
        assertTrue(needsPublicSearchFallback("Android AccessibilityService getWindowsOnAllDisplays",unrelated,5))
        val exact=item("AccessibilityService","https://developer.android.com/reference/android/accessibilityservice/AccessibilityService",
            "AccessibilityService getWindowsOnAllDisplays reads all displays")
        val found=collectPublicSearchResults("Android AccessibilityService getWindowsOnAllDisplays",5,{unrelated},
            listOf(PublicSearchSource("fixture") {listOf(exact)}))
        assertEquals(exact.url,found.first().url)
        assertEquals(5,found.size)
        assertTrue(needsPublicSearchFallback("Kotlin coroutines",listOf(item()),10))
    }

    @Test fun fastUsefulFallbackCancelsItsStillRunningPeers() = runBlocking {
        val started=CompletableDeferred<Unit>()
        val cancelled=AtomicBoolean()
        val found=collectPublicSearchResults("Kotlin coroutines",1,{emptyList()},listOf(
            PublicSearchSource("slow") {try {started.complete(Unit);awaitCancellation()} finally {cancelled.set(true)}},
            PublicSearchSource("fast") {started.await();listOf(item())},
        ))
        assertEquals(1,found.size)
        assertTrue(cancelled.get())
    }

    @Test fun aPrimaryTimeoutReachesTheFallbackAndCallerCancellationStopsAllWork() = runBlocking {
        val timedOut=AtomicBoolean()
        val result=collectPublicSearchResults("Kotlin coroutines",1,
            {try {awaitCancellation()} finally {timedOut.set(true)}},
            listOf(PublicSearchSource("fast") {listOf(item())}),sourceTimeoutMs=20,totalTimeoutMs=200)
        assertEquals(1,result.size);assertTrue(timedOut.get())
        val pending=CompletableDeferred<Unit>();val stopped=AtomicBoolean()
        val job=launch { collectPublicSearchResults("Kotlin",10,{emptyList()},
            listOf(PublicSearchSource("pending") {try{pending.complete(Unit);awaitCancellation()}finally{stopped.set(true)}})) }
        pending.await();job.cancelAndJoin();assertTrue(stopped.get())
    }

    @Test fun allEmptyOrChallengedProvidersFailWithoutFabricatedResults() = runBlocking {
        val failure=runCatching {collectPublicSearchResults("query",10,{emptyList()},
            listOf(PublicSearchSource("blocked") {error("private=never echo this")},PublicSearchSource("empty") {emptyList()}))}.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("No readable web results"))
        assertFalse(failure.message!!.contains("private"))
    }

    @Test fun rssDescriptionsAreReadableAndProviderDatesArePreserved() {
        val found=parseBingRssResults("""<rss><channel><item><title>Example</title><link>https://example.org/article</link>
          <description>&lt;b&gt;Readable&lt;/b&gt; &amp;amp; useful</description><pubDate>2026-10-30</pubDate></item></channel></rss>""",
            "https://www.bing.com/search".toHttpUrl(),10)
        assertEquals("Readable & useful",found.single().text)
        assertEquals("2026-10-30",found.single().publishedDate)
    }

    @Test fun duckduckgoLiteAndHtmlRedirectsResolveToRealPagesAndKeepSnippets() {
        val html="""<table><tr><td><a class="result-link" href="/l/?uddg=https%3A%2F%2Fexample.org%2Fguide%3Futm_source%3Dsearch">Guide</a></td></tr>
          <tr><td></td></tr><tr><td class="result-snippet">Useful guide snippet</td></tr></table>
          <div class="result"><a class="result__a" href="https://example.org/other">Other</a><a class="result__snippet">Other snippet</a></div>"""
        val found=parseDuckDuckGoResults(html,"https://lite.duckduckgo.com/lite/".toHttpUrl(),10)
        assertEquals(listOf("https://example.org/guide","https://example.org/other"),found.map {it.url})
        assertEquals("Useful guide snippet",found.first().text)
        assertEquals("Other snippet",found.last().text)
    }

    @Test fun sogouAnd360UseCanonicalMetadataInsteadOfOpaqueSearchRedirects() {
        val sogou="""<div class="vrwrap"><h3 class="vr-title"><a href="/link?url=opaque">Official guide</a></h3>
          <div class="fz-mid">Real page summary</div><div class="r-sech" data-url="https://developer.example/guide"></div></div>
          <div class="vrwrap"><h3 class="vr-title"><a href="/link?url=unknown">Truncated citation</a></h3><span>https://example.org/...</span></div>"""
        assertEquals("https://developer.example/guide",parseSogouSearchResults(sogou,"https://www.sogou.com/web".toHttpUrl(),10).single().url)
        val so="""<li class="res-list" data-result-type="normal"><h3 class="res-title"><a href="/link?m=opaque"
          data-mdurl="https://example.org/a">Actual title</a></h3><span data-result-summary>Actual snippet</span></li>
          <li class="res-list" data-result-type="ad"><h3 class="res-title"><a href="https://ads.example/">Ad</a></h3></li>"""
        assertEquals("Actual snippet",parse360SearchResults(so,"https://www.so.com/s".toHttpUrl(),10).single().text)
    }

    @Test fun trackingDuplicatesMergeAndExplicitDomainRestrictionsApplyToEveryFallback() {
        val items=listOf(item("First","https://docs.example/a?utm_source=x#top","short"),
            item("Second","https://docs.example/a","much longer useful snippet"),item("Elsewhere","https://other.example/a"),
            item("Login","https://user:pass@docs.example/a"))
        val found=rankPublicSearchResults("site:docs.example -site:private.docs.example Kotlin",items,10)
        assertEquals(1,found.size)
        assertEquals("https://docs.example/a",found.single().url)
        assertEquals("much longer useful snippet",found.single().text)
        assertTrue(rankPublicSearchResults("site:docs.example",listOf(item(url="https://docs.example.evil/a")),10).isEmpty())
    }
    @Test fun providerDocumentationRemainsAValidSourceButSearchLocatorsDoNot() {
        assertEquals("https://www.sogou.com/labs/",publicResultUrl("https://www.sogou.com/labs/".toHttpUrl()))
        assertEquals("https://duckduckgo.com/about",publicResultUrl("https://duckduckgo.com/about".toHttpUrl()))
        assertNull(publicResultUrl("https://www.so.com/link?m=opaque".toHttpUrl()))
        assertNull(publicResultUrl("https://www.bing.com/search?q=terms".toHttpUrl()))
    }

}
