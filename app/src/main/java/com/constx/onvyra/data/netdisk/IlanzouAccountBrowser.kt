/* Read-only personal-drive protocol adapted from YunGet ILanzouApi.kt, commit
 * 845932c48a04e5092bc6795e4dcb0fd67d202f10; Copyright (C) 2026 CYQawa,
 * jiayuxuan123 and contributors. AGPL-3.0-or-later; see LICENSE.
 * Only listing, account metadata and an existing file's download URL are exposed. */
package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.providers.IlanzouProtocol
import com.constx.onvyra.data.netdisk.providers.LanzouProtocol
import java.security.MessageDigest
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Uses the native login's appToken and matching device uuid, without needing a website session. */
class IlanzouAccountBrowser(private val http: NetDiskHttp) {
    private data class Credential(val token: String, val uuid: String)
    private data class Page(val number: Int, val previous: String? = null)

    suspend fun list(credential: String, directoryId: String = "", cursor: String? = null): NetDiskListing {
        val auth = credential(credential)
        val parent = directory(directoryId)
        val page = page(cursor, parent)
        val json = check(request("/proved/record/file/list", auth, mapOf(
            "offset" to page.number.toString(), "limit" to PAGE_SIZE.toString(), "folderId" to parent, "type" to "0")))
        val raw = json["list"] as? JsonArray
            ?: throw NetDiskException("invalid_response", "蓝奏优享未返回可识别的本人文件目录，请刷新重试。")
        val files = raw.map { element ->
            val item = element as? JsonObject ?: invalidEntry()
            val file = IlanzouProtocol.parseItem(item) ?: invalidEntry()
            if (!Regex("^[df]:[0-9]{1,24}$").matches(file.id)) invalidEntry()
            file.copy(extra = JsonObject(file.extra + ("parent_id" to JsonPrimitive(parent))))
        }.distinctBy(NetDiskFile::id)
        val signature = signature(files)
        if (files.isNotEmpty() && signature == page.previous)
            throw NetDiskException("pagination_loop", "蓝奏优享文件分页重复，请刷新当前目录后重试。")
        val total = (json["totalPage"] as? JsonPrimitive)?.intOrNull ?: 0
        val more = files.isNotEmpty() && if (total > 0) page.number < total else raw.size >= PAGE_SIZE
        if (more && page.number == MAX_PAGES)
            throw NetDiskException("pagination_limit", "蓝奏优享目录较大，已达本次分页上限，请到官网查看剩余文件。")
        return NetDiskListing(files, "iz:$parent:${page.number + 1}:$signature".takeIf { more },
            if (parent == "0") "我的文件" else null)
    }

    suspend fun resolve(file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        val auth = credential(credential)
        val id = file.id.removePrefix("f:")
        if (!Regex("^[0-9]{1,24}$").matches(id) || id.all { it == '0' })
            throw NetDiskException("invalid_file", "蓝奏优享文件编号无效，请刷新目录。")
        val user = text(file.extra, "userId").ifBlank {
            val account = check(request("/proved/user/account/map", auth))
            val map = account["map"] as? JsonObject ?: (account["data"] as? JsonObject)?.get("map") as? JsonObject
            map?.let { text(it, "userId") }.orEmpty()
        }
        if (!Regex("^[0-9]{1,24}$").matches(user) || user.all { it == '0' })
            throw NetDiskException("invalid_response", "蓝奏优享未返回本人账号信息，请重新登录后重试。")
        val now = System.currentTimeMillis().toString()
        val response = request("/unproved/file/redirect", auth, mapOf(
            "enable" to "1", "downloadId" to IlanzouProtocol.encrypt("$id|$user"),
            "auth" to IlanzouProtocol.encrypt("$id|$now")), extra = false, timestamp = now)
        val target = if (response.status in 300..399) response.headers["Location"].orEmpty() else {
            val json = check(response)
            text(json, "url").ifBlank { (json["data"] as? JsonObject)?.let { text(it, "url") }.orEmpty() }
        }
        if (target.isBlank()) throw NetDiskException("download_unavailable", "蓝奏优享未返回下载地址，请在官网确认文件权限。")
        return NetDiskDownload(LanzouProtocol.absolute(API, target), file.name, file.mimeType,
            mapOf("User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Referer" to "https://www.ilanzou.com/"))
    }

