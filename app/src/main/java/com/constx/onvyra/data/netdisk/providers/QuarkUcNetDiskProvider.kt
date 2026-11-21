/*
 * Share protocol adapted from YunX, Copyright (C) 2026 CYQawa and contributors.
 * Source: CYQawa/YunX commit cc9afe24c9409b239c361263b976f7aad77d1d9e,
 * QuarkApi.kt / UcApi.kt. Licensed under GNU AGPL-3.0-or-later.
 * Onvyra adaptation keeps only read-only share listing and direct-link requests.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class QuarkNetDiskProvider(http: NetDiskHttp) : QuarkUcShareProvider(http, NetDiskPlatform.QUARK)
class UcNetDiskProvider(http: NetDiskHttp) : QuarkUcShareProvider(http, NetDiskPlatform.UC)

/** These endpoints accept share credentials; no save/copy/delete endpoint is called. */
open class QuarkUcShareProvider(private val http: NetDiskHttp, final override val platform: NetDiskPlatform) : NetDiskProvider {
    private val uc = platform == NetDiskPlatform.UC
    private val base = if (uc) "https://pc-api.uc.cn/1/clouddrive" else "https://drive-pc.quark.cn/1/clouddrive"
    private val origin = if (uc) "https://drive.uc.cn" else "https://pan.quark.cn"
    private val guestCookies = ConcurrentHashMap<String, String>()
    private val apiAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        (if (uc) "uc-cloud-drive/2.5.20" else "quark-cloud-drive/2.5.20") +
        " Chrome/100.0.4896.160 Electron/18.3.5.12 Safari/537.36"

    private fun endpoint(path: String, extra: Map<String, String> = emptyMap()): String =
        "$base/$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("pr", if (uc) "UCBrowser" else "ucpro")
            addQueryParameter("fr", "pc")
            extra.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()

