package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.NetDiskException
import com.constx.onvyra.data.netdisk.NetDiskResponse
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun JsonObject.ndText(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: default
internal fun JsonObject.ndLong(key: String, default: Long = 0L): Long =
    ndText(key).toLongOrNull() ?: default
internal fun JsonObject.ndObject(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.ndArray(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.ndFlag(key: String): Boolean = ndText(key).let { it == "true" || it == "1" }
internal fun ndJson(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
internal fun ndStrings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

internal fun ndPage(cursor: String?): Int = (cursor?.toIntOrNull() ?: if (cursor == null) 1 else 0).also {
    if (it !in 1..100) throw NetDiskException("invalid_cursor", "目录页码无效，请重新打开分享。")
}

internal fun ndDownloadUrl(value: String): String = value.trim().toHttpUrlOrNull()
    ?.takeIf { it.username.isBlank() && it.password.isBlank() }
    ?.toString() ?: throw NetDiskException("invalid_download_url", "网盘未返回有效下载地址。")

internal fun ndMessage(root: JsonObject, fallback: String): String {
    val text = root.ndText("message").ifBlank { root.ndText("msg") }.ifBlank { root.ndText("Message") }
    return text.takeIf { it.isNotBlank() }?.replace(Regex("https?://\\S+"), "[地址]")
        ?.replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+"), "[凭据]")
        ?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(180) ?: fallback
}

internal fun ndFailure(response: NetDiskResponse, root: JsonObject, fallback: String): Nothing {
    val code = root.ndText("code").ifBlank { root.ndText("Code") }
    val message = ndMessage(root, fallback)
    val reason = when {
        response.status == 429 -> "rate_limited"
        code == "23018" -> "file_limit"
        code in setOf("31001", "AccessTokenInvalid", "AccessTokenExpired", "InvalidAccessToken", "401") || response.status == 401 -> "auth_required"
        message.contains("提取码") || message.contains("分享密码") || code.contains("ShareLinkPwd", true) -> "password_required"
        response.status == 404 || code in setOf("41006", "14001", "ShareLinkNotFound", "ShareLinkExpired") -> "share_expired"
        code in setOf("41020", "ShareLinkTokenInvalid", "ShareLinkTokenExpired") -> "share_token_expired"
        response.status == 403 -> "verification_required"
        else -> "provider_error"
    }
    val prefix = when (reason) {
        "auth_required" -> "需要登录或更新网盘凭据"
        "file_limit" -> "网盘限制了此文件的直接下载，请登录后重试"
        "rate_limited" -> "网盘请求过于频繁，请稍后重试"
        else -> fallback
    }
    throw NetDiskException(reason, "$prefix：$message".take(260))
}

internal fun ndCloudResult(response: NetDiskResponse, fallback: String): JsonObject {
    val root = response.json()
    val status = root.ndLong("status", -1)
    val code = root.ndLong("code", -1)
    if (response.status !in 200..299 || (status != -1L && status != 200L) ||
        (code != -1L && code != 0L) || (status == -1L && code == -1L))
        ndFailure(response, root, fallback)
    return root
}
