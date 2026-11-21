package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class Pan123NetDiskProviderTest {
    @Test fun anActual5112AccessRequirementAsksForLoginWithoutBlindHostRetries() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("/b/api/share/download/info", chain.request().url.encodedPath)
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":5112,"message":"您需要注册登录或付费后下载"}""".toResponseBody()).build()
        }.build()
        val provider = Pan123NetDiskProvider(NetDiskHttp(client))
        val session = NetDiskSession(NetDiskShare(NetDiskPlatform.PAN123, "https://www.123pan.cn/s/fixture-key", "fixture-key"))
        val file = NetDiskFile("42", "fixture.dat", size = 1024, extra = buildJsonObject {
            put("s3_key_flag", "fixture-node"); put("etag", "fixture-hash")
        })
        try {
            provider.resolve(session, file, "")
            fail("An account requirement is not a successful download")
        } catch (error: NetDiskException) {
            assertEquals("auth_required", error.reason)
            assertTrue(error.message.contains("登录"))
            assertTrue(error.message.contains("付费"))
        }
        assertEquals("An explicit access denial must not cause blind alternate-host retries", 1, requests)
    }
}
