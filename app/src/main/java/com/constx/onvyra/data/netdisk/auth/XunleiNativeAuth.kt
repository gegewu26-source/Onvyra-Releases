/* Account protocol adapted from YunGet XunleiApi.kt/XunleiDeviceFingerprint.kt,
 * commit 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors; AGPL-3.0-or-later, see LICENSE. */
package com.constx.onvyra.data.netdisk.auth

import com.constx.onvyra.data.netdisk.*
import com.constx.onvyra.data.netdisk.providers.XunleiCredential
import com.constx.onvyra.data.netdisk.providers.XunleiProtocol
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class XunleiSmsChallenge internal constructor(
    internal val phone: String,
    internal val creditKey: String,
    internal val token: String,
    internal val device: String,
    internal val expiresAt: Long,
    val expiresIn: Int = 600,
)

class XunleiNativeAuth(private val http: NetDiskHttp = NetDiskHttp()) {
    private val device = UUID.randomUUID().toString().replace("-", "")
    private val peer = UUID.randomUUID().toString().replace("-", "")
    private val deviceSign = "div101.$device" + digest("MD5", digest("SHA-1", device + "com.xunlei.downloadprovider40" + "34a062aaa22f906fca4fefe9fb3a3021"))
    private val lock = Mutex()

    suspend fun login(account: String, password: String): String = lock.withLock {
        val name = validNativeAccount(account, password)
        val reply = post("/xluser.core.login/v3/login", base("25.0.5.25", "513006") + buildJsonObject {
            put("userName", name); put("passWord", password); put("verifyKey", ""); put("verifyCode", ""); put("isMd5Pwd", "0")
        }, LOGIN_UA)
        credential(reply, name)
    }

    suspend fun sendSms(phone: String): XunleiSmsChallenge = lock.withLock {
        val mobile = validatePhone(phone)
        val reply = post("/xluser.core.login/v3/sendsms", base("8.31.0.9726", "231500") + buildJsonObject {
            put("mobile", mobile); put("register", "0")
        }, SMS_UA)
        requireSuccess(reply, sending = true)
        val credit = reply.authText("creditkey")
        val token = reply.authText("token")
        if (credit.isBlank() || token.isBlank() || credit.length > 16384 || token.length > 16384 ||
            credit.any(Char::isISOControl) || token.any(Char::isISOControl))
            throw NetDiskException("sms_failed", "迅雷未确认发送验证码，请重试或使用官方登录。")
        XunleiSmsChallenge(mobile, credit, token, device, System.currentTimeMillis() + 600_000)
    }

    suspend fun loginSms(phone: String, code: String, challenge: XunleiSmsChallenge): String = lock.withLock {
        val mobile = validatePhone(phone)
        if (!Regex("^[0-9]{4,8}$").matches(code.trim())) throw NetDiskException("invalid_input", "请输入4至8位短信验证码。")
        if (mobile != challenge.phone || device != challenge.device || challenge.expiresAt <= System.currentTimeMillis())
            throw NetDiskException("sms_expired", "手机号已更改或验证码已过期，请重新获取。")
        val reply = post("/xluser.core.login/v3/smslogin", base("8.31.0.9726", "231500", challenge.creditKey) + buildJsonObject {
            put("mobile", mobile); put("smsCode", code.trim()); put("token", challenge.token); put("register", "0")
        }, SMS_UA)
        credential(reply, mobile)
    }

