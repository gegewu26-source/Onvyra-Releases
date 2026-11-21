/* Native sign-in adapted from YunGet ILanzouApi.kt, commit 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors; AGPL-3.0-or-later, see LICENSE. */
package com.constx.onvyra.data.netdisk.auth

import com.constx.onvyra.data.netdisk.*
import com.constx.onvyra.data.netdisk.providers.IlanzouProtocol
import com.constx.onvyra.data.netdisk.providers.LanzouProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class IlanzouNativeAuth(private val http: NetDiskHttp = NetDiskHttp()) {
    private val lock = Mutex()

    suspend fun login(account: String, password: String): String = lock.withLock {
        val name = validNativeAccount(account, password)
        http.clearCookies()
        var challenge: String? = null
        fun url(path: String, uuid: String) = "https://apis.ilanzou.com$path".toHttpUrl().newBuilder()
            .addQueryParameter("uuid", uuid).addQueryParameter("devType", "6").addQueryParameter("devCode", uuid)
            .addQueryParameter("devModel", "chrome").addQueryParameter("devVersion", "125").addQueryParameter("appVersion", "")
            .addQueryParameter("timestamp", IlanzouProtocol.encrypt(System.currentTimeMillis().toString()))
            .addQueryParameter("extra", "2").build().toString()
        suspend fun request(path: String, uuid: String, body: JsonObject? = null): JsonObject {
            repeat(3) {
                val headers = mapOf("Origin" to "https://www.ilanzou.com", "Referer" to "https://www.ilanzou.com/",
                    "Accept" to "application/json, text/plain, */*", "Accept-Language" to "zh-CN,zh;q=0.9",
                    "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                    "sec-ch-ua-mobile" to "?0", "sec-ch-ua-platform" to "\"Windows\"", "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-site") +
                    (challenge?.let { mapOf("Cookie" to "acw_sc__v2=$it") } ?: emptyMap())
                val response = if (body == null) http.get(url(path, uuid), headers, false)
                    else http.postJson(url(path, uuid), body, headers, false)
                val next = LanzouProtocol.challenge(response.body)
                if (next != null) { challenge = next; return@repeat }
                val json = nativeAuthJson(response, "蓝奏优享")
                if (json.authText("code") != "200") {
                    if (json.authText("msg").contains("验证")) throw nativeVerification("蓝奏优享")
                    throw NetDiskException("login_failed", "蓝奏优享登录未成功，请检查账号密码。")
                }
                return json
            }
            throw nativeVerification("蓝奏优享")
        }
        val uuid = request("/unproved/getUuid", "").authText("uuid")
        if (!Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)) throw nativeVerification("蓝奏优享")
        val json = request("/unproved/login", uuid, buildJsonObject { put("loginName", name); put("loginPwd", password) })
        val token = json.authNode().authText("appToken")
        if (!validNativeToken(token)) throw NetDiskException("login_failed", "蓝奏优享未返回有效登录凭据。")
        buildJsonObject { put("appToken", token); put("uuid", uuid) }.toString()
    }
}
