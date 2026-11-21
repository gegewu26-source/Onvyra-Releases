package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class IlanzouNetDiskProviderTest {
    @Test fun timestampsUseTheKnownAESWireFormatAndFileMetadataPreservesOwnerId() {
        assertEquals("6e34576a3615fb11ef81f07a050bb9a3", IlanzouProtocol.encrypt("1700000000000"))
        val row = Json.parseToJsonElement("""{"fileType":1,"fileId":"52","fileName":"测试.bin","fileSize":1.5,"userId":"owner-1"}""").jsonObject
        val file = requireNotNull(IlanzouProtocol.parseItem(row))
        assertEquals("f:52", file.id); assertEquals(1536L, file.size)
        assertEquals("owner-1", file.extra["userId"]!!.jsonPrimitive.content)
    }

    @Test fun anonymousDirectoryPagingAndDownloadStayOnOfficialAPIWithoutAnAccountToken() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val url = request.url
            paths.add(url.encodedPath)
            assertEquals("apix.ilanzou.com", url.host)
            assertNull(url.queryParameter("appToken")); assertNull(request.header("Authorization"))
            assertEquals("share-id", url.queryParameter("shareId"))
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            when (url.encodedPath) {
                "/unproved/share/list" -> {
                    assertEquals("ab12", url.queryParameter("code"))
                    val body = when {
                        url.queryParameter("folderId") == null -> """{"code":200,"totalPage":1,"list":[{"fileType":2,"folderId":5,"folderName":"目录"}]}"""
                        url.queryParameter("offset") == "1" -> """{"code":200,"totalPage":2,"list":[{"fileType":1,"fileId":52,"fileName":"a.txt","fileSize":2,"userId":7}]}"""
                        else -> """{"code":200,"totalPage":2,"list":[{"fileType":1,"fileId":53,"fileName":"b.txt","fileSize":3,"userId":7}]}"""
                    }
                    response.code(200).body(body.toResponseBody("application/json".toMediaType())).build()
                }
                "/unproved/file/redirect" -> {
                    assertEquals(IlanzouProtocol.encrypt("52|7"), url.queryParameter("downloadId"))
                    response.code(302).header("Location", "https://cdn.ilanzou.com/a.txt?sign=fixture")
                        .body("".toResponseBody()).build()
                }
                else -> throw AssertionError("Unexpected path")
            }
        }.build()
        val provider = IlanzouNetDiskProvider(NetDiskHttp(client))
        val session = provider.open(NetDiskShare(NetDiskPlatform.ILANZOU, "https://www.ilanzou.com/s/share-id", "share-id", "ab12"), "")
        val root = provider.list(session, "", null, "")
        assertEquals("d:5", root.files.single().id)
        val first = provider.list(session, "d:5", null, "")
        assertEquals("2", first.nextCursor)
        val second = provider.list(session, "d:5", first.nextCursor, "")
        assertNull(second.nextCursor); assertEquals("b.txt", second.files.single().name)
        assertEquals("https://cdn.ilanzou.com/a.txt?sign=fixture", provider.resolve(session, first.files.single(), "").url)
        assertEquals(3, paths.count { it == "/unproved/share/list" }) // root list reuses the response that opened the session.
    }

    @Test fun aProviderBusinessErrorIsNeverPresentedAsAnEmptySuccessfulDirectory() = runBlocking {
        val provider = IlanzouNetDiskProvider(NetDiskHttp(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":400,"msg":"分享不存在"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()))
        try {
            provider.open(NetDiskShare(NetDiskPlatform.ILANZOU, "https://www.ilanzou.com/s/missing", "missing"), "")
            fail("Provider failure must propagate")
        } catch (expected: NetDiskException) { assertEquals("share_expired", expected.reason) }
    }
}
