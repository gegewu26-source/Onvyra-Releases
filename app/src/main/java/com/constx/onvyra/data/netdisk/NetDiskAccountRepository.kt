/*
 * Read-only personal-drive protocol adapted from YunGet / YunX.
 * Copyright (C) 2026 CYQawa, jiayuxuan123 and contributors.
 * Source: jiayuxuan123/YunGet commit 845932c48a04e5092bc6795e4dcb0fd67d202f10,
 * Pan123Api.kt, QuarkApi.kt and UcApi.kt. GNU AGPL-3.0-or-later; see LICENSE.
 * Only directory listing and a user-selected file's download link are retained.
 */
package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.providers.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Personal account files, separate from share-link sessions. Never writes to a cloud account. */
class NetDiskAccountRepository(
    private val credentialReader: (NetDiskPlatform) -> String,
    private val http: NetDiskHttp = NetDiskHttp(),
) {
    constructor(credentials: NetDiskRepository, http: NetDiskHttp = NetDiskHttp()) : this(credentials::credential, http)

    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private var credentialFingerprint = ""
    private val lanzou = LanzouAccountBrowser(http)
    private val ilanzou = IlanzouAccountBrowser(http)
    private val xunlei = XunleiAccountBrowser(http)
    private val guangya = GuangyaAccountBrowser(http)

    suspend fun list(platform: NetDiskPlatform, directoryId: String = "", cursor: String? = null): NetDiskListing =
        withContext(Dispatchers.IO) {
            requireSupported(platform)
            val credential = credential(platform)
            when (platform) {
                NetDiskPlatform.PAN123 -> list123(directoryId, cursor, credential)
                NetDiskPlatform.QUARK, NetDiskPlatform.UC -> listQuarkUc(platform, directoryId, cursor, credential)
                NetDiskPlatform.LANZOU -> lanzou.list(credential, directoryId, cursor)
                NetDiskPlatform.ILANZOU -> ilanzou.list(credential, directoryId, cursor)
                NetDiskPlatform.XUNLEI -> xunlei.list(credential, directoryId, cursor)
                NetDiskPlatform.GUANGYA -> guangya.list(credential, directoryId, cursor)
                else -> error("Unsupported platform checked above")
            }
        }

    suspend fun resolve(platform: NetDiskPlatform, file: NetDiskFile): NetDiskDownload = withContext(Dispatchers.IO) {
        requireSupported(platform)
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        if (file.id.isBlank()) throw NetDiskException("invalid_file", "文件编号缺失，请重新读取目录。")
        val credential = credential(platform)
        when (platform) {
            NetDiskPlatform.PAN123 -> resolve123(file, credential)
            NetDiskPlatform.QUARK, NetDiskPlatform.UC -> resolveQuarkUc(platform, file, credential)
            NetDiskPlatform.LANZOU -> lanzou.resolve(file, credential)
            NetDiskPlatform.ILANZOU -> ilanzou.resolve(file, credential)
            NetDiskPlatform.XUNLEI -> xunlei.resolve(file, credential)
            NetDiskPlatform.GUANGYA -> guangya.resolve(file, credential)
            else -> error("Unsupported platform checked above")
        }
    }

    @Synchronized
    private fun credential(platform: NetDiskPlatform): String {
        val value = credentialReader(platform).trim()
        if (value.isBlank()) throw NetDiskException("auth_required", "请先登录${platform.label}，再查看本人网盘文件。")
        if (value.length > 65536 || value.any { it == '\r' || it == '\n' || it == '\u0000' })
            throw NetDiskException("auth_required", "已保存的登录信息无效，请重新登录${platform.label}。")
        val fingerprint = platform.name + ":" + MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (fingerprint != credentialFingerprint) {
            // A previous account's response cookies must not outlive a credential change.
            http.clearCookies()
            credentialFingerprint = fingerprint
        }
        return value
    }

    private suspend fun list123(directory: String, cursor: String?, credential: String): NetDiskListing {
        val parent = directory.ifBlank { "0" }
        if (parent.toLongOrNull() == null || parent.startsWith('-'))
            throw NetDiskException("invalid_directory", "123 云盘目录编号无效，请返回根目录。")
        val (page, next) = cursor123(cursor)
        val path = "/b/api/file/list/new"
        val url = "https://yun.123pan.cn$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("driveId", "0"); addQueryParameter("limit", "100"); addQueryParameter("next", next)
            addQueryParameter("orderBy", "update_time"); addQueryParameter("orderDirection", "desc")
            addQueryParameter("parentFileId", parent); addQueryParameter("trashed", "false")
            addQueryParameter("SearchData", ""); addQueryParameter("Page", "1")
            addQueryParameter("OnlyLookAbnormalFile", "0"); addQueryParameter("event", "homeListFile")
            addQueryParameter("operateType", "1"); addQueryParameter("inDirectSpace", "false")
        }.build().toString()
        val root = account123Result(http.get(url, headers123(path, credential)), "读取 123 云盘文件失败")
        val data = root.ndObject("data") ?: throw NetDiskException("invalid_response", "123 云盘没有返回目录数据，请重试。")
        return pan123Listing(data, page).copy(title = if (parent == "0") "我的文件" else null)
    }

    private suspend fun resolve123(file: NetDiskFile, credential: String): NetDiskDownload {
        val id = file.id.toLongOrNull()?.takeIf { it > 0 }
            ?: throw NetDiskException("invalid_file", "123 文件编号无效，请重新读取目录。")
        val path = "/api/file/download_info"
        val response = http.postJson("https://yun.123pan.cn$path", buildJsonObject {
            put("driveId", 0); put("fileId", id); put("fileName", file.name); put("type", 0)
            put("size", file.size); put("s3keyFlag", file.extra.ndText("s3_key_flag")); put("etag", file.extra.ndText("etag"))
        }, headers123(path, credential))
        val data = account123Result(response, "获取 123 文件下载地址失败").ndObject("data")
            ?: throw NetDiskException("invalid_response", "123 云盘没有返回下载信息。")
        val raw = data.ndText("DownloadUrl").ifBlank { data.ndText("DownloadURL") }.ifBlank { data.ndText("downloadUrl") }
        val url = pan123DecodeDownloadUrl(raw)
        return NetDiskDownload(url, file.name, file.mimeType,
            mapOf("User-Agent" to "Dart/3.12 (dart:io)", "Referer" to "https://yun.123pan.cn/"),
            "本人网盘文件；下载链接具有时效，速度和可用性受网盘账号与流量规则影响。")
    }

    private fun headers123(path: String, credential: String): Map<String, String> {
        val (key, value) = pan123Signature(path)
        return mapOf("Authorization" to ndBearer(token123(credential)), "platform" to "web", "app-version" to "3",
            "loginuuid" to deviceId, "auth-key" to key, "auth-value" to value, "Cookie" to "",
            "User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Origin" to "https://yun.123pan.cn",
            "Referer" to "https://yun.123pan.cn/", "Accept" to "application/json, text/plain, */*")
    }

    private fun account123Result(response: NetDiskResponse, fallback: String): JsonObject {
        if (response.status == 401) throw NetDiskException("auth_required", "123 云盘登录已失效，请重新登录后查看文件。")
        val root = response.json()
        val code = root.ndLong("code", -1)
        if (code in setOf(401L, 40101L, 40102L, 31001L) ||
            (code !in setOf(0L, 200L) && listOf("登录过期", "登录已失效", "未登录", "token expired", "invalid token")
                .any { ndMessage(root, "").contains(it, ignoreCase = true) }))
            throw NetDiskException("auth_required", "123 云盘登录已失效，请重新登录后查看文件。")
        return pan123Result(response, fallback)
    }

    private fun token123(credential: String): String {
        var value = credential.trim().replace(Regex("(?i)^Bearer\\s+"), "")
        val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull()
        value = when (parsed) {
            is JsonPrimitive -> parsed.contentOrNull.orEmpty()
            is JsonObject -> parsed.ndText("access_token").ifBlank { parsed.ndText("token") }
                .ifBlank { parsed.ndText("authorToken") }.ifBlank { parsed.ndObject("data")?.ndText("token").orEmpty() }
            else -> value
        }.trim().replace(Regex("(?i)^Bearer\\s+"), "")
        if (value.isBlank() || value.length > 16384 || value.any(Char::isISOControl))
            throw NetDiskException("auth_required", "已保存的 123 登录信息无效，请重新登录。")
        return value
    }

    private fun cursor123(cursor: String?): Pair<Int, String> {
        if (cursor == null) return 1 to "0"
        val page = cursor.substringBefore(':').toIntOrNull() ?: 0
        if (!cursor.contains(':') || page !in 2..100 || cursor.length > 512)
            throw NetDiskException("invalid_cursor", "目录分页信息无效，请刷新当前目录。")
        return page to cursor.substringAfter(':')
    }

    private suspend fun listQuarkUc(platform: NetDiskPlatform, directory: String, cursor: String?, credential: String): NetDiskListing {
        val page = ndPage(cursor)
        val params = linkedMapOf("pdir_fid" to directory.ifBlank { "0" }, "_page" to page.toString(), "_size" to "100",
            "_fetch_total" to "1", "_fetch_sub_dirs" to "0", "_sort" to "file_type:asc,updated_at:desc")
        if (platform == NetDiskPlatform.QUARK) params.putAll(mapOf("uc_param_str" to "", "fetch_all_file" to "1", "fetch_risk_file_name" to "1"))
        val response = http.get(quarkUcEndpoint(platform, "file/sort", params), quarkUcHeaders(platform, credential))
        val data = accountQuarkUcResult(platform, response, "读取${platform.label}文件失败").ndObject("data")
            ?: throw NetDiskException("invalid_response", "网盘没有返回本人目录数据，请重试。")
        val values = data.ndArray("list") ?: throw NetDiskException("invalid_response", "网盘没有返回本人文件列表，请重试。")
        val files = values.map { value ->
            val item = value as? JsonObject ?: throw NetDiskException("invalid_response", "网盘返回了无效文件记录。")
            val id = item.ndText("fid").takeIf(String::isNotBlank)
                ?: throw NetDiskException("invalid_response", "网盘文件编号缺失。")
            NetDiskFile(id, item.ndText("file_name").ifBlank { item.ndText("fname", "未命名文件") },
                item.ndFlag("dir") || item.ndFlag("isdir"), item.ndLong("size", item.ndLong("fsize")),
                item.ndText("mime_type", "application/octet-stream"))
        }
        return NetDiskListing(files, if (values.size >= 100 && page < 100) (page + 1).toString() else null,
            if (directory.isBlank() || directory == "0") "我的文件" else null)
    }

    private suspend fun resolveQuarkUc(platform: NetDiskPlatform, file: NetDiskFile, credential: String): NetDiskDownload {
        val extra = if (platform == NetDiskPlatform.UC) mapOf("entry" to "ft") else mapOf("sys" to "win32", "ve" to "3.23.2")
        val response = http.postJson(quarkUcEndpoint(platform, "file/download", extra),
            buildJsonObject { put("fids", ndStrings(file.id)) }, quarkUcHeaders(platform, credential))
        val item = accountQuarkUcResult(platform, response, "获取${platform.label}下载地址失败").ndArray("data")?.firstOrNull() as? JsonObject
            ?: throw NetDiskException("invalid_response", "网盘没有返回文件下载地址。")
        val url = ndDownloadUrl(item.ndText("download_url"))
        val headers = quarkUcHeaders(platform, credential).filterKeys { it in setOf("User-Agent", "Referer") }.toMutableMap()
        // Only the selected provider's own download host may receive account cookies.
        val allowed = if (platform == NetDiskPlatform.UC) "uc.cn" else "quark.cn"
        val host = url.toHttpUrl().host
        if (host == allowed || host.endsWith(".$allowed")) {
            val jar = http.cookieHeader(url)
            val cookie = if (jar.isBlank()) credential else mergeAccountCookies(credential, jar)
            headers["Cookie"] = cookie
        }
        return NetDiskDownload(url, item.ndText("file_name").ifBlank { item.ndText("filename").ifBlank { file.name } },
            file.mimeType, headers, "本人网盘文件；下载链接具有时效。")
    }

    private fun quarkUcEndpoint(platform: NetDiskPlatform, path: String, extra: Map<String, String>): String {
        val base = if (platform == NetDiskPlatform.UC) "https://pc-api.uc.cn" else "https://drive-pc.quark.cn"
        return "$base/1/clouddrive/$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("pr", if (platform == NetDiskPlatform.UC) "UCBrowser" else "ucpro")
            addQueryParameter("fr", "pc")
            extra.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
    }

    private fun quarkUcHeaders(platform: NetDiskPlatform, credential: String): Map<String, String> {
        val uc = platform == NetDiskPlatform.UC
        val origin = if (uc) "https://drive.uc.cn" else "https://pan.quark.cn"
        val userAgent = if (uc) "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) uc-cloud-drive/1.6.1 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d Safari/537.36 Channel/ucpan_other_ch"
        else "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch"
        return mapOf("Cookie" to credential, "User-Agent" to userAgent, "Origin" to origin, "Referer" to "$origin/",
            "Accept" to "application/json, text/plain, */*")
    }

    private fun accountQuarkUcResult(platform: NetDiskPlatform, response: NetDiskResponse, fallback: String): JsonObject {
        if (response.status == 401) throw NetDiskException("auth_required", "${platform.label}登录已失效，请重新登录。")
        val root = response.json()
        val code = root.ndText("code")
        if (code in setOf("401", "31001", "32001", "41017") ||
            (root.ndLong("status", 200) != 200L && ndMessage(root, "").contains("登录")))
            throw NetDiskException("auth_required", "${platform.label}登录已失效，请重新登录。")
        return ndCloudResult(response, fallback)
    }

    private fun mergeAccountCookies(first: String, second: String): String = linkedMapOf<String, String>().apply {
        for (raw in listOf(first, second)) raw.split(';').forEach { part ->
            val name = part.substringBefore('=', "").trim()
            if (name.isNotBlank()) put(name, part.substringAfter('=', "").trim())
        }
    }.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun requireSupported(platform: NetDiskPlatform) {
        if (!isNativeSupported(platform)) throw NetDiskException("account_browser_required", "请使用${platform.label}官网文件页查看本人文件。")
    }

    companion object {
        fun isNativeSupported(platform: NetDiskPlatform): Boolean = platform in setOf(
            NetDiskPlatform.PAN123, NetDiskPlatform.QUARK, NetDiskPlatform.UC, NetDiskPlatform.LANZOU,
            NetDiskPlatform.ILANZOU, NetDiskPlatform.XUNLEI, NetDiskPlatform.GUANGYA,
        )
    }
}
