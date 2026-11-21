/*
 * Guangya share wire protocol adapted from YunX (CYQawa), cc9afe24c9409b239c361263b976f7aad77d1d9e,
 * and YunGet (jiayuxuan123), 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa and YunGet contributors. AGPL-3.0-or-later; see LICENSE.
 * Onvyra adaptation: guest download first, user-approved retained transfer only when required.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class GuangyaNetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.GUANGYA
    private val deviceId = UUID.randomUUID().toString().replace("-", "")
    private data class Saved(val folder: String, var submitted: Boolean = false, var target: String = "", var task: String = "")
    private val savedFiles = LinkedHashMap<String, Saved>()
    private val transferLock = Mutex()

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val token = post("get_share_access_token", buildJsonObject {
            put("shareId", share.shareId); put("code", share.password)
        }).let { it.gyText("accessToken").ifBlank { it.gyText("access_token") } }
        if (token.isBlank()) throw NetDiskException("invalid_response", "光鸭未返回分享访问令牌，请检查提取码。")
        val summary = post("get_share_summary", buildJsonObject { put("shareId", share.shareId) })
        return NetDiskSession(share, summary.gyText("title").ifBlank { summary.gyText("shareName") }.ifBlank { "光鸭分享" }, token)
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        val json = post("get_share_page_files_list", buildJsonObject {
            put("parentId", directoryId); put("pageSize", 100); put("orderBy", 0); put("sortType", 0)
            put("accessToken", session.token)
            if (!cursor.isNullOrBlank()) put("cursor", cursor.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(cursor))
        })
        val files = GuangyaProtocol.array(json)?.mapNotNull { (it as? JsonObject)?.let(GuangyaProtocol::file) }
            ?: throw NetDiskException("invalid_response", "光鸭没有提供可识别的分享文件列表。")
        val next = json.gyText("cursor").takeIf(String::isNotBlank)
        val hasMore = (json["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: if (json.containsKey("total"))
            (next?.toLongOrNull() ?: files.size.toLong()) < json.gyLong("total") else files.size >= 100
        return NetDiskListing(files, next?.takeIf { hasMore && it != cursor })
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("directory", "请先打开文件夹，再选择文件。")
        val response = rawPost("get_share_download_url", buildJsonObject { put("fileId", file.id); put("accessToken", session.token) })
        val json = response.json()
        if (json.gyText("code") != "207") return download(check(response), file)
        val auth = GuangyaProtocol.credential(credential)
        if (auth.first.isBlank()) throw NetDiskException("auth_required", "该光鸭分享未开放匿名下载，请先登录光鸭云盘。")
        if (session.extra["allow_transfer"]?.jsonPrimitive?.booleanOrNull != true)
            throw NetDiskException("transfer_required", "此光鸭分享需要转存到你账号中新建的 Onvyra 临时目录后取链；文件会保留并占用空间。")
        return withTimeout(45_000) { transferLock.withLock {
            val key = XunleiProtocol.digest(auth.first) + ":" + session.share.shareId + ":" + file.id
            var state = savedFiles[key]
            if (state == null) {
                val folder = post("file/create_dir", buildJsonObject {
                    put("parentId", ""); put("dirName", "Onvyra临时转存_${UUID.randomUUID()}"); put("failIfNameExist", true)
                }, auth).let { it.gyText("fileId").ifBlank { it.gyText("id") } }
                if (folder.isBlank()) throw NetDiskException("transfer_failed", "光鸭没有确认创建转存目录。")
                state = Saved(folder)
                savedFiles[key] = state
                while (savedFiles.size > 64) savedFiles.remove(savedFiles.keys.first())
            }
            val saved = requireNotNull(state)
            if (!saved.submitted) {
                // A lost response is ambiguous; retries check the same new folder rather than copying again.
                saved.submitted = true
                val restored = post("restore_share", buildJsonObject {
                    put("accessToken", session.token); put("fileIds", JsonArray(listOf(JsonPrimitive(file.id))))
                    put("parentId", saved.folder)
                }, auth, onRejected = { saved.submitted = false })
                saved.task = restored.gyText("taskId")
            }
            if (saved.task.isNotBlank()) {
                for (attempt in 0 until 12) {
                    val status = post("get_task_status", buildJsonObject { put("taskId", saved.task) }, auth)
                    val detail = status["detail"] as? JsonObject
                    if (status.gyLong("status") == 2L) {
                        if (detail != null && detail.gyLong("code") != 0L)
                            throw NetDiskException("transfer_failed", detail.gyText("msg").ifBlank { "光鸭未能完成转存，临时目录已保留。" })
                        saved.task = ""
                        break
                    }
                    if (status.gyLong("status") == 3L)
                        throw NetDiskException("transfer_failed", "光鸭未能完成转存，临时目录已保留。")
                    delay(750)
                }
                if (saved.task.isNotBlank()) throw NetDiskException("transfer_pending", "光鸭转存仍在处理中，重试会检查同一临时目录。")
            }
            if (saved.target.isBlank()) {
                for (attempt in 0 until 12) {
                    val listing = post("file/get_file_list", buildJsonObject {
                        put("parentId", saved.folder); put("pageSize", 100); put("orderBy", 0); put("sortType", 0); put("page", 0)
                    }, auth)
                    val candidates = GuangyaProtocol.array(listing).orEmpty().mapNotNull { (it as? JsonObject)?.let(GuangyaProtocol::file) }
                        .filter { !it.isDirectory && it.name == file.name && (file.size <= 0 || it.size <= 0 || it.size == file.size) }
                    if (candidates.size > 1) throw NetDiskException("ambiguous_transfer", "光鸭转存目录有多个同名文件，无法确认下载目标，请在官网检查。")
                    candidates.singleOrNull()?.let { saved.target = it.id }
                    if (saved.target.isNotBlank()) break
                    delay(750)
                }
            }
            if (saved.target.isBlank()) throw NetDiskException("transfer_pending", "光鸭尚未确认转存文件；目录已保留，重试不会再次复制。")
            download(post("get_res_download_url", buildJsonObject { put("fileId", saved.target) }, auth), file)
                .copy(note = "已转存到你网盘的新建 Onvyra 临时目录，文件已保留，请按需自行管理。")
        } }
    }

    private fun download(json: JsonObject, file: NetDiskFile): NetDiskDownload {
        val url = listOf("signedURL", "signedUrl", "downloadUrl", "download_url", "url", "cdnUrl")
            .firstNotNullOfOrNull { json.gyText(it).takeIf(String::isNotBlank) }
            ?: throw NetDiskException("download_unavailable", "光鸭没有返回有效下载地址，请在官网确认文件的下载权限。")
        val resolved = LanzouProtocol.absolute("https://www.guangyapan.com/", url)
        return NetDiskDownload(resolved, file.name, file.mimeType,
            mapOf("User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Referer" to "https://www.guangyapan.com/"))
    }

    private suspend fun post(path: String, body: JsonObject, auth: Pair<String, String> = "" to "",
        onRejected: (() -> Unit)? = null): JsonObject = check(rawPost(path, body, auth), onRejected)
    private suspend fun rawPost(path: String, body: JsonObject, auth: Pair<String, String> = "" to ""): NetDiskResponse =
        http.postJson("https://api.guangyapan.com/userres/v1/$path", body, buildMap {
            put("User-Agent", NetDiskHttp.WEB_USER_AGENT); put("Accept", "application/json, text/plain, */*")
            put("Origin", "https://www.guangyapan.com"); put("Referer", "https://www.guangyapan.com/")
            put("dt", "4"); put("did", auth.second.ifBlank { deviceId })
            put("traceparent", "00-${UUID.randomUUID().toString().replace("-", "")}-${UUID.randomUUID().toString().replace("-", "").take(16)}-01")
            put("Cookie", "")
            if (auth.first.isNotBlank()) put("Authorization", "Bearer ${auth.first}")
        })

    private fun check(response: NetDiskResponse, onRejected: (() -> Unit)? = null): JsonObject {
        val json = response.json()
        val code = json.gyText("code")
        if (response.status in 200..299 && code in setOf("", "0") && (json["success"] as? JsonPrimitive)?.booleanOrNull != false && json.gyText("error").isBlank())
            return json["data"] as? JsonObject ?: json
        val reason = when {
            response.status == 401 || response.status == 403 || json.gyText("error") == "unauthenticated" -> "auth_required"
            code == "209" -> "password_required"
            code in setOf("200", "201", "202") -> "share_expired"
            code == "157" -> "storage_full"
            response.status == 429 -> "rate_limited"
            else -> "provider_error"
        }
        val message = json.gyText("msg").ifBlank { json.gyText("message") }.ifBlank { json.gyText("error_description") }.take(250)
        // Retry only a definite refusal. A server/transport failure may have
        // committed the copy, so it must keep the original submitted ledger.
        if (response.status in 400..499 && response.status != 408 || response.status in 200..299 &&
            (code.isNotBlank() && code != "0" || (json["success"] as? JsonPrimitive)?.booleanOrNull == false || json.gyText("error").isNotBlank()))
            onRejected?.invoke()
        throw NetDiskException(reason, message.ifBlank { "光鸭请求失败（HTTP ${response.status} / $code）。" })
    }
}

