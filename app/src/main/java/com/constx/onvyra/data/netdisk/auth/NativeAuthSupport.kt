package com.constx.onvyra.data.netdisk.auth

import com.constx.onvyra.data.netdisk.*
import kotlinx.serialization.json.*

internal fun validNativeAccount(account: String, password: String): String {
    val value = account.trim()
    if (value.isBlank() || password.isEmpty() || value.length > 254 || password.length > 256 || value.any(Char::isISOControl))
        throw NetDiskException("invalid_input", "请输入有效账号和密码。")
    return value
}

internal fun nativeVerification(platform: String) = NetDiskException("verification_required", "${platform}要求进一步验证，请在官方页面完成后继续。")

internal fun nativeAuthJson(response: NetDiskResponse, platform: String): JsonObject {
    if (response.status == 429) throw NetDiskException("rate_limited", "请求过于频繁，请稍后重试。")
    if (response.status in 300..399 || response.status in setOf(403, 409) || response.body.trimStart().startsWith('<'))
        throw nativeVerification(platform)
    val json = runCatching { response.json() }.getOrNull()
    if (response.status !in 200..299 || json == null)
        throw NetDiskException("login_failed", "${platform}登录未完成，请重试或使用官方登录。")
    return json
}

internal fun JsonObject.authText(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.authNode() = this["data"] as? JsonObject ?: this
internal fun validNativeToken(value: String) = value.length in 16..16384 && value.none { it.isISOControl() || it.isWhitespace() }
