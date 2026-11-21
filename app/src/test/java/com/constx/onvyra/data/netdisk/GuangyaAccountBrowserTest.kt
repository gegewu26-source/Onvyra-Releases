package com.constx.onvyra.data.netdisk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Personal file contracts reuse the native token/device pair; fixtures do not authenticate real accounts. */
class GuangyaAccountBrowserTest {
    @Test fun nativeCredentialsReadRootAndKeepFolderAndFileWithTheSameIdentifierDistinct() = runBlocking {
        val api = browser { request ->
            assertEquals("POST", request.method)
            assertEquals("api.guangyapan.com", request.url.host)
            assertEquals("/userres/v1/file/get_file_list", request.url.encodedPath)
            assertEquals("Bearer test-access-token-0123456789", request.header("Authorization"))
            assertEquals("native-device-01234567", request.header("did"))
            assertEquals("4", request.header("dt"))
            val body = body(request)
            assertEquals("", body["parentId"]!!.jsonPrimitive.content)
            assertEquals(0, body["page"]!!.jsonPrimitive.int)
            assertEquals(100, body["pageSize"]!!.jsonPrimitive.int)
            200 to """{"code":0,"data":{"total":2,"list":[{"fileId":"same-id","resType":2,"dirName":"目录"},{"fileId":"same-id","resType":1,"fileName":"说明.pdf","fileSize":2048}]}}"""
        }
        val result = api.list(CREDENTIAL)
        assertEquals(listOf("d:same-id", "f:same-id"), result.files.map(NetDiskFile::id))
        assertEquals(2048L, result.files.last().size)
        assertEquals("我的文件", result.title)
        assertNull(result.nextCursor)
    }

    @Test fun folderPaginationStartsAtZeroAndRetainsLastPartialPage() = runBlocking {
        val pages = mutableListOf<Int>()
        val api = browser { request ->
            val body = body(request)
            assertEquals("folder-42", body["parentId"]!!.jsonPrimitive.content)
            val page = body["page"]!!.jsonPrimitive.int; pages += page
            val rows = if (page == 0) (1..100).joinToString(",") { """{"fileId":"file-$it","fileName":"$it.txt","resType":1}""" }
                else """{"fileId":"file-101","fileName":"101.txt","resType":1}"""
            200 to """{"code":0,"data":{"total":101,"items":[$rows]}}"""
        }
        val first = api.list(CREDENTIAL, "d:folder-42")
        assertEquals(100, first.files.size)
        assertNull(first.title)
        failure("invalid_cursor") { api.list(CREDENTIAL, "d:other-folder", first.nextCursor) }
        val last = api.list(CREDENTIAL, "d:folder-42", first.nextCursor)
        assertEquals(listOf(0, 1), pages)
        assertEquals("f:file-101", last.files.single().id)
        assertNull(last.nextCursor)
    }

    @Test fun repeatedPagesStopWithoutLoopingOrFlatteningOtherDirectories() = runBlocking {
        val api = browser { 200 to """{"code":0,"data":{"total":200,"files":[{"fileId":"one","fileName":"one.txt"}]}}""" }
        val first = api.list(CREDENTIAL)
        failure("pagination_loop") { api.list(CREDENTIAL, cursor = first.nextCursor) }
    }

    @Test fun invalidCredentialsDirectoryAndCursorNeverReachNetwork() = runBlocking {
        var calls = 0
        val api = browser { calls++; throw AssertionError("Invalid inputs must not reach network") }
        for (credential in listOf("", "short", "{}", """{"access_token":"test-access-token-0123456789","device_id":"invalid device"}"""))
            failure("auth_required") { api.list(credential) }
        for (directory in listOf("f:one", "../file", "https://bad.invalid/"))
            failure("invalid_directory") { api.list(CREDENTIAL, directory) }
        for (cursor in listOf("", "2", "gy::0:0123456789abcdef", "gy::1000:0123456789abcdef"))
            failure("invalid_cursor") { api.list(CREDENTIAL, cursor = cursor) }
        assertEquals(0, calls)
    }

    @Test fun existingPersonalFileGetsItsUrlWithoutShareCreationOrTransfer() = runBlocking {
        var calls = 0
        val api = browser { request ->
            calls++
            assertEquals("/userres/v1/get_res_download_url", request.url.encodedPath)
            assertEquals(setOf("fileId"), body(request).keys)
            assertEquals("file-42", body(request)["fileId"]!!.jsonPrimitive.content)
            assertEquals("native-device-01234567", request.header("did"))
            200 to """{"code":0,"data":{"signedURL":"https://cdn.example.test/private.bin"}}"""
        }
        val result = api.resolve(NetDiskFile("f:file-42", "private.bin"), CREDENTIAL)
        assertEquals("https://cdn.example.test/private.bin", result.url)
        assertEquals(1, calls)
        assertFalse(result.headers.containsKey("Authorization"))
    }

    @Test fun authFailuresAndMalformedListsAreNotReportedAsAnEmptyAccount() = runBlocking {
        for ((status, text, reason) in listOf(
            Triple(401, "", "auth_required"), Triple(403, "", "auth_required"), Triple(429, "", "rate_limited"),
            Triple(200, """{"error":"unauthenticated"}""", "auth_required"),
            Triple(200, """{"success":false,"data":{"list":[]}}""", "provider_error"),
            Triple(200, """{"code":157}""", "provider_error"),
            Triple(200, """{"code":0,"data":{}}""", "invalid_response"),
            Triple(200, """{"code":0,"data":{"list":[{}]}}""", "invalid_response"),
            Triple(200, """{"error":"captcha_required"}""", "verification_required"),
        )) failure(reason) { browser { status to text }.list(CREDENTIAL) }
        val empty = browser { 200 to """{"code":0,"data":{"list":[],"total":0}}""" }.list(CREDENTIAL)
        assertTrue(empty.files.isEmpty())
        assertNull(empty.nextCursor)
    }

    @Test fun invalidDownloadRequestsAndAbsentLinksStayVisible() = runBlocking {
        var calls = 0
        val api = browser { calls++; 200 to """{"code":0,"data":{}}""" }
        failure("is_directory") { api.resolve(NetDiskFile("d:one", "目录", true), CREDENTIAL) }
        failure("invalid_file") { api.resolve(NetDiskFile("d:one", "错误类型"), CREDENTIAL) }
        assertEquals(0, calls)
        failure("download_unavailable") { api.resolve(NetDiskFile("f:one", "一.txt"), CREDENTIAL) }
        assertEquals(1, calls)
    }

    private fun body(request: Request): JsonObject = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun browser(answer: (Request) -> Pair<Int, String>): GuangyaAccountBrowser = GuangyaAccountBrowser(NetDiskHttp(
        OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()))
    private suspend fun failure(reason: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (error: NetDiskException) { assertEquals(reason, error.reason) }
    }
    private companion object { const val CREDENTIAL = """{"access_token":"test-access-token-0123456789","device_id":"native-device-01234567"}""" }
}
