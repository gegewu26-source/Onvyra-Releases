package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** A definite rejected copy may be retried; an ambiguous lost response must not be replayed. */
class NetDiskTransferRetryTest {
    @Test fun xunleiRateLimitedRestoreCanRetryInTheSameNewFolder() = runBlocking {
        var creates = 0
        var restores = 0
        val provider = XunleiNetDiskProvider(NetDiskHttp(client { request ->
            when (request.url.encodedPath) {
                "/drive/v1/files" -> if (request.method == "POST") {
                    creates++; Reply("""{"id":"retained-folder"}""")
                } else Reply("""{"files":[]}""")
                "/drive/v1/share/restore" -> {
                    restores++
                    if (restores == 1) Reply("""{"error":"rate_limited","error_description":"try later"}""", 429)
                    else Reply("""{"params":{"trace_file_ids":"{\"original\":\"saved\"}"}}""")
                }
                "/drive/v1/files/saved" -> Reply("""{"web_content_link":"https://dl.xunlei.com/file"}""")
                else -> Reply("""{"error":"not_found"}""", 404)
            }
        }))
        val session = approved(NetDiskPlatform.XUNLEI)
        val file = NetDiskFile("original", "file.txt", size = 3)
        try { provider.resolve(session, file, "account-token"); fail("Expected definite rate limit") }
        catch (e: NetDiskException) { assertEquals("rate_limited", e.reason) }
        val retried = withTimeout(1_000) { provider.resolve(session, file, "account-token") }
        assertEquals("https://dl.xunlei.com/file", retried.url)
        assertEquals("Retry must reuse the directory already created", 1, creates)
        assertEquals("A rejected request must be submitted again", 2, restores)
    }

    @Test fun guangyaRateLimitedRestoreCanRetryInTheSameNewFolder() = runBlocking {
        var creates = 0
        var restores = 0
        val provider = GuangyaNetDiskProvider(NetDiskHttp(client { request ->
            when (request.url.encodedPath.substringAfter("/userres/v1/")) {
                "get_share_download_url" -> Reply("""{"code":207,"msg":"requires transfer"}""")
                "file/create_dir" -> { creates++; Reply("""{"code":0,"data":{"fileId":"retained-folder"}}""") }
                "restore_share" -> {
                    restores++
                    if (restores == 1) Reply("""{"code":429,"msg":"try later"}""", 429)
                    else Reply("""{"code":0,"data":{}}""")
                }
                "file/get_file_list" -> if (restores > 1)
                    Reply("""{"code":0,"data":{"list":[{"fileId":"saved","fileName":"file.txt","fileSize":3,"resType":1}]}}""")
                    else Reply("""{"code":0,"data":{"list":[]}}""")
                "get_res_download_url" -> Reply("""{"code":0,"data":{"signedURL":"https://cdn.guangyapan.com/file"}}""")
                else -> Reply("""{"code":404,"msg":"unexpected request"}""", 404)
            }
        }))
        val session = approved(NetDiskPlatform.GUANGYA)
        val file = NetDiskFile("original", "file.txt", size = 3)
        try { provider.resolve(session, file, "account-token"); fail("Expected definite rate limit") }
        catch (e: NetDiskException) { assertEquals("rate_limited", e.reason) }
        val retried = withTimeout(1_000) { provider.resolve(session, file, "account-token") }
        assertEquals("https://cdn.guangyapan.com/file", retried.url)
        assertEquals("Retry must reuse the directory already created", 1, creates)
        assertEquals("A rejected request must be submitted again", 2, restores)
    }

    @Test fun xunleiAmbiguousServerFailureChecksTheRetainedFolderWithoutCopyingAgain() = runBlocking {
        var creates = 0
        var restores = 0
        val provider = XunleiNetDiskProvider(NetDiskHttp(client { request ->
            when (request.url.encodedPath) {
                "/drive/v1/files" -> if (request.method == "POST") {
                    creates++; Reply("""{"id":"retained-folder"}""")
                } else Reply("""{"files":[{"id":"saved","name":"file.txt","size":3,"kind":"drive#file"}]}""")
                "/drive/v1/share/restore" -> { restores++; Reply("""{"error":"server_error"}""", 503) }
                "/drive/v1/files/saved" -> Reply("""{"web_content_link":"https://dl.xunlei.com/file"}""")
                else -> Reply("""{"error":"not_found"}""", 404)
            }
        }))
        val session = approved(NetDiskPlatform.XUNLEI)
        val file = NetDiskFile("original", "file.txt", size = 3)
        try { provider.resolve(session, file, "account-token"); fail("Expected ambiguous server failure") }
        catch (e: NetDiskException) { assertEquals("provider_error", e.reason) }
        assertEquals("https://dl.xunlei.com/file", withTimeout(1_000) { provider.resolve(session, file, "account-token") }.url)
        assertEquals(1, creates); assertEquals("An ambiguous copy must not be replayed", 1, restores)
    }

    @Test fun guangyaAmbiguousServerFailureChecksTheRetainedFolderWithoutCopyingAgain() = runBlocking {
        var creates = 0
        var restores = 0
        val provider = GuangyaNetDiskProvider(NetDiskHttp(client { request ->
            when (request.url.encodedPath.substringAfter("/userres/v1/")) {
                "get_share_download_url" -> Reply("""{"code":207}""")
                "file/create_dir" -> { creates++; Reply("""{"code":0,"data":{"fileId":"retained-folder"}}""") }
                "restore_share" -> { restores++; Reply("""{"code":500,"msg":"server error"}""", 503) }
                "file/get_file_list" -> Reply("""{"code":0,"data":{"list":[{"fileId":"saved","fileName":"file.txt","fileSize":3,"resType":1}]}}""")
                "get_res_download_url" -> Reply("""{"code":0,"data":{"signedURL":"https://cdn.guangyapan.com/file"}}""")
                else -> Reply("""{"code":404}""", 404)
            }
        }))
        val session = approved(NetDiskPlatform.GUANGYA)
        val file = NetDiskFile("original", "file.txt", size = 3)
        try { provider.resolve(session, file, "account-token"); fail("Expected ambiguous server failure") }
        catch (e: NetDiskException) { assertEquals("provider_error", e.reason) }
        assertEquals("https://cdn.guangyapan.com/file", withTimeout(1_000) { provider.resolve(session, file, "account-token") }.url)
        assertEquals(1, creates); assertEquals("An ambiguous copy must not be replayed", 1, restores)
    }

    @Test fun emptyXunleiCredentialJsonDoesNotBecomeAnAccountToken() {
        assertEquals("", XunleiProtocol.credential("{}", "device").access)
        assertEquals("", XunleiProtocol.credential("""{"device_id":"device"}""", "fallback").access)
    }

    private fun approved(platform: NetDiskPlatform) = NetDiskSession(
        NetDiskShare(platform, "https://example.invalid/s/share", "share"), token = "share-token",
        extra = buildJsonObject { put("allow_transfer", true) })
    private data class Reply(val body: String, val status: Int = 200)
    private fun client(answer: (Request) -> Reply) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val reply = answer(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status).message("fixture")
            .body(reply.body.toResponseBody("application/json".toMediaType())).build()
    }.build()
}
