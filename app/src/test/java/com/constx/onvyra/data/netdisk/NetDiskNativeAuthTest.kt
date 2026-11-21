package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.auth.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetDiskNativeAuthTest {
    private data class Reply(val body: String, val status: Int = 200, val headers: List<Pair<String, String>> = emptyList())
    private fun http(reply: (Request) -> Reply) = NetDiskHttp(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("https", request.url.scheme)
        val result = reply(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(result.status).message("fixture")
            .apply { result.headers.forEach { (key, value) -> addHeader(key, value) } }
            .body(result.body.toResponseBody()).build()
    }.build())
    private fun body(request: Request) = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun form(request: Request) = request.body as FormBody
    private suspend fun expect(reason: String, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: NetDiskException) { assertEquals(reason, error.reason) }
    }
    private fun text(json: JsonObject, key: String) = json[key]!!.jsonPrimitive.content
    private val access = "fixture-valid-access-token-0123456789"

    @Test fun lanzouPasswordIsSentOnceToAccountsAndCookiesFollowOnlyTheirOwnDomain() = runBlocking {
        var posts = 0
        val api = LanzouNativeAuth(http { request ->
            when {
                request.method == "POST" -> {
                    posts++
                    assertEquals("accounts.woozooo.com", request.url.host)
                    val fields = form(request)
                    assertEquals("uselogin", fields.value(fields.names().indexOf("task")))
                    assertEquals("fixture-account", fields.value(fields.names().indexOf("username")))
                    assertEquals("  fixture password  ", fields.value(fields.names().indexOf("password")))
                    assertFalse(request.header("Cookie").orEmpty().contains("pc-cookie"))
                    Reply("""{"zt":"1","msgs":"https://pc.woozooo.com/auth.php"}""")
                }
                request.url.encodedPath == "/auth.php" -> Reply("", 302, listOf(
                    "Location" to "/mydisk.php", "Set-Cookie" to "ylogin=pc-cookie; Path=/; Secure; HttpOnly",
                    "Set-Cookie" to "phpdisk_info=fixture-disk-cookie; Path=/; Secure; HttpOnly"))
                request.url.host == "pc.woozooo.com" -> {
                    assertTrue(request.header("Cookie").orEmpty().contains("ylogin=pc-cookie"))
                    Reply("<html>uid=123 vei='fixture'</html>", headers = listOf("Set-Cookie" to "PHPSESSID=fixture-session; Path=/; Secure"))
                }
                else -> Reply("<html>Official login</html>")
            }
        })
        val cookie = api.login(" fixture-account ", "  fixture password  ")
        assertEquals(1, posts)
        assertTrue(cookie.contains("ylogin=pc-cookie"))
        assertTrue(cookie.contains("phpdisk_info=fixture-disk-cookie"))
        assertTrue(cookie.contains("PHPSESSID=fixture-session"))
    }

    @Test fun lanzouOfficialJavascriptCookieChallengeDoesNotRepeatSuccessfulSignIn() = runBlocking {
        var posts = 0
        val api = LanzouNativeAuth(http { request ->
            if (request.method == "POST") {
                posts++
                if (posts == 1) Reply("<script>var arg1='0123456789abcdef0123456789abcdef01234567';</script>")
                else {
                    assertTrue(request.header("Cookie").orEmpty().contains("acw_sc__v2="))
                    Reply("""{"zt":1,"msgs":"https://pc.woozooo.com/auth.php"}""")
                }
            } else if (request.url.host == "pc.woozooo.com") Reply("<html>Files</html>", headers = listOf(
                "Set-Cookie" to "ylogin=fixture; Path=/; Secure", "Set-Cookie" to "phpdisk_info=fixture; Path=/; Secure"))
            else Reply("<html>Login</html>")
        })
        assertTrue(api.login("fixture", "fixture").contains("ylogin="))
        assertEquals(2, posts)
    }

    @Test fun lanzouForeignAuthRelayNeverReceivesAnyRequestOrCredential() = runBlocking {
        var requests = 0
        val api = LanzouNativeAuth(http { request ->
            requests++
            assertEquals("accounts.woozooo.com", request.url.host)
            if (request.method == "POST") Reply("""{"zt":1,"msgs":"https://evil.example/auth.php"}""") else Reply("<html>Login</html>")
        })
        expect("verification_required") { api.login("fixture", "fixture") }
        assertEquals(2, requests)
    }

    @Test fun lanzouRejectsBusinessFailureAndMissingSessionCookies() = runBlocking {
        val rejected = LanzouNativeAuth(http { request ->
            Reply(if (request.method == "POST") """{"zt":0,"msgs":"密码错误 fixture-secret"}""" else "<html>Login</html>")
        })
        expect("login_failed") { rejected.login("fixture", "fixture") }
        val incomplete = LanzouNativeAuth(http { request ->
            Reply(if (request.method == "POST") """{"zt":1,"msgs":""}""" else "<html>Login</html>")
        })
        expect("login_failed") { incomplete.login("fixture", "fixture") }
    }

    @Test fun ilanzouUsesServerUuidAndNativePasswordWithoutChangingSecret() = runBlocking {
        var requests = 0
        val api = IlanzouNativeAuth(http { request ->
            requests++
            assertEquals("apis.ilanzou.com", request.url.host)
            assertEquals("6", request.url.queryParameter("devType"))
            assertEquals("2", request.url.queryParameter("extra"))
            assertTrue(request.url.queryParameter("timestamp")!!.matches(Regex("[0-9a-f]+")))
            if (request.method == "GET") Reply("""{"code":200,"uuid":"fixture-device-0123456789"}""") else {
                assertEquals("/unproved/login", request.url.encodedPath)
                assertEquals("fixture-device-0123456789", request.url.queryParameter("uuid"))
                assertEquals("fixture-device-0123456789", request.url.queryParameter("devCode"))
                assertEquals("fixture-account", text(body(request), "loginName"))
                assertEquals("  fixture password  ", text(body(request), "loginPwd"))
                Reply("""{"code":200,"data":{"appToken":"$access"}}""")
            }
        })
        val result = Json.parseToJsonElement(api.login(" fixture-account ", "  fixture password  ")).jsonObject
        assertEquals(access, text(result, "appToken"))
        assertEquals("fixture-device-0123456789", text(result, "uuid"))
        assertEquals(2, requests)
    }

    @Test fun ilanzouMissingUuidStopsBeforePasswordAndMissingTokenNeverAuthenticates() = runBlocking {
        var requests = 0
        val badUuid = IlanzouNativeAuth(http { requests++; Reply("""{"code":200,"uuid":"bad"}""") })
        expect("verification_required") { badUuid.login("fixture", "fixture") }
        assertEquals(1, requests)
        val badToken = IlanzouNativeAuth(http { request -> Reply(if (request.method == "GET")
            """{"code":200,"uuid":"fixture-device-0123456789"}""" else """{"code":200,"data":{}}""") })
        expect("login_failed") { badToken.login("fixture", "fixture") }
    }

    @Test fun guangyaPasswordUsesSameGeneratedDeviceAndOfficialCaptchaBeforeSignIn() = runBlocking {
        var requests = 0
        var device = ""
        val api = GuangyaNativeAuth(http { request ->
            requests++
            assertEquals("account.guangyapan.com", request.url.host)
            assertEquals("aMe-8VSlkrbQXpUR", request.header("X-Client-Id"))
            val current = request.header("X-Device-Id")!!
            if (device.isEmpty()) device = current else assertEquals(device, current)
            assertTrue(request.header("X-Device-Sign")!!.startsWith("wdi10.$device"))
            if (request.url.encodedPath.endsWith("/captcha/init")) {
                val json = body(request)
                assertEquals("POST:/v1/auth/signin", text(json, "action"))
                assertEquals("+86 13800138000", text(json["meta"]!!.jsonObject, "phone_number"))
                Reply("""{"captcha_token":"fixture-captcha-token"}""")
            } else {
                assertEquals("fixture-captcha-token", request.header("X-Captcha-Token"))
                assertEquals("+86 13800138000", text(body(request), "username"))
                assertEquals("  fixture password  ", text(body(request), "password"))
                Reply("""{"access_token":"$access"}""")
            }
        })
        val result = Json.parseToJsonElement(api.login("13800138000", "  fixture password  ")).jsonObject
        assertEquals(access, text(result, "access_token"))
        assertEquals(device, text(result, "device_id"))
        assertEquals(2, requests)
    }

    @Test fun guangyaHumanVerificationUrlStopsBeforeSendingPasswordOrSms() = runBlocking {
        var requests = 0
        val api = GuangyaNativeAuth(http { request ->
            requests++
            assertTrue(request.url.encodedPath.endsWith("/captcha/init"))
            assertFalse(body(request).containsKey("password"))
            Reply("""{"captcha_token":"fixture-captcha","url":"https://account.guangyapan.com/verify"}""")
        })
        expect("verification_required") { api.login("fixture", "fixture") }
        expect("verification_required") { api.sendSms("13800138000") }
        assertEquals(2, requests)
    }

    @Test fun guangyaSmsIsVerifiedThenUsedForExistingUserSignInWithoutRegistration() = runBlocking {
        val paths = mutableListOf<String>()
        val api = GuangyaNativeAuth(http { request ->
            paths += request.url.encodedPath
            assertFalse(request.url.encodedPath.contains("signup"))
            val json = body(request)
            when (request.url.encodedPath) {
                "/v1/shield/captcha/init" -> { assertEquals("POST:/v1/auth/verification", text(json, "action")); Reply("""{"captcha_token":"fixture-captcha"}""") }
                "/v1/auth/verification" -> { assertEquals("fixture-captcha", request.header("X-Captcha-Token")); Reply("""{"data":{"verification_id":"fixture-id","is_user":true,"expires_in":120}}""") }
                "/v1/auth/verification/verify" -> { assertEquals("fixture-id", text(json, "verification_id")); assertEquals("123456", text(json, "verification_code")); Reply("""{"verification_token":"fixture-verified-token"}""") }
                "/v1/auth/signin" -> { assertEquals("fixture-verified-token", text(json, "verification_token")); assertFalse(json.containsKey("password")); Reply("""{"data":{"access_token":"$access"}}""") }
                else -> error("Unexpected request")
            }
        })
        val challenge = api.sendSms("13800138000")
        assertEquals(120, challenge.expiresIn)
        assertEquals(access, text(Json.parseToJsonElement(api.loginSms("13800138000", "123456", challenge)).jsonObject, "access_token"))
        assertEquals(4, paths.size)
    }

    @Test fun guangyaSmsNeverUsesChallengeForDifferentPhoneOrDeviceAndNeverRegisters() = runBlocking {
        var requests = 0
        val transport = http { request ->
            requests++
            Reply(if (request.url.encodedPath.endsWith("/captcha/init")) "{}" else
                """{"verification_id":"fixture-id","is_user":false,"expires_in":120}""")
        }
        val api = GuangyaNativeAuth(transport)
        val challenge = api.sendSms("13800138000")
        expect("sms_expired") { api.loginSms("13900139000", "123456", challenge) }
        expect("sms_expired") { GuangyaNativeAuth(transport).loginSms("13800138000", "123456", challenge) }
        expect("account_not_found") { api.loginSms("13800138000", "123456", challenge) }
        assertEquals(2, requests)
    }

    @Test fun xunleiPasswordSessionIsExchangedWithFreshUserIdAndSameDevice() = runBlocking {
        val paths = mutableListOf<String>()
        var device = ""
        val api = XunleiNativeAuth(http { request ->
            paths += request.url.encodedPath
            assertEquals("xluser-ssl.xunlei.com", request.url.host)
            assertTrue(request.header("Cookie").isNullOrBlank())
            val json = body(request)
            when (request.url.encodedPath) {
                "/xluser.core.login/v3/login" -> {
                    assertEquals("fixture-account", text(json, "userName"))
                    assertEquals("  fixture password  ", text(json, "passWord"))
                    device = text(json, "devicesign").removePrefix("div101.").take(32)
                    Reply("""{"errorCode":0,"sessionID":"fixture-session","userID":"fixture-user"}""")
                }
                "/v1/shield/captcha/init" -> {
                    assertEquals(device, text(json, "device_id"))
                    assertEquals("fixture-user", text(json["meta"]!!.jsonObject, "user_id"))
                    assertTrue(text(json["meta"]!!.jsonObject, "captcha_sign").startsWith("1."))
                    Reply("""{"captcha_token":"fixture-captcha"}""")
                }
                "/v1/auth/signin/token" -> {
                    assertEquals(device, request.header("X-Device-Id"))
                    assertEquals("fixture-captcha", request.header("X-Captcha-Token"))
                    assertEquals("fixture-session", text(json, "signin_token"))
                    assertEquals("access_end_point_token", text(json, "provider"))
                    Reply("""{"access_token":"$access"}""")
                }
                else -> error("Unexpected request")
            }
        })
        val result = Json.parseToJsonElement(api.login(" fixture-account ", "  fixture password  ")).jsonObject
        assertEquals(access, text(result, "access_token"))
        assertEquals(device, text(result, "device_id"))
        assertEquals("fixture-user", text(result, "user_id"))
        assertEquals("Xp6vsxz_7IYVw2BB", text(result, "client_id"))
        assertFalse(result.containsKey("captcha_token"))
        assertEquals(3, paths.size)
    }

    @Test fun xunleiReviewPanelRequiresSmsWithoutSendingItAutomatically() = runBlocking {
        var requests = 0
        val api = XunleiNativeAuth(http { request ->
            requests++
            assertEquals("/xluser.core.login/v3/login", request.url.encodedPath)
            Reply("""{"errorCode":1007,"error":"review_panel","reviewurl":"https://xluser-ssl.xunlei.com/verify"}""")
        })
        expect("sms_required") { api.login("fixture", "fixture") }
        assertEquals(1, requests)
    }

    @Test fun xunleiSmsUsesOfficialContextAndNeverRegisters() = runBlocking {
        val paths = mutableListOf<String>()
        var device = ""
        val api = XunleiNativeAuth(http { request ->
            paths += request.url.encodedPath
            val json = body(request)
            when (request.url.encodedPath) {
                "/xluser.core.login/v3/sendsms" -> {
                    assertEquals("0", text(json, "register"))
                    device = text(json, "devicesign")
                    Reply("""{"errorCode":0,"creditkey":"fixture-credit","token":"fixture-sms-token"}""")
                }
                "/xluser.core.login/v3/smslogin" -> {
                    assertEquals(device, text(json, "devicesign"))
                    assertEquals("fixture-credit", text(json, "creditkey"))
                    assertEquals("fixture-sms-token", text(json, "token"))
                    assertEquals("0", text(json, "register"))
                    Reply("""{"errorCode":0,"sessionID":"fixture-session","userID":"fixture-user"}""")
                }
                "/v1/shield/captcha/init" -> Reply("""{"captcha_token":"fixture-captcha"}""")
                "/v1/auth/signin/token" -> Reply("""{"access_token":"$access"}""")
                else -> error("Unexpected request")
            }
        })
        val challenge = api.sendSms("13800138000")
        assertEquals(access, text(Json.parseToJsonElement(api.loginSms("13800138000", "123456", challenge)).jsonObject, "access_token"))
        assertEquals(4, paths.size)
    }

    @Test fun xunleiDoesNotAnnounceSmsSuccessOnRejectedOrMissingChallenge() = runBlocking {
        val rejected = XunleiNativeAuth(http { Reply("""{"errorCode":1,"error":"invalid_phone","errorDesc":"fixture"}""") })
        expect("sms_failed") { rejected.sendSms("13800138000") }
        val incomplete = XunleiNativeAuth(http { Reply("""{"errorCode":0}""") })
        expect("sms_failed") { incomplete.sendSms("13800138000") }
    }

    @Test fun xunleiCaptchaWebChallengeStopsBeforeSessionTokenExchange() = runBlocking {
        var requests = 0
        val api = XunleiNativeAuth(http { request ->
            requests++
            Reply(if (request.url.encodedPath.endsWith("/v3/login"))
                """{"errorCode":0,"sessionID":"fixture-session","userID":"fixture-user"}""" else
                """{"captcha_token":"fixture-token","url":"https://xluser-ssl.xunlei.com/verify"}""")
        })
        expect("verification_required") { api.login("fixture", "fixture") }
        assertEquals(2, requests)
    }

    @Test fun invalidPasswordsAndPhonesAreRejectedBeforeAnyNetworkRequest() = runBlocking {
        var requests = 0
        val transport = http { requests++; Reply("{}") }
        expect("invalid_input") { LanzouNativeAuth(transport).login(" ", "fixture") }
        expect("invalid_input") { IlanzouNativeAuth(transport).login("fixture", "") }
        expect("invalid_input") { GuangyaNativeAuth(transport).sendSms("not-a-phone") }
        expect("invalid_input") { XunleiNativeAuth(transport).sendSms("not-a-phone") }
        assertEquals(0, requests)
    }

    private fun FormBody.names() = (0 until size).map { name(it) }
}
