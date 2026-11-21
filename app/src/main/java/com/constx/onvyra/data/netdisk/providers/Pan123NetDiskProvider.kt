/*
 * 123 share protocol/signature adapted from YunX, Copyright (C) 2026 CYQawa
 * and contributors. Source: CYQawa/YunX commit
 * cc9afe24c9409b239c361263b976f7aad77d1d9e, Pan123Api.kt / Pan123Constants.kt.
 * GNU AGPL-3.0-or-later; see the project's LICENSE.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.CRC32
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class Pan123NetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.PAN123
    private val deviceId = UUID.randomUUID().toString().replace("-", "")

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val session = NetDiskSession(share, "123 云盘分享")
        val first = fetchPage(session, "", null)
        return session.copy(title = first.ndText("ShareName").ifBlank { session.title },
            extra = buildJsonObject { put("first_page", first) })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        val (page, _) = pageCursor(cursor)
        val data = if (directoryId.isBlank() && cursor == null) session.extra.ndObject("first_page")
            ?: fetchPage(session, directoryId, cursor) else fetchPage(session, directoryId, cursor)
        return pan123Listing(data, page)
    }

    private suspend fun fetchPage(session: NetDiskSession, directoryId: String, cursor: String?): JsonObject {
        val (page, next) = pageCursor(cursor)
        val url = "https://yun.123pan.cn/b/api/share/get".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", "100"); addQueryParameter("next", next)
            addQueryParameter("orderBy", "file_name"); addQueryParameter("orderDirection", "asc")
            addQueryParameter("shareKey", session.share.shareId)
            addQueryParameter("ParentFileId", directoryId.ifBlank { "0" }); addQueryParameter("Page", page.toString())
            if (session.share.password.isNotBlank()) addQueryParameter("SharePwd", session.share.password)
        }.build().toString()
        val response = http.get(url, mapOf("User-Agent" to "Dart/3.12 (dart:io)", "Cookie" to ""))
        val root = pan123Result(response, "读取 123 分享目录失败")
        val data = root.ndObject("data") ?: throw NetDiskException("invalid_response", "123 云盘未返回目录数据。")
        if (data.ndFlag("Expired")) throw NetDiskException("share_expired", "该 123 分享已失效。")
        return data
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        val path = "/b/api/share/download/info"
        val (authKey, authValue) = pan123Signature(path)
        val headers = linkedMapOf("User-Agent" to NetDiskHttp.WEB_USER_AGENT, "platform" to "android",
            "app-version" to "39", "loginuuid" to deviceId, "auth-key" to authKey,
            "auth-value" to authValue, "Origin" to "https://www.123865.com",
            "Referer" to "https://yun.123pan.cn/", "Cookie" to "")
        if (credential.isNotBlank()) headers["Authorization"] = ndBearer(credential)
        val response = http.postJson("https://www.123865.com$path", buildJsonObject {
            put("ShareKey", session.share.shareId); put("FileID", file.id)
            put("S3KeyFlag", file.extra.ndText("s3_key_flag")); put("Size", file.size)
            put("Etag", file.extra.ndText("etag"))
        }, headers)
        val root = pan123Result(response, "获取 123 下载地址失败")
        val data = root.ndObject("data") ?: throw NetDiskException("invalid_response", "123 云盘未返回下载信息。")
        val raw = data.ndText("DownloadURL").ifBlank { data.ndText("downloadURL") }.ifBlank { data.ndText("downloadUrl") }
            .ifBlank { data.ndText("redirect_url") }
        val direct = pan123DecodeDownloadUrl(raw)
        return NetDiskDownload(direct, file.name, file.mimeType,
            mapOf("Referer" to "https://yun.123pan.cn/", "User-Agent" to "Dart/3.12 (dart:io)"),
            "链接具有时效；网盘可能要求登录或受流量限制。")
    }

    private fun pageCursor(cursor: String?): Pair<Int, String> {
        if (cursor == null) return 1 to "0"
        val index = cursor.indexOf(':')
        val page = cursor.substringBefore(':').toIntOrNull() ?: 0
        if (index < 0 || page !in 2..100 || cursor.length > 512)
            throw NetDiskException("invalid_cursor", "123 目录页码无效，请重新打开分享。")
        return page to cursor.substring(index + 1)
    }
}

internal fun ndBearer(credential: String): String = "Bearer " + credential.trim().replace(Regex("(?i)^Bearer\\s+"), "")

internal fun pan123Result(response: NetDiskResponse, fallback: String): JsonObject {
    val root = response.json()
    // Verified on a real anonymous share: this is an account/access requirement,
    // not a malformed link or a transport error. Do not retry alternate hosts to
    // evade the provider's restriction, and never claim a download succeeded.
    if (root.ndLong("code", -1) == 5112L) throw NetDiskException("auth_required",
        "123 云盘要求注册登录或付费后才能下载此文件，请在“账号与登录”中登录本人账号后重试。")
    if (response.status !in 200..299 || root.ndLong("code", -1) !in setOf(0L, 200L)) ndFailure(response, root, fallback)
    return root
}

internal fun pan123Listing(data: JsonObject, page: Int): NetDiskListing {
    val values = data.ndArray("InfoList") ?: throw NetDiskException("invalid_response", "123 云盘未返回文件列表。")
    val files = values.mapNotNull { it as? JsonObject }.map { file ->
        val id = file.ndText("FileId").ifBlank { file.ndText("FileID") }
        if (id.isBlank()) throw NetDiskException("invalid_response", "123 文件列表缺少文件编号。")
        NetDiskFile(id, file.ndText("FileName").ifBlank { "未命名文件" }, file.ndLong("Type") == 1L,
            file.ndLong("Size"), extra = buildJsonObject {
                put("s3_key_flag", file.ndText("S3KeyFlag")); put("etag", file.ndText("Etag"))
            })
    }
    val next = data.ndText("Next", "-1")
    return NetDiskListing(files, if (next != "-1" && files.isNotEmpty() && page < 100) "${page + 1}:$next" else null,
        data.ndText("ShareName").takeIf(String::isNotBlank))
}

internal fun pan123Signature(path: String, seconds: Long = System.currentTimeMillis() / 1000,
    random: Int = ThreadLocalRandom.current().nextInt(0, 10_000_000)): Pair<String, String> {
    require(path.startsWith('/') && !path.contains('?') && random in 0 until 10_000_000)
    fun crc32(value: String): String = CRC32().apply { update(value.toByteArray(Charsets.UTF_8)) }.value.toString(16)
    val minute = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.US).withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochSecond(seconds + 57_600))
    val table = "adefghlmyijnopkqrstubcvwsz"
    val key = crc32(minute.map { table[it - '0'] }.joinToString(""))
    return key to "$seconds-$random-${crc32("$seconds|$random|$path|web|3|$key")}"
}

/** Decode only the provider's known wrappers; never fetch a file just to probe its type. */
internal fun pan123DecodeDownloadUrl(raw: String): String {
    fun decode(value: String): String? = runCatching {
        String(Base64.getDecoder().decode(value.replace('-', '+').replace('_', '/').replace(' ', '+')), Charsets.UTF_8)
    }.getOrNull()
    var value = raw.trim()
    if (!value.contains("://")) value = decode(value).orEmpty()
    val wrapped = value.toHttpUrlOrNull()
    val encoded = wrapped?.queryParameter("params")
    if (!encoded.isNullOrBlank()) value = decode(encoded) ?: throw NetDiskException("invalid_download_url", "123 下载包装地址无效。")
    val parsed = ndDownloadUrl(value).toHttpUrl()
    return if (parsed.queryParameter("auto_redirect") == "0") parsed.newBuilder().setQueryParameter("auto_redirect", "1").build().toString()
        else parsed.toString()
}
