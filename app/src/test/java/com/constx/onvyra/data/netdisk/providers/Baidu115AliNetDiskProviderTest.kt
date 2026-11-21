package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
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

class Baidu115AliNetDiskProviderTest {
    private class Service(val answer: (Request) -> String) {
        val requests = CopyOnWriteArrayList<Request>()
        val http = NetDiskHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer(request).toResponseBody("application/json".toMediaType())).build()
        }.build())
    }
    private fun json(request: Request) = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun form(request: Request): Map<String, String> = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        .split('&').associate { item -> URLDecoder.decode(item.substringBefore('='), "UTF-8") to URLDecoder.decode(item.substringAfter('=', ""), "UTF-8") }
    private fun session(platform: NetDiskPlatform, approved: Boolean = false) = NetDiskSession(
        NetDiskShare(platform, platform.loginUrl + "s/share", "share"), token = "share-token",
        extra = buildJsonObject { put("allow_transfer", approved); put("share_id", "sid"); put("uk", "uk"); put("user_id", "owner") })
    private val file = NetDiskFile("file-id", "empty.txt", size = 0)

    @Test fun baiduDirectShareDownloadUsesOwnerMetadataWithoutAnyAccountWrite() = runBlocking {
        val service = Service { r -> when (r.url.encodedPath) {
            "/share/tplconfig" -> """{"errno":0,"data":{"sign":"signature","timestamp":123}}"""
            "/api/sharedownload" -> {
                assertEquals("sid", form(r)["primaryid"]); assertEquals("uk", form(r)["uk"])
                """{"errno":0,"list":[{"dlink":"https://cdn.example.org/empty.txt"}]}"""
            }
            else -> error("Unexpected endpoint ${r.url.encodedPath}")
        } }
        val link = BaiduNetDiskProvider(service.http).resolve(session(NetDiskPlatform.BAIDU), file, "")
        assertEquals("https://cdn.example.org/empty.txt", link.url)
        assertNull(link.headers["Cookie"])
        assertEquals(2, service.requests.size)
    }

    @Test fun baiduDoesNotCreateOrTransferWithoutPerFileConsent() = runBlocking {
        val service = Service { """{"errno":0,"data":{}}""" }
        try { BaiduNetDiskProvider(service.http).resolve(session(NetDiskPlatform.BAIDU), file, "BDUSS=account"); fail() }
        catch (e: NetDiskException) { assertEquals("transfer_required", e.reason) }
        assertEquals(1, service.requests.size)
        assertTrue(service.requests.none { it.url.encodedPath.contains("transfer") || it.url.encodedPath.contains("create") })
    }

    @Test fun baiduApprovedTransferRetainsOneDirectoryAndCopyAcrossDownloadRetries() = runBlocking {
        var directory = ""
        val service = Service { r -> when (r.url.encodedPath) {
            "/share/tplconfig" -> """{"errno":0,"data":{}}"""
            "/api/gettemplatevariable" -> """{"errno":0,"result":{"bdstoken":"account-token"}}"""
            "/api/create" -> { if (form(r)["path"]!!.contains("reader49-")) directory = form(r).getValue("path"); """{"errno":0}""" }
            "/share/transfer" -> {
                assertEquals(directory, form(r)["path"])
                buildJsonObject { put("errno", 0); put("extra", buildJsonObject { put("list", buildJsonArray {
                    add(buildJsonObject { put("to", "$directory/empty.txt"); put("to_fs_id", "copy-id") })
                }) }) }.toString()
            }
            "/rest/2.0/pcs/file" -> """{"errno":0,"urls":[{"encrypt":0,"url":"https://cdn.example.org/file"}]}"""
            else -> error("Unexpected ${r.url}")
        } }
        val provider = BaiduNetDiskProvider(service.http)
        repeat(2) { assertTrue(provider.resolve(session(NetDiskPlatform.BAIDU, true), file, "BDUSS=account").note!!.contains("保留")) }
        assertEquals(2, service.requests.count { it.url.encodedPath == "/api/create" })
        assertEquals(1, service.requests.count { it.url.encodedPath == "/share/transfer" })
        assertTrue(service.requests.none { it.url.encodedPath.contains("delete") })
    }

    @Test fun pan115TriesBothReadOnlyShareRoutesBeforeRequestingTransferPermission() = runBlocking {
        val service = Service { r -> when (r.url.encodedPath) {
            "/share/downurl" -> """{"state":false,"errno":50029,"error":"client limit"}"""
            "/os_windows/2.0/share/downurl" -> """{"state":true,"data":{"file_url_302":"https://cdn.example.org/file"}}"""
            else -> error("Unexpected endpoint")
        } }
        val link = Pan115NetDiskProvider(service.http).resolve(session(NetDiskPlatform.PAN115), file, "UID=x; CID=y; SEID=z")
        assertEquals("https://cdn.example.org/file", link.url)
        assertEquals(listOf("webapi.115.com", "proapi.115.com"), service.requests.map { it.url.host })
        assertTrue(service.requests.all { it.method == "GET" })
        assertNull(link.headers["Cookie"])
    }

    @Test fun pan115ListingPreservesFoldersZeroByteFilesAndPagination() = runBlocking {
        val rows = buildJsonArray {
            add(buildJsonObject { put("cid", "folder-id"); put("fc", 0); put("n", "folder") })
            repeat(99) { add(buildJsonObject { put("fid", "file-$it"); put("fc", 1); put("n", "zero-$it.txt"); put("s", 0) }) }
        }
        val service = Service { r ->
            assertEquals("/share/snap", r.url.encodedPath)
            """{"state":true,"data":{"count":150,"list":$rows}}"""
        }
        val provider = Pan115NetDiskProvider(service.http)
        val s = provider.open(session(NetDiskPlatform.PAN115).share, "")
        val list = provider.list(s)
        assertTrue(list.files.first().isDirectory); assertEquals("folder-id", list.files.first().id)
        assertEquals(0L, list.files[1].size); assertEquals("100", list.nextCursor)
        assertEquals(1, service.requests.size)
        provider.list(s, "folder-id", "100", "")
        assertEquals("100", service.requests.last().url.queryParameter("offset"))
        assertEquals("folder-id", service.requests.last().url.queryParameter("cid"))
    }

    @Test fun pan115MissingConsentNeverReceivesFilesAndInvalidAccountsRemainAuthenticationErrors() = runBlocking {
        val service = Service { """{"state":false,"errno":50029,"error":"client limit"}""" }
        try { Pan115NetDiskProvider(service.http).resolve(session(NetDiskPlatform.PAN115), file, "UID=x"); fail() }
        catch (e: NetDiskException) { assertEquals("transfer_required", e.reason) }
        assertEquals(2, service.requests.size)
        val authService = Service { """{"state":false,"errno":990001,"error":"login required"}""" }
        try { Pan115NetDiskProvider(authService.http).resolve(session(NetDiskPlatform.PAN115, true), file, "UID=x"); fail() }
        catch (e: NetDiskException) { assertEquals("auth_required", e.reason) }
        assertEquals(1, authService.requests.size)
    }

    @Test fun aliyunTransferIsExplicitAndReusesItsCopyAfterDownloadFailure() = runBlocking {
        var downloads = 0
        val service = Service { r -> when (r.url.encodedPath) {
            "/v2/file/get_share_link_download_url" -> """{"code":"NotAllowedShareDownload"}"""
            "/v2/user/get" -> """{"resource_drive_id":"drive-id"}"""
            "/adrive/v2/file/createWithFolders" -> {
                assertTrue(json(r).ndText("name").startsWith("Onvyra临时转存-")); """{"file_id":"folder-id"}"""
            }
            "/adrive/v3/file/list" -> """{"items":[]}"""
            "/adrive/v2/file/copy" -> {
                assertEquals("folder-id", json(r).ndText("to_parent_file_id")); assertFalse(json(r).ndFlag("auto_rename"))
                """{"file_id":"saved-file"}"""
            }
            "/v2/file/get_download_url" -> {
                assertEquals("saved-file", json(r).ndText("file_id"))
                if (++downloads == 1) """{"code":"TooManyRequests","message":"retry"}"""
                else """{"url":"https://cdn.example.org/file"}"""
            }
            else -> error("Unexpected ${r.url.encodedPath}")
        } }
        val provider = AliyunNetDiskProvider(service.http)
        try { provider.resolve(session(NetDiskPlatform.ALIYUN), file, "account"); fail() }
        catch (e: NetDiskException) { assertEquals("transfer_required", e.reason) }
        assertEquals(1, service.requests.size)
        try { provider.resolve(session(NetDiskPlatform.ALIYUN, true), file, "account"); fail() }
        catch (_: NetDiskException) { }
        assertEquals("https://cdn.example.org/file", provider.resolve(session(NetDiskPlatform.ALIYUN, true), file, "account").url)
        assertEquals(1, service.requests.count { it.url.encodedPath.endsWith("/copy") })
        assertEquals(1, service.requests.count { it.url.encodedPath.endsWith("/createWithFolders") })
    }

    @Test fun aliyunAcknowledgedCopyRejectionCanRetryButAnUncertainNetworkResponseCannotDuplicateIt() = runBlocking {
        var copies = 0
        var copied = false
        val service = Service { r -> when (r.url.encodedPath) {
            "/v2/file/get_share_link_download_url" -> """{"code":"NotAllowedShareDownload"}"""
            "/v2/user/get" -> """{"default_drive_id":"drive-id"}"""
            "/adrive/v2/file/createWithFolders" -> """{"file_id":"folder-id"}"""
            "/adrive/v3/file/list" -> if (copied) """{"items":[{"file_id":"saved","name":"empty.txt","size":0,"type":"file"}]}""" else """{"items":[]}"""
            "/adrive/v2/file/copy" -> if (++copies == 1) """{"code":"TooManyRequests","message":"retry"}"""
                else { copied = true; throw IOException("Response lost after server accepted copy") }
            "/v2/file/get_download_url" -> """{"url":"https://cdn.example.org/file"}"""
            else -> error("Unexpected endpoint")
        } }
        val provider = AliyunNetDiskProvider(service.http)
        val s = session(NetDiskPlatform.ALIYUN, true)
        try { provider.resolve(s, file, "account"); fail() } catch (_: NetDiskException) { }
        try { provider.resolve(s, file, "account"); fail() } catch (_: NetDiskException) { }
        assertEquals("https://cdn.example.org/file", provider.resolve(s, file, "account").url)
        assertEquals(2, copies)
        assertEquals(1, service.requests.count { it.url.encodedPath.endsWith("/createWithFolders") })
    }

    @Test fun rsaCodecRejectsMalformedServerResponsesInsteadOfReturningGarbledDownloads() {
        assertThrows(IllegalArgumentException::class.java) { NetDisk115Crypto.encode("{}", ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { NetDisk115Crypto.decode("invalid", ByteArray(16)) }
        assertThrows(IllegalStateException::class.java) { NetDisk115Crypto.decode(java.util.Base64.getEncoder().encodeToString(ByteArray(128)), ByteArray(16)) }
        val encoded = java.util.Base64.getDecoder().decode(NetDisk115Crypto.encode("""{"pickcode":"test"}""", ByteArray(16)))
        assertEquals(128, encoded.size)
        assertEquals(16, NetDisk115Crypto.newKey().size)
    }
}