internal object GuangyaProtocol {
    fun credential(raw: String): Pair<String, String> {
        val value = raw.trim()
        if (!value.startsWith('{')) return value.removePrefix("Bearer ").trim() to ""
        val root = try { Json.parseToJsonElement(value).jsonObject } catch (_: Exception) {
            throw NetDiskException("invalid_credential", "光鸭凭据 JSON 格式不正确。")
        }
        val json = root["data"] as? JsonObject ?: root
        return json.gyText("access_token").ifBlank { json.gyText("accessToken") } to
            json.gyText("device_id").ifBlank { json.gyText("did") }
    }
    fun array(json: JsonObject): JsonArray? = listOf("list", "items", "files", "records", "content")
        .firstNotNullOfOrNull { json[it] as? JsonArray }
    fun file(json: JsonObject): NetDiskFile? {
        val id = listOf("fileId", "file_id", "id", "resId", "resourceId").firstNotNullOfOrNull { json.gyText(it).takeIf(String::isNotBlank) } ?: return null
        val name = listOf("fileName", "name", "filename", "dirName", "title").firstNotNullOfOrNull { json.gyText(it).takeIf(String::isNotBlank) } ?: id
        val directory = when (json.gyText("resType")) {
            "2" -> true
            "1" -> false
            else -> listOf("isDir", "isdir", "dir", "isFolder", "folder").any { (json[it] as? JsonPrimitive)?.booleanOrNull == true } ||
                json.gyText("dirName").isNotBlank() || json.gyText("type").lowercase() in setOf("folder", "dir", "directory", "0") ||
                json.gyText("fileType").lowercase() in setOf("folder", "dir", "directory", "0")
        }
        val size = listOf("fileSize", "size", "file_size").firstNotNullOfOrNull { (json[it] as? JsonPrimitive)?.longOrNull } ?: 0L
        return NetDiskFile(id, name, directory, size)
    }
}

private fun JsonObject.gyText(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.gyLong(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
