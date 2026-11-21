/*
 * Anonymous share API adapted from YunX (CYQawa), commit cc9afe24c9409b239c361263b976f7aad77d1d9e,
 * and YunGet (jiayuxuan123), commit 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa and YunGet contributors.
 * GNU Affero General Public License, version 3 or later. See the repository LICENSE.
 * The local JxPan capability notes were used to cross-check wire-format details only.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class IlanzouNetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.ILANZOU
    private val challengeCookies = ConcurrentHashMap<String, String>()

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val uuid = UUID.randomUUID().toString().lowercase()
        val response = listRequest(share, uuid, "", 1)
        val list = response["list"] as? JsonArray
            ?: throw NetDiskException("invalid_response", "蓝奏优享版没有返回分享目录，请在官网确认分享链接。")
        val title = response.izText("shareName").ifBlank {
            (list.firstOrNull() as? JsonObject)?.let { it.izText("folderName").ifBlank { it.izText("fileName") } }.orEmpty()
        }.ifBlank { "蓝奏优享版分享" }
        return NetDiskSession(share, title, extra = buildJsonObject {
            put("uuid", uuid); put("initial", response)
        })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        require(session.share.platform == platform)
        val page = cursor?.toIntOrNull() ?: 1
        if (page !in 1..100) throw NetDiskException("pagination_limit", "蓝奏优享版目录分页已达本次读取上限，请重新打开目录。")
        val uuid = session.extra.izText("uuid").ifBlank { UUID.randomUUID().toString() }
        val json = if (directoryId.isBlank() && cursor == null) session.extra["initial"] as? JsonObject
            ?: listRequest(session.share, uuid, directoryId, page)
        else listRequest(session.share, uuid, directoryId, page)
        val list = json["list"] as? JsonArray
            ?: throw NetDiskException("invalid_response", "蓝奏优享版没有返回可识别的目录。")
        val files = list.flatMap { element ->
            val row = element as? JsonObject ?: return@flatMap emptyList()
            val nested = row["fileList"] as? JsonArray
            if (row.izLong("fileType") != 2L && !nested.isNullOrEmpty()) nested.mapNotNull { item ->
                (item as? JsonObject)?.let { IlanzouProtocol.parseItem(JsonObject(row + it)) }
            } else listOfNotNull(IlanzouProtocol.parseItem(row))
        }.distinctBy { it.id }
        val total = json.izLong("totalPage")
        val more = if (total > 0) page < total else list.size >= PAGE_SIZE
        return NetDiskListing(files, (page + 1).toString().takeIf { more && page < 100 })
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("directory", "请先打开文件夹，再选择文件。")
        val uuid = session.extra.izText("uuid").ifBlank { UUID.randomUUID().toString() }
        val fileId = file.id.removePrefix("f:")
        val userId = file.extra.izText("userId")
        val now = System.currentTimeMillis().toString()
        // These are public client protocol constants, not account credentials.
        val url = "$API/unproved/file/redirect".toHttpUrl().newBuilder()
            .addQueryParameter("downloadId", IlanzouProtocol.encrypt("$fileId|$userId"))
            .addQueryParameter("auth", IlanzouProtocol.encrypt("$fileId|$now"))
            .addQueryParameter("timestamp", IlanzouProtocol.encrypt(now))
            .addQueryParameter("enable", "1").addQueryParameter("devType", "6")
            .addQueryParameter("uuid", uuid).addQueryParameter("shareId", session.share.shareId).build().toString()
        val response = request(url)
        val target = if (response.status in 300..399) {
            response.headers["Location"] ?: throw NetDiskException("invalid_response", "蓝奏优享版下载跳转地址为空。")
        } else {
            val json = check(response)
            json.izText("url").ifBlank { (json["data"] as? JsonObject)?.izText("url").orEmpty() }
                .takeIf { it.isNotBlank() } ?: throw NetDiskException("download_unavailable", "蓝奏优享版未提供下载地址，请在官网检查文件权限。")
        }
        val resolved = LanzouProtocol.absolute(API, target)
        return NetDiskDownload(resolved, file.name, file.mimeType,
            mapOf("User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Referer" to "https://www.ilanzou.com/"))
    }

    private suspend fun listRequest(share: NetDiskShare, uuid: String, directoryId: String, page: Int): JsonObject {
        val builder = "$API/unproved/share/list".toHttpUrl().newBuilder()
            .addQueryParameter("uuid", uuid).addQueryParameter("devCode", uuid)
            .addQueryParameter("devType", "6").addQueryParameter("devModel", "Chrome")
            .addQueryParameter("devVersion", "125").addQueryParameter("appVersion", "")
            .addQueryParameter("timestamp", IlanzouProtocol.encrypt(System.currentTimeMillis().toString()))
            .addQueryParameter("extra", "2").addQueryParameter("shareId", share.shareId)
            .addQueryParameter("offset", page.toString()).addQueryParameter("limit", PAGE_SIZE.toString())
        if (directoryId.isNotBlank()) builder.addQueryParameter("folderId", directoryId.removePrefix("d:"))
        if (share.password.isNotBlank()) builder.addQueryParameter("code", share.password)
        return check(request(builder.build().toString()))
    }

    private suspend fun request(url: String): NetDiskResponse {
        repeat(3) {
            val response = http.get(url, buildMap {
                put("User-Agent", NetDiskHttp.WEB_USER_AGENT); put("Accept", "application/json, text/plain, */*")
                put("Origin", "https://www.ilanzou.com"); put("Referer", "https://www.ilanzou.com/")
                put("Accept-Language", "zh-CN,zh;q=0.9"); put("sec-ch-ua-mobile", "?0")
                put("sec-ch-ua-platform", "\"Windows\"")
                put("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                put("Sec-Fetch-Dest", "empty"); put("Sec-Fetch-Mode", "cors"); put("Sec-Fetch-Site", "same-site")
                challengeCookies[URI(url).host.orEmpty()]?.let { value -> put("Cookie", "acw_sc__v2=$value") }
            }, followRedirects = false)
            val challenge = LanzouProtocol.challenge(response.body)
            if (challenge == null) return response
            challengeCookies[URI(url).host.orEmpty()] = challenge
        }
        throw NetDiskException("verification_required", "蓝奏优享版需要进一步网页验证，请在官网完成后重试。")
    }

    private fun check(response: NetDiskResponse): JsonObject {
        val json = response.json()
        val code = json.izLong("code")
        if (response.status in 200..299 && code == 200L) return json
        val message = json.izText("msg").ifBlank { json.izText("message") }.let(LanzouProtocol::plain).take(250)
        val reason = when {
            message.contains("密码") || message.contains("提取码") -> "password_required"
            message.contains("不存在") || message.contains("过期") || message.contains("取消") -> "share_expired"
            response.status == 429 || message.contains("频繁") -> "rate_limited"
            response.status in setOf(401, 403) || code in setOf(-1L, -2L) -> "auth_required"
            else -> "provider_error"
        }
        throw NetDiskException(reason, message.ifBlank { "蓝奏优享版请求失败（HTTP ${response.status} / $code）。" })
    }

    private companion object {
        const val API = "https://apix.ilanzou.com"
        const val PAGE_SIZE = 60
    }
}

internal object IlanzouProtocol {
    fun encrypt(plain: String): String = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec("lanZouY-disk-app".toByteArray(Charsets.UTF_8), "AES"))
        doFinal(plain.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    fun parseItem(row: JsonObject): NetDiskFile? {
        val folder = row.izLong("fileType") == 2L
        val id = if (folder) row.izText("folderId") else row.izText("fileId").ifBlank { row.izText("fileIds") }.ifBlank { row.izText("id") }
        if (id.isBlank() || id == "0") return null
        val name = (if (folder) row.izText("folderName") else row.izText("fileName")).ifBlank { row.izText("name") }.ifBlank { id }
        // The share API reports KB. Preserve fractional values and reject overflow.
        val size = ((row["fileSize"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 1024.0
        return NetDiskFile((if (folder) "d:" else "f:") + id, name, folder,
            size.takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE }?.toLong() ?: 0,
            extra = buildJsonObject { put("userId", row.izText("userId")) })
    }
}

private fun JsonObject.izText(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.izLong(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
