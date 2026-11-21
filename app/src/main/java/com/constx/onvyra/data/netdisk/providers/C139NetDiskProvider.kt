/*
 * China Mobile share protocol adapted from YunX, Copyright (C) 2026 CYQawa
 * and contributors. Source: CYQawa/YunX commit
 * cc9afe24c9409b239c361263b976f7aad77d1d9e, C139Api.kt / C139Constants.kt.
 * GNU AGPL-3.0-or-later; see the project's LICENSE.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Direct official share requests. No account copy, temporary folder, or delete operation exists here. */
class C139NetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.C139

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val info = post("getOutLinkGeneral", buildJsonObject {
            put("getOutLinkGeneralReq", buildJsonObject {
                put("linkID", share.shareId); put("isPasswd", 1); put("account", "")
            })
        }, anonymous = true)
        val general = info.ndObject("data")?.ndObject("getOutLinkGeneralResp")
            ?.ndArray("outLinkGeneral")?.firstOrNull() as? JsonObject
        val effective = share.copy(password = share.password.ifBlank { general?.ndText("passwd").orEmpty() })
        val initial = listData(effective, "root", 1)
        return NetDiskSession(effective, general?.ndText("lkName")?.takeIf(String::isNotBlank) ?: "移动云盘分享",
            extra = buildJsonObject { put("initial", initial) })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?,
        credential: String): NetDiskListing {
        require(session.share.platform == platform)
        val begin = C139Protocol.pageStart(cursor)
        val data = if (directoryId.isBlank() && cursor == null) session.extra.ndObject("initial")
            ?: listData(session.share, "root", begin)
            else listData(session.share, directoryId.ifBlank { "root" }, begin)
        return C139Protocol.listing(data, begin)
    }

    private suspend fun listData(share: NetDiskShare, directory: String, begin: Int): JsonObject {
        val response = post("getOutLinkInfoV6", buildJsonObject {
            put("getOutLinkInfoReq", buildJsonObject {
                // The official list endpoint requires an anonymous context, even after login.
                put("account", ""); put("linkID", share.shareId); put("passwd", share.password)
                put("caSrt", 1); put("coSrt", 1); put("srtDr", 0)
                put("bNum", begin); put("pCaID", directory); put("eNum", begin + PAGE_SIZE - 1)
            })
        }, anonymous = true)
        return response.ndObject("data")
            ?: throw NetDiskException("invalid_response", "移动云盘未返回目录数据。")
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile,
        credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("is_directory", "请先打开文件夹，再选择文件。")
        val login = C139Protocol.login(credential)
        val account = login.account ?: throw NetDiskException("auth_required",
            "移动云盘取下载地址需要登录，请在账号中网页登录并导入包含完整账号的登录信息。")
        val response = post("dlFromOutLinkV3", buildJsonObject {
            put("dlFromOutLinkReqV3", buildJsonObject {
                put("account", account); put("linkID", session.share.shareId)
                put("coIDLst", buildJsonObject { put("item", ndStrings(file.id)) })
                put("commonAccountInfo", buildJsonObject { put("account", account); put("accountType", 1) })
            })
        }, anonymous = false, authorization = login.authorization)
        val data = response.ndObject("data")
            ?: throw NetDiskException("invalid_response", "移动云盘未返回下载信息。")
        val url = ndDownloadUrl(data.ndText("redrUrl"))
        // OBS/CDN addresses already carry their own signature; never export account cookies or Authorization.
        return NetDiskDownload(url, file.name, file.mimeType,
            mapOf("User-Agent" to C139Protocol.MOBILE_UA, "Referer" to "https://yun.139.com/"),
            "移动云盘直链具有时效，请及时下载。")
    }

    private suspend fun post(action: String, body: JsonObject, anonymous: Boolean,
        authorization: String? = null): JsonObject {
        val plaintext = body.toString()
        val headers = linkedMapOf(
            "Cookie" to "", "hcy-cool-flag" to "1",
            "x-deviceinfo" to "||3|12.27.0|||||chrome 150.0.0.0|360X444|zh-cn|||",
            "x-huawei-channelsrc" to "10245500", "x-mm-source" to "0002",
            "User-Agent" to C139Protocol.MOBILE_UA, "Origin" to "https://yun.139.com",
            "Referer" to "https://yun.139.com/", "Accept" to "application/json, text/plain, */*",
        )
        if (!anonymous) {
            headers["mcloud-sign"] = C139Protocol.signHeader(plaintext)
            if (!authorization.isNullOrBlank()) headers["Authorization"] = authorization
        }
        val response = http.request("POST", "$SHARE_BASE/$action", headers,
            C139Protocol.encrypt(plaintext).toRequestBody("application/json;charset=UTF-8".toMediaType()),
            followRedirects = false)
        val decoded = C139Protocol.decodeResponse(response.body)
        C139Protocol.check(response.status, decoded)
        return decoded
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val SHARE_BASE = "https://share-kd-njs.yun.139.com/yun-share/richlifeApp/devapp/IOutLink"
    }
}

