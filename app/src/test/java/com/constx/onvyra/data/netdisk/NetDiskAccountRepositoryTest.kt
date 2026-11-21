package com.constx.onvyra.data.netdisk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Authenticated endpoint contracts, with no real account or provider requests. */
class NetDiskAccountRepositoryTest {
    private fun repository(credential: () -> String = { "fixture-token" }, respond: (Request) -> Pair<Int, String>): NetDiskAccountRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return NetDiskAccountRepository({ credential() }, NetDiskHttp(client))
    }

    @Test fun pan123PersonalDirectoriesUseTheSavedBearerAndCursorRatherThanAnonymousShareListing() = runBlocking {
        var calls = 0
        val api = repository {
            calls++
            assertEquals("yun.123pan.cn", it.url.host)
            assertEquals("/b/api/file/list/new", it.url.encodedPath)
            assertEquals("Bearer fixture-token", it.header("Authorization"))
            assertEquals("web", it.header("platform"))
            assertEquals("3", it.header("app-version"))
            assertFalse(it.header("auth-key").isNullOrBlank())
            assertFalse(it.header("auth-value").isNullOrBlank())
            assertEquals("false", it.url.queryParameter("trashed"))
            assertEquals("100", it.url.queryParameter("limit"))
            if (calls == 1) {
                assertEquals("0", it.url.queryParameter("parentFileId"))
                assertEquals("0", it.url.queryParameter("next"))
                200 to """{"code":0,"data":{"InfoList":[{"FileId":42,"FileName":"folder","Type":1,"Size":0},{"FileId":43,"FileName":"file.bin","Type":0,"Size":123,"S3KeyFlag":"node","Etag":"hash"}],"Next":"83"}}"""
            } else {
                assertEquals("42", it.url.queryParameter("parentFileId"))
                assertEquals("83", it.url.queryParameter("next"))
                200 to """{"code":0,"data":{"InfoList":[],"Next":"-1"}}"""
            }
        }
        val root = api.list(NetDiskPlatform.PAN123)
        assertEquals("我的文件", root.title)
        assertEquals("2:83", root.nextCursor)
        assertTrue(root.files[0].isDirectory)
        assertEquals("node", root.files[1].extra["s3_key_flag"]?.jsonPrimitive?.content)
        assertEquals(123L, root.files[1].size)
        val child = api.list(NetDiskPlatform.PAN123, "42", root.nextCursor)
        assertTrue(child.files.isEmpty())
        assertNull(child.nextCursor)
        assertEquals(2, calls)
    }

    @Test fun pan123PersonalDownloadUsesThePersonalEndpointAndNeverLeaksBearerToCdn() = runBlocking {
        var calls = 0
        val api = repository(credential = { """{"access_token":"fixture-token"}""" }) {
            calls++
            assertEquals("POST", it.method)
            assertEquals("/api/file/download_info", it.url.encodedPath)
            assertEquals("Bearer fixture-token", it.header("Authorization"))
            val buffer = okio.Buffer()
            it.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("43", body["fileId"]?.jsonPrimitive?.content)
            assertEquals("node", body["s3keyFlag"]?.jsonPrimitive?.content)
            assertEquals("hash", body["etag"]?.jsonPrimitive?.content)
            assertNull(body["ShareKey"])
            200 to """{"code":0,"data":{"DownloadUrl":"https://cdn.example.invalid/file.bin?auto_redirect=0"}}"""
        }
        val file = NetDiskFile("43", "file.bin", size = 123, extra = buildJsonObject {
            put("s3_key_flag", "node"); put("etag", "hash")
        })
        val result = api.resolve(NetDiskPlatform.PAN123, file)
        assertTrue(result.url.contains("auto_redirect=1"))
        assertFalse(result.headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) })
        assertEquals("file.bin", result.fileName)
        assertEquals(1, calls)
    }

    @Test fun missingCredentialsFailBeforeAnyRequestAndExpiredCredentialsAreNotAnEmptyDirectory() = runBlocking {
        var calls = 0
        val missing = repository(credential = { "" }) { calls++; 200 to "{}" }
        try { missing.list(NetDiskPlatform.PAN123); fail("Login is required") }
        catch (failure: NetDiskException) { assertEquals("auth_required", failure.reason) }
        assertEquals(0, calls)
        val expired = repository { calls++; 200 to """{"code":401,"message":"expired"}""" }
        try { expired.list(NetDiskPlatform.PAN123); fail("Expired account is not an empty folder") }
        catch (failure: NetDiskException) { assertEquals("auth_required", failure.reason) }
        assertEquals(1, calls)
    }

    @Test fun aValidEmptyDirectoryAndMalformedProviderResponseRemainDistinct() = runBlocking {
        val empty = repository { 200 to """{"code":0,"data":{"InfoList":[],"Next":"-1"}}""" }
        assertTrue(empty.list(NetDiskPlatform.PAN123).files.isEmpty())
        val malformed = repository { 200 to """{"code":0,"data":{}}""" }
        try { malformed.list(NetDiskPlatform.PAN123); fail("Missing list is not an empty account") }
        catch (failure: NetDiskException) { assertEquals("invalid_response", failure.reason) }
    }

    @Test fun quarkAndUcUseAuthenticatedPersonalSortEndpointsAndRepresentFolders() = runBlocking {
        for ((platform, host, product) in listOf(
            Triple(NetDiskPlatform.QUARK, "drive-pc.quark.cn", "ucpro"),
            Triple(NetDiskPlatform.UC, "pc-api.uc.cn", "UCBrowser"))) {
            val api = repository(credential = { "session=fixture" }) {
                assertEquals(host, it.url.host)
                assertEquals("/1/clouddrive/file/sort", it.url.encodedPath)
                assertEquals("session=fixture", it.header("Cookie"))
                assertEquals("folder-id", it.url.queryParameter("pdir_fid"))
                assertEquals("2", it.url.queryParameter("_page"))
                assertEquals(product, it.url.queryParameter("pr"))
                assertNull(it.url.queryParameter("pwd_id"))
                assertNull(it.header("Authorization"))
                200 to """{"status":200,"code":0,"data":{"list":[{"fid":"child-id","file_name":"folder","dir":true,"size":0}]}}"""
            }
            val result = api.list(platform, "folder-id", "2")
            assertEquals(1, result.files.size)
            assertTrue(result.files.single().isDirectory)
            assertEquals("child-id", result.files.single().id)
            assertNull(result.nextCursor)
        }
    }

    @Test fun personalQuarkDownloadSendsOnlyFileIdsAndCdnNeverReceivesAnAccountCookie() = runBlocking {
        val api = repository(credential = { "__pus=fixture; __puus=fixture" }) {
            assertEquals("/1/clouddrive/file/download", it.url.encodedPath)
            val buffer = okio.Buffer(); it.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(setOf("fids"), body.keys)
            assertEquals("43", body.getValue("fids").jsonArray.single().jsonPrimitive.content)
            200 to """{"status":200,"code":0,"data":[{"download_url":"https://cdn.example.invalid/file.bin","file_name":"file.bin"}]}"""
        }
        val link = api.resolve(NetDiskPlatform.QUARK, NetDiskFile("43", "file.bin"))
        assertNull(link.headers["Cookie"])
        assertNull(link.headers["Authorization"])
    }

    @Test fun directoryDownloadAndUnsupportedNativePlatformsNeverIssueMutatingOrDownloadRequests() = runBlocking {
        var calls = 0
        val api = repository { calls++; 200 to "{}" }
        try { api.resolve(NetDiskPlatform.PAN123, NetDiskFile("42", "folder", isDirectory = true)); fail("Folder must open") }
        catch (failure: NetDiskException) { assertEquals("is_directory", failure.reason) }
        try { api.list(NetDiskPlatform.ALIYUN); fail("Use official web file page") }
        catch (failure: NetDiskException) { assertEquals("account_browser_required", failure.reason) }
        assertEquals(0, calls)
    }
}
