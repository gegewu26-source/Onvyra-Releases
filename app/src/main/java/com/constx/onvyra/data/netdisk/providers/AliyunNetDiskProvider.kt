// Native protocol implementation. Share/copy/download field names cross-checked
// against the supplied JxPan capability notes; createWithFolders is also used by
// https://github.com/AlistGo/alist/blob/main/drivers/aliyundrive/driver.go.
// No JxPan or Alist implementation code is included in this provider.
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Direct share download first; account writes require this file's explicit UI approval. */
class AliyunNetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.ALIYUN
    private val api = "https://api.aliyundrive.com"
    private val saved = mutableMapOf<String, AliyunSavedFile>()
    private val transferLock = Mutex()
    private fun headers(token: String = "", credential: String = "") = linkedMapOf(
        "Origin" to "https://www.alipan.com", "Referer" to "https://www.alipan.com/",
        "User-Agent" to NetDiskHttp.WEB_USER_AGENT,
        "X-Canary" to "client=windows,app=adrive,version=v6.0.0",
        "Cookie" to "",
    ).apply {
        if (token.isNotBlank()) put("X-Share-Token", token)
        if (credential.isNotBlank()) put("Authorization", ndBearer(credential))
    }

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val tokenResponse = http.postJson("$api/v2/share_link/get_share_token", buildJsonObject {
            put("share_id", share.shareId)
            if (share.password.isNotBlank()) put("share_pwd", share.password)
        }, headers())
        val tokenRoot = aliyunResult(tokenResponse, "获取阿里分享访问凭证失败")
        val token = tokenRoot.ndText("share_token").takeIf(String::isNotBlank)
            ?: throw NetDiskException("invalid_response", "阿里云盘未返回分享访问凭证。")
        val infoResponse = http.postJson("$api/adrive/v3/share_link/get_share_by_anonymous", buildJsonObject {
            put("share_id", share.shareId)
        }, headers(token))
        val info = aliyunResult(infoResponse, "读取阿里分享信息失败")
        return NetDiskSession(share, info.ndText("share_name").ifBlank { "阿里云盘分享" }, token,
            buildJsonObject { info.ndArray("file_infos")?.let { put("root_files", it) } })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        if (cursor != null && cursor.length > 4096) throw NetDiskException("invalid_cursor", "目录游标无效，请重新读取分享。")
        // The anonymous share metadata includes selected files, even for a
        // single-file share. It does not require /user/get or an account drive.
        val rootFiles = session.extra.ndArray("root_files")
        if (directoryId.isBlank() && cursor == null && !rootFiles.isNullOrEmpty())
            return NetDiskListing(rootFiles.mapNotNull { it as? JsonObject }.map(::aliyunFile))
        val response = http.postJson("$api/adrive/v3/file/list", buildJsonObject {
            put("share_id", session.share.shareId); put("parent_file_id", directoryId.ifBlank { "root" })
            put("limit", 100); put("order_by", "name"); put("order_direction", "ASC")
            if (!cursor.isNullOrBlank()) put("marker", cursor)
        }, headers(session.token))
        val root = aliyunResult(response, "读取阿里分享目录失败")
        val items = root.ndArray("items") ?: throw NetDiskException("invalid_response", "阿里云盘未返回文件列表。")
        val next = root.ndText("next_marker").takeIf { it.isNotBlank() && it != cursor }
        return NetDiskListing(items.mapNotNull { it as? JsonObject }.map(::aliyunFile), next)
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        val response = http.postJson("$api/v2/file/get_share_link_download_url", buildJsonObject {
            put("share_id", session.share.shareId); put("file_id", file.id); put("expire_sec", 600)
        }, headers(session.token, credential))
        val root = response.json()
        if (response.status !in 200..299 || root.ndText("code").isNotBlank()) {
            val code = root.ndText("code")
            if (credential.isBlank() && (response.status in setOf(401, 403) || code.contains("AccessToken", true) || code.contains("Forbidden", true)))
                throw NetDiskException("auth_required", "阿里云盘要求登录后获取此文件的下载地址，请在网盘账号中配置有效 Token。")
            if (code in setOf("InvalidAccessToken", "AccessTokenInvalid", "AccessTokenExpired"))
                throw NetDiskException("auth_required", "阿里云盘登录凭据已失效，请更新 Token。")
            if (code in setOf("NotAllowedShareDownload", "ShareLinkDownloadForbidden", "ForbiddenNoPermission.File")) {
                if (credential.isBlank()) throw NetDiskException("auth_required", "阿里云盘要求登录后转存此文件，请先登录。")
                if (!session.extra.ndFlag("allow_transfer")) throw NetDiskException("transfer_required",
                    "阿里未允许此文件直接下载。继续会在本人网盘新建 Onvyra 临时目录并转存此文件；文件保留占用空间，不会自动删除。")
                return transferLock.withLock { transferredDownload(session, file, credential) }
            }
            ndFailure(response, root, "获取阿里下载地址失败")
        }
        val url = ndDownloadUrl(root.ndText("download_url").ifBlank { root.ndText("url") })
        return NetDiskDownload(url, file.name, file.mimeType,
            mapOf("Referer" to "https://www.alipan.com/", "User-Agent" to NetDiskHttp.WEB_USER_AGENT),
            "直链具有时效，请及时下载。")
    }

    private suspend fun transferredDownload(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        val ledgerKey = MessageDigest.getInstance("SHA-256").digest(
            "$credential\u0000${session.share.shareId}\u0000${file.id}".toByteArray()).joinToString("") { "%02x".format(it) }
        var entry = saved[ledgerKey] ?: run {
            val user = aliyunResult(http.postJson("$api/v2/user/get", JsonObject(emptyMap()), headers(credential = credential)), "读取阿里账号云盘失败")
            val driveId = listOf("resource_drive_id", "default_drive_id", "backup_drive_id", "drive_id")
                .firstNotNullOfOrNull { user.ndText(it).takeIf(String::isNotBlank) }
                ?: throw NetDiskException("auth_required", "阿里账号没有返回可用云盘编号，请重新登录。")
            val name = "Onvyra临时转存-${UUID.randomUUID()}"
            val folder = aliyunResult(http.postJson("$api/adrive/v2/file/createWithFolders", buildJsonObject {
                put("drive_id", driveId); put("parent_file_id", "root"); put("name", name)
                put("check_name_mode", "refuse"); put("type", "folder")
            }, headers(credential = credential)), "创建阿里 Onvyra 转存目录失败")
            val id = folder.ndText("file_id").takeIf(String::isNotBlank)
                ?: throw NetDiskException("invalid_response", "阿里未返回临时目录编号。")
            AliyunSavedFile(driveId, id, name).also { saved[ledgerKey] = it }
        }
        if (entry.fileId.isBlank()) {
            // A previous response can be lost after the server completed a copy.
            // Reconcile only our unique directory before considering another write.
            var found = personalFile(entry, file, credential)
            if (found == null && !entry.copySubmitted) {
                entry = entry.copy(copySubmitted = true)
                saved[ledgerKey] = entry
                val response = http.postJson("$api/adrive/v2/file/copy", buildJsonObject {
                    put("file_id", file.id); put("to_parent_file_id", entry.directoryId)
                    put("to_drive_id", entry.driveId); put("share_id", session.share.shareId)
                    put("auto_rename", false)
                }, headers(session.token, credential))
                val acknowledged = response.json()
                if (response.status !in 200..299 || acknowledged.ndText("code").isNotBlank()) {
                    entry = entry.copy(copySubmitted = false)
                    saved[ledgerKey] = entry
                }
                val copied = aliyunResult(response, "阿里文件转存失败")
                // Mark any acknowledged copy before polling; retries then cannot
                // issue duplicate copies while the asynchronous task finishes.
                entry = entry.copy(copySubmitted = true, fileId = copied.ndText("file_id")
                    .ifBlank { copied.ndObject("body")?.ndText("file_id").orEmpty() }
                    .ifBlank { copied.ndObject("data")?.ndText("file_id").orEmpty() })
                saved[ledgerKey] = entry
            }
            if (entry.fileId.isBlank()) {
                repeat(5) { attempt ->
                    if (found == null) { if (attempt > 0) delay(500); found = personalFile(entry, file, credential) }
                }
                if (found == null) throw NetDiskException("transfer_pending",
                    "阿里已接受转存，文件尚未在 ${entry.directoryName} 中就绪，请稍后重试；不会重复转存。")
                entry = entry.copy(fileId = found!!.ndText("file_id"))
                saved[ledgerKey] = entry
            }
        }
        val response = http.postJson("$api/v2/file/get_download_url", buildJsonObject {
            put("drive_id", entry.driveId); put("file_id", entry.fileId); put("expire_sec", 600)
        }, headers(credential = credential))
        val download = aliyunResult(response, "获取阿里转存文件下载地址失败")
        return NetDiskDownload(ndDownloadUrl(download.ndText("url").ifBlank { download.ndText("download_url") }),
            file.name, file.mimeType, mapOf("Referer" to "https://www.alipan.com/", "User-Agent" to NetDiskHttp.WEB_USER_AGENT),
            "已转存到 ${entry.directoryName}，文件保留并占用网盘空间；本功能不会自动删除。")
    }

    private suspend fun personalFile(entry: AliyunSavedFile, expected: NetDiskFile, credential: String): JsonObject? {
        val response = http.postJson("$api/adrive/v3/file/list", buildJsonObject {
            put("drive_id", entry.driveId); put("parent_file_id", entry.directoryId); put("limit", 100)
            put("order_by", "name"); put("order_direction", "ASC")
        }, headers(credential = credential))
        val root = aliyunResult(response, "读取阿里转存结果失败")
        val rows = root.ndArray("items") ?: throw NetDiskException("invalid_response", "阿里未返回本人临时目录内容。")
        val matches = rows.mapNotNull { it as? JsonObject }.filter {
            it.ndText("type") != "folder" && it.ndText("name") == expected.name &&
                (expected.size <= 0 || it.ndLong("size") == expected.size) && it.ndText("file_id").isNotBlank()
        }
        if (matches.size > 1) throw NetDiskException("ambiguous_transfer", "阿里临时目录中有多个同名文件，请在官方网盘确认后重试。")
        return matches.singleOrNull()
    }
}

private data class AliyunSavedFile(val driveId: String, val directoryId: String, val directoryName: String,
    val fileId: String = "", val copySubmitted: Boolean = false)

internal fun aliyunResult(response: NetDiskResponse, fallback: String): JsonObject {
    val root = response.json()
    if (response.status !in 200..299 || root.ndText("code").isNotBlank()) ndFailure(response, root, fallback)
    return root
}

internal fun aliyunFile(file: JsonObject): NetDiskFile {
    val id = file.ndText("file_id").takeIf(String::isNotBlank)
        ?: throw NetDiskException("invalid_response", "阿里文件列表缺少文件编号。")
    return NetDiskFile(id, file.ndText("name").ifBlank { "未命名文件" }, file.ndText("type") == "folder",
        file.ndLong("size"), file.ndText("mime_type", "application/octet-stream"))
}
