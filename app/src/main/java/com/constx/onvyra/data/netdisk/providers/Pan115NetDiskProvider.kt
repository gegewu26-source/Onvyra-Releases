/*
 * 115 share protocol adapted from YunX, Copyright (C) 2026 CYQawa and
 * contributors. Source: CYQawa/YunX cc9afe24c9409b239c361263b976f7aad77d1d9e,
 * Pan115Api.kt / Pan115ResolveRepository.kt. GNU AGPL-3.0-or-later.
 * The additional os_windows share/downurl route follows the current official
 * app protocol documented by chenyanggao/p115client; it does not save a file.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class Pan115NetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.PAN115
    private val saved = mutableMapOf<String, Saved115File>()
    private val transferLock = Mutex()
    private val clientAgent = "Mozilla/5.0 115Browser/36.0.1"

    private fun headers(share: NetDiskShare?, credential: String, client: Boolean = false): Map<String, String> = mapOf(
        "Cookie" to credential, "User-Agent" to if (client) clientAgent else NetDiskHttp.WEB_USER_AGENT,
        "Referer" to (share?.url ?: "https://115.com/"), "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/plain, */*",
    )
    private fun url(base: String, parameters: Map<String, String>): String = base.toHttpUrl().newBuilder()
        .apply { parameters.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val data = page(share, "", null, credential)
        return NetDiskSession(share, data.ndObject("shareinfo")?.ndText("share_title").orEmpty().ifBlank { "115 网盘分享" },
            extra = buildJsonObject { put("first_page", data); put("user_id", data.ndObject("userinfo")?.ndText("user_id").orEmpty()) })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        val offset = offset(cursor)
        val data = if (directoryId.isBlank() && cursor == null) session.extra.ndObject("first_page")
            ?: page(session.share, directoryId, cursor, credential) else page(session.share, directoryId, cursor, credential)
        val rows = data.ndArray("list") ?: throw NetDiskException("invalid_response", "115 未返回分享文件列表。")
        val files = rows.mapNotNull { it as? JsonObject }.map(::pan115ShareFile)
        val total = data.ndLong("count")
        val next = offset + rows.size
        return NetDiskListing(files, if (rows.size >= 100 && next < 10_000 && (total <= 0 || next < total)) next.toString() else null)
    }

    private fun offset(cursor: String?): Int = (cursor?.toIntOrNull() ?: if (cursor == null) 0 else -1).also {
        if (it !in 0..9_999) throw NetDiskException("invalid_cursor", "115 目录页码无效，请重新解析。")
    }

    private suspend fun page(share: NetDiskShare, directoryId: String, cursor: String?, credential: String): JsonObject {
        val response = http.get(url("https://webapi.115.com/share/snap", mapOf("share_code" to share.shareId,
            "receive_code" to share.password, "cid" to directoryId.ifBlank { "0" }, "offset" to offset(cursor).toString(),
            "limit" to "100", "asc" to "0", "format" to "json")), headers(share, credential))
        return pan115Result(response, "读取 115 分享目录失败").ndObject("data")
            ?: throw NetDiskException("invalid_response", "115 未返回分享目录数据。")
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        var last: NetDiskException? = null
        for (app in listOf(false, true)) {
            try { return directDownload(session, file, credential, app) }
            catch (e: CancellationException) { throw e }
            catch (e: NetDiskException) {
                if (e.reason in setOf("network", "rate_limited", "password_required", "share_expired", "auth_required", "verification_required")) throw e
                last = e
            }
        }
        if (credential.isBlank()) throw NetDiskException("auth_required", "115 未向游客提供此文件的下载地址，请先登录。${last?.message.orEmpty()}".take(260))
        if (!session.extra.ndFlag("allow_transfer")) throw NetDiskException("transfer_required",
            "115 分享接口未提供此文件的直接下载地址。继续会在本人网盘新建 Onvyra 临时目录并转存此文件；文件保留占用空间，不会自动删除。")
        return transferLock.withLock { transferredDownload(session, file, credential) }
    }

    private suspend fun directDownload(session: NetDiskSession, file: NetDiskFile, credential: String, app: Boolean): NetDiskDownload {
        val parameters = linkedMapOf("share_code" to session.share.shareId, "receive_code" to session.share.password,
            "file_id" to file.id, "dl" to "1")
        if (!app) session.extra.ndText("user_id").takeIf(String::isNotBlank)?.let { parameters["user_id"] = it }
        val endpoint = if (app) "https://proapi.115.com/os_windows/2.0/share/downurl" else "https://webapi.115.com/share/downurl"
        val response = http.get(url(endpoint, parameters), headers(session.share, credential, client = true))
        val data = pan115Result(response, "获取 115 分享下载地址失败").ndObject("data")
            ?: throw NetDiskException("invalid_response", "115 未返回下载信息。")
        return downloadResult(data, response, file, null)
    }

    private suspend fun transferredDownload(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        val key = MessageDigest.getInstance("SHA-256").digest("$credential\u0000${session.share.shareId}\u0000${file.id}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        var item = saved[key] ?: run {
            val name = "Onvyra临时转存-${UUID.randomUUID()}"
            val response = http.postForm("https://webapi.115.com/files/add", mapOf("pid" to "0", "cname" to name), headers(null, credential))
            val result = pan115Result(response, "创建 115 转存目录失败")
            val cid = result.ndText("cid").ifBlank { result.ndObject("data")?.ndText("cid").orEmpty() }
            if (cid.isBlank()) throw NetDiskException("invalid_response", "115 未返回新建转存目录编号。")
            Saved115File(cid, name, false, "").also { saved[key] = it }
        }
        var pickCode = findPersonalFile(item.directoryId, file, credential)?.ndText("pc").orEmpty()
        if (pickCode.isBlank() && !item.received) {
            // A timeout can happen after the server accepted receive. Remember
            // the submitted write before awaiting its response, then reconcile.
            item = item.copy(received = true)
            saved[key] = item
            val response = http.postForm("https://webapi.115.com/share/receive", mapOf("share_code" to session.share.shareId,
                "receive_code" to session.share.password, "file_id" to file.id, "cid" to item.directoryId), headers(session.share, credential))
            if (response.status !in 200..299 || !response.json().ndFlag("state")) {
                item = item.copy(received = false)
                saved[key] = item
            }
            pan115Result(response, "115 转存失败")
        }
        repeat(5) { attempt ->
            if (pickCode.isBlank()) {
                if (attempt > 0) delay(500)
                pickCode = findPersonalFile(item.directoryId, file, credential)?.ndText("pc").orEmpty()
            }
        }
        if (pickCode.isBlank()) throw NetDiskException("transfer_pending", "115 已接收转存请求，文件尚未出现在 ${item.directoryName}。请稍后重试，不会重复转存。")
        item = item.copy(pickCode = pickCode)
        saved[key] = item
        val requestKey = NetDisk115Crypto.newKey()
        val encoded = NetDisk115Crypto.encode(buildJsonObject { put("pickcode", pickCode) }.toString(), requestKey)
        val response = http.postForm("https://proapi.115.com/app/chrome/downurl?t=${System.currentTimeMillis() / 1000}",
            mapOf("data" to encoded), headers(null, credential, client = true))
        val result = pan115Result(response, "获取 115 转存文件下载地址失败")
        val encrypted = result.ndText("data").takeIf(String::isNotBlank)
            ?: throw NetDiskException("invalid_response", "115 未返回客户端加密下载数据，转存文件已保留。")
        val decoded = try { Json.parseToJsonElement(NetDisk115Crypto.decode(encrypted, requestKey)).jsonObject }
        catch (_: Exception) { throw NetDiskException("invalid_response", "115 客户端下载数据无法解码，转存文件已保留。") }
        val data = decoded.values.mapNotNull { it as? JsonObject }.firstOrNull { it.ndObject("url") != null }
            ?: throw NetDiskException("invalid_response", "115 客户端响应缺少下载地址。")
        return downloadResult(data, response, file, "已转存到 ${item.directoryName}，文件保留并占用网盘空间，不会自动删除。")
    }

    private suspend fun findPersonalFile(cid: String, expected: NetDiskFile, credential: String): JsonObject? {
        val response = http.get(url("https://webapi.115.com/files", mapOf("aid" to "1", "cid" to cid,
            "o" to "user_ptime", "asc" to "0", "offset" to "0", "limit" to "100", "show_dir" to "1", "format" to "json")),
            headers(null, credential))
        val result = pan115Result(response, "读取 115 转存结果失败")
        val rows = result.ndArray("data") ?: throw NetDiskException("invalid_response", "115 未返回本人临时目录数据。")
        val matches = rows.mapNotNull { it as? JsonObject }.filter {
            it.ndText("fid").isNotBlank() && it.ndText("n") == expected.name && (expected.size <= 0 || it.ndLong("s") == expected.size)
        }
        if (matches.size > 1) throw NetDiskException("ambiguous_transfer", "临时目录中存在多个同名转存文件，请在官方网盘确认后重试。")
        return matches.singleOrNull()
    }

    private fun downloadResult(data: JsonObject, response: NetDiskResponse, file: NetDiskFile, note: String?): NetDiskDownload {
        val value = data.ndText("file_url_302").ifBlank { data.ndText("file_url") }
            .ifBlank { data.ndObject("url")?.ndText("url").orEmpty() }
        val downloadHeaders = linkedMapOf("User-Agent" to clientAgent, "Referer" to "https://115.com/")
        // Only the short-lived cookie actually issued for this download is
        // handed to the CDN; UID/CID/SEID account cookies stay on the API.
        val cdnCookie = response.headers.values("Set-Cookie").map { it.substringBefore(';').trim() }
            .filter { it.substringBefore('=') !in setOf("UID", "CID", "SEID", "KID", "PHPSESSID") }
            .joinToString("; ")
        if (cdnCookie.isNotBlank()) downloadHeaders["Cookie"] = cdnCookie
        return NetDiskDownload(ndDownloadUrl(value), file.name, file.mimeType, downloadHeaders, note ?: "下载地址及专用凭证有时效，请及时下载。")
    }
}

