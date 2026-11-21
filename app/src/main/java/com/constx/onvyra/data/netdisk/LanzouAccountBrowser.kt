/*
 * Read-only personal-drive protocol adapted from YunGet / YunX.
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors.
 * Source: jiayuxuan123/YunGet commit 845932c48a04e5092bc6795e4dcb0fd67d202f10,
 * core/.../network/LanzouApi.kt and LanzouConstants.kt.
 * GNU AGPL-3.0-or-later; see the repository LICENSE.
 * Adaptation: typed hierarchical ids, incremental pagination and explicit auth errors.
 * Only directory listing is retained; no cloud mutation or share creation is performed.
 */
package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.providers.LanzouProtocol
import com.constx.onvyra.data.netdisk.providers.ndArray
import com.constx.onvyra.data.netdisk.providers.ndText
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Personal files using the official, authenticated file page and its read-only list tasks. */
class LanzouAccountBrowser(private val http: NetDiskHttp) {
    private data class LoginParams(val uid: String, val vei: String)
    private data class Page(val number: Int, val previousSignature: String? = null)

    suspend fun list(credential: String, directoryId: String = "", cursor: String? = null): NetDiskListing {
        val cookie = validatedCookie(credential)
        val parent = directory(directoryId)
        val page = page(cursor, parent)
        // vei belongs to the current PHP session. Do not persist it across account changes,
        // or reuse a WebView's old copy after the provider has refreshed its session cookie.
        val login = bootstrap(cookie)
        val folders = if (page.number == 1) {
            entries(listTask(cookie, login, "47", parent), "文件夹").map { entry ->
                folder(entry, parent)
            }.filterNotNull().distinctBy(NetDiskFile::id)
        } else emptyList()
        val response = listTask(cookie, login, "5", parent, page.number)
        val files = entries(response, "文件").map { entry -> file(entry, parent) }
            .distinctBy(NetDiskFile::id)
        val signature = signature(files)
        if (files.isNotEmpty() && signature == page.previousSignature)
            throw NetDiskException("pagination_loop", "蓝奏文件分页重复，请刷新当前目录后重试。")
        val hasMore = response.ndText("zt") == "1" && files.isNotEmpty()
        if (hasMore && page.number == MAX_PAGES)
            throw NetDiskException("pagination_limit", "蓝奏目录分页已达本次读取上限，请到官网查看剩余文件。")
        return NetDiskListing(
            files = folders + files,
            nextCursor = if (hasMore) "lz:$parent:${page.number + 1}:$signature" else null,
            title = if (parent == ROOT_FOLDER) "我的文件" else null,
        )
    }

    suspend fun resolve(file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        validatedCookie(credential)
        if (!FILE_ID.matches(file.id))
            throw NetDiskException("invalid_file", "蓝奏文件编号无效，请重新读取目录。")
        // The pinned upstream implements personal download by task=22 (createFileShare).
        // Creating a public share is a cloud write, so never silently do that for a download.
        throw NetDiskException("account_browser_required", "请在蓝奏云官网文件页打开此文件并下载。")
    }

    private suspend fun bootstrap(cookie: String): LoginParams {
        val response = http.get(FILES_URL, headers(cookie), followRedirects = false)
        checkStatus(response)
        val html = response.body.replace(Regex("<!--[\\s\\S]*?-->"), "")
        if (Regex("""\barg1\s*=\s*['\"][0-9a-fA-F]{40}['\"]""").containsMatchIn(html))
            throw NetDiskException("verification_required", "蓝奏云需要网页验证，请在官网文件页完成验证后重试。")
        // Official file-page literals: url:'/doupload.php?uid=123', data:{'vei':'...='}.
        // Query parameters and quoted property/assignment forms are both supported.
        val uid = Regex("""\buid['\"]?\s*[=:]\s*['\"]?(\d{1,20})(?!\d)""")
            .find(html)?.groupValues?.get(1)
        val vei = Regex("""\bvei['\"]?\s*[=:]\s*['\"]?([A-Za-z0-9_\-+/=]{1,256})""")
            .find(html)?.groupValues?.get(1)
        if (uid.isNullOrBlank() || vei.isNullOrBlank()) authRequired()
        return LoginParams(uid, vei)
    }

