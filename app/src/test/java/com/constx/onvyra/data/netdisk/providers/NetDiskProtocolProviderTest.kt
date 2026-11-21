package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetDiskProtocolProviderTest {
    private class FakeService(val answer: (Request, Int) -> Pair<String, Map<String, String>>) {
        val requests = CopyOnWriteArrayList<Request>()
        val http = NetDiskHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (body, headers) = answer(request, requests.size)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .headers(Headers.Builder().apply { headers.forEach { (key, value) -> add(key, value) } }.build())
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
    }
    private fun body(request: Request): JsonObject = Json.parseToJsonElement(Buffer().also {
        request.body!!.writeTo(it)
    }.readUtf8()).jsonObject

    @Test fun quarkAnonymousShareListsAndResolvesWithoutCopyOrDelete() = runBlocking {
        val service = FakeService { request, _ -> when (request.url.encodedPath) {
            "/1/clouddrive/share/sharepage/token" -> """{"status":200,"code":0,"data":{"stoken":"share-token","title":"Fixture"}}""" to emptyMap()
            "/1/clouddrive/share/sharepage/detail" -> """{"status":200,"code":0,"data":{"list":[{"fid":"file1","file_name":"test.bin","size":0,"dir":false,"share_fid_token":"file-token"}]}}""" to emptyMap()
            "/1/clouddrive/file/download" -> """{"status":200,"code":0,"data":[{"fid":"file1","file_name":"test.bin","download_url":"https://dl-guest-a.quark.cn/file"}]}""" to mapOf("Set-Cookie" to "__pugs=guest-token; Path=/; Secure")
            else -> error("Unexpected endpoint ${request.url.encodedPath}")
        } }
        val provider = QuarkNetDiskProvider(service.http)
        val session = provider.open(NetDiskShare(NetDiskPlatform.QUARK, "https://pan.quark.cn/s/abc", "abc", "p4ss"), "")
        val file = provider.list(session).files.single()
        val link = provider.resolve(session, file, "")
        assertEquals(0L, file.size)
        assertEquals("__pugs=guest-token", link.headers["Cookie"])
        assertEquals("https://pan.quark.cn/", link.headers["Referer"])
        assertEquals("p4ss", body(service.requests.first()).ndText("passcode"))
        assertEquals("file-token", body(service.requests.last())["fids_token"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(service.requests.none { it.url.encodedPath.endsWith("/save") || it.url.encodedPath.endsWith("/delete") })
        assertTrue(service.requests.all { it.header("Cookie").isNullOrEmpty() })
    }

    @Test fun ucUsesStokenBoundTransferDetailAndDoesNotLeakAccountCookieToForeignCdn() = runBlocking {
        val service = FakeService { request, _ -> when {
            request.url.encodedPath.endsWith("/token") -> """{"status":200,"code":0,"data":{"stoken":"uc/token+value"}}""" to emptyMap()
            request.url.encodedPath.endsWith("/transfer_share/detail") -> """{"status":200,"code":0,"data":{"detail_info":{"list":[{"fid":"uc-file","file_name":"image.png","share_fid_token":"uc-fid-token"}]}}}""" to emptyMap()
            request.url.encodedPath.endsWith("/download") -> """{"status":200,"code":0,"data":[{"download_url":"https://cdn.example.org/file"}]}""" to emptyMap()
            else -> error("Unexpected endpoint")
        } }
        val provider = UcNetDiskProvider(service.http)
        val cookie = "__pus=account; __puus=sensitive; unrelated=secret"
        val session = provider.open(NetDiskShare(NetDiskPlatform.UC, "https://drive.uc.cn/s/abc", "abc"), cookie)
        val file = provider.list(session, "folder-id", null, cookie).files.single()
        val link = provider.resolve(session, file, cookie)
        val listRequest = service.requests[1]
        assertEquals("GET", listRequest.method)
        assertEquals("uc/token+value", listRequest.url.queryParameter("stoken"))
        assertEquals("folder-id", listRequest.url.queryParameter("pdir_fid"))
        assertEquals("ft", listRequest.url.queryParameter("entry"))
        assertNull(link.headers["Cookie"])
        assertEquals("https://drive.uc.cn/", link.headers["Referer"])
        assertTrue(service.requests.none { it.url.encodedPath.contains("/save") || it.url.encodedPath.contains("/copy") })
    }

    @Test fun pan123UsesCurrentAnonymousListingAndPreservesZeroSizedFileInSignedDownload() = runBlocking {
        val direct = "https://cdn.example.org/file?auto_redirect=0&value=1"
        val encoded = Base64.getEncoder().encodeToString(direct.toByteArray())
        val service = FakeService { request, _ -> when (request.url.encodedPath) {
            "/b/api/share/get" -> """{"code":0,"data":{"InfoList":[{"FileId":42,"FileName":"empty.txt","Type":0,"Size":0,"S3KeyFlag":"s3","Etag":"hash"}],"Next":"-1"}}""" to emptyMap()
            "/b/api/share/download/info" -> """{"code":0,"data":{"DownloadURL":"https://www.123865.com/download-v2?params=$encoded"}}""" to emptyMap()
            else -> error("Unexpected endpoint")
        } }
        val provider = Pan123NetDiskProvider(service.http)
        val session = provider.open(NetDiskShare(NetDiskPlatform.PAN123, "https://www.123pan.cn/s/abc-key", "abc-key"), "")
        val file = provider.list(session).files.single()
        assertEquals("yun.123pan.cn", service.requests.first().url.host)
        assertNull(service.requests.first().url.queryParameter("SharePwd"))
        assertEquals(1, service.requests.size)
        val link = provider.resolve(session, file, "Bearer fixture-token")
        val request = service.requests.last()
        assertEquals("www.123865.com", request.url.host)
        assertEquals("Bearer fixture-token", request.header("Authorization"))
        assertFalse(request.header("auth-key").isNullOrBlank())
        assertFalse(request.header("auth-value").isNullOrBlank())
        assertEquals(0L, body(request)["Size"]!!.jsonPrimitive.long)
        assertEquals("s3", body(request).ndText("S3KeyFlag"))
        val directUrl = link.url.toHttpUrl()
        assertEquals("cdn.example.org", directUrl.host)
        assertEquals("/file", directUrl.encodedPath)
        assertEquals("1", directUrl.queryParameter("auto_redirect"))
        assertEquals("1", directUrl.queryParameter("value"))
        assertNull(link.headers["Authorization"])
    }

    @Test fun pan123SignatureUsesTheDocumentedClockOffsetAndRawPath() {
        assertEquals("f2ac3dd" to "1700000000-1234567-ecfbd1b2",
            pan123Signature("/b/api/share/download/info", 1_700_000_000L, 1_234_567))
        assertThrows(IllegalArgumentException::class.java) { pan123Signature("/path?query=1", 1, 1) }
    }

    @Test fun pan123PaginationKeepsEmptyButValidContinuationCursor() {
        val data = Json.parseToJsonElement("""{"InfoList":[{"FileId":1,"FileName":"dir","Type":1}],"Next":""}""").jsonObject
        val listing = pan123Listing(data, 1)
        assertEquals("2:", listing.nextCursor)
        assertTrue(listing.files.single().isDirectory)
        assertEquals(null, pan123Listing(JsonObject(data + ("Next" to JsonPrimitive("-1"))), 2).nextCursor)
    }

    @Test fun aliSingleFileShareIsListableWithoutAnAccountDriveOrTransfer() = runBlocking {
        val service = FakeService { request, _ -> when (request.url.encodedPath) {
            "/v2/share_link/get_share_token" -> """{"share_token":"ali-share-token"}""" to emptyMap()
            "/adrive/v3/share_link/get_share_by_anonymous" -> """{"share_name":"Single file","file_infos":[{"file_id":"ali-file","name":"book.epub","type":"file","size":100}]}""" to emptyMap()
            "/v2/file/get_share_link_download_url" -> """{"download_url":"https://cdn.example.org/book.epub"}""" to emptyMap()
            else -> error("Unexpected endpoint")
        } }
        val provider = AliyunNetDiskProvider(service.http)
        val session = provider.open(NetDiskShare(NetDiskPlatform.ALIYUN, "https://www.alipan.com/s/test", "test"), "")
        val file = provider.list(session).files.single()
        assertEquals("book.epub", file.name)
        val link = provider.resolve(session, file, "")
        assertEquals(3, service.requests.size)
        assertEquals("https://cdn.example.org/book.epub", link.url)
        assertTrue(service.requests.none { it.url.encodedPath.contains("/copy") || it.url.encodedPath.contains("/user/get") })
        assertTrue(service.requests.all { it.header("Authorization") == null })
        assertEquals("ali-share-token", service.requests.last().header("X-Share-Token"))
    }

    @Test fun providerBusinessFailureCannotBecomeAnEmptySuccessfulListing() = runBlocking {
        val service = FakeService { _, _ -> """{"status":404,"code":41006,"message":"分享不存在"}""" to emptyMap() }
        try {
            QuarkNetDiskProvider(service.http).open(NetDiskShare(NetDiskPlatform.QUARK, "https://pan.quark.cn/s/no", "no"), "")
            fail("Invalid shares must fail")
        } catch (e: NetDiskException) {
            assertEquals("share_expired", e.reason)
            assertTrue(e.message.contains("分享不存在"))
        }
        assertEquals(1, service.requests.size)
    }

    @Test fun accountRequirementAndMissingTokenAreExplicitFailures() {
        val response = NetDiskResponse(200, """{"status":400,"code":31001,"message":"请登录"}""", Headers.headersOf(), "https://drive-pc.quark.cn/")
        val error = assertThrows(NetDiskException::class.java) { ndCloudResult(response, "取链失败") }
        assertEquals("auth_required", error.reason)
        assertThrows(NetDiskException::class.java) { pan123DecodeDownloadUrl("not a URL") }
        assertThrows(NetDiskException::class.java) { ndPage("0") }
        assertThrows(NetDiskException::class.java) { aliyunFile(JsonObject(emptyMap())) }
    }
}