    private fun headers(cookie: String, transfer: Boolean = false): Map<String, String> = mapOf(
        // An explicit empty Cookie also prevents a prior account cookie from
        // being inherited by an anonymous request in the host transport.
        "Cookie" to cookie,
        "User-Agent" to apiAgent,
        "Origin" to if (uc && transfer) "https://fast.uc.cn" else origin,
        "Referer" to if (uc && transfer) "https://fast.uc.cn/" else "$origin/",
        "Accept" to "application/json, text/plain, */*",
    )

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val response = http.postJson(endpoint("share/sharepage/token"), buildJsonObject {
            put("pwd_id", share.shareId); put("passcode", share.password)
            if (uc) put("share_for_transfer", true) else put("support_visit_limit_private_share", true)
        }, headers(credential))
        val data = ndCloudResult(response, "获取分享访问凭证失败").ndObject("data")
            ?: throw NetDiskException("invalid_response", "网盘没有返回分享访问凭证。")
        val token = data.ndText("stoken").takeIf(String::isNotBlank)
            ?: throw NetDiskException("invalid_response", "分享访问凭证为空，请检查提取码。")
        if (credential.isBlank()) rememberGuestCookie(token, response)
        return NetDiskSession(share, data.ndText("title").ifBlank { "${platform.label}分享" }, token)
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        val page = ndPage(cursor)
        val pageSize = if (uc) 50 else 100
        val path = if (uc) "transfer_share/detail" else "share/sharepage/detail"
        val params = linkedMapOf("pwd_id" to session.share.shareId, "stoken" to session.token,
            "pdir_fid" to directoryId.ifBlank { "0" }, "_page" to page.toString(), "_size" to pageSize.toString(),
            "_fetch_total" to "1", "_sort" to "file_type:asc,file_name:asc")
        if (uc) params.putAll(mapOf("entry" to "ft", "fetch_file_list" to "1", "passcode" to session.share.password,
            "_fetch_task" to "1", "_fetch_share" to "1"))
        else params.putAll(mapOf("ver" to "2", "force" to "0", "_fetch_banner" to "0", "_fetch_share" to "0"))
        val response = http.get(endpoint(path, params), headers(cookieFor(session, credential), transfer = uc))
        if (credential.isBlank()) rememberGuestCookie(session.token, response)
        val data = ndCloudResult(response, "读取分享目录失败").ndObject("data")
            ?: throw NetDiskException("invalid_response", "网盘没有返回目录数据。")
        val list = data.ndArray("list") ?: data.ndObject("detail_info")?.ndArray("list")
            ?: throw NetDiskException("invalid_response", "网盘没有返回文件列表。")
        val files = list.mapNotNull { it as? JsonObject }.map { file ->
            val id = file.ndText("fid").takeIf(String::isNotBlank)
                ?: throw NetDiskException("invalid_response", "文件列表缺少文件编号。")
            NetDiskFile(id, file.ndText("file_name").ifBlank { file.ndText("fname", "未命名文件") },
                file.ndFlag("dir") || file.ndFlag("isdir"), file.ndLong("size", file.ndLong("fsize")),
                file.ndText("mime_type", "application/octet-stream"), buildJsonObject {
                    put("fid_token", file.ndText("share_fid_token").ifBlank { file.ndText("fid_token") })
                })
        }
        return NetDiskListing(files, if (list.size >= pageSize && page < 100) (page + 1).toString() else null,
            data.ndObject("share")?.ndText("title")?.takeIf(String::isNotBlank))
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        val fidToken = file.extra.ndText("fid_token").takeIf(String::isNotBlank)
            ?: throw NetDiskException("share_token_expired", "文件分享凭证缺失，请重新读取所在目录。")
        val body = buildJsonObject {
            put("fids", ndStrings(file.id)); put("fids_token", ndStrings(fidToken))
            put("pwd_id", session.share.shareId); put("stoken", session.token)
            if (!uc) { put("speedup_session", ""); put("token", "") }
        }
        val requestCookie = cookieFor(session, credential)
        val response = http.postJson(endpoint("file/download", if (uc) mapOf("entry" to "ft") else emptyMap()),
            body, headers(requestCookie))
        if (credential.isBlank()) rememberGuestCookie(session.token, response)
        val root = ndCloudResult(response, "获取文件下载地址失败")
        val item = root.ndArray("data")?.firstOrNull() as? JsonObject
            ?: throw NetDiskException("invalid_response", "网盘未返回文件下载信息。")
        val url = ndDownloadUrl(item.ndText("download_url"))
        val downloadHeaders = linkedMapOf("User-Agent" to apiAgent, "Referer" to "$origin/")
        val host = url.toHttpUrl().host
        // Guest cookies are narrow, response-bound download credentials. Never
        // attach an entire account cookie to a CDN supplied by a share response.
        val cookie = if (credential.isBlank()) guestCookies[session.token].orEmpty()
        else if (host == (if (uc) "uc.cn" else "quark.cn") || host.endsWith(if (uc) ".uc.cn" else ".quark.cn"))
            accountDownloadCookie(requestCookie, response) else ""
        if (cookie.isNotBlank()) downloadHeaders["Cookie"] = cookie
        return NetDiskDownload(url, item.ndText("file_name").ifBlank { item.ndText("filename").ifBlank { file.name } },
            file.mimeType, downloadHeaders,
            if (credential.isBlank()) "游客下载可能受网盘大小或频率限制；链接具有时效，请及时下载。" else "链接具有时效，请及时下载。")
    }

    private fun cookieFor(session: NetDiskSession, credential: String): String =
        credential.ifBlank { guestCookies[session.token].orEmpty() }

    private fun rememberGuestCookie(token: String, response: NetDiskResponse) {
        response.headers.values("Set-Cookie").map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("__pugs=") && it.length > 7 }?.let {
                if (guestCookies.size >= 32) guestCookies.clear()
                guestCookies[token] = it
            }
    }

    private fun accountDownloadCookie(current: String, response: NetDiskResponse): String {
        val values = current.split(';').map(String::trim).filter(String::isNotEmpty).associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }.toMutableMap()
        response.headers.values("Set-Cookie").forEach { value ->
            val entry = value.substringBefore(';').trim()
            values[entry.substringBefore('=')] = entry.substringAfter('=', "")
        }
        return listOf("__pus", "__puus").mapNotNull { name -> values[name]?.let { "$name=$it" } }.joinToString("; ")
    }
}
