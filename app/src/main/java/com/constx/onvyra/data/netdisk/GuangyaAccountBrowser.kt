/* Read-only personal-drive protocol adapted from YunGet GuangYaApi.kt, commit
 * 845932c48a04e5092bc6795e4dcb0fd67d202f10; Copyright (C) 2026 CYQawa,
 * jiayuxuan123 and contributors. AGPL-3.0-or-later; see LICENSE.
 * Only file listing and a selected existing file's download URL are exposed. */
package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.providers.GuangyaProtocol
import com.constx.onvyra.data.netdisk.providers.LanzouProtocol
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Uses the same token/device pair as native sign-in, rather than assuming a WebView session exists. */
class GuangyaAccountBrowser(private val http: NetDiskHttp) {
    private data class Credential(val token: String, val device: String)
    private data class Page(val number: Int, val previous: String? = null)
    private val fallbackDevice = UUID.randomUUID().toString().replace("-", "")

    suspend fun list(credential: String, directoryId: String = "", cursor: String? = null): NetDiskListing {
        val auth = credential(credential)
        val parent = directory(directoryId)
        val page = page(cursor, parent)
        val json = post("file/get_file_list", buildJsonObject {
            put("parentId", parent); put("pageSize", PAGE_SIZE); put("orderBy", 0); put("sortType", 0); put("page", page.number)
        }, auth)
        val raw = GuangyaProtocol.array(json)
            ?: throw NetDiskException("invalid_response", "光鸭未返回可识别的本人文件目录，请刷新重试。")
        val files = raw.map { element ->
            val item = element as? JsonObject ?: invalidEntry()
            val file = GuangyaProtocol.file(item) ?: invalidEntry()
            if (!validId(file.id)) invalidEntry()
            file.copy(id = (if (file.isDirectory) "d:" else "f:") + file.id, size = file.size.coerceAtLeast(0),
                extra = JsonObject(file.extra + ("parent_id" to JsonPrimitive(parent))))
        }.distinctBy(NetDiskFile::id)
        val signature = signature(files)
        if (files.isNotEmpty() && signature == page.previous)
            throw NetDiskException("pagination_loop", "光鸭文件分页重复，请刷新当前目录后重试。")
        val total = (json["total"] as? JsonPrimitive)?.longOrNull ?: 0L
        val more = files.isNotEmpty() && ((json["hasMore"] as? JsonPrimitive)?.booleanOrNull
            ?: if (total > 0) page.number.toLong() * PAGE_SIZE + raw.size < total else raw.size >= PAGE_SIZE)
        if (more && page.number == MAX_PAGES - 1)
            throw NetDiskException("pagination_limit", "光鸭目录较大，已达本次分页上限，请到官网查看剩余文件。")
        return NetDiskListing(files, "gy:${parentKey(parent)}:${page.number + 1}:$signature".takeIf { more },
            if (parent.isEmpty()) "我的文件" else null)
    }

    suspend fun resolve(file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        val auth = credential(credential)
        val id = file.id.removePrefix("f:")
        if (!validId(id)) throw NetDiskException("invalid_file", "光鸭文件编号无效，请刷新目录。")
        val json = post("get_res_download_url", buildJsonObject { put("fileId", id) }, auth)
        val target = listOf("signedURL", "signedUrl", "downloadUrl", "download_url", "url", "cdnUrl")
            .firstNotNullOfOrNull { text(json, it).takeIf(String::isNotBlank) }
            ?: throw NetDiskException("download_unavailable", "光鸭未返回下载地址，请在官网确认文件权限。")
        return NetDiskDownload(LanzouProtocol.absolute("https://www.guangyapan.com/", target), file.name, file.mimeType,
            mapOf("User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Referer" to "https://www.guangyapan.com/"))
    }

    private suspend fun post(path: String, body: JsonObject, auth: Credential): JsonObject {
        val response = http.postJson("https://api.guangyapan.com/userres/v1/$path", body, mapOf(
            "Cookie" to "", "Authorization" to "Bearer ${auth.token}", "did" to auth.device, "dt" to "4",
            "traceparent" to "00-${UUID.randomUUID().toString().replace("-", "")}-${UUID.randomUUID().toString().replace("-", "").take(16)}-01",
            "Origin" to "https://www.guangyapan.com", "Referer" to "https://www.guangyapan.com/",
            "Accept" to "application/json, text/plain, */*"), followRedirects = false)
        if (response.status in setOf(401, 403)) authExpired()
        if (response.status == 429) throw NetDiskException("rate_limited", "光鸭请求过于频繁，请稍后重试。")
        if (response.status !in 200..299) throw NetDiskException("provider_error", "光鸭读取失败，请稍后重试。")
        val json = response.json()
        val code = text(json, "code")
        val error = text(json, "error")
        if (code in setOf("401", "403") || error in setOf("unauthenticated", "invalid_token", "token_expired")) authExpired()
        if (error.contains("captcha", true))
            throw NetDiskException("verification_required", "光鸭需要完成官方验证，请到官方登录页继续。")
        if (code !in setOf("", "0") || error.isNotBlank() || (json["success"] as? JsonPrimitive)?.booleanOrNull == false)
            throw NetDiskException("provider_error", "光鸭未确认读取成功，请刷新重试。")
        return json["data"] as? JsonObject ?: json
    }

    private fun credential(raw: String): Credential {
        val parsed = GuangyaProtocol.credential(raw)
        val token = parsed.first
        val device = parsed.second.ifBlank { fallbackDevice }
        if (token.length !in 16..16384 || token.any { it.code !in 33..126 } ||
            !Regex("^[A-Za-z0-9_.-]{1,256}$").matches(device)) authExpired()
        return Credential(token, device)
    }
    private fun directory(raw: String): String {
        if (raw.isEmpty()) return ""
        val id = raw.removePrefix("d:")
        if (!validId(id)) throw NetDiskException("invalid_directory", "光鸭目录编号无效，请返回根目录。")
        return id
    }
    private fun page(cursor: String?, parent: String): Page {
        if (cursor == null) return Page(0)
        val parts = cursor.split(':')
        val page = parts.getOrNull(2)?.toIntOrNull()
        if (parts.size != 4 || parts[0] != "gy" || parts[1] != parentKey(parent) || page == null || page !in 1 until MAX_PAGES ||
            !Regex("^[0-9a-f]{16}$").matches(parts[3]))
            throw NetDiskException("invalid_cursor", "光鸭分页信息无效，请刷新当前目录。")
        return Page(page, parts[3])
    }
    private fun parentKey(parent: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(parent.toByteArray())
    private fun validId(value: String): Boolean = Regex("^[A-Za-z0-9_-]{1,128}$").matches(value)
    private fun signature(files: List<NetDiskFile>): String = MessageDigest.getInstance("SHA-256")
        .digest(files.map(NetDiskFile::id).sorted().joinToString("\n").toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
    private fun text(json: JsonObject, key: String): String = (json[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun authExpired(): Nothing = throw NetDiskException("auth_required", "光鸭登录信息无效或已过期，请重新登录。")
    private fun invalidEntry(): Nothing = throw NetDiskException("invalid_response", "光鸭返回的目录项目不完整，请刷新重试。")
    private companion object { const val PAGE_SIZE = 100; const val MAX_PAGES = 1000 }
}
