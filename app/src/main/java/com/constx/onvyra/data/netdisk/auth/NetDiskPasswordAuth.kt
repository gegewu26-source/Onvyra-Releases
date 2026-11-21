/* Native 123 sign-in adapted from YunGet Pan123Api.kt (845932c48a04e5092bc6795e4dcb0fd67d202f10).
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors; AGPL-3.0-or-later, see LICENSE. */
package com.constx.onvyra.data.netdisk.auth

import com.constx.onvyra.data.netdisk.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Passwords are used for this single HTTPS request and are never persisted or logged. */
class NetDiskPasswordAuth(private val http: NetDiskHttp = NetDiskHttp()) {
    suspend fun login123(account: String, password: String): String = withContext(Dispatchers.IO) {
        val name = account.trim()
        if (name.isEmpty() || password.isEmpty() || name.length > 254 || password.length > 256)
            throw NetDiskException("invalid_input", "请输入有效账号和密码。")
        val response = http.postJson("https://user.123pan.cn/api/user/sign_in", buildJsonObject {
            put("passport", name); put("password", password); put("remember", false)
        }, mapOf("platform" to "web", "app-version" to "132", "loginuuid" to UUID.randomUUID().toString(),
            "Origin" to "https://user.123pan.cn", "Referer" to "https://user.123pan.cn/", "Cookie" to ""),
            followRedirects = false)
        val root = runCatching { response.json() }.getOrNull()
        val code = (root?.get("code") as? JsonPrimitive)?.intOrNull
        val token = (((root?.get("data") as? JsonObject)?.get("token")) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (response.status in 200..299 && code == 200 && token.length in 16..16384 &&
            token.none(Char::isISOControl)) return@withContext token
        val message = (root?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (response.status in listOf(403, 429) || listOf("验证码", "验证", "风控", "captcha", "verify").any { message.contains(it, true) })
            throw NetDiskException("verification_required", "官方要求进一步验证，请完成安全验证后继续。")
        if (response.status in 300..399 || root == null)
            throw NetDiskException("verification_required", "官方登录页面需要确认，请完成安全验证后继续。")
        throw NetDiskException("login_failed", "登录未成功，请检查账号密码，或切换验证码登录。")
    }
}
