/* Account protocol adapted from YunGet GuangYaApi.kt/GuangYaConstants.kt,
 * commit 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors; AGPL-3.0-or-later, see LICENSE. */
package com.constx.onvyra.data.netdisk.auth

import com.constx.onvyra.data.netdisk.*
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class GuangyaSmsChallenge internal constructor(
    internal val verificationId: String,
    internal val phone: String,
    internal val device: String,
    internal val expiresAt: Long,
    val expiresIn: Int,
    val isUser: Boolean?,
)

/** A single instance retains one device across the complete send/verify/sign-in flow. No registration API is called. */
class GuangyaNativeAuth(private val http: NetDiskHttp = NetDiskHttp()) {
    private val device = UUID.randomUUID().toString().replace("-", "")
    private val deviceSign = "wdi10.$device${UUID.randomUUID().toString().replace("-", "")}"
    private val lock = Mutex()

    suspend fun login(account: String, password: String): String = lock.withLock {
        val normalized = normalize(validNativeAccount(account, password))
        val captcha = captcha("POST:/v1/auth/signin", buildJsonObject {
            put(when { normalized.startsWith('+') -> "phone_number"; normalized.contains('@') -> "email"; else -> "username" }, normalized)
        })
        val reply = post("/v1/auth/signin", buildJsonObject {
            put("client_id", CLIENT); put("username", normalized); put("password", password)
        }, captcha)
        credential(reply)
    }

    suspend fun sendSms(phone: String): GuangyaSmsChallenge = lock.withLock {
        val normalized = normalizePhone(phone)
        val captcha = captcha("POST:/v1/auth/verification", buildJsonObject { put("phone_number", normalized) })
        val reply = post("/v1/auth/verification", buildJsonObject {
            put("client_id", CLIENT); put("phone_number", normalized); put("target", "ANY")
        }, captcha, includeEmptyCaptcha = true)
        val node = reply.authNode()
        val id = node.authText("verification_id").ifBlank { reply.authText("verification_id") }
        if (id.isBlank() || id.length > 8192 || id.any(Char::isISOControl))
            throw NetDiskException("sms_failed", "光鸭未确认发送验证码，请稍后重试。")
        val expires = (node["expires_in"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 1800) ?: 600
        GuangyaSmsChallenge(id, normalized, device, System.currentTimeMillis() + expires * 1000L, expires,
            (node["is_user"] as? JsonPrimitive)?.booleanOrNull ?: (reply["is_user"] as? JsonPrimitive)?.booleanOrNull)
    }

    suspend fun loginSms(phone: String, code: String, challenge: GuangyaSmsChallenge): String = lock.withLock {
        val normalized = normalizePhone(phone)
        if (!Regex("^[0-9]{4,8}$").matches(code.trim())) throw NetDiskException("invalid_input", "请输入4至8位短信验证码。")
        if (challenge.phone != normalized || challenge.device != device || challenge.expiresAt <= System.currentTimeMillis())
            throw NetDiskException("sms_expired", "手机号已更改或验证码已过期，请重新获取。")
        if (challenge.isUser == false) throw NetDiskException("account_not_found", "该手机号尚无光鸭账号，请先在官方页面注册。")
        val verified = post("/v1/auth/verification/verify", buildJsonObject {
            put("client_id", CLIENT); put("verification_id", challenge.verificationId); put("verification_code", code.trim())
        })
        val verificationToken = verified.authNode().authText("verification_token").ifBlank { verified.authText("verification_token") }
        if (verificationToken.isBlank() || verificationToken.length > 16384)
            throw NetDiskException("sms_failed", "短信验证码错误或已过期，请重新获取。")
        val reply = post("/v1/auth/signin", buildJsonObject {
            put("client_id", CLIENT); put("username", normalized); put("verification_code", code.trim())
            put("verification_token", verificationToken)
        })
        credential(reply)
    }

    private suspend fun captcha(action: String, meta: JsonObject): String {
        val reply = post("/v1/shield/captcha/init", buildJsonObject {
            put("client_id", CLIENT); put("action", action); put("device_id", device); put("captcha_token", ""); put("meta", meta)
        })
        if (reply.authNode().authText("url").isNotBlank() || reply.authText("url").isNotBlank()) throw nativeVerification("光鸭")
        return reply.authNode().authText("captcha_token").ifBlank { reply.authText("captcha_token") }
    }

    private suspend fun post(path: String, body: JsonObject, captcha: String? = null, includeEmptyCaptcha: Boolean = false): JsonObject {
        val headers = buildMap {
            put("Cookie", ""); put("X-Client-Id", CLIENT); put("X-Client-Version", "0.0.1")
            put("X-Device-Id", device); put("X-Device-Sign", URLEncoder.encode(deviceSign, "UTF-8"))
            put("X-Device-Model", "chrome%2F131.0.0.0"); put("X-Device-Name", "PC-Chrome")
            put("X-Os-Version", "Win32"); put("X-Platform-Version", "1"); put("X-Protocol-Version", "301")
            put("X-Sdk-Version", "9.1.3"); put("Accept", "application/json, text/plain, */*")
            if (!captcha.isNullOrBlank() || includeEmptyCaptcha) put("X-Captcha-Token", captcha.orEmpty())
        }
        val reply = nativeAuthJson(http.postJson("https://account.guangyapan.com$path", body, headers, false), "光鸭")
        val error = reply.authText("error").ifBlank { reply.authText("code") }
        if (error.contains("captcha", true) || error.contains("verification_required", true)) throw nativeVerification("光鸭")
        if (error.isNotBlank() && error != "0" || (reply["success"] as? JsonPrimitive)?.booleanOrNull == false) {
            val reason = if (error.contains("frequent", true) || error.contains("many_requests", true)) "rate_limited" else "login_failed"
            throw NetDiskException(reason, if (reason == "rate_limited") "请求过于频繁，请稍后重试。" else
                if (path.contains("verification")) "验证码操作未成功，请检查手机号和验证码后重试。" else "光鸭账号或密码不正确，请重试或使用其他方式。")
        }
        return reply
    }

    private fun credential(json: JsonObject): String {
        val node = json.authNode()
        val token = node.authText("access_token").ifBlank { node.authText("accessToken") }
        if (!validNativeToken(token)) throw NetDiskException("login_failed", "光鸭未返回有效登录凭据。")
        return buildJsonObject {
            put("access_token", token); put("device_id", device)
            node.authText("refresh_token").ifBlank { node.authText("refreshToken") }.takeIf(::validNativeToken)?.let { put("refresh_token", it) }
        }.toString()
    }

    private fun normalizePhone(raw: String): String {
        val normalized = normalize(raw)
        if (!Regex("^\\+86 1[3-9][0-9]{9}$|^\\+[1-9][0-9]{7,14}$").matches(normalized))
            throw NetDiskException("invalid_input", "请输入有效手机号。")
        return normalized
    }

    private fun normalize(raw: String): String {
        val value = raw.trim()
        val digits = value.filter(Char::isDigit)
        return when {
            value.startsWith("+86") -> "+86 " + digits.removePrefix("86")
            value.length == 11 && value.all(Char::isDigit) -> "+86 $value"
            else -> value
        }
    }

    private companion object { const val CLIENT = "aMe-8VSlkrbQXpUR" }
}
