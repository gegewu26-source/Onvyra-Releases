package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.providers.LanzouNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.QuarkNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.Pan123NetDiskProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit, read-only integration probes of public samples documented by upstream.
 * Default test runs never contact the Internet. No user credential store is accessed,
 * no transfer is approved, no file body is downloaded, and signed URLs are never logged.
 */
class NetDiskLiveProbeTest {
    @Test fun provided123ShareCanListAndResolveWithoutAnAccount() = runBlocking {
        assumeTrue(System.getenv("ONVYRA_NETDISK_LIVE_PROBE") == "1")
        val input = System.getenv("ONVYRA_NETDISK_123_SHARE").orEmpty()
        assumeTrue(input.isNotBlank())
        val share = NetDiskLinkParser.parse(input)
        assertTrue("The supplied probe must be a 123 share", share.platform == NetDiskPlatform.PAN123)
        val diagnostics = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.encodedPath.endsWith("/share/download/info")) {
                val root = runCatching { Json.parseToJsonElement(response.peekBody(8192).string()).jsonObject }.getOrNull()
                val code = root?.get("code")?.jsonPrimitive?.contentOrNull?.take(24).orEmpty()
                val message = root?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
                    .replace(Regex("https?://\\S+"), "[address]").replace(Regex("[A-Za-z0-9_./:+%=-]{16,}"), "[redacted]").take(120)
                println("NETDISK_LIVE provider=PAN123 api_http=${response.code} business_code=$code service_message=$message")
            }
            response
        }.build()
        probe(Pan123NetDiskProvider(NetDiskHttp(diagnostics)), share)
    }
    @Test fun quarkPublicUpstreamMirrorCanListAndResolveAnActualFile() = runBlocking {
        assumeTrue(System.getenv("ONVYRA_NETDISK_LIVE_PROBE") == "1")
        probe(QuarkNetDiskProvider(NetDiskHttp()), NetDiskShare(NetDiskPlatform.QUARK,
            "https://pan.quark.cn/s/fe29d0d39745", "fe29d0d39745"))
    }

    @Test fun lanzouPublicIssueSampleCanListAndResolveAnActualFile() = runBlocking {
        assumeTrue(System.getenv("ONVYRA_NETDISK_LIVE_PROBE") == "1")
        probe(LanzouNetDiskProvider(NetDiskHttp()), NetDiskShare(NetDiskPlatform.LANZOU,
            "https://wow2333.lanzn.com/iXnuF4c8sqmb", "iXnuF4c8sqmb"))
    }

    private suspend fun probe(provider: NetDiskProvider, share: NetDiskShare) {
        var stage = "open"
        try {
            withTimeout(90_000) {
                val session = provider.open(share, "")
                stage = "list"
                var listing = provider.list(session, credential = "")
                var directoriesOpened = 0
                while (listing.files.none { !it.isDirectory } && directoriesOpened < 3) {
                    val folder = listing.files.firstOrNull { it.isDirectory } ?: break
                    listing = provider.list(session, folder.id, credential = "")
                    directoriesOpened++
                }
                println("NETDISK_LIVE provider=${provider.platform.name} listing_count=${listing.files.size} directories_opened=$directoriesOpened")
                val file = listing.files.filter { !it.isDirectory }.minByOrNull { it.size }
                    ?: throw NetDiskException("no_public_file", "No publicly accessible file in the bounded listing")
                stage = "resolve"
                val link = provider.resolve(session, file, "")
                val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
                    .followRedirects(false).followSslRedirects(false).build()
                try {
                    stage = "head"
                    val request = Request.Builder().url(link.url).head().apply {
                        link.headers.forEach { (name, value) -> header(name, value) }
                    }.build()
                    val status = client.newCall(request).execute().use { it.code }
                    println("NETDISK_LIVE provider=${provider.platform.name} direct_link_returned=true head_status=$status downloaded_bytes=0")
                    assertTrue("${provider.platform.name}: HEAD did not authorize a readable file (HTTP $status)", status in 200..299)
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        } catch (e: NetDiskException) {
            println("NETDISK_LIVE provider=${provider.platform.name} stage=$stage outcome=unverified reason=${e.reason} downloaded_bytes=0")
            if (provider.platform == NetDiskPlatform.PAN123) {
                val message = e.message.replace(Regex("https?://\\S+"), "[address]")
                    .replace(Regex("[A-Za-z0-9_./:+%=-]{16,}"), "[redacted]").take(180)
                println("NETDISK_LIVE provider=PAN123 service_message=$message")
            }
            throw AssertionError("${provider.platform.name}: real provider probe failed (${e.reason}); no successful download claimed")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            println("NETDISK_LIVE provider=${provider.platform.name} stage=$stage outcome=unverified error_type=${e.javaClass.simpleName} downloaded_bytes=0")
            throw AssertionError("${provider.platform.name}: real provider probe did not complete (${e.javaClass.simpleName})")
        }
    }
}