/** Pure protocol helpers, shared by the native adapter and deterministic JVM tests. */
internal object C139Protocol {
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    private val key = SecretKeySpec("PVGDwmcvfs1uV3d1".toByteArray(Charsets.UTF_8), "AES")
    private const val MAX_PLAIN_BYTES = 8 * 1024 * 1024
    private val random = SecureRandom()
    data class Login(val account: String?, val authorization: String?)

    fun encrypt(plaintext: String, iv: ByteArray = ByteArray(16).also(random::nextBytes)): String {
        require(iv.size == 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decodeResponse(body: String): JsonObject {
        val trimmed = body.trim().removePrefix("\uFEFF")
        val text = if (trimmed.startsWith('{')) trimmed else {
            try {
                val encoded = if (trimmed.startsWith('"'))
                    (Json.parseToJsonElement(trimmed) as? JsonPrimitive)?.contentOrNull.orEmpty() else trimmed
                val raw = Base64.getMimeDecoder().decode(encoded)
                if (raw.size < 32 || (raw.size - 16) % 16 != 0) throw IllegalArgumentException()
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(raw.copyOfRange(0, 16)))
                val bytes = cipher.doFinal(raw.copyOfRange(16, raw.size))
                if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                        ByteArrayOutputStream().use { out ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = gzip.read(buffer)
                                if (read < 0) break
                                if (out.size() + read > MAX_PLAIN_BYTES)
                                    throw NetDiskException("response_too_large", "移动云盘响应过大，已停止读取。")
                                out.write(buffer, 0, read)
                            }
                            out.toString(Charsets.UTF_8.name())
                        }
                    }
                } else bytes.toString(Charsets.UTF_8)
            } catch (error: NetDiskException) { throw error }
            catch (_: Exception) { throw NetDiskException("invalid_response", "移动云盘返回的数据无法解密，请稍后重试。") }
        }
        return try { Json.parseToJsonElement(text).jsonObject }
        catch (_: Exception) { throw NetDiskException("invalid_response", "移动云盘未返回可识别的数据。") }
    }

    fun check(status: Int, root: JsonObject) {
        val code = root.ndText("resultCode").ifBlank { root.ndText("code") }
        val successfulCode = code.isBlank() || code.toLongOrNull() == 0L
        val successFlag = (root["success"] as? JsonPrimitive)?.booleanOrNull
        val rejected = successFlag == false
        val explicitSuccess = code.toLongOrNull() == 0L || successFlag == true
        if (status in 200..299 && successfulCode && explicitSuccess && !rejected && root["data"] is JsonObject) return
        val description = root.ndText("desc").ifBlank { root.ndText("message") }
        val reason = when {
            status == 429 -> "rate_limited"
            code == "9188" || description.contains("密码") || description.contains("提取码") -> "password_required"
            status in setOf(401, 403) || code in setOf("04000005", "401", "9115", "9113") ||
                description.contains("登录") || description.contains("认证") -> "auth_required"
            status == 404 || description.contains("失效") || description.contains("取消分享") -> "share_expired"
            successfulCode && !rejected && status in 200..299 -> "invalid_response"
            else -> "provider_error"
        }
        val message = when (reason) {
            "rate_limited" -> "移动云盘请求过于频繁，请稍后重试。"
            "password_required" -> "移动云盘分享需要正确的提取码，请检查后重试。"
            "auth_required" -> "移动云盘登录信息已失效或不完整，请重新网页登录并导入。"
            "share_expired" -> "该移动云盘分享已失效或已被取消。"
            "invalid_response" -> "移动云盘未返回完整的分享数据。"
            else -> "移动云盘请求失败（代码 ${code.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,40}")) } ?: status.toString()}）。"
        }
        throw NetDiskException(reason, message)
    }

    fun pageStart(cursor: String?): Int = (cursor?.toIntOrNull() ?: if (cursor == null) 1 else 0).also {
        if (it !in 1..19_801 || (it - 1) % 200 != 0)
            throw NetDiskException("invalid_cursor", "移动云盘目录页码无效，请重新打开分享。")
    }

    fun listing(data: JsonObject, begin: Int): NetDiskListing {
        val folders = data.ndArray("caLst").orEmpty().mapNotNull { it as? JsonObject }.map { row ->
            NetDiskFile(row.ndText("caID"), row.ndText("caName").ifBlank { "未命名文件夹" }, true)
        }
        val contents = data.ndArray("coLst").orEmpty().mapNotNull { it as? JsonObject }.map { row ->
            NetDiskFile(row.ndText("coID"), row.ndText("coName").ifBlank { "未命名文件" },
                row.ndFlag("isdir") || row.ndLong("coType", 1) == 2L, row.ndLong("coSize"))
        }
        val all = (folders + contents).onEach {
            if (it.id.isBlank()) throw NetDiskException("invalid_response", "移动云盘目录缺少文件编号。")
        }.distinctBy { it.id }
        return NetDiskListing(all, (begin + 200).toString().takeIf { all.size >= 200 && begin < 19_801 })
    }

    fun login(credential: String): Login {
        val values = credential.split(';').mapNotNull { part ->
            val name = part.substringBefore('=', "").trim()
            if (name.isBlank()) null else name to part.substringAfter('=', "").trim()
        }.toMap()
        fun decodeCookie(value: String): String = if ('%' !in value) value else
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
        val auth = if (credential.trim().startsWith("Basic ", true)) credential.trim()
            else values["authorization"]?.let(::decodeCookie)?.takeIf(String::isNotBlank)
        fun base64(value: String?): String? = value?.let {
            runCatching { Base64.getDecoder().decode(decodeCookie(it)).toString(Charsets.UTF_8) }.getOrNull()
        }
        val embedded = auth?.let { base64(it.replaceFirst(Regex("^Basic\\s+", RegexOption.IGNORE_CASE), "")) }
            ?.split(':')?.getOrNull(1)
        val account = listOf(base64(values["ORCHES-I-ACCOUNT-ENCRYPT"]), embedded,
            values["Login_UserNumber"]?.let(::decodeCookie)).firstOrNull {
                !it.isNullOrBlank() && it.length <= 128 && it.none { char -> char.isISOControl() || char == '*' || char == ':' || char.isWhitespace() }
            }
        return Login(account, auth)
    }

    fun signHeader(body: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US))
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val nonce = buildString { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }
        return "$timestamp,$nonce,${signature(body, timestamp, nonce)}"
    }

    fun signature(body: String, timestamp: String, nonce: String): String {
        fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val encoded = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
            .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%2A", "*")
        val sorted = encoded.toCharArray().sorted().joinToString("")
        val b64 = Base64.getEncoder().encodeToString(sorted.toByteArray(Charsets.UTF_8))
        return md5(md5(b64) + md5("$timestamp:$nonce")).uppercase(Locale.US)
    }
}
