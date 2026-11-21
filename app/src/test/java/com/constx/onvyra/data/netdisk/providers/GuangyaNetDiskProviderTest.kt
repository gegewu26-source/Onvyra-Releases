package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GuangyaNetDiskProviderTest {
    private val share = NetDiskShare(NetDiskPlatform.GUANGYA, "https://www.guangyapan.com/s/share-id", "share-id", "ab12")

    @Test fun anonymousShareUsesTheShareTokenAndCursorWithoutLeakingTheSavedAccount() = runBlocking {
        var lists = 0
        val provider = GuangyaNetDiskProvider(NetDiskHttp(client { request ->
            assertNull(request.header("Authorization")); assertEquals("4", request.header("dt"))
            assertTrue(request.header("did").orEmpty().isNotBlank())
            when (request.url.encodedPath.substringAfter("/userres/v1/")) {
                "get_share_access_token" -> {
                    assertEquals("ab12", body(request)["code"]!!.jsonPrimitive.content)
                    response("""{"accessToken":"share-token"}""")
                }
                "get_share_summary" -> response("""{"title":"分享标题"}""")
                "get_share_page_files_list" -> {
                    lists++
                    assertEquals("share-token", body(request)["accessToken"]!!.jsonPrimitive.content)
                    if (lists == 1) response("""{"list":[{"fileId":"f1","fileName":"a.txt","resType":"1","fileSize":"3"}],"cursor":"100","hasMore":true}""")
                    else { assertEquals(100L, body(request)["cursor"]!!.jsonPrimitive.long); response("""{"list":[],"cursor":"100","hasMore":false}""") }
                }
                "get_share_download_url" -> response("""{"signedURL":"https://cdn.guangyapan.com/a.txt?sign=fixture"}""")
                else -> throw AssertionError("Unexpected operation")
            }
        }))
        val session = provider.open(share, "private-account-token")
        assertEquals("分享标题", session.title)
        val first = provider.list(session, "", null, "private-account-token")
        assertEquals("100", first.nextCursor); assertEquals(3L, first.files.single().size)
        assertNull(provider.list(session, "", first.nextCursor, "private-account-token").nextCursor)
        val link = provider.resolve(session, first.files.single(), "private-account-token")
        assertEquals("https://cdn.guangyapan.com/a.txt?sign=fixture", link.url)
        assertFalse(link.headers.containsKey("Authorization"))
    }

    @Test fun restrictedDownloadRestoresOnlyAfterConsentAndReusesItsNewRetainedFolder() = runBlocking {
        var creates = 0; var restores = 0; var personalLinks = 0
        val provider = GuangyaNetDiskProvider(NetDiskHttp(client { request ->
            val path = request.url.encodedPath.substringAfter("/userres/v1/")
            when (path) {
                "get_share_access_token" -> response("""{"accessToken":"share-token"}""")
                "get_share_summary" -> response("""{"title":"分享"}""")
                "get_share_page_files_list" -> response("""{"list":[{"fileId":"original","fileName":"a.txt","resType":1,"fileSize":3}],"hasMore":false}""")
                "get_share_download_url" -> """{"code":207,"msg":"需转存"}"""
                "file/create_dir" -> {
                    creates++
                    assertEquals("Bearer account-token", request.header("Authorization"))
                    assertTrue(body(request)["dirName"]!!.jsonPrimitive.content.startsWith("Onvyra临时转存_"))
                    assertTrue(body(request)["failIfNameExist"]!!.jsonPrimitive.boolean)
                    response("""{"fileId":"new-folder","resType":2}""")
                }
                "restore_share" -> {
                    restores++
                    assertEquals("share-token", body(request)["accessToken"]!!.jsonPrimitive.content)
                    assertEquals("Bearer account-token", request.header("Authorization"))
                    assertEquals("new-folder", body(request)["parentId"]!!.jsonPrimitive.content)
                    response("""{"taskId":"task-one"}""")
                }
                "get_task_status" -> response("""{"status":2,"detail":{"code":0}}""")
                "file/get_file_list" -> {
                    assertEquals("new-folder", body(request)["parentId"]!!.jsonPrimitive.content)
                    response("""{"list":[{"fileId":"copied","fileName":"a.txt","resType":1,"fileSize":3}]}""")
                }
                "get_res_download_url" -> {
                    personalLinks++
                    assertEquals("copied", body(request)["fileId"]!!.jsonPrimitive.content)
                    response("""{"signedURL":"https://cdn.guangyapan.com/a.txt?sign=fixture"}""")
                }
                else -> throw AssertionError("Unexpected mutation or cleanup path")
            }
        }))
        val session = provider.open(share, "")
        val file = provider.list(session, "", null, "").files.single()
        assertReason("auth_required") { provider.resolve(session, file, "") }
        assertReason("transfer_required") { provider.resolve(session, file, "account-token") }
        assertEquals(0, creates)
        val allowed = session.copy(extra = JsonObject(session.extra + ("allow_transfer" to JsonPrimitive(true))))
        repeat(2) { assertTrue(provider.resolve(allowed, file, "account-token").note!!.contains("保留")) }
        assertEquals(1, creates); assertEquals(1, restores); assertEquals(2, personalLinks)
    }

    @Test fun businessPasswordErrorsAreNotAnEmptySuccessfulShare() = runBlocking {
        val provider = GuangyaNetDiskProvider(NetDiskHttp(client { """{"code":209,"msg":"提取码错误"}""" }))
        assertReason("password_required") { provider.open(share, "") }
    }

    private suspend fun assertReason(reason: String, action: suspend () -> Any?) {
        try { action(); fail("Expected $reason") } catch (e: NetDiskException) { assertEquals(reason, e.reason) }
    }
    private fun response(data: String): String = "{\"code\":0,\"data\":$data}"
    private fun client(answer: (Request) -> String): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(answer(chain.request()).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun body(request: Request): JsonObject = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
}
