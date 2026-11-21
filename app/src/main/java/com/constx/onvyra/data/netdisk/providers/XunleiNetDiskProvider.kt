/*
 * Xunlei share wire protocol adapted from YunX (CYQawa), cc9afe24c9409b239c361263b976f7aad77d1d9e,
 * and YunGet (jiayuxuan123), 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa and YunGet contributors. AGPL-3.0-or-later; see LICENSE.
 * Onvyra adaptation: explicit, retained transfer folders; no automatic cloud deletion.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class XunleiNetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.XUNLEI
    private val guestDeviceId = UUID.randomUUID().toString().replace("-", "")
    private data class Saved(val folder: String, var file: String = "", var submitted: Boolean = false)
    private val transferLock = Mutex()
    private val savedFiles = LinkedHashMap<String, Saved>()
    private data class Captcha(val value: String, val expires: Long)
    private val captchas = java.util.concurrent.ConcurrentHashMap<String, Captcha>()

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val auth = XunleiProtocol.credential(credential, guestDeviceId)
        val response = sharePage(share, auth, "", "", "")
        requirePassCode(response)
        return NetDiskSession(share, response.xlText("title").ifBlank { "迅雷分享" }, response.xlText("pass_code_token"),
            buildJsonObject { put("initial", response) })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        val auth = XunleiProtocol.credential(credential, guestDeviceId)
        val json = if (directoryId.isBlank() && cursor == null) session.extra["initial"] as? JsonObject
            ?: sharePage(session.share, auth, "", "", session.token)
        else sharePage(session.share, auth, directoryId, cursor.orEmpty(), session.token)
        requirePassCode(json)
        val files = (json["files"] as? JsonArray)
            ?: throw NetDiskException("invalid_response", "迅雷未提供可识别的分享文件列表。")
        val next = json.xlText("next_page_token").takeIf { it.isNotBlank() && it != cursor }
        return NetDiskListing(files.mapNotNull { (it as? JsonObject)?.let(XunleiProtocol::file) }, next)
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("directory", "请先打开文件夹，再选择文件。")
        file.extra.xlText("download_url").takeIf(String::isNotBlank)?.let { return download(it, file) }
        val auth = XunleiProtocol.credential(credential, guestDeviceId)
        if (auth.access.isBlank()) throw NetDiskException("auth_required", "迅雷分享取链需要登录，请先导入迅雷网页登录凭据。")
        if (session.extra["allow_transfer"]?.jsonPrimitive?.booleanOrNull != true)
            throw NetDiskException("transfer_required", "迅雷需要先将此文件转存到你账号中新建的 Onvyra 临时目录，才能获取下载地址；转存文件会保留并占用空间。")
        return withTimeout(45_000) { transferLock.withLock {
            val key = XunleiProtocol.digest(auth.access) + ":" + session.share.shareId + ":" + file.id
            var state = savedFiles[key]
            if (state == null) {
                val folder = call("POST", "$API/drive/v1/files", auth, buildJsonObject {
                    put("kind", "drive#folder"); put("name", "Onvyra临时转存_${UUID.randomUUID()}")
                    put("parent_id", ""); put("space", "")
                }).xlText("id").takeIf(String::isNotBlank)
                    ?: throw NetDiskException("transfer_failed", "迅雷没有确认创建转存目录。")
                state = Saved(folder)
                savedFiles[key] = state
                // Keep a bounded retry ledger. Each entry represents only this feature's new folder.
                while (savedFiles.size > 64) savedFiles.remove(savedFiles.keys.first())
            }
            val saved = requireNotNull(state)
            if (!saved.submitted) {
                // Mark before sending: a lost response must not repeatedly copy the file on retries.
                saved.submitted = true
                val restored = call("POST", "$API/drive/v1/share/restore", auth, buildJsonObject {
                    put("share_id", session.share.shareId); put("pass_code_token", session.token)
                    put("parent_id", saved.folder); put("ancestor_ids", JsonArray(emptyList()))
                    put("file_ids", JsonArray(listOf(JsonPrimitive(file.id)))); put("specify_parent_id", true)
                }, onRejected = { saved.submitted = false })
                val traceElement = (restored["params"] as? JsonObject)?.get("trace_file_ids")
                val trace = when (traceElement) {
                    is JsonObject -> traceElement
                    is JsonPrimitive -> try { Json.parseToJsonElement(traceElement.content).jsonObject } catch (_: Exception) { null }
                    else -> null
                }
                saved.file = trace?.xlText(file.id).orEmpty().ifBlank { restored.xlText("file_id") }
            }
            if (saved.file.isBlank()) {
                for (attempt in 0 until 12) {
                    val listing = call("GET", "$API/drive/v1/files".toHttpUrl().newBuilder()
                        .addQueryParameter("parent_id", saved.folder).addQueryParameter("limit", "100").build().toString(), auth)
                    val candidates = (listing["files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(XunleiProtocol::file) }
                        .filter { !it.isDirectory && it.name == file.name && (file.size <= 0 || it.size <= 0 || file.size == it.size) }
                    if (candidates.size > 1) throw NetDiskException("ambiguous_transfer", "迅雷转存目录存在多个同名文件，无法确认目标；请在官网检查。")
                    candidates.singleOrNull()?.let { saved.file = it.id }
                    if (saved.file.isNotBlank()) break
                    delay(750)
                }
            }
            if (saved.file.isBlank()) throw NetDiskException("transfer_pending", "迅雷尚未确认转存结果；临时目录已保留，重试将检查该目录，不会再次复制。")
            val detail = call("GET", "$API/drive/v1/files/${segment(saved.file)}".toHttpUrl().newBuilder()
                .addQueryParameter("_magic", "2021").addQueryParameter("usage", "PLAY")
                .addQueryParameter("thumbnail_size", "SIZE_LARGE").addQueryParameter("with", "task").build().toString(), auth)
            val url = XunleiProtocol.directUrl(detail)
            if (url.isBlank()) throw NetDiskException("download_unavailable", "迅雷转存成功，但没有返回下载地址；文件已保留在 Onvyra 临时目录中。")
            download(url, file).copy(note = "已转存到你网盘的新建 Onvyra 临时目录，文件已保留，请按需自行管理。")
        } }
    }

    private suspend fun sharePage(share: NetDiskShare, auth: XunleiCredential, parent: String, cursor: String, token: String): JsonObject {
        val url = (if (parent.isBlank()) "$API/drive/v1/share" else "$API/drive/v1/share/detail").toHttpUrl().newBuilder()
            .addQueryParameter("share_id", share.shareId).addQueryParameter("limit", "100")
            .addQueryParameter("page_token", cursor).addQueryParameter("thumbnail_size", "SIZE_SMALL")
        if (parent.isBlank()) url.addQueryParameter("pass_code", share.password)
        else { url.addQueryParameter("parent_id", parent); url.addQueryParameter("pass_code_token", token) }
        return call("GET", url.build().toString(), auth)
    }

    private suspend fun call(method: String, url: String, auth: XunleiCredential, body: JsonObject? = null,
        onRejected: (() -> Unit)? = null): JsonObject {
        val action = "$method:${url.toHttpUrl().encodedPath}"
        var captcha = captchas[auth.key]?.takeIf { it.expires > System.currentTimeMillis() }?.value ?: auth.captcha
        repeat(2) { attempt ->
            val headers = buildMap {
                put("User-Agent", NetDiskHttp.WEB_USER_AGENT); put("X-Device-Id", auth.device)
                put("X-Client-Version", if (auth.client == XunleiProtocol.WEB_CLIENT) "1.93.10" else "8.31.0.9726")
                put("Origin", "https://pan.xunlei.com"); put("Referer", "https://pan.xunlei.com/")
                put("Cookie", "")
                if (auth.access.isNotBlank()) put("Authorization", "Bearer ${auth.access}")
                if (captcha.isNotBlank() && auth.access.isNotBlank()) put("X-Captcha-Token", captcha)
            }
            val response = if (method == "GET") http.get(url, headers) else http.postJson(url, body ?: JsonObject(emptyMap()), headers)
            val json = response.json()
            val error = json.xlText("error")
            if (response.status in 200..299 && error.isBlank()) return json["data"] as? JsonObject ?: json
            if (error == "captcha_invalid" && auth.access.isNotBlank() && attempt == 0) {
                try { captcha = initCaptcha(auth, action) }
                catch (failure: Exception) {
                    // The original mutation was explicitly rejected before a
                    // captcha refresh. A failed refresh did not dispatch it again.
                    onRejected?.invoke()
                    throw failure
                }
                return@repeat
            }
            val message = json.xlText("error_description").ifBlank { json.xlText("message") }.ifBlank { error }.take(250)
            val reason = when {
                response.status == 401 || error == "unauthenticated" -> "auth_required"
                error.contains("captcha") || error.contains("verification") -> "verification_required"
                response.status == 429 || error.contains("limit") -> "rate_limited"
                response.status == 404 || error.contains("not_found") -> "share_expired"
                else -> "provider_error"
            }
            // A 5xx, malformed response or transport cancellation is ambiguous:
            // retain the ledger and inspect the same folder on the next attempt.
            // An explicit client/business rejection permits retrying this folder.
            if (response.status in 400..499 && response.status != 408 || response.status in 200..299 && error.isNotBlank())
                onRejected?.invoke()
            throw NetDiskException(reason, message.ifBlank { "迅雷请求失败（HTTP ${response.status}）。" })
        }
        throw NetDiskException("verification_required", "迅雷验证令牌已失效，请重新导入同一网页登录会话的 access_token、device_id 和 captcha_token。")
    }

    private suspend fun initCaptcha(auth: XunleiCredential, action: String): String {
        val now = System.currentTimeMillis()
        val web = auth.client == XunleiProtocol.WEB_CLIENT
        val body = buildJsonObject {
            put("client_id", auth.client); put("action", action); put("device_id", auth.device)
            if (!web) put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
            put("meta", buildJsonObject {
                put("client_version", if (web) "1.93.10" else "8.31.0.9726")
                put("package_name", if (web) "pan.xunlei.com" else "com.xunlei.downloadprovider")
                put("timestamp", now.toString()); put("captcha_sign", XunleiProtocol.captchaSign(auth, now)); put("user_id", auth.user)
                if (web) { put("username", ""); put("phone_number", ""); put("email", "") }
            })
        }
        val response = http.postJson("https://xluser-ssl.xunlei.com/v1/shield/captcha/init", body, mapOf(
            "User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Origin" to "https://pan.xunlei.com", "Referer" to "https://pan.xunlei.com/", "Cookie" to ""))
        val json = response.json()
        val token = json.xlText("captcha_token")
        if (response.status !in 200..299 || token.isBlank())
            throw NetDiskException("verification_required", "迅雷验证签名未被接受，请在官网完成验证后重新导入同一会话的完整凭据。")
        val ttl = (json["expires_in"] as? JsonPrimitive)?.longOrNull?.coerceIn(1, 3600) ?: 300
        captchas[auth.key] = Captcha(token, now + (ttl * 1000 - 30_000).coerceAtLeast(1000))
        return token
    }

    private fun requirePassCode(json: JsonObject) {
        if (json.xlText("share_status") in setOf("PASS_CODE_EMPTY", "PASS_CODE_ERROR", "PASS_CODE_NEED"))
            throw NetDiskException("password_required", "迅雷分享提取码缺失或不正确，请填写后重试。")
    }
    private fun download(url: String, file: NetDiskFile): NetDiskDownload {
        val resolved = LanzouProtocol.absolute("https://pan.xunlei.com/", url)
        return NetDiskDownload(resolved, file.name, file.mimeType, mapOf("User-Agent" to NetDiskHttp.WEB_USER_AGENT, "Referer" to "https://pan.xunlei.com/"))
    }
    private fun segment(value: String): String = "https://placeholder.invalid/".toHttpUrl().newBuilder().addPathSegment(value).build().encodedPath.trimStart('/')
    private companion object { const val API = "https://api-pan.xunlei.com" }
}

internal data class XunleiCredential(val access: String, val device: String, val captcha: String, val user: String, val client: String) {
    val key: String get() = XunleiProtocol.digest(access) + ":" + device + ":" + client
}

internal object XunleiProtocol {
    const val WEB_CLIENT = "Xqp0kJBXWhwaTpB6"
    private const val APP_CLIENT = "Xp6vsxz_7IYVw2BB"
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun credential(value: String, fallbackDevice: String): XunleiCredential {
        val raw = value.trim()
        val json = if (raw.startsWith('{')) try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) {
            throw NetDiskException("invalid_credential", "迅雷凭据 JSON 格式不正确。")
        } else JsonObject(emptyMap())
        val access = if (!raw.startsWith('{')) raw.removePrefix("Bearer ").trim() else json.xlText("access_token").ifBlank { json.xlText("accessToken") }
        val payload = try {
            val part = access.split('.').getOrNull(1).orEmpty()
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)).jsonObject
        } catch (_: Exception) { JsonObject(emptyMap()) }
        val client = json.xlText("client_id").ifBlank { payload.xlText("client_id") }.ifBlank { WEB_CLIENT }
        if (client !in setOf(WEB_CLIENT, APP_CLIENT)) throw NetDiskException("invalid_credential", "该迅雷 Token 的登录通道不受支持，请导入网页版凭据。")
        return XunleiCredential(access,
            json.xlText("device_id").ifBlank { json.xlText("deviceId") }.ifBlank { fallbackDevice },
            json.xlText("captcha_token").ifBlank { json.xlText("captchaToken") },
            json.xlText("user_id").ifBlank { json.xlText("sub") }.ifBlank { payload.xlText("sub") }, client)
    }
    fun directUrl(json: JsonObject): String = ((json["links"] as? JsonObject)?.get("application/octet-stream") as? JsonObject)?.xlText("url")
        .orEmpty().ifBlank { json.xlText("web_content_link") }
    fun file(json: JsonObject): NetDiskFile? {
        val id = json.xlText("id"); if (id.isBlank()) return null
        return NetDiskFile(id, json.xlText("name").ifBlank { id }, json.xlText("kind") == "drive#folder",
            (json["size"] as? JsonPrimitive)?.longOrNull ?: 0L, extra = buildJsonObject { put("download_url", directUrl(json)) })
    }
    fun captchaSign(auth: XunleiCredential, timestamp: Long): String {
        val web = auth.client == WEB_CLIENT
        var value = auth.client + (if (web) "1.93.10" else "8.31.0.9726") +
            (if (web) "pan.xunlei.com" else "com.xunlei.downloadprovider") + auth.device + timestamp
        val salts = if (web) listOf(
            "DVM8BRQfJWDsIE0JbH5GCUM0z0jU1yGymBTj4Eyloazv1F+w0G7ig", "T", "a1fUxfjOO1/dW++DpGupRgw9P93L",
            "aktKbp+/lQc3MvlMHIupt65LjAgJOL6ttMFvTKK+7+WKubSf9rT", "SB/ne9FQUnQmWz4Wuv9KqKfGOBOCUubpHpECxOHfD4hNoMclYA2Yq",
            "sOZFBQ8IMFy3OFjAIf/eA65iqFvS9TIKzLyrhiT6", "ZhJdLgL7MGh2GanboB0yk", "Osf", "v")
        else listOf("9uJNVj/wLmdwKrJaVj/omlQ", "Oz64Lp0GigmChHMf/6TNfxx7O9PyopcczMsnf", "Eb+L7Ce+Ej48u", "jKY0",
            "ASr0zCl6v8W4aidjPK5KHd1Lq3t+vBFf41dqv5+fnOd", "wQlozdg6r1qxh0eRmt3QgNXOvSZO6q/GXK", "gmirk+ciAvIgA/cxUUCema47jr/YToixTT+Q6O",
            "5IiCoM9B1/788ntB", "P07JH0h6qoM6TSUAK2aL9T5s2QBVeY9JWvalf", "+oK0AN")
        for (salt in salts) value = MessageDigest.getInstance("MD5").digest((value + salt).toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return "1.$value"
    }
}

private fun JsonObject.xlText(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