private data class Saved115File(val directoryId: String, val directoryName: String, val received: Boolean, val pickCode: String)

internal fun pan115ShareFile(value: JsonObject): NetDiskFile {
    val fid = value.ndText("fid")
    val folder = value.ndLong("fc", if (fid.isBlank()) 0 else 1) == 0L
    val id = if (folder) value.ndText("cid") else fid
    if (id.isBlank()) throw NetDiskException("invalid_response", "115 文件列表缺少文件编号。")
    return NetDiskFile(id, value.ndText("n").ifBlank { "未命名文件" }, folder, value.ndLong("s"))
}

internal fun pan115Result(response: NetDiskResponse, fallback: String): JsonObject {
    val root = response.json()
    if (response.status in 200..299 && root.ndFlag("state")) return root
    val code = root.ndLong("errno", root.ndLong("errNo", root.ndLong("code", -1)))
    val reason = when {
        response.status == 429 -> "rate_limited"
        code in setOf(990001L, 99L, 401L) || response.status == 401 -> "auth_required"
        code in setOf(4100012L, 4100008L) -> "password_required"
        code in setOf(50029L, 50028L) -> "client_limit"
        code in setOf(4100001L, 4100002L, 4100004L) || response.status == 404 -> "share_expired"
        response.status == 403 -> "verification_required"
        else -> "provider_error"
    }
    val text = root.ndText("error").ifBlank { ndMessage(root, fallback) }.take(180)
    throw NetDiskException(reason, "$fallback（$code）：$text".take(260))
}
