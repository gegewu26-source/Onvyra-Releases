package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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

class C139NetDiskProviderTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun encryptedTransportRetainsIvAndDecodesBothJsonAndGzipResponses() {
        val value = """{"resultCode":"0","data":{"name":"中文资料"}}"""
        val wrapped = C139Protocol.encrypt(value, ByteArray(16) { it.toByte() })
        assertEquals(json(value), C139Protocol.decodeResponse(wrapped))
        assertEquals(json(value), C139Protocol.decodeResponse(value))
        assertFalse(wrapped.contains("中文"))
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(value.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val iv = ByteArray(16) { 9 }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("PVGDwmcvfs1uV3d1".toByteArray(), "AES"), IvParameterSpec(iv))
        val gzipResponse = Base64.getEncoder().encodeToString(iv + cipher.doFinal(compressed))
        assertEquals(json(value), C139Protocol.decodeResponse(gzipResponse))
        assertThrows(NetDiskException::class.java) { C139Protocol.decodeResponse("not-an-encrypted-response") }
    }

    @Test fun signatureMatchesTheIndependentUtf8ReferenceVector() {
        assertEquals("0AD511308274F54F00EC575069B6AE08", C139Protocol.signature(
            """{"getOutLinkInfoReq":{"account":"","linkID":"share-id","passwd":"码123"}}""",
            "2026-11-13 20:00:00", "abcdefghijklmnop"))
    }

    @Test fun loginDerivesTheAccountWithoutExposingItToASeparateEndpoint() {
        val auth = "Basic " + Base64.getEncoder().encodeToString("pc:13800000000:fixture-token".toByteArray())
        val login = C139Protocol.login("authorization=$auth; tracking=unrelated")
        assertEquals("13800000000", login.account)
        assertEquals(auth, login.authorization)
        assertEquals("13800000000", C139Protocol.login(auth).account)
        assertNull(C139Protocol.login("ORCHES-I-ACCOUNT-SIMPLIFY=138****0000; Os_SSo_Sid=sid; RMKEY=key").account)
        assertEquals("13800000000", C139Protocol.login("Login_UserNumber=13800000000").account)
    }

    @Test fun foldersAndFilesBothSurviveTheListAndEmptyDirectoriesRemainEmpty() {
        val listing = C139Protocol.listing(json("""{"caLst":[{"caID":"folder","caName":"目录"}],
            "coLst":[{"coID":"nested","coName":"嵌套目录","coType":2},{"coID":"file","coName":"作业.txt","coSize":"1024"}]}"""), 1)
        assertEquals(listOf("folder", "nested", "file"), listing.files.map { it.id })
        assertTrue(listing.files[0].isDirectory)
        assertTrue(listing.files[1].isDirectory)
        assertFalse(listing.files[2].isDirectory)
        assertEquals(1024L, listing.files[2].size)
        assertNull(listing.nextCursor)
        assertTrue(C139Protocol.listing(json("{}"), 1).files.isEmpty())
        val full = buildJsonObject { put("coLst", JsonArray((1..200).map { buildJsonObject {
            put("coID", "file$it"); put("coName", "file$it")
        } })) }
        assertEquals("201", C139Protocol.listing(full, 1).nextCursor)
        assertEquals(201, C139Protocol.pageStart("201"))
        for (invalid in listOf("abc", "-1", "200", "20001")) {
            assertThrows(NetDiskException::class.java) { C139Protocol.pageStart(invalid) }
        }
    }

    @Test fun passwordAndAuthenticationFailuresCannotBecomeEmptySuccesses() {
        val pwd = assertThrows(NetDiskException::class.java) {
            C139Protocol.check(200, json("""{"resultCode":"9188","desc":"密码错误","success":false}"""))
        }
        assertEquals("password_required", pwd.reason)
        val auth = assertThrows(NetDiskException::class.java) {
            C139Protocol.check(200, json("""{"resultCode":"04000005","desc":"认证失败"}"""))
        }
        assertEquals("auth_required", auth.reason)
        assertThrows(NetDiskException::class.java) { C139Protocol.check(200, json("{}")) }
        assertThrows(NetDiskException::class.java) { C139Protocol.check(200, json("""{"data":{}}""")) }
        assertThrows(NetDiskException::class.java) { C139Protocol.check(404, json("""{"resultCode":"0","data":{}}""")) }
    }

    @Test fun nativeShareFlowUsesAnonymousListingAndSignedDirectDownloadWithoutCopyingAnything() = runBlocking {
        val requests = ArrayList<Pair<Request, JsonObject>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val encrypted = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val body = C139Protocol.decodeResponse(encrypted)
            requests += request to body
            val response = when (request.url.pathSegments.last()) {
                "getOutLinkGeneral" -> """{"resultCode":"0","data":{"getOutLinkGeneralResp":{"outLinkGeneral":[{"lkName":"课程资料","passwd":"1234"}]}}}"""
                "getOutLinkInfoV6" -> if (body.ndObject("getOutLinkInfoReq")!!.ndText("pCaID") == "root")
                    """{"resultCode":"0","data":{"caLst":[{"caID":"folder","caName":"目录"}],"coLst":[{"coID":"file","coName":"课件.txt","coSize":123}]}}"""
                    else """{"resultCode":"0","data":{"caLst":[],"coLst":[]}}"""
                "dlFromOutLinkV3" -> """{"resultCode":"0","data":{"redrUrl":"https://download.yun.139.com/file?signature=share-only"}}"""
                else -> error("Unexpected endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(C139Protocol.encrypt(response).toResponseBody("text/plain".toMediaType())).build()
        }.build()
        val provider = C139NetDiskProvider(NetDiskHttp(client))
        val share = NetDiskShare(NetDiskPlatform.C139, "https://yun.139.com/shareweb/#/w/i/fixture", "fixture")
        val session = provider.open(share, "authorization=must-not-leak-to-list")
        assertEquals("课程资料", session.title)
        val root = provider.list(session, "", null, "authorization=must-not-leak-to-list")
        assertEquals(2, requests.size)
        assertEquals("1234", requests[1].second.ndObject("getOutLinkInfoReq")!!.ndText("passwd"))
        for ((request, body) in requests) {
            assertNull(request.header("Authorization"))
            assertNull(request.header("mcloud-sign"))
            assertEquals("", request.header("Cookie"))
            assertFalse(body.toString().contains("must-not-leak"))
        }
        assertTrue(provider.list(session, "folder", null, "").files.isEmpty())
        assertEquals("folder", requests.last().second.ndObject("getOutLinkInfoReq")!!.ndText("pCaID"))
        val file = root.files.single { !it.isDirectory }
        try { provider.resolve(session, file, ""); fail("A download must require an actual account") }
        catch (failure: NetDiskException) { assertEquals("auth_required", failure.reason) }
        val auth = "Basic " + Base64.getEncoder().encodeToString("pc:13800000000:fixture-token".toByteArray())
        val download = provider.resolve(session, file, "authorization=$auth")
        assertEquals(auth, requests.last().first.header("Authorization"))
        assertNotNull(requests.last().first.header("mcloud-sign"))
        assertEquals("file", requests.last().second.ndObject("dlFromOutLinkReqV3")!!
            .ndObject("coIDLst")!!.ndArray("item")!!.single().jsonPrimitive.content)
        assertEquals("课件.txt", download.fileName)
        assertFalse(download.headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) })
        assertTrue(requests.all { it.first.url.host == "share-kd-njs.yun.139.com" })
        assertTrue(requests.all { it.first.url.pathSegments.last() in setOf("getOutLinkGeneral", "getOutLinkInfoV6", "dlFromOutLinkV3") })
    }
}
