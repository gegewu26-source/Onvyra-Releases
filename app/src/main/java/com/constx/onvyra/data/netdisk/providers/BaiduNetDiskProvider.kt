/*
 * Baidu share/list and explicit-transfer protocol adapted from YunX,
 * Copyright (C) 2026 CYQawa and contributors, GNU AGPL-3.0-or-later.
 * Source: CYQawa/YunX cc9afe24c9409b239c361263b976f7aad77d1d9e, BaiduApi.kt.
 * The share/tplconfig + api/sharedownload read-only path follows the official
 * browser protocol, as also implemented by ly0/baidupcsapi and JDownloader.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class BaiduNetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.BAIDU
    private val transfers = mutableMapOf<String, BaiduSavedFile>()
    private val transferLock = Mutex()
    private val appAgent = "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0"

    private fun headers(session: NetDiskSession?, credential: String, app: Boolean = false): Map<String, String> {
        val sekey = session?.token.orEmpty()
        val cookie = if (sekey.isBlank()) credential else credential.split(';').map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("BDCLND=") }.plus("BDCLND=$sekey").joinToString("; ")
        return mapOf("Cookie" to cookie, "User-Agent" to if (app) appAgent else NetDiskHttp.WEB_USER_AGENT,
            "Referer" to (session?.share?.url ?: "https://pan.baidu.com/"), "Origin" to "https://pan.baidu.com")
    }

    private fun url(path: String, parameters: Map<String, String>): String = "https://pan.baidu.com$path".toHttpUrl()
        .newBuilder().apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        // Baidu's shorturl/surl parameters omit the leading route marker "1".
        val normalized = share.copy(shareId = share.shareId.removePrefix("1"))
        val sekey = if (share.password.isBlank()) "" else {
            val response = http.postForm(url("/share/verify", mapOf("surl" to normalized.shareId)),
                mapOf("pwd" to share.password, "vcode_str" to "", "vcode" to ""), headers(null, credential))
            baiduResult(response, "百度提取码验证失败").ndText("randsk").takeIf(String::isNotBlank)
                ?: throw NetDiskException("invalid_response", "百度未返回分享验证凭证。")
        }
        val session = NetDiskSession(normalized, "百度网盘分享", sekey)
        val first = fetchPage(session, "", null, credential)
        return session.copy(title = first.ndText("title").ifBlank { session.title }, extra = buildJsonObject {
            put("first_page", first); put("share_id", first.ndText("share_id")); put("uk", first.ndText("uk"))
        })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        val page = ndPage(cursor)
        val data = if (directoryId.isBlank() && cursor == null) session.extra.ndObject("first_page")
            ?: fetchPage(session, directoryId, cursor, credential) else fetchPage(session, directoryId, cursor, credential)
        val rows = data.ndArray("list") ?: throw NetDiskException("invalid_response", "百度未返回分享文件列表。")
        val files = rows.mapNotNull { it as? JsonObject }.map { item ->
            val folder = item.ndFlag("isdir")
            val path = item.ndText("path")
            val id = if (folder) path else item.ndText("fs_id")
            if (id.isBlank()) throw NetDiskException("invalid_response", "百度文件列表缺少文件编号。")
            NetDiskFile(id, item.ndText("server_filename").ifBlank { "未命名文件" }, folder, item.ndLong("size"),
                extra = buildJsonObject { put("path", path) })
        }
        return NetDiskListing(files, if (rows.size >= 100 && page < 100) (page + 1).toString() else null,
            data.ndText("title").takeIf(String::isNotBlank))
    }

    private suspend fun fetchPage(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): JsonObject {
        val params = linkedMapOf("method" to "list", "shorturl" to session.share.shareId,
            "page" to ndPage(cursor).toString(), "num" to "100", "root" to if (directoryId.isBlank() || directoryId == "/") "1" else "0",
            "dir" to directoryId.ifBlank { "/" })
        if (session.token.isNotBlank()) params["sekey"] = decodedSeKey(session.token)
        return baiduResult(http.get(url("/rest/2.0/xpan/share", params), headers(session, credential)), "读取百度分享目录失败")
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        val direct = try { directShareDownload(session, file, credential) } catch (e: CancellationException) { throw e }
        catch (e: NetDiskException) {
            if (e.reason in setOf("network", "rate_limited", "password_required", "share_expired", "verification_required")) throw e
            null
        }
        if (direct != null) return direct
        if (credential.isBlank()) throw NetDiskException("auth_required", "百度网盘要求登录后获取此文件的下载地址，请先配置有效 Cookie。")
        if (!session.extra.ndFlag("allow_transfer")) throw NetDiskException("transfer_required",
            "百度未允许此文件直接下载。继续将仅把此文件转存到本人网盘新建的 Onvyra 临时目录；文件会保留并占用空间，不会自动删除。")
        return transferLock.withLock { transferredDownload(session, file, credential) }
    }

    private suspend fun directShareDownload(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload? {
        val config = baiduResult(http.get(url("/share/tplconfig", mapOf("surl" to "1${session.share.shareId}",
            "fields" to "sign,timestamp", "channel" to "chunlei", "web" to "1", "app_id" to "250528", "clienttype" to "0")),
            headers(session, credential)), "读取百度分享下载签名失败")
        val data = config.ndObject("data") ?: config
        val sign = data.ndText("sign").takeIf(String::isNotBlank) ?: return null
        val time = data.ndText("timestamp").takeIf(String::isNotBlank) ?: return null
        val info = shareInfo(session)
        val response = http.postForm(url("/api/sharedownload", mapOf("sign" to sign, "timestamp" to time,
            "bdstoken" to "", "channel" to "chunlei", "clienttype" to "0", "web" to "1", "app_id" to "250528")),
            linkedMapOf("encrypt" to "0", "product" to "share", "uk" to info.second, "primaryid" to info.first,
                "fid_list" to ndStrings(file.id).toString(), "extra" to buildJsonObject { put("sekey", decodedSeKey(session.token)) }.toString()),
            headers(session, credential))
        val result = baiduResult(response, "获取百度分享直链失败")
        val entry = result.ndArray("list")?.firstOrNull() as? JsonObject ?: return null
        val value = entry.ndText("dlink").takeIf(String::isNotBlank) ?: return null
        return NetDiskDownload(ndDownloadUrl(value), file.name, file.mimeType,
            mapOf("User-Agent" to appAgent, "Referer" to "https://pan.baidu.com/"), "下载地址有时效，请及时下载。")
    }

    private suspend fun transferredDownload(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        val key = baiduTransferKey(session, file, credential)
        val saved = transfers[key] ?: run {
            val bdstoken = getBdstoken(credential)
            val directory = "/Onvyra临时转存/reader49-${UUID.randomUUID()}"
            createDirectory("/Onvyra临时转存", bdstoken, credential, allowExisting = true)
            createDirectory(directory, bdstoken, credential, allowExisting = false)
            // Remember the directory before copying. A failed API retry must not
            // create another directory or touch the user's pre-existing files.
            BaiduSavedFile(directory, "", "").also { transfers[key] = it }
        }
        var entry = saved
        if (entry.path.isBlank()) {
            if (!entry.submitted) {
                val bdstoken = getBdstoken(credential)
                val (shareId, uk) = shareInfo(session)
                entry = entry.copy(submitted = true)
                transfers[key] = entry
                val response = http.postForm(url("/share/transfer", mapOf("shareid" to shareId, "from" to uk,
                    "channel" to "chunlei", "sekey" to decodedSeKey(session.token), "ondup" to "newcopy", "web" to "1",
                    "app_id" to "250528", "clienttype" to "0", "bdstoken" to bdstoken)),
                    mapOf("fsidlist" to ndStrings(file.id).toString(), "path" to entry.directory), headers(session, credential))
                if (response.status !in 200..299 || response.json().ndLong("errno", -1) != 0L) {
                    entry = entry.copy(submitted = false)
                    transfers[key] = entry
                }
                val result = baiduResult(response, "百度转存失败")
                val row = result.ndObject("extra")?.ndArray("list")?.firstOrNull() as? JsonObject
                val path = row?.ndText("to").orEmpty()
                if (path.isNotBlank()) {
                    if (!path.startsWith("${entry.directory}/")) throw NetDiskException("invalid_response", "百度返回了意外的转存路径，已停止下载。")
                    entry = entry.copy(path = path, fileId = row!!.ndText("to_fs_id"))
                    transfers[key] = entry
                }
            }
            repeat(5) { attempt ->
                if (entry.path.isBlank()) {
                    if (attempt > 0) delay(500)
                    val rows = baiduResult(http.get(url("/api/list", mapOf("dir" to entry.directory,
                        "num" to "100", "page" to "1", "order" to "time", "desc" to "1", "web" to "1")),
                        headers(null, credential)), "读取百度转存结果失败").ndArray("list")
                        ?: throw NetDiskException("invalid_response", "百度未返回本人临时目录内容。")
                    val matches = rows.mapNotNull { it as? JsonObject }.filter {
                        !it.ndFlag("isdir") && it.ndText("server_filename") == file.name &&
                            (file.size <= 0 || it.ndLong("size") == file.size) && it.ndText("path").startsWith("${entry.directory}/")
                    }
                    if (matches.size > 1) throw NetDiskException("ambiguous_transfer", "百度临时目录有多个同名文件，请在官网确认。")
                    matches.singleOrNull()?.let { entry = entry.copy(path = it.ndText("path"), fileId = it.ndText("fs_id")); transfers[key] = entry }
                }
            }
            if (entry.path.isBlank()) throw NetDiskException("transfer_pending", "百度转存请求已经提交，文件尚未就绪，请稍后重试；不会重复转存。")
        }
        val endpoint = "https://d.pcs.baidu.com/rest/2.0/pcs/file".toHttpUrl().newBuilder().apply {
            mapOf("method" to "locatedownload", "app_id" to "250528", "clienttype" to "17", "ver" to "4.0",
                "ant" to "1", "check_blue" to "1", "es" to "1", "esl" to "1", "path" to entry.path,
                "time" to (System.currentTimeMillis() / 1000).toString(), "rand" to "5ed606e9da222cde0474cdf70eda884b",
                "devuid" to "0F1E9FC2E084472DA5A61C4CF4C759AF", "cuid" to "0F1E9FC2E084472DA5A61C4CF4C759AF",
                "deviceid" to "348642637967375013", "psign" to "860a071f77c860e8cea06e4e54c518f3",
                "version" to "2.2.111.34", "version_app" to "12.24.6", "vip" to "0").forEach { (k, v) -> addQueryParameter(k, v) }
        }.build().toString()
        val result = baiduResult(http.postForm(endpoint, emptyMap(), headers(session, credential, app = true)), "获取百度转存文件下载地址失败")
        val choices = result.ndArray("urls")?.mapNotNull { it as? JsonObject }.orEmpty()
        // An encrypted CDN payload must never be presented as a normal file.
        val value = choices.filter { it.ndLong("encrypt", 1) == 0L }.sortedBy { !it.ndText("url").startsWith("https://") }
            .firstOrNull()?.ndText("url") ?: throw NetDiskException("encrypted_download", "百度只返回了加密下载通道，本功能未把它作为普通文件保存。转存文件已保留。")
        return NetDiskDownload(ndDownloadUrl(value), file.name, file.mimeType,
            mapOf("User-Agent" to appAgent, "Referer" to "https://pan.baidu.com/"),
            "已转存到 ${entry.directory}，文件保留并占用网盘空间；本功能不会自动删除。")
    }

    private suspend fun getBdstoken(credential: String): String {
        val response = http.get(url("/api/gettemplatevariable", mapOf("clienttype" to "0", "app_id" to "250528", "web" to "1",
            "fields" to "[\"bdstoken\"]")), headers(null, credential))
        val data = baiduResult(response, "读取百度登录凭证失败").ndObject("result")
        return data?.ndText("bdstoken")?.takeIf(String::isNotBlank)
            ?: throw NetDiskException("auth_required", "百度 Cookie 无效或已过期，请重新登录。")
    }

    private suspend fun createDirectory(path: String, bdstoken: String, credential: String, allowExisting: Boolean) {
        val response = http.postForm(url("/api/create", mapOf("a" to "commit", "channel" to "chunlei", "web" to "1",
            "app_id" to "250528", "clienttype" to "0", "bdstoken" to bdstoken)),
            mapOf("path" to path, "isdir" to "1", "size" to "", "block_list" to "[]", "method" to "post", "dataType" to "json"),
            headers(null, credential, app = true))
        val root = response.json()
        if (allowExisting && root.ndLong("errno") == -8L) return
        baiduResult(response, "创建百度 Onvyra 转存目录失败")
    }

    private fun shareInfo(session: NetDiskSession): Pair<String, String> {
        val shareId = session.extra.ndText("share_id").ifBlank { session.extra.ndObject("first_page")?.ndText("share_id").orEmpty() }
        val uk = session.extra.ndText("uk").ifBlank { session.extra.ndObject("first_page")?.ndText("uk").orEmpty() }
        if (shareId.isBlank() || uk.isBlank()) throw NetDiskException("invalid_response", "百度分享缺少分享者信息，请重新解析。")
        return shareId to uk
    }
}

private data class BaiduSavedFile(val directory: String, val path: String, val fileId: String, val submitted: Boolean = false)
private fun decodedSeKey(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
private fun baiduTransferKey(session: NetDiskSession, file: NetDiskFile, credential: String): String =
    MessageDigest.getInstance("SHA-256").digest("$credential\u0000${session.share.shareId}\u0000${file.id}".toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun baiduResult(response: NetDiskResponse, fallback: String): JsonObject {
    val root = response.json()
    val errno = root.ndLong("errno", -9999)
    if (response.status in 200..299 && errno == 0L) return root
    val message = root.ndText("errmsg").ifBlank { root.ndText("show_msg").ifBlank { ndMessage(root, fallback) } }.take(180)
    val reason = when {
        response.status == 429 -> "rate_limited"
        errno == -6L || response.status == 401 -> "auth_required"
        errno in setOf(-9L, -12L) -> "password_required"
        errno in setOf(-20L, 112L, 9019L) -> "verification_required"
        errno in setOf(-7L, 115L) || response.status == 404 -> "share_expired"
        else -> "provider_error"
    }
    throw NetDiskException(reason, "$fallback（$errno）：$message".take(260))
}
