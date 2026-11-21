/* Native sign-in adapted from YunGet LanzouApi.kt, commit 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors; AGPL-3.0-or-later, see LICENSE. */
package com.constx.onvyra.data.netdisk.auth

import com.constx.onvyra.data.netdisk.*
import com.constx.onvyra.data.netdisk.providers.LanzouProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only official HTTPS login endpoints receive a password; cookies never leave their domain. */
class LanzouNativeAuth(private val http: NetDiskHttp = NetDiskHttp()) {
    private val lock = Mutex()

    suspend fun login(account: String, password: String): String = lock.withLock {
        val name = validNativeAccount(account, password)
        http.clearCookies()
        val cookies = mutableListOf<Cookie>()
        fun capture(response: NetDiskResponse) {
            for (cookie in Cookie.parseAll(response.url.toHttpUrl(), response.headers)) {
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
            }
        }
        fun cookieHeader(url: String): String = cookies.filter { it.matches(url.toHttpUrl()) && it.expiresAt > System.currentTimeMillis() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        suspend fun get(url: String): NetDiskResponse {
            requireOfficial(url)
            return http.get(url, mapOf("Cookie" to cookieHeader(url), "Referer" to LOGIN_PAGE), false).also(::capture)
        }
        // Establish the website's own session before submitting the form.
        get(LOGIN_PAGE)
        var challenge: String? = null
        var loginResponse: NetDiskResponse? = null
        for (attempt in 0 until 4) {
            val value = cookieHeader(ACCOUNTS_URL)
            val response = http.postForm(ACCOUNTS_URL, mapOf("task" to "uselogin", "username" to name,
                "password" to password, "ref" to "pc.woozooo.com"), mapOf(
                "Origin" to "https://accounts.woozooo.com", "Referer" to LOGIN_PAGE,
                "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Cookie" to (value + challenge?.let { "; acw_sc__v2=$it" }.orEmpty()).trimStart(';', ' ')), false)
            capture(response)
            val next = LanzouProtocol.challenge(response.body)
            if (next == null) { loginResponse = response; break }
            challenge = next
        }
        val response = loginResponse ?: throw nativeVerification("蓝奏云")
        val json = nativeAuthJson(response, "蓝奏云")
        if ((json["zt"] as? JsonPrimitive)?.contentOrNull != "1") {
            val message = (json["msgs"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (listOf("验证", "captcha", "verify").any { message.contains(it, true) }) throw nativeVerification("蓝奏云")
            throw NetDiskException("login_failed", "蓝奏云账号或密码不正确，请检查后重试。")
        }
        var redirect = (json["msgs"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        var completed = redirect.isBlank()
        repeat(6) {
            if (!completed) {
                val reply = get(redirect)
                val location = reply.headers["Location"]
                if (reply.status in 300..399 && !location.isNullOrBlank()) {
                    redirect = reply.url.toHttpUrl().resolve(location)?.toString() ?: throw nativeVerification("蓝奏云")
                } else if (reply.status in 200..299) completed = true
                else throw nativeVerification("蓝奏云")
            }
        }
        if (!completed) throw nativeVerification("蓝奏云")
        // PHPSESSID and the following directory's vei must originate in the same session.
        get(FILES_URL)
        val result = cookieHeader(FILES_URL)
        val names = result.split(';').map { it.trim().substringBefore('=') }.toSet()
        if (!names.containsAll(setOf("ylogin", "phpdisk_info")))
            throw NetDiskException("login_failed", "蓝奏云未返回有效登录凭据，请重试或使用官方登录。")
        result
    }

    private fun requireOfficial(url: String) {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || parsed.scheme != "https" || parsed.port != 443 || parsed.username.isNotBlank() ||
            parsed.password.isNotBlank() || parsed.host !in setOf("accounts.woozooo.com", "pc.woozooo.com", "www.lanzou.com"))
            throw NetDiskException("verification_required", "蓝奏云返回了其他登录入口，请使用官方页面继续。")
    }

    private companion object {
        const val ACCOUNTS_URL = "https://accounts.woozooo.com/accounts.php"
        const val LOGIN_PAGE = "$ACCOUNTS_URL?action=login&ref=pc.woozooo.com"
        const val FILES_URL = "https://pc.woozooo.com/mydisk.php?item=files&action=index"
    }
}