    private suspend fun credential(reply: JsonObject, account: String): String {
        requireSuccess(reply)
        val session = reply.authText("sessionID")
        val user = reply.authText("userID")
        if (session.isBlank() || session.length > 16384 || user.isBlank() || user.length > 256)
            throw NetDiskException("login_failed", "迅雷未返回有效账号会话，请重试或使用官方登录。")
        val timestamp = System.currentTimeMillis()
        val signature = XunleiProtocol.captchaSign(XunleiCredential("", device, "", user, CLIENT), timestamp)
        val init = post("/v1/shield/captcha/init", buildJsonObject {
            put("action", "POST:/auth/signin/token"); put("captcha_token", ""); put("client_id", CLIENT); put("device_id", device)
            put("meta", buildJsonObject {
                put("username", account); put("client_version", "8.31.0.9726"); put("package_name", "com.xunlei.downloadprovider")
                put("timestamp", timestamp.toString()); put("captcha_sign", signature); put("user_id", user)
            })
            put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
        }, APP_UA, accountHeaders())
        if (init.authText("url").isNotBlank() || init.authText("error").contains("captcha", true)) throw nativeVerification("迅雷")
        val captcha = init.authText("captcha_token")
        if (captcha.isBlank() || captcha.length > 16384) throw nativeVerification("迅雷")
        val exchanged = post("/v1/auth/signin/token", buildJsonObject {
            // Public SDK client identifiers used by the upstream login protocol; not user credentials.
            put("client_id", CLIENT); put("client_secret", "Xp6vsy4tN9toTVdMSpomVdXpRmES")
            put("provider", "access_end_point_token"); put("signin_token", session)
        }, APP_UA, accountHeaders() + ("X-Captcha-Token" to captcha))
        if (exchanged.authText("error").contains("captcha", true)) throw nativeVerification("迅雷")
        val node = exchanged.authNode()
        val access = node.authText("access_token").ifBlank { node.authText("accessToken") }
        if (!validNativeToken(access)) throw NetDiskException("login_failed", "迅雷尚未完成登录，请重试或使用官方登录。")
        return buildJsonObject {
            put("access_token", access); put("device_id", device); put("user_id", user); put("client_id", CLIENT)
            // The token-exchange captcha is action-scoped; future drive calls obtain their own captcha.
            node.authText("refresh_token").ifBlank { node.authText("refreshToken") }.takeIf(::validNativeToken)?.let { put("refresh_token", it) }
        }.toString()
    }

    private suspend fun post(path: String, body: Map<String, JsonElement>, ua: String, extra: Map<String, String> = emptyMap()): JsonObject =
        nativeAuthJson(http.postJson("https://xluser-ssl.xunlei.com$path", JsonObject(body),
            mapOf("User-Agent" to ua, "Accept" to "application/json;charset=UTF-8", "Cookie" to "") + extra, false), "迅雷")

    private fun requireSuccess(json: JsonObject, sending: Boolean = false) {
        if (json.authText("errorCode") == "0" || json.authText("error") == "success") return
        val error = json.authText("error")
        if (error == "review_panel" || json.authText("errorCode") == "1007" || json.authText("verifyType").isNotBlank())
            throw NetDiskException("sms_required", "迅雷要求短信验证，请切换验证码登录。")
        if (error.contains("captcha", true) || json.authText("reviewurl").isNotBlank()) throw nativeVerification("迅雷")
        throw NetDiskException(if (sending) "sms_failed" else "login_failed", if (sending)
            "迅雷未能发送验证码，请稍后重试或使用官方登录。" else "迅雷账号密码或验证码不正确，请检查后重试。")
    }

    private fun base(version: String, sdk: String, credit: String = "") = buildJsonObject {
        put("protocolVersion", "301"); put("sequenceNo", Random.nextLong(10_000_000, 99_999_999).toString())
        put("platformVersion", "10"); put("isCompressed", "0"); put("appid", "40"); put("clientVersion", version)
        put("peerID", peer); put("appName", "ANDROID-com.xunlei.downloadprovider"); put("sdkVersion", sdk)
        put("devicesign", deviceSign); put("netWorkType", "WIFI"); put("providerName", "NONE")
        put("deviceModel", "M2004J7AC"); put("deviceName", "Xiaomi_M2004j7ac"); put("OSVersion", "12")
        put("creditkey", credit); put("hl", "zh-CN")
    }

    private fun accountHeaders() = mapOf("X-Client-Id" to CLIENT, "X-Device-Id" to device, "X-Client-Version" to "8.31.0.9726")

    private fun validatePhone(raw: String): String {
        val value = raw.trim()
        if (!Regex("^1[3-9][0-9]{9}$").matches(value)) throw NetDiskException("invalid_input", "请输入有效的11位手机号。")
        return value
    }

    private companion object {
        const val CLIENT = "Xp6vsxz_7IYVw2BB"
        const val LOGIN_UA = "android-ok-http-client/xl-acc-sdk/version-5.1.3.513006"
        const val SMS_UA = "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000"
        const val APP_UA = "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 netWorkType/5G appid/40 deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/12 protocolVersion/301 platformVersion/10 sdkVersion/512000 Oauth2Client/0.9 (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)"
        fun digest(algorithm: String, value: String) = MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
