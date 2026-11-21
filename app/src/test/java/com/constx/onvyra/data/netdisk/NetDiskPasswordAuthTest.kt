package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.auth.NetDiskPasswordAuth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NetDiskPasswordAuthTest {
    private fun auth(reply: (Request) -> Pair<Int,String>): NetDiskPasswordAuth {
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val (code,body)=reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        return NetDiskPasswordAuth(NetDiskHttp(client))
    }
    @Test fun passwordWhitespaceIsPreservedAndOnlyOfficial123EndpointReceivesIt()= runBlocking {
        val api=auth { request ->
            assertEquals("https",request.url.scheme)
            assertEquals("user.123pan.cn",request.url.host)
            assertEquals("/api/user/sign_in",request.url.encodedPath)
            assertEquals("132",request.header("app-version"))
            assertEquals("web",request.header("platform"))
            val buffer=okio.Buffer();request.body!!.writeTo(buffer)
            val body=Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals("fixture@example.invalid",body["passport"]!!.jsonPrimitive.content)
            assertEquals("  fixture pass  ",body["password"]!!.jsonPrimitive.content)
            assertFalse(body["remember"]!!.jsonPrimitive.boolean)
            200 to """{"code":200,"data":{"token":"fixture-access-token-123456789"}}"""
        }
        assertEquals("fixture-access-token-123456789",api.login123(" fixture@example.invalid ","  fixture pass  "))
    }
    @Test fun codeZeroIsNotMisreportedAsSuccessfulPasswordLogin()=runBlocking {
        val api=auth { 200 to """{"code":0,"message":"fixture secret must not be repeated","data":{"token":"fake-long-enough-token"}}""" }
        try { api.login123("fixture","fixture");fail("must fail") }
        catch(e:NetDiskException) { assertEquals("login_failed",e.reason);assertFalse(e.message.contains("fixture")) }
    }
    @Test fun verificationMustBeCompletedByUserRatherThanTreatedAsAuthentication()=runBlocking {
        val api=auth { 403 to """{"code":403,"message":"captcha required"}""" }
        try { api.login123("fixture","fixture");fail("must require verification") }
        catch(e:NetDiskException) { assertEquals("verification_required",e.reason) }
    }
    @Test fun invalidInputFailsBeforePasswordLeavesTheDevice()=runBlocking {
        var requests=0
        val api=auth { requests++;200 to "{}" }
        try { api.login123(" ","fixture");fail("must reject") } catch(e:NetDiskException) { assertEquals("invalid_input",e.reason) }
        assertEquals(0,requests)
    }
}
