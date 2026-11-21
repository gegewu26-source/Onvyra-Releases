/* Read-only personal-drive protocol adapted from YunGet XunleiApi.kt,
 * commit 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors; AGPL-3.0-or-later, see LICENSE.
 * Only existing-file directory and download-detail operations are retained. No cloud mutation or share creation. */
package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.providers.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class XunleiAccountBrowser(private val http: NetDiskHttp) {
    private val fallbackDevice = UUID.randomUUID().toString().replace("-", "")
    private data class Captcha(val value: String, val expires: Long)
    private data class Page(val number: Int, val token: String, val previous: String)
    private val captchas = ConcurrentHashMap<String, Captcha>()

    suspend fun list(credential: String, directoryId: String = "", cursor: String? = null): NetDiskListing {
        val auth = auth(credential)
        val parent = directoryId.takeIf(String::isNotBlank)?.also(::validId).orEmpty()
        val page = page(cursor, parent, auth)
        val url = "$API/drive/v1/files".toHttpUrl().newBuilder()
            .addQueryParameter("parent_id", parent).addQueryParameter("page_token", page.token)
            .addQueryParameter("limit", "100").addQueryParameter("with_audit", "true")
            .addQueryParameter("filters", """{"trashed":{"eq":false}}""")
            .addQueryParameter("thumbnail_size", "SIZE_SMALL").build().toString()
        val json = get(url, auth)
        val rows = json["files"] as? JsonArray
            ?: throw NetDiskException("invalid_response", "迅雷未返回本人文件列表，请刷新或重新登录。")
        val files = rows.map { element ->
            val row = element as? JsonObject ?: throw NetDiskException("invalid_response", "迅雷返回的文件记录无效。")
            val file = XunleiProtocol.file(row) ?: throw NetDiskException("invalid_response", "迅雷文件编号缺失。")
            validId(file.id)
            file.copy(mimeType = row.text("mime_type").ifBlank { "application/octet-stream" })
        }.distinctBy(NetDiskFile::id)
        val next = json.text("next_page_token")
        if (next.length > 8192 || next.any(Char::isISOControl)) throw NetDiskException("invalid_cursor", "迅雷分页信息异常，请刷新目录。")
        val signature = XunleiProtocol.digest(files.joinToString("\n") { it.id })
        if (next.isNotBlank() && (next == page.token || files.isNotEmpty() && signature == page.previous))
            throw NetDiskException("pagination_loop", "迅雷目录分页重复，请刷新当前目录。")
        if (next.isNotBlank() && page.number >= 100)
            throw NetDiskException("pagination_limit", "迅雷目录已达本次分页上限，请在官网查看剩余内容。")
        return NetDiskListing(files, if (next.isBlank()) null else cursor(parent, auth, Page(page.number + 1, next, signature)),
            if (parent.isEmpty()) "我的文件" else null)
    }

    suspend fun resolve(file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        validId(file.id)
        val auth = auth(credential)
        val url = "$API/drive/v1/files".toHttpUrl().newBuilder().addPathSegment(file.id)
            .addQueryParameter("_magic", "2021").addQueryParameter("usage", "PLAY")
            .addQueryParameter("thumbnail_size", "SIZE_LARGE").addQueryParameter("with", "task").build().toString()
        val json = get(url, auth)
        val value = XunleiProtocol.directUrl(json)
        if (value.isBlank()) throw NetDiskException("download_unavailable", "迅雷没有返回此文件的下载地址，请在官网确认下载权限。")
        return NetDiskDownload(ndDownloadUrl(value), file.name, file.mimeType,
            mapOf("User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Referer" to "https://pan.xunlei.com/"),
            "本人网盘文件；下载链接具有时效，速度受网盘账号与流量规则影响。")
    }

    private fun auth(credential: String): XunleiCredential {
        val auth = XunleiProtocol.credential(credential, fallbackDevice)
        if (auth.access.length !in 16..16384 || auth.access.any { it.isISOControl() || it.isWhitespace() } ||
            !Regex("^[A-Za-z0-9_-]{8,128}$").matches(auth.device))
            throw NetDiskException("auth_required", "迅雷登录信息无效，请重新登录。")
        return auth
    }

    private suspend fun get(url: String, auth: XunleiCredential): JsonObject {
        val action = "GET:${url.toHttpUrl().encodedPath}"
        val key = auth.key + ":" + action
        var captcha = captchas[key]?.takeIf { it.expires > System.currentTimeMillis() }?.value
            ?: auth.captcha.takeIf(String::isNotBlank) ?: initCaptcha(auth, action)
        repeat(2) { attempt ->
            val response = http.get(url, mapOf(
                "Authorization" to "Bearer ${auth.access}", "X-Device-Id" to auth.device,
                "X-Client-Version" to if (auth.client == XunleiProtocol.WEB_CLIENT) "1.93.10" else "8.31.0.9726",
                "X-Captcha-Token" to captcha, "User-Agent" to NetDiskHttp.WEB_USER_AGENT,
                "Origin" to "https://pan.xunlei.com", "Referer" to "https://pan.xunlei.com/", "Cookie" to ""), false)
            if (response.status == 401) throw NetDiskException("auth_required", "迅雷登录已失效，请重新登录。")
            val json = runCatching { response.json() }.getOrNull()
                ?: throw NetDiskException("invalid_response", "迅雷没有返回有效文件数据，请重试或在官方页面完成验证。")
            val error = json.text("error")
            if (response.status in 200..299 && error.isBlank()) return json["data"] as? JsonObject ?: json
            if (error in setOf("captcha_invalid", "captcha_required") && attempt == 0) {
                captchas.remove(key)
                captcha = initCaptcha(auth, action)
                return@repeat
            }
            val reason = when {
                response.status == 401 || error in setOf("unauthenticated", "invalid_token", "token_expired") -> "auth_required"
                error.contains("captcha", true) || error.contains("verification", true) -> "verification_required"
                response.status == 429 || error.contains("limit", true) -> "rate_limited"
                else -> "provider_error"
            }
            throw NetDiskException(reason, when (reason) {
                "auth_required" -> "迅雷登录已失效，请重新登录。"
                "verification_required" -> "迅雷要求进一步安全验证，请在官方页面完成后重试。"
                "rate_limited" -> "迅雷请求过于频繁，请稍后重试。"
                else -> "迅雷未能读取本人文件，请重试或重新登录。"
            })
        }
        throw NetDiskException("verification_required", "迅雷验证未完成，请在官方页面完成后重试。")
    }

    private suspend fun initCaptcha(auth: XunleiCredential, action: String): String {
        val now = System.currentTimeMillis()
        val web = auth.client == XunleiProtocol.WEB_CLIENT
        val version = if (web) "1.93.10" else "8.31.0.9726"
        val body = buildJsonObject {
            put("client_id", auth.client); put("action", action); put("device_id", auth.device); put("captcha_token", "")
            if (!web) put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
            put("meta", buildJsonObject {
                put("client_version", version); put("package_name", if (web) "pan.xunlei.com" else "com.xunlei.downloadprovider")
                put("timestamp", now.toString()); put("captcha_sign", XunleiProtocol.captchaSign(auth, now)); put("user_id", auth.user)
                if (web) { put("username", ""); put("phone_number", ""); put("email", "") }
            })
        }
        val response = http.postJson("https://xluser-ssl.xunlei.com/v1/shield/captcha/init", body, mapOf(
            "X-Client-Id" to auth.client, "X-Device-Id" to auth.device, "X-Client-Version" to version,
            "User-Agent" to if (web) NetDiskHttp.WEB_USER_AGENT else APP_UA,
            "Origin" to "https://pan.xunlei.com", "Referer" to "https://pan.xunlei.com/", "Cookie" to ""), false)
        val json = runCatching { response.json() }.getOrNull()
        val token = json?.text("captcha_token").orEmpty()
        if (response.status !in 200..299 || token.isBlank() || token.length > 16384 || token.any(Char::isISOControl) ||
            json?.text("url")?.isNotBlank() == true || json?.text("error")?.isNotBlank() == true)
            throw NetDiskException("verification_required", "迅雷要求官方安全验证，请完成后重新登录。")
        val ttl = (json?.get("expires_in") as? JsonPrimitive)?.longOrNull?.coerceIn(1, 3600) ?: 300
        while (captchas.size >= 64) captchas.keys.firstOrNull()?.let(captchas::remove)
        captchas[auth.key + ":" + action] = Captcha(token, now + (ttl * 1000 - 30_000).coerceAtLeast(1000))
        return token
    }

    private fun page(cursor: String?, parent: String, auth: XunleiCredential): Page {
        if (cursor == null) return Page(1, "", "")
        val json = runCatching {
            require(cursor.length in 1..16384)
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: throw NetDiskException("invalid_cursor", "迅雷分页信息无效，请刷新当前目录。")
        val number = (json["page"] as? JsonPrimitive)?.intOrNull ?: 0
        val token = json.text("token")
        if (json.text("directory") != parent || json.text("account") != XunleiProtocol.digest(auth.key) || number !in 2..100 ||
            token.isBlank() || token.length > 8192 || token.any(Char::isISOControl))
            throw NetDiskException("invalid_cursor", "迅雷分页与当前账号或目录不符，请刷新目录。")
        return Page(number, token, json.text("previous"))
    }

    private fun cursor(parent: String, auth: XunleiCredential, page: Page): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(buildJsonObject {
            put("directory", parent); put("account", XunleiProtocol.digest(auth.key)); put("page", page.number)
            put("token", page.token); put("previous", page.previous)
        }.toString().toByteArray(Charsets.UTF_8))

    private fun validId(value: String) {
        if (!Regex("^[A-Za-z0-9_-]{1,256}$").matches(value))
            throw NetDiskException("invalid_directory", "迅雷文件或目录编号无效，请刷新目录。")
    }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private companion object {
        const val API = "https://api-pan.xunlei.com"
        const val APP_UA = "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 netWorkType/5G appid/40 deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/12 protocolVersion/301 platformVersion/10 sdkVersion/512000 Oauth2Client/0.9 (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)"
    }
}
