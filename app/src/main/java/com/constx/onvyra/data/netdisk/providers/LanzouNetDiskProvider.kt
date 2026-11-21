/*
 * Share-protocol handling adapted from YunX (CYQawa), commit cc9afe24c9409b239c361263b976f7aad77d1d9e,
 * and YunGet (jiayuxuan123), commit 845932c48a04e5092bc6795e4dcb0fd67d202f10.
 * Copyright (C) 2026 CYQawa and YunGet contributors.
 * GNU Affero General Public License, version 3 or later. See the repository LICENSE.
 * Adaptation: native Onvyra provider, scoped guest cookies, bounded pagination and cancellable HTTP.
 */
package com.constx.onvyra.data.netdisk.providers

import com.constx.onvyra.data.netdisk.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class LanzouNetDiskProvider(private val http: NetDiskHttp) : NetDiskProvider {
    override val platform = NetDiskPlatform.LANZOU
    private val challengeCookies = ConcurrentHashMap<String, String>()

    override suspend fun open(share: NetDiskShare, credential: String): NetDiskSession {
        require(share.platform == platform)
        val entry = share.url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://").substringBefore('#')
        val (url, html) = sharePage(entry, share.password)
        val folder = LanzouProtocol.isFolder(url, html)
        if (!folder && LanzouProtocol.iframe(html) == null && LanzouProtocol.downFields(html, share.password).isEmpty()) {
            throw NetDiskException("verification_required", "蓝奏分享页未提供文件信息，请在官网确认链接或完成网页验证后重试。")
        }
        return NetDiskSession(share.copy(url = url), LanzouProtocol.title(html).ifBlank { "蓝奏分享" }, extra = buildJsonObject {
            put("html", html); put("is_folder", folder)
        })
    }

    override suspend fun list(session: NetDiskSession, directoryId: String, cursor: String?, credential: String): NetDiskListing {
        require(session.share.platform == platform)
        val root = directoryId.isBlank()
        val url = if (root) session.share.url else LanzouProtocol.absolute(session.share.url, "/${directoryId.trimStart('/')}")
        val html = if (root && cursor == null) session.extra.string("html") else sharePage(url, session.share.password).second
        if (root && session.extra["is_folder"]?.jsonPrimitive?.booleanOrNull == false) {
            return NetDiskListing(listOf(NetDiskFile(session.share.shareId, session.title, size = LanzouProtocol.displaySize(html),
                extra = buildJsonObject { put("share_url", session.share.url) })))
        }
        val page = cursor?.toIntOrNull() ?: 1
        if (page !in 1..100) throw NetDiskException("pagination_limit", "蓝奏目录分页已达本次读取上限，请重新打开目录。")
        val params = LanzouProtocol.folderParams(html, URI(url).path.trim('/').substringAfterLast('/'))
            ?: throw NetDiskException("invalid_response", "蓝奏分享页缺少目录参数，请检查提取码或在官网确认分享。")
        val origin = LanzouProtocol.origin(url)
        var password = session.share.password
        var response: JsonObject? = null
        for (attempt in 0..2) {
            if (page > 1 || attempt > 0) delay(300L * (attempt + 1))
            val body = http.postForm("$origin/filemoreajax.php", params + mapOf("pwd" to password, "pg" to page.toString()),
                headers(url, origin, ajax = true)).json()
            when (body.int("zt")) {
                1, 2 -> { response = body; break }
                3 -> if (password.isNotBlank() && attempt == 0) password = "" else fail(body, "蓝奏提取码不正确，请检查后重试。")
                4 -> if (attempt == 2) throw NetDiskException("rate_limited", "蓝奏请求过于频繁，请稍后重试。")
                else -> fail(body, "蓝奏目录解析失败，请检查分享链接和提取码。")
            }
        }
        val body = response ?: throw NetDiskException("rate_limited", "蓝奏暂时没有返回目录，请稍后重试。")
        val children = if (page == 1) LanzouProtocol.subfolders(html).filter { it.id != directoryId } else emptyList()
        if (body.int("zt") == 2) return NetDiskListing(children, title = LanzouProtocol.title(html).ifBlank { null })
        val rows = (body["text"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val files = rows.mapNotNull { row ->
            val reference = row.string("id").trim()
            if (reference.isBlank() || reference.startsWith("http", ignoreCase = true) || reference.startsWith("//")) null
            else NetDiskFile(reference.substringBefore('?'), row.string("name_all").ifBlank { row.string("name") }.let(LanzouProtocol::plain),
                size = LanzouProtocol.parseSize(row.string("size")), extra = buildJsonObject {
                    put("share_url", LanzouProtocol.absolute(url, "/$reference"))
                })
        }.distinctBy { it.id }
        return NetDiskListing((children + files).distinctBy { it.id },
            nextCursor = (page + 1).toString().takeIf { rows.isNotEmpty() && page < 100 },
            title = LanzouProtocol.title(html).ifBlank { null })
    }

    override suspend fun resolve(session: NetDiskSession, file: NetDiskFile, credential: String): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("directory", "请先打开文件夹，再选择文件。")
        val entry = file.extra.string("share_url").ifBlank {
            if (session.extra["is_folder"]?.jsonPrimitive?.booleanOrNull == true)
                LanzouProtocol.absolute(session.share.url, "/${file.id}") else session.share.url
        }
        val (pageUrl, entryHtml) = sharePage(entry, session.share.password)
        val origin = LanzouProtocol.origin(pageUrl)
        val iframe = LanzouProtocol.iframe(entryHtml)?.let { LanzouProtocol.absolute(pageUrl, it) }
        var referer = pageUrl
        val html = if (iframe != null) {
            if (!URI(iframe).host.equals(URI(pageUrl).host, ignoreCase = true))
                throw NetDiskException("invalid_url", "蓝奏下载页指向了不同站点，已停止解析。")
            referer = iframe
            pageRequest(iframe, pageUrl).body
        } else entryHtml
        val fields = LanzouProtocol.downFields(html, session.share.password)
        if (fields["sign"].isNullOrBlank()) throw NetDiskException("invalid_response", "蓝奏分享页缺少下载参数，请重新解析。")
        val ajaxUrl = LanzouProtocol.ajaxUrl(html, referer, session.share.password.isNotBlank())
        val ajaxHost = URI(ajaxUrl).host.orEmpty()
        if (!ajaxHost.equals(URI(pageUrl).host, ignoreCase = true) && !ajaxHost.equals("apifile.woozooo.com", ignoreCase = true))
            throw NetDiskException("invalid_url", "蓝奏分享页提供了非官方网盘接口，已停止解析。")
        val json = http.postForm(ajaxUrl, fields, headers(referer, origin, ajax = true)).json()
        if (json.int("zt") != 1) fail(json, "蓝奏未能生成下载地址，请检查提取码。")
        val dom = json.string("dom")
        val relative = json.string("url")
        if (relative.isBlank() || relative.startsWith('/') || relative.contains("://"))
            throw NetDiskException("invalid_response", "蓝奏没有返回有效的下载地址。")
        val jump = LanzouProtocol.absolute(dom, "/file/$relative")
        val finalUrl = resolveDownload(jump, origin)
        return NetDiskDownload(finalUrl, file.name.ifBlank { json.string("inf").ifBlank { "蓝奏文件" } }, file.mimeType,
            headers = headers(finalUrl, origin) + ("Cookie" to downloadCookie(finalUrl)))
    }

    private suspend fun sharePage(entry: String, password: String): Pair<String, String> {
        var current = entry
        repeat(8) {
            if (!LanzouProtocol.isLanzouHost(URI(current).host.orEmpty()))
                throw NetDiskException("invalid_url", "蓝奏分享页跳转到了非蓝奏域名。")
            val response = pageRequest(current, current)
            if (response.status in 300..399) {
                current = LanzouProtocol.absolute(current, response.headers["Location"]
                    ?: throw NetDiskException("invalid_response", "蓝奏分享页跳转地址为空。"))
            } else {
                var html = response.body
                if (LanzouProtocol.locked(html) && password.isNotBlank()) {
                    val unlocked = http.postForm(current, mapOf("pwd" to password), headers(current, LanzouProtocol.origin(current)), followRedirects = false)
                    html = if (unlocked.status in 300..399) {
                        val next = LanzouProtocol.absolute(current, unlocked.headers["Location"].orEmpty())
                        if (!LanzouProtocol.isLanzouHost(URI(next).host.orEmpty()))
                            throw NetDiskException("invalid_url", "蓝奏解锁页跳转到了非蓝奏域名。")
                        current = next
                        pageRequest(next, entry).body
                    } else unlocked.body
                }
                if (LanzouProtocol.locked(html))
                    throw NetDiskException("password_required", if (password.isBlank()) "该蓝奏分享需要提取码。" else "蓝奏提取码不正确。")
                if (response.status !in 200..299) throw NetDiskException("http_${response.status}", "蓝奏分享请求失败（HTTP ${response.status}）。")
                val text = LanzouProtocol.plain(html)
                if (text.contains("文件已取消") || text.contains("文件不存在或已取消") || text.contains("来晚啦"))
                    throw NetDiskException("share_expired", "蓝奏文件不存在或已取消分享。")
                return current to html
            }
        }
        throw NetDiskException("redirect_limit", "蓝奏分享页跳转过多，请检查链接。")
    }

    private suspend fun pageRequest(url: String, referer: String): NetDiskResponse {
        repeat(3) {
            val response = http.get(url, headers(url, LanzouProtocol.origin(referer)) + ("Referer" to referer), followRedirects = false)
            val challenge = LanzouProtocol.challenge(response.body)
            if (challenge == null) return response
            challengeCookies[URI(url).host.orEmpty()] = challenge
        }
        throw NetDiskException("verification_required", "蓝奏需要进一步网页验证，请在官网完成后重试。")
    }

    private suspend fun resolveDownload(start: String, origin: String): String {
        var current = start
        val visited = HashSet<String>()
        val verified = HashSet<String>()
        repeat(8) {
            if (!visited.add(current)) throw NetDiskException("redirect_loop", "蓝奏下载地址出现循环跳转。")
            val host = URI(current).host ?: throw NetDiskException("invalid_url", "蓝奏下载地址无效。")
            val response = http.head(current, headers(current, origin) + ("Cookie" to downloadCookie(current)), followRedirects = false)
            when {
                response.status in 300..399 -> current = LanzouProtocol.absolute(current, response.headers["Location"]
                    ?: throw NetDiskException("invalid_response", "蓝奏下载跳转地址为空。"))
                response.status in 200..299 && !response.headers["Content-Type"].orEmpty().contains("text/html", ignoreCase = true) -> return current
                response.status in setOf(200, 409) -> {
                    // Read only a bounded verification page, then return to HEAD after a
                    // challenge. Retrying GET after unlocking could start fetching the file.
                    val page = http.get(current, headers(current, origin) + mapOf(
                        "Cookie" to downloadCookie(current), "Range" to "bytes=0-65535"), followRedirects = false)
                    val challenge = LanzouProtocol.challenge(page.body)
                    if (challenge != null) {
                        if (challengeCookies.put(host, challenge) == challenge)
                            throw NetDiskException("verification_required", "蓝奏下载节点仍需要网页验证，请在官网完成后重试。")
                        visited.remove(current)
                        return@repeat
                    }
                    if (page.status in 200..299 && !page.headers["Content-Type"].orEmpty().contains("text/html", ignoreCase = true))
                        return current
                    if (!page.body.contains("验证并下载"))
                        throw NetDiskException("verification_required", "蓝奏下载节点需要网页验证，请在官网完成后重试。")
                    if (!verified.add(host)) throw NetDiskException("verification_required", "蓝奏下载节点未通过验证。")
                    val fields = LanzouProtocol.verificationFields(page.body)
                        ?: throw NetDiskException("verification_required", "蓝奏下载节点缺少确认参数，请在官网完成验证。")
                    delay(2_000)
                    val json = http.postForm(LanzouProtocol.absolute(current, "ajax.php"), fields,
                        headers(current, origin) + ("Cookie" to downloadCookie(current))).json()
                    val next = json.string("url")
                    if (json.int("zt") != 1 || next.isBlank() || next == "?SignError") fail(json, "蓝奏下载确认失败，请重新解析。")
                    current = LanzouProtocol.absolute(current, next)
                }
                response.status == 429 -> throw NetDiskException("rate_limited", "蓝奏下载请求过于频繁，请稍后重试。")
                response.status == 405 -> return current // Some nodes do not support HEAD; the download performs the GET.
                else -> throw NetDiskException("download_unavailable", "蓝奏下载地址不可用（HTTP ${response.status}），请重新解析。")
            }
        }
        throw NetDiskException("redirect_limit", "蓝奏下载跳转过多，请重新解析。")
    }

    private fun downloadCookie(url: String): String = buildMap {
        // Export only cookies matching the actual final URL; never a different
        // service's cookies or the caller's account credential.
        http.cookieHeader(url).split(';').forEach { part ->
            val name = part.substringBefore('=', "").trim()
            if (name.isNotBlank()) put(name, part.substringAfter('=').trim())
        }
        put("down_ip", "1")
        challengeCookies[URI(url).host.orEmpty()]?.let { put("acw_sc__v2", it) }
    }.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun headers(url: String, origin: String, ajax: Boolean = false): Map<String, String> = buildMap {
        put("User-Agent", NetDiskHttp.WEB_USER_AGENT); put("Referer", "$origin/")
        challengeCookies[URI(url).host.orEmpty()]?.let { put("Cookie", "acw_sc__v2=$it") }
        if (ajax) { put("Origin", origin); put("X-Requested-With", "XMLHttpRequest") }
    }

    private fun fail(json: JsonObject, fallback: String): Nothing {
        val msg = json.string("inf").ifBlank { json.string("msg") }.let(LanzouProtocol::plain).take(250)
        val reason = when {
            msg.contains("密码") || msg.contains("提取码") -> "password_required"
            msg.contains("不存在") || msg.contains("取消") || msg.contains("过期") -> "share_expired"
            msg.contains("频繁") -> "rate_limited"
            else -> "provider_error"
        }
        throw NetDiskException(reason, msg.ifBlank { fallback })
    }
}

/** Parse only data literals from provider pages. No downloaded JavaScript is ever evaluated. */
internal object LanzouProtocol {
    fun isLanzouHost(host: String): Boolean = Regex("""^(?:[a-z0-9-]+\.)*(?:lanzou[a-z0-9-]*|lan[zs]o[ux]|lanzn)\.(?:com|net|org|cn)$""", RegexOption.IGNORE_CASE).matches(host)
    fun origin(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }
    fun absolute(base: String, relative: String): String {
        val resolved = URI(base).resolve(relative.replace("&amp;", "&").trim()).toString()
        val parsed = URI(resolved)
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank() || parsed.userInfo != null)
            throw NetDiskException("invalid_url", "网盘返回了无效的网页或下载地址。")
        return resolved
    }
    fun plain(html: String): String = Jsoup.parse(html).text().trim()
    fun title(html: String): String = Jsoup.parse(html).let { page ->
        page.selectFirst(".n, .filename, #filenajax")?.text()?.takeIf { it.isNotBlank() }
            ?: page.title().replace(Regex("""\s*[-|]\s*蓝奏云.*$"""), "").takeUnless { it.contains("密码") }.orEmpty()
    }
    fun isFolder(url: String, html: String): Boolean = URI(url).path.trim('/').substringAfterLast('/').startsWith("b") ||
        html.contains("filemoreajax") || html.contains("file_more")
    fun locked(html: String): Boolean = html.contains("请输入密码") || html.contains("input_password") ||
        html.contains("id=\"pwbox\"") || html.contains("密码不正确")
    fun iframe(html: String): String? = Jsoup.parse(html).selectFirst("iframe[src]")?.attr("src")?.takeIf { it.isNotBlank() }
    fun stripNotes(html: String): String = html.replace(Regex("""<!--.*?-->|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "\n")
    fun variable(html: String, key: String): String? = Regex("""(?:(?:var|let|const)\s+)?\b${Regex.escape(key)}\s*=\s*(?:'([^']*)'|"([^"]*)"|([A-Za-z0-9_-]+))""")
        .findAll(html).mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }.lastOrNull()
    fun parameter(html: String, key: String): String? {
        val value = Regex("""(?:['"]${Regex.escape(key)}['"]|\b${Regex.escape(key)})\s*:\s*(?:'([^']*)'|"([^"]*)"|([A-Za-z0-9_-]+))""")
            .findAll(html).mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }.lastOrNull()
        return value?.let { if (it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) variable(html, it) ?: it else it } ?: variable(html, key)
    }
    fun objects(html: String): List<Map<String, String>> = Regex("""\bdata\s*:\s*\{([^{}]*)\}""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { obj ->
        buildMap {
            Regex("""(?:['"]([A-Za-z_][\w]*)['"]|([A-Za-z_][\w]*))\s*:\s*('(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|[A-Za-z_][\w]*|-?\d+)""").findAll(obj.groupValues[1]).forEach { field ->
                val key = field.groupValues[1].ifBlank { field.groupValues[2] }
                val raw = field.groupValues[3]
                val value = when { raw.first() in "\"'" -> raw.substring(1, raw.length - 1); raw.matches(Regex("-?\\d+")) -> raw; else -> variable(html, raw) }
                if (value != null) put(key, value)
            }
        }
    }.toList()
    fun folderParams(html: String, fallback: String): Map<String, String>? {
        val clean = stripNotes(html)
        val t = parameter(clean, "t") ?: return null
        val k = parameter(clean, "k") ?: return null
        return mapOf("lx" to (parameter(clean, "lx") ?: "2"), "fid" to (parameter(clean, "fid") ?: fallback), "t" to t, "k" to k)
            .takeIf { it.values.none(String::isBlank) }
    }
    fun subfolders(html: String): List<NetDiskFile> = Jsoup.parse(html).select("a[href]").mapNotNull { element ->
        val href = element.attr("href")
        val id = href.substringBefore('?').trim('/')
        val marker = element.className() + " " + element.parent()?.className().orEmpty()
        if (!id.matches(Regex("b[A-Za-z0-9_-]+")) || !marker.contains("folder", ignoreCase = true)) null
        else NetDiskFile(id, element.text().ifBlank { id }, isDirectory = true)
    }.distinctBy { it.id }
    fun downFields(html: String, password: String): Map<String, String> {
        val clean = stripNotes(html)
        val declared = objects(clean).lastOrNull { it["action"] == "downprocess" && !it["sign"].isNullOrBlank() }
        val fields = declared?.toMutableMap() ?: linkedMapOf<String, String>().apply {
            val sign = parameter(clean, "wp_sign") ?: parameter(clean, "sign") ?: return emptyMap()
            put("action", "downprocess"); put("sign", sign)
            val key = parameter(clean, "ajaxdata") ?: parameter(clean, "websignkey").orEmpty()
            put("signs", parameter(clean, "signs") ?: key); put("websignkey", key)
            put("websign", parameter(clean, "websign").orEmpty()); put("kd", parameter(clean, "kdns") ?: "1"); put("ves", "1")
        }
        if (password.isNotBlank()) { fields["p"] = password; fields["kd"] = "1" }
        return fields
    }
    fun ajaxUrl(html: String, base: String, withPassword: Boolean): String {
        val clean = stripNotes(html)
        val literal = Regex("""['"]?url['"]?\s*:\s*['"]([^'"]*ajax(?:m|file)\.php[^'"]*)['"]""").find(clean)?.groupValues?.get(1)
            ?: variable(clean, "domain1")?.takeIf { it.contains("/ajax") }
            ?: variable(clean, "domain2")?.takeIf { it.contains("/ajax") }
            ?: Regex("""/ajax(?:m|file)\.php(?:\?file=\d+)?""").find(clean)?.value
        val id = Regex("""(?:[?&]file=|\b(?:f_id|fid)\s*=\s*['"]?)(\d+)""").find(clean)?.groupValues?.get(1)
        return absolute(base, literal ?: if (withPassword) "/ajaxfile.php${id?.let { "?file=$it" }.orEmpty()}" else "/ajaxm.php${id?.let { "?file=$it" }.orEmpty()}")
    }
    fun verificationFields(html: String): Map<String, String>? {
        val data = objects(html).firstOrNull { it.containsKey("file") && it.containsKey("sign") }
        val doc = Jsoup.parse(html)
        val file = data?.get("file") ?: doc.selectFirst("input[name=file]")?.attr("value") ?: return null
        val sign = data?.get("sign") ?: variable(html, "sign") ?: doc.selectFirst("input[name=sign]")?.attr("value") ?: return null
        return mapOf("file" to file, "sign" to sign, "el" to "2")
    }
    fun displaySize(html: String): Long = Regex("""(?:文件大小|大小)\s*[:：]\s*([\d.]+\s*[KMGT]?)""", RegexOption.IGNORE_CASE)
        .find(plain(html))?.groupValues?.get(1)?.let(::parseSize) ?: 0L
    fun parseSize(value: String): Long {
        val match = Regex("""([\d.]+)\s*([KMGT]?)""", RegexOption.IGNORE_CASE).find(value) ?: return 0
        val size = match.groupValues[1].toDoubleOrNull() ?: return 0
        val power = "KMGT".indexOf(match.groupValues[2].uppercase()).takeIf { match.groupValues[2].isNotEmpty() }?.plus(1) ?: 0
        var factor = 1L; repeat(power.coerceIn(0, 4)) { factor *= 1024 }
        return (size * factor).takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE }?.toLong() ?: 0
    }
    fun challenge(html: String): String? = Regex("""\barg1\s*=\s*['"]([0-9a-fA-F]{40})['"]""").find(html)?.groupValues?.get(1)?.let(::acw)
    fun acw(arg: String): String {
        require(arg.matches(Regex("[0-9a-fA-F]{40}")))
        val permutation = intArrayOf(15,35,29,24,33,16,1,38,10,9,19,31,40,27,22,23,25,13,6,11,39,18,20,8,14,21,32,26,2,30,7,4,17,5,3,28,34,37,12,36)
        val mask = "3000176000856006061501533003690027800375"
        val rearranged = permutation.map { arg[it - 1] }.joinToString("")
        return (0 until 40 step 2).joinToString("") { offset ->
            (rearranged.substring(offset, offset + 2).toInt(16) xor mask.substring(offset, offset + 2).toInt(16)).toString(16).padStart(2, '0')
        }
    }
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