    private suspend fun listTask(cookie: String, login: LoginParams, task: String, parent: String, page: Int? = null): JsonObject {
        // These are the only two personal-account tasks this class can issue.
        check(task == "47" || task == "5")
        val endpoint = "$ORIGIN/doupload.php".toHttpUrl().newBuilder()
            .addQueryParameter("uid", login.uid).addQueryParameter("vei", login.vei).build().toString()
        val fields = linkedMapOf("task" to task, "folder_id" to parent)
        page?.let { fields["pg"] = it.toString() }
        val response = http.postForm(endpoint, fields, headers(cookie) + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        ), followRedirects = false)
        checkStatus(response)
        val root = try { response.json() } catch (_: NetDiskException) {
            if (Regex("(?i)(?:accounts\\.woozooo\\.com|action=login|name=[\"']password[\"'])")
                    .containsMatchIn(response.body)) authRequired()
            throw NetDiskException("invalid_response", "蓝奏云未返回可识别的目录，请到官网确认登录和验证状态。")
        }
        when (root.ndText("zt")) {
            "1", "2" -> return root
            "9" -> authRequired()
            "4" -> throw NetDiskException("rate_limited", "蓝奏云请求频繁，请稍后重试。")
            else -> throw NetDiskException("provider_error", "读取蓝奏云目录失败，请到官网确认登录和文件状态。")
        }
    }

    private fun entries(root: JsonObject, label: String): List<JsonObject> {
        val values = root.ndArray("text") ?: if (root.ndText("zt") == "2" &&
            (root["text"] == null || root["text"] == JsonNull || root["text"] is JsonPrimitive)) {
            JsonArray(emptyList())
        } else throw NetDiskException("invalid_response", "蓝奏云未返回${label}列表，请刷新当前目录。")
        return values.map { it as? JsonObject
            ?: throw NetDiskException("invalid_response", "蓝奏云返回了无效的${label}记录，请刷新当前目录。") }
    }

    private fun folder(item: JsonObject, parent: String): NetDiskFile? {
        val id = item.ndText("fol_id").ifBlank { item.ndText("id") }
        if (!NUMERIC_ID.matches(id)) invalidRecord()
        // Most official responses already scope task=47 to folder_id. If a response
        // explicitly supplies parents, retain only this directory's direct children.
        val returnedParent = item.ndText("parent_id")
        if (returnedParent.isNotBlank() && returnedParent != parent) return null
        if (id == parent) return null
        val name = item.ndText("name").ifBlank { item.ndText("name_all") }
        if (name.isBlank()) invalidRecord()
        return NetDiskFile("d:$id", name, isDirectory = true, extra = metadata(item, parent))
    }

    private fun file(item: JsonObject, parent: String): NetDiskFile {
        val id = item.ndText("id")
        val name = item.ndText("name_all").ifBlank { item.ndText("name") }
        if (!NUMERIC_ID.matches(id) || name.isBlank()) invalidRecord()
        return NetDiskFile("f:$id", name, size = LanzouProtocol.parseSize(item.ndText("size")),
            extra = metadata(item, parent))
    }

    private fun metadata(item: JsonObject, parent: String): JsonObject = buildJsonObject {
        put("parent_id", parent)
        item.ndText("time").takeIf(String::isNotBlank)?.let { put("modified_at", it) }
    }

    private fun headers(cookie: String): Map<String, String> = mapOf(
        // The bootstrap can refresh PHPSESSID. Its subsequent tasks must use that
        // response cookie instead of an older PHPSESSID saved by the login WebView.
        "Cookie" to mergeCookies(cookie, http.cookieHeader(FILES_URL)),
        "User-Agent" to NetDiskHttp.WEB_USER_AGENT,
        "Referer" to "$ORIGIN/mydisk.php",
        "Origin" to ORIGIN,
    )

    private fun mergeCookies(saved: String, refreshed: String): String = linkedMapOf<String, String>().apply {
        listOf(saved, refreshed).forEach { value -> value.split(';').forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) put(pair[0], pair[1])
        } }
    }.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun validatedCookie(value: String): String = value.trim().also { cookie ->
        if (cookie.isBlank() || cookie.length > 65536 || cookie.any { it.isISOControl() } ||
            cookie.split(';').none(String::isNotBlank) ||
            cookie.split(';').filter(String::isNotBlank).any { part ->
                val pair = part.trim().split('=', limit = 2)
                pair.size != 2 || !COOKIE_NAME.matches(pair[0])
            }) authRequired()
    }

    private fun directory(value: String): String {
        val id = value.ifBlank { ROOT_FOLDER }.removePrefix("d:")
        if (id != ROOT_FOLDER && !NUMERIC_ID.matches(id))
            throw NetDiskException("invalid_directory", "蓝奏目录编号无效，请返回根目录。")
        return id
    }

    private fun page(cursor: String?, parent: String): Page {
        if (cursor == null) return Page(1)
        val match = CURSOR.matchEntire(cursor)
            ?: throw NetDiskException("invalid_cursor", "蓝奏目录分页信息无效，请刷新当前目录。")
        val number = match.groupValues[2].toIntOrNull() ?: 0
        if (match.groupValues[1] != parent || number !in 2..MAX_PAGES)
            throw NetDiskException("invalid_cursor", "蓝奏目录分页信息已失效，请刷新当前目录。")
        return Page(number, match.groupValues[3])
    }

    private fun signature(files: List<NetDiskFile>): String = MessageDigest.getInstance("SHA-256")
        .digest(files.map(NetDiskFile::id).sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }

    private fun checkStatus(response: NetDiskResponse) {
        when {
            response.status in 300..399 || response.status == 401 || response.status == 403 -> authRequired()
            response.status == 429 -> throw NetDiskException("rate_limited", "蓝奏云请求频繁，请稍后重试。")
            response.status !in 200..299 -> throw NetDiskException("provider_error", "蓝奏云暂时无法读取文件，请稍后重试。")
        }
    }

    private fun authRequired(): Nothing = throw NetDiskException("auth_required", "蓝奏云登录已失效，请在官网文件页重新登录后重试。")
    private fun invalidRecord(): Nothing = throw NetDiskException("invalid_response", "蓝奏云文件信息不完整，请刷新当前目录。")

    private companion object {
        const val ORIGIN = "https://pc.woozooo.com"
        const val FILES_URL = "$ORIGIN/mydisk.php?item=files&action=index"
        const val ROOT_FOLDER = "-1"
        const val MAX_PAGES = 1000
        val NUMERIC_ID = Regex("[0-9]{1,20}")
        val FILE_ID = Regex("f:[0-9]{1,20}")
        val COOKIE_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
        val CURSOR = Regex("lz:(-1|[0-9]{1,20}):([0-9]{1,4}):([0-9a-f]{16})")
    }
}
