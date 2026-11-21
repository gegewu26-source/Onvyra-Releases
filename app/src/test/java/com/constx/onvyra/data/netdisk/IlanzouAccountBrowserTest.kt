package com.constx.onvyra.data.netdisk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Read-only personal API fixtures; no real account, login or cloud mutation. */
class IlanzouAccountBrowserTest {
    @Test fun nativeTokenAndDeviceReadPersonalRootWithTypedFoldersAndKilobyteSizes() = runBlocking {
        val api = browser { request ->
            assertEquals("GET", request.method)
            assertEquals("apis.ilanzou.com", request.url.host)
            assertEquals("/proved/record/file/list", request.url.encodedPath)
            assertEquals("test-app-token-0123456789", request.url.queryParameter("appToken"))
            assertEquals("device_fixture123", request.url.queryParameter("uuid"))
            assertEquals("device_fixture123", request.url.queryParameter("devCode"))
            assertEquals("0", request.url.queryParameter("folderId"))
            assertEquals("1", request.url.queryParameter("offset"))
            assertEquals("60", request.url.queryParameter("limit"))
            assertEquals("0", request.url.queryParameter("type"))
            assertEquals("2", request.url.queryParameter("extra"))
            assertTrue(request.url.queryParameter("timestamp")!!.matches(Regex("[0-9a-f]+")))
            reply("""{"code":200,"totalPage":1,"list":[{"fileType":2,"folderId":42,"folderName":"资料"},{"fileType":1,"fileId":42,"fileName":"说明.txt","fileSize":1.5,"userId":23}]}""")
        }
        val result = api.list(CREDENTIAL)
        assertEquals(listOf("d:42", "f:42"), result.files.map(NetDiskFile::id))
        assertEquals("我的文件", result.title)
        assertEquals(1536L, result.files.last().size)
        assertEquals("23", result.files.last().extra["userId"]?.jsonPrimitive?.content)
        assertNull(result.nextCursor)
    }

    @Test fun folderPagesStayScopedAndTheLastPageStopsAtServerTotal() = runBlocking {
        val offsets = mutableListOf<String?>()
        val api = browser { request ->
            assertEquals("42", request.url.queryParameter("folderId"))
            val offset = request.url.queryParameter("offset"); offsets += offset
            reply("""{"code":200,"totalPage":2,"list":[{"fileType":1,"fileId":${if (offset == "1") 71 else 72},"fileName":"文件$offset.txt","fileSize":1}]}""")
        }
        val first = api.list(CREDENTIAL, "d:42")
        assertNull(first.title)
        failure("invalid_cursor") { api.list(CREDENTIAL, "d:43", first.nextCursor) }
        val second = api.list(CREDENTIAL, "d:42", first.nextCursor)
        assertEquals(listOf("1", "2"), offsets)
        assertEquals("f:72", second.files.single().id)
        assertNull(second.nextCursor)
    }

    @Test fun repeatedPagesAreReportedInsteadOfLooping() = runBlocking {
        val api = browser { reply("""{"code":200,"totalPage":10,"list":[{"fileId":71,"fileName":"一.txt"}]}""") }
        val first = api.list(CREDENTIAL)
        failure("pagination_loop") { api.list(CREDENTIAL, cursor = first.nextCursor) }
    }

    @Test fun invalidCredentialsDirectoryAndCursorFailBeforeNetwork() = runBlocking {
        var calls = 0
        val api = browser { calls++; throw AssertionError("Invalid input must not reach the network") }
        for (credential in listOf("", "token-only", "{}", """{"appToken":"short","uuid":"device_fixture123"}""",
            """{"appToken":"test-app-token-0123456789","uuid":"https://evil.invalid"}"""))
            failure("auth_required") { api.list(credential) }
        for (directory in listOf("f:1", "../2", "-1", "d:https://example.invalid/"))
            failure("invalid_directory") { api.list(CREDENTIAL, directory) }
        for (cursor in listOf("", "2", "iz:0:1:0123456789abcdef", "iz:0:1001:0123456789abcdef"))
            failure("invalid_cursor") { api.list(CREDENTIAL, cursor = cursor) }
        assertEquals(0, calls)
    }

    @Test fun failedAndMalformedListingsNeverBecomeEmptySuccessfulDirectories() = runBlocking {
        for ((status, body, reason) in listOf(
            Triple(401, "", "auth_required"), Triple(403, "", "auth_required"), Triple(429, "", "rate_limited"),
            Triple(200, """{"code":-1}""", "auth_required"), Triple(200, """{"code":500,"list":[]}""", "provider_error"),
            Triple(200, """{"code":200}""", "invalid_response"), Triple(200, """{"code":200,"list":[{}]}""", "invalid_response"),
            Triple(200, "<html><input name='password'></html>", "verification_required"),
        )) {
            val api = browser { Reply(status, body) }
            failure(reason) { api.list(CREDENTIAL) }
        }
        assertTrue(browser { reply("""{"code":200,"list":[]}""") }.list(CREDENTIAL).files.isEmpty())
    }

    @Test fun existingPersonalFileResolvesThroughAccountMetadataWithoutCreatingShares() = runBlocking {
        val paths = mutableListOf<String>()
        val api = browser { request ->
            paths += request.url.encodedPath
            assertEquals("GET", request.method)
            assertEquals("test-app-token-0123456789", request.url.queryParameter("appToken"))
            when (request.url.encodedPath) {
                "/proved/user/account/map" -> reply("""{"code":200,"map":{"userId":23}}""")
                "/unproved/file/redirect" -> {
                    assertNull(request.url.queryParameter("extra"))
                    assertNull(request.url.queryParameter("shareId"))
                    assertNotNull(request.url.queryParameter("downloadId"))
                    assertNotNull(request.url.queryParameter("auth"))
                    Reply(302, "", mapOf("Location" to "https://cdn.example.test/real.bin"))
                }
                else -> throw AssertionError("Unexpected endpoint; no share/transfer is authorized")
            }
        }
        val result = api.resolve(NetDiskFile("f:71", "real.bin"), CREDENTIAL)
        assertEquals("https://cdn.example.test/real.bin", result.url)
        assertEquals(listOf("/proved/user/account/map", "/unproved/file/redirect"), paths)
        assertFalse(result.headers.containsKey("Authorization"))
    }

    @Test fun folderDownloadsAndMissingAccountMetadataStayVisible() = runBlocking {
        var calls = 0
        val api = browser { calls++; reply("""{"code":200,"map":{}}""") }
        failure("is_directory") { api.resolve(NetDiskFile("d:1", "目录", true), CREDENTIAL) }
        failure("invalid_file") { api.resolve(NetDiskFile("d:1", "错误类型"), CREDENTIAL) }
        assertEquals(0, calls)
        failure("invalid_response") { api.resolve(NetDiskFile("f:1", "文件"), CREDENTIAL) }
        assertEquals(1, calls)
    }

    private data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    private fun reply(body: String) = Reply(200, body)
    private fun browser(answer: (Request) -> Reply): IlanzouAccountBrowser = IlanzouAccountBrowser(NetDiskHttp(
        OkHttpClient.Builder().addInterceptor { chain ->
            val reply = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply.status).message("fixture")
                .apply { reply.headers.forEach { (key, value) -> header(key, value) } }.body(reply.body.toResponseBody()).build()
        }.build()))
    private suspend fun failure(reason: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (error: NetDiskException) { assertEquals(reason, error.reason) }
    }
    private companion object { const val CREDENTIAL = """{"appToken":"test-app-token-0123456789","uuid":"device_fixture123"}""" }
}