    private suspend fun request(path: String, auth: Credential, params: Map<String, String> = emptyMap(),
        extra: Boolean = true, timestamp: String = System.currentTimeMillis().toString()): NetDiskResponse {
        val url = "$API$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("uuid", auth.uuid); addQueryParameter("devCode", auth.uuid)
            addQueryParameter("devType", "6"); addQueryParameter("devModel", "chrome")
            addQueryParameter("devVersion", "125"); addQueryParameter("appVersion", "")
            addQueryParameter("timestamp", IlanzouProtocol.encrypt(timestamp)); addQueryParameter("appToken", auth.token)
            if (extra) addQueryParameter("extra", "2")
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
        var challenge: String? = null
        repeat(3) {
            val response = http.get(url, buildMap {
                put("Origin", "https://www.ilanzou.com"); put("Referer", "https://www.ilanzou.com/")
                put("Accept", "application/json, text/plain, */*"); put("Accept-Language", "zh-CN,zh;q=0.9")
                put("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                put("sec-ch-ua-mobile", "?0"); put("sec-ch-ua-platform", "\"Windows\"")
                put("Sec-Fetch-Dest", "empty"); put("Sec-Fetch-Mode", "cors"); put("Sec-Fetch-Site", "same-site")
                challenge?.let { put("Cookie", "acw_sc__v2=$it") }
            }, followRedirects = false)
            val next = LanzouProtocol.challenge(response.body)
            if (next == null) return response
            challenge = next
        }
        throw NetDiskException("verification_required", "蓝奏优享需要完成官方验证，请到官方登录页继续。")
    }

    private fun check(response: NetDiskResponse): JsonObject {
        if (response.status in setOf(401, 403)) authExpired()
        if (response.status == 429) throw NetDiskException("rate_limited", "蓝奏优享请求过于频繁，请稍后重试。")
        if (response.status !in 200..299) throw NetDiskException("provider_error", "蓝奏优享读取失败，请稍后重试。")
        if (response.body.trimStart().startsWith('<'))
            throw NetDiskException("verification_required", "蓝奏优享需要完成官方验证，请到官方登录页继续。")
        val json = response.json()
        val code = text(json, "code")
        if (code in setOf("-1", "-2", "401", "403")) authExpired()
        if (code != "200") throw NetDiskException("provider_error", "蓝奏优享未确认读取成功，请刷新重试。")
        return json
    }

    private fun credential(raw: String): Credential {
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: authExpired()
        val node = root["data"] as? JsonObject ?: root
        val token = text(node, "appToken")
        val uuid = text(node, "uuid")
        if (token.length !in 16..16384 || token.any { it.code !in 33..126 } ||
            !Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)) authExpired()
        return Credential(token, uuid)
    }

    private fun directory(raw: String): String {
        val id = raw.ifBlank { "0" }.removePrefix("d:")
        if (!Regex("^[0-9]{1,24}$").matches(id))
            throw NetDiskException("invalid_directory", "蓝奏优享目录编号无效，请返回根目录。")
        return id
    }
    private fun page(cursor: String?, parent: String): Page {
        if (cursor == null) return Page(1)
        val parts = cursor.split(':')
        val page = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 4 || parts[0] != "iz" || parts[1] != parent || page == null || page !in 2..MAX_PAGES ||
            !Regex("^[0-9a-f]{16}$").matches(parts[3]))
            throw NetDiskException("invalid_cursor", "蓝奏优享分页信息无效，请刷新当前目录。")
        return Page(page, parts[3])
    }
    private fun signature(files: List<NetDiskFile>): String = MessageDigest.getInstance("SHA-256")
        .digest(files.map(NetDiskFile::id).sorted().joinToString("\n").toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
    private fun text(json: JsonObject, key: String): String = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun authExpired(): Nothing = throw NetDiskException("auth_required", "蓝奏优享登录信息无效或已过期，请重新登录。")
    private fun invalidEntry(): Nothing = throw NetDiskException("invalid_response", "蓝奏优享返回的目录项目不完整，请刷新重试。")
    private companion object { const val API = "https://apis.ilanzou.com"; const val PAGE_SIZE = 60; const val MAX_PAGES = 1000 }
}
