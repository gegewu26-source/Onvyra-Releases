package me.rerere.search

import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** Optional, explicit live CLI probe. Not a JUnit test and never part of the APK. No model/API keys. */
object PublicWebLiveProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val proxy=args.firstOrNull {it.startsWith("--proxy=") }?.removePrefix("--proxy=")
        if(proxy!=null) {
            val parts=proxy.split(':');require(parts.size==2)
            val port=parts[1].toInt();require(port in 1..65535)
            // Explicit probe transport only; production keeps the app's configured client.
            SearchService.httpClient=OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP,InetSocketAddress(parts[0],port))).build()
        }
        val queries=args.filterNot {it.startsWith("--proxy=") }.ifEmpty {
            listOf("Android AccessibilityService getWindowsOnAllDisplays","北京 天气")
        }
        try { for(query in queries) {
            var found: SearchResult?=null
            val elapsed=measureTimeMillis { found=BingSearchService.search(buildJsonObject {put("query",query)},
                SearchCommonOptions(8),SearchServiceOptions.BingLocalOptions()).getOrThrow() }
            val items=requireNotNull(found).items
            check(items.isNotEmpty())
            check(items.size<=8 && items.map {it.url}.distinct().size==items.size)
            println(buildJsonObject {
                put("query",query);put("elapsed_ms",elapsed);put("count",items.size)
                put("items",buildJsonArray {items.take(5).forEach {item->add(buildJsonObject {
                    put("title",item.title);put("url",item.url)
                })}})
            }.toString())
        } } finally {
            SearchService.httpClient.dispatcher.executorService.shutdown()
            SearchService.httpClient.connectionPool.evictAll()
        }
    }
}
