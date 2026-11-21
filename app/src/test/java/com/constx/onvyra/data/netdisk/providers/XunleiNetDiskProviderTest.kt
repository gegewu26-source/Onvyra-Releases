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

class XunleiNetDiskProviderTest {
    private val auth = """{"access_token":"account-token","device_id":"device-one","captcha_token":"captcha-one","sub":"user-one"}"""
    private val share = NetDiskShare(NetDiskPlatform.XUNLEI, "https://pan.xunlei.com/s/share-id", "share-id", "ab12")

    @Test fun anonymousPagingUsesSharePassTokenAndDoesNotSendEmptyOrAccountAuthorization() = runBlocking {
        val provider = XunleiNetDiskProvider(NetDiskHttp(client { request ->
            assertNull(request.header("Authorization")); assertNull(request.header("X-Captcha-Token"))
            when (request.url.encodedPath) {
                "/drive/v1/share" -> {
                    assertEquals("ab12", request.url.queryParameter("pass_code"))
                    ResponseData("""{"title":"目录","pass_code_token":"share-token","files":[{"id":"folder","kind":"drive#folder","name":"子目录"}]}""")
                }
                "/drive/v1/share/detail" -> {
                    assertEquals("folder", request.url.queryParameter("parent_id"))
                    assertEquals("share-token", request.url.queryParameter("pass_code_token"))
                    assertEquals("next-page", request.url.queryParameter("page_token"))
                    ResponseData("""{"files":[{"id":"f1","kind":"drive#file","name":"a.txt","size":"3","web_content_link":"https://dl.xunlei.com/a.txt?sign=fixture"}]}""")
                }
                else -> throw AssertionError("Unexpected request")
            }
        }))
        val session = provider.open(share, "")
        assertTrue(provider.list(session, "", null, "").files.single().isDirectory)
        val file = provider.list(session, "folder", "next-page", "").files.single()
        assertEquals(3L, file.size)
        assertEquals("https://dl.xunlei.com/a.txt?sign=fixture", provider.resolve(session, file, "").url)
    }

    @Test fun restoringRequiresExplicitConsentAndRetriesReuseTheSameNewFolderWithoutDeletingAnything() = runBlocking {
        var creates = 0; var restores = 0; var details = 0
        val provider = XunleiNetDiskProvider(NetDiskHttp(client { request ->
            assertNotEquals("DELETE", request.method)
            when (request.url.encodedPath) {
                "/drive/v1/share" -> ResponseData("""{"pass_code_token":"share-token","files":[{"id":"original","name":"a.txt","kind":"drive#file","size":"3"}]}""")
                "/drive/v1/files" -> {
                    assertEquals("POST", request.method); creates++
                    val body = body(request)
                    assertEquals("drive#folder", body["kind"]!!.jsonPrimitive.content)
                    assertEquals("", body["space"]!!.jsonPrimitive.content)
                    assertTrue(body["name"]!!.jsonPrimitive.content.startsWith("Onvyra临时转存_"))
                    ResponseData("""{"id":"created-folder"}""")
                }
                "/drive/v1/share/restore" -> {
                    restores++
                    val body = body(request)
                    assertEquals("share-token", body["pass_code_token"]!!.jsonPrimitive.content)
                    assertEquals("created-folder", body["parent_id"]!!.jsonPrimitive.content)
                    assertEquals(listOf("original"), body["file_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
                    ResponseData("""{"params":{"trace_file_ids":"{\"original\":\"copied-file\"}"}}""")
                }
                "/drive/v1/files/copied-file" -> {
                    details++
                    assertEquals("Bearer account-token", request.header("Authorization"))
                    assertEquals("device-one", request.header("X-Device-Id"))
                    ResponseData("""{"id":"copied-file","links":{"application/octet-stream":{"url":"https://dl.xunlei.com/a.txt?sign=fixture"}}}""")
                }
                else -> throw AssertionError("Unexpected cloud mutation or path")
            }
        }))
        val session = provider.open(share, "")
        val file = provider.list(session, "", null, "").files.single()
        assertReason("auth_required") { provider.resolve(session, file, "") }
        assertReason("transfer_required") { provider.resolve(session, file, auth) }
        assertEquals(0, creates); assertEquals(0, restores)
        val consent = session.copy(extra = JsonObject(session.extra + ("allow_transfer" to JsonPrimitive(true))))
        repeat(2) { assertEquals("https://dl.xunlei.com/a.txt?sign=fixture", provider.resolve(consent, file, auth).url) }
        assertEquals(1, creates); assertEquals(1, restores); assertEquals(2, details)
    }

    @Test fun passwordStatusWithAnEmptyArrayDoesNotMeanTheDirectoryIsEmpty() = runBlocking {
        val provider = XunleiNetDiskProvider(NetDiskHttp(client { ResponseData("""{"share_status":"PASS_CODE_NEED","files":[]}""") }))
        assertReason("password_required") { provider.open(share, "") }
    }

    @Test fun aStaleImportedCaptchaGetsOneLocalRefreshBoundToItsLoginDevice() = runBlocking {
        var shareCalls = 0; var captchaCalls = 0
        val provider = XunleiNetDiskProvider(NetDiskHttp(client { request ->
            when (request.url.encodedPath) {
                "/drive/v1/share" -> {
                    shareCalls++
                    if (shareCalls == 1) ResponseData("""{"error":"captcha_invalid"}""", 400)
                    else { assertEquals("fresh-captcha", request.header("X-Captcha-Token")); ResponseData("""{"files":[]}""") }
                }
                "/v1/shield/captcha/init" -> {
                    captchaCalls++
                    assertEquals("xluser-ssl.xunlei.com", request.url.host)
                    val body = body(request)
                    assertEquals("GET:/drive/v1/share", body["action"]!!.jsonPrimitive.content)
                    assertEquals("device-one", body["device_id"]!!.jsonPrimitive.content)
                    assertEquals(XunleiProtocol.WEB_CLIENT, body["client_id"]!!.jsonPrimitive.content)
                    assertTrue(body["meta"]!!.jsonObject["captcha_sign"]!!.jsonPrimitive.content.matches(Regex("1\\.[a-f0-9]{32}")))
                    ResponseData("""{"captcha_token":"fresh-captcha","expires_in":300}""")
                }
                else -> throw AssertionError("Unexpected request")
            }
        }))
        assertTrue(provider.list(provider.open(share, auth), "", null, auth).files.isEmpty())
        assertEquals(2, shareCalls); assertEquals(1, captchaCalls)
    }

    private suspend fun assertReason(reason: String, action: suspend () -> Any?) {
        try { action(); fail("Expected $reason") } catch (e: NetDiskException) { assertEquals(reason, e.reason) }
    }
    private data class ResponseData(val body: String, val status: Int = 200)
    private fun client(answer: (Request) -> ResponseData): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val data = answer(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(data.status).message("fixture")
            .body(data.body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun body(request: Request): JsonObject = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
}
