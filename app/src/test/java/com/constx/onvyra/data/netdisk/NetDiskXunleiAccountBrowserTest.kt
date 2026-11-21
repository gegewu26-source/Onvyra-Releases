package com.constx.onvyra.data.netdisk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetDiskXunleiAccountBrowserTest {
    private val device = "0123456789abcdef0123456789abcdef"
    private val access = "fixture-account-access-token-1234567890"
    private fun credential(token: String = access, client: String = "Xp6vsxz_7IYVw2BB", captcha: Boolean = true) = buildJsonObject {
        put("access_token", token); put("client_id", client); put("device_id", device); put("user_id", "fixture-user")
        if (captcha) put("captcha_token", "fixture-imported-captcha")
    }.toString()
    private fun http(reply: (Request) -> Pair<Int, String>) = NetDiskHttp(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("https", request.url.scheme)
        assertTrue(request.url.host in setOf("api-pan.xunlei.com", "xluser-ssl.xunlei.com"))
        assertTrue(request.method == "GET" || request.method == "POST" && request.url.encodedPath == "/v1/shield/captcha/init")
        val (status, body) = reply(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody()).build()
    }.build())
    private suspend fun expect(reason: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: NetDiskException) { assertEquals(reason, error.reason) }
    }
    private fun body(request: Request) = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    @Test fun appRealmListsRootAndNextPageWithScopedCursorAndTypedFiles() = runBlocking {
        var calls = 0
        val browser = XunleiAccountBrowser(http { request ->
            calls++
            assertEquals("GET", request.method)
            assertEquals("/drive/v1/files", request.url.encodedPath)
            assertEquals("Bearer $access", request.header("Authorization"))
            assertEquals(device, request.header("X-Device-Id"))
            assertEquals("8.31.0.9726", request.header("X-Client-Version"))
            assertEquals("", request.url.queryParameter("parent_id"))
            assertEquals("100", request.url.queryParameter("limit"))
            assertEquals("""{"trashed":{"eq":false}}""", request.url.queryParameter("filters"))
            if (request.url.queryParameter("page_token").isNullOrEmpty()) 200 to """{"files":[{"id":"folder-one","name":"资料","kind":"drive#folder"},{"id":"file-one","name":"文件2.txt","size":"123","mime_type":"text/plain"}],"next_page_token":"opaque+cursor/one="}"""
            else {
                assertEquals("opaque+cursor/one=", request.url.queryParameter("page_token"))
                200 to """{"files":[{"id":"file-two","name":"文件10.txt","size":"234"}],"next_page_token":""}"""
            }
        })
        val first = browser.list(credential())
        assertEquals("我的文件", first.title)
        assertEquals(listOf("folder-one", "file-one"), first.files.map { it.id })
        assertTrue(first.files.first().isDirectory)
        assertEquals("text/plain", first.files.last().mimeType)
        assertEquals(123L, first.files.last().size)
        val next = first.nextCursor!!
        expect("invalid_cursor") { browser.list(credential(), "folder-one", next) }
        expect("invalid_cursor") { browser.list(credential(token = "fixture-other-account-token-1234567890"), "", next) }
        assertEquals(1, calls)
        val second = browser.list(credential(), "", next)
        assertEquals("file-two", second.files.single().id)
        assertNull(second.nextCursor)
        assertEquals(2, calls)
    }

    @Test fun webRealmCaptchaUsesCorrectClientUserDeviceAndAction() = runBlocking {
        var inits = 0
        var reads = 0
        val browser = XunleiAccountBrowser(http { request ->
            if (request.method == "POST") {
                inits++
                val json = body(request)
                assertEquals("Xqp0kJBXWhwaTpB6", json["client_id"]!!.jsonPrimitive.content)
                assertEquals(device, json["device_id"]!!.jsonPrimitive.content)
                assertEquals("GET:/drive/v1/files", json["action"]!!.jsonPrimitive.content)
                val meta = json["meta"]!!.jsonObject
                assertEquals("fixture-user", meta["user_id"]!!.jsonPrimitive.content)
                assertEquals("pan.xunlei.com", meta["package_name"]!!.jsonPrimitive.content)
                assertEquals("1.93.10", meta["client_version"]!!.jsonPrimitive.content)
                assertFalse(json.containsKey("redirect_uri"))
                200 to """{"captcha_token":"fixture-live-action-token","expires_in":300}"""
            } else {
                reads++
                assertEquals("1.93.10", request.header("X-Client-Version"))
                assertEquals("fixture-live-action-token", request.header("X-Captcha-Token"))
                200 to """{"files":[],"next_page_token":""}"""
            }
        })
        val saved = credential(client = "Xqp0kJBXWhwaTpB6", captcha = false)
        assertTrue(browser.list(saved).files.isEmpty())
        assertTrue(browser.list(saved).files.isEmpty())
        assertEquals(1, inits)
        assertEquals(2, reads)
    }

    @Test fun explicitCaptchaRejectionRefreshesOnlyTheRejectedReadOnce() = runBlocking {
        var reads = 0
        var inits = 0
        val browser = XunleiAccountBrowser(http { request ->
            if (request.method == "POST") {
                inits++
                assertEquals("Xp6vsxz_7IYVw2BB", body(request)["client_id"]!!.jsonPrimitive.content)
                assertEquals("fixture-user", body(request)["meta"]!!.jsonObject["user_id"]!!.jsonPrimitive.content)
                200 to """{"captcha_token":"fixture-refreshed-token","expires_in":300}"""
            } else {
                reads++
                if (reads == 1) 403 to """{"error":"captcha_invalid"}"""
                else {
                    assertEquals("fixture-refreshed-token", request.header("X-Captcha-Token"))
                    200 to """{"files":[],"next_page_token":""}"""
                }
            }
        })
        assertTrue(browser.list(credential()).files.isEmpty())
        assertEquals(2, reads)
        assertEquals(1, inits)
    }

    @Test fun humanCaptchaAndExpiredAccountNeverBecomeAnEmptySuccessfulListing() = runBlocking {
        var requests = 0
        val challenged = XunleiAccountBrowser(http { request ->
            requests++
            assertEquals("POST", request.method)
            200 to """{"captcha_token":"fixture-token","url":"https://xluser-ssl.xunlei.com/verify"}"""
        })
        expect("verification_required") { challenged.list(credential(captcha = false)) }
        assertEquals(1, requests)
        val expired = XunleiAccountBrowser(http { 401 to "{}" })
        expect("auth_required") { expired.list(credential()) }
        val malformed = XunleiAccountBrowser(http { 200 to "{}" })
        expect("invalid_response") { malformed.list(credential()) }
    }

    @Test fun repeatedPageWithChangingServerTokenIsRejected() = runBlocking {
        var calls = 0
        val browser = XunleiAccountBrowser(http {
            calls++
            200 to """{"files":[{"id":"file-one","name":"文件.txt"}],"next_page_token":"page-$calls"}"""
        })
        val first = browser.list(credential())
        expect("pagination_loop") { browser.list(credential(), "", first.nextCursor) }
    }

    @Test fun selectedFileDownloadReadsItsDetailAndDoesNotForwardAccountHeadersToCdn() = runBlocking {
        var calls = 0
        val browser = XunleiAccountBrowser(http { request ->
            calls++
            assertEquals("GET", request.method)
            assertEquals("/drive/v1/files/file-one", request.url.encodedPath)
            assertEquals("Bearer $access", request.header("Authorization"))
            assertEquals("PLAY", request.url.queryParameter("usage"))
            200 to """{"id":"file-one","links":{"application/octet-stream":{"url":"https://cdn.example.test/file.bin?signature=fixture"}}}"""
        })
        val download = browser.resolve(NetDiskFile("file-one", "文件.bin", size = 100), credential())
        assertEquals("https://cdn.example.test/file.bin?signature=fixture", download.url)
        assertEquals("文件.bin", download.fileName)
        assertFalse(download.headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) || it.equals("X-Captcha-Token", true) })
        expect("is_directory") { browser.resolve(NetDiskFile("folder-one", "文件夹", true), credential()) }
        assertEquals(1, calls)
    }

    @Test fun missingCredentialsAndBadIdsStopBeforeNetwork() = runBlocking {
        var calls = 0
        val browser = XunleiAccountBrowser(http { calls++; 200 to "{}" })
        expect("auth_required") { browser.list("") }
        expect("invalid_directory") { browser.list(credential(), "../invalid") }
        assertEquals(0, calls)
    }
}
