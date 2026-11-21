package com.constx.onvyra.data.netdisk

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object NetDiskLinkParser {
    private val urls = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val textPassword = Regex("(?:提取码|访问码|提取密码|密码|口令)\\s*[：:]?\\s*([A-Za-z0-9]{2,16})", RegexOption.IGNORE_CASE)
    private val lanzouDomain = Regex("(?:lanzou[a-z0-9-]*|lan[zs]o[ux]|lanzn)\\.(?:com|net|org|cn)")

    fun parse(text: String, password: String = ""): NetDiskShare {
        require(text.length <= 32_768) { "分享内容过长，请只粘贴分享链接和提取码。" }
        val trimmed = text.trim()
        val candidates = urls.findAll(trimmed).take(32).map { it.value.trimEnd('。', '，', ',', '；', ';', ')', '）', ']', '}', '》') }.toList()
            .ifEmpty { listOf("https://$trimmed") }
        for (candidate in candidates) {
            val url = candidate.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: continue
            val platform = platform(url.host) ?: continue
            val segments = url.pathSegments.filter(String::isNotEmpty)
            val id = when {
                platform == NetDiskPlatform.PAN123 && url.encodedPath == "/api/srr" -> url.queryParameter("sk")
                platform == NetDiskPlatform.C139 -> Regex("(?:/w/i/|/i/)([A-Za-z0-9_-]+)").find(url.toString())?.groupValues?.get(1)
                platform == NetDiskPlatform.LANZOU -> segments.lastOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
                platform == NetDiskPlatform.PAN115 -> segments.lastOrNull()?.takeIf { it.startsWith("sw") }?.substringBefore('-')
                platform == NetDiskPlatform.BAIDU && url.queryParameter("surl") != null ->
                    url.queryParameter("surl")?.let { if (it.startsWith('1')) it else "1$it" }
                segments.size >= 2 && segments[0] in setOf("s", "123pan") -> segments[1]
                else -> null
            }?.takeIf { it.isNotBlank() && it.length <= 256 && it.matches(Regex("[A-Za-z0-9_-]+")) } ?: continue
            val fragmentPwd = url.fragment?.let { Regex("(?:pwd|password|passcode)=([A-Za-z0-9]+)").find(it)?.groupValues?.get(1) }
            val commandPwd = if (platform == NetDiskPlatform.PAN115)
                segments.lastOrNull()?.substringAfter('-', "")?.takeIf { it.isNotBlank() } else null
            val extracted = password.trim().ifBlank {
                listOf("pwd", "password", "pass", "passcode", "code").firstNotNullOfOrNull { url.queryParameter(it)?.takeIf(String::isNotBlank) }
                    ?: fragmentPwd ?: commandPwd ?: textPassword.find(text)?.groupValues?.get(1).orEmpty()
            }
            if (extracted.length > 64 || extracted.any(Char::isISOControl))
                throw NetDiskException("invalid_password", "提取码格式不正确。")
            return NetDiskShare(platform, url.toString(), id, extracted)
        }
        throw NetDiskException("unsupported_link", "没有识别到受支持的网盘分享链接，请检查链接是否完整。")
    }

    fun platform(host: String): NetDiskPlatform? {
        val h = host.lowercase().trimEnd('.')
        fun matches(domain: String) = h == domain || h.endsWith(".$domain")
        return when {
            listOf("ilanzou.com", "ilanzou.net", "ilanzou.org", "ilanzou.cn").any(::matches) -> NetDiskPlatform.ILANZOU
            lanzouDomain.matches(h.split('.').takeLast(2).joinToString(".")) -> NetDiskPlatform.LANZOU
            matches("pan.quark.cn") -> NetDiskPlatform.QUARK
            matches("drive.uc.cn") -> NetDiskPlatform.UC
            matches("alipan.com") || matches("aliyundrive.com") -> NetDiskPlatform.ALIYUN
            listOf("123pan.com", "123pan.cn", "123865.com", "123684.com", "123912.com", "123592.com").any(::matches) -> NetDiskPlatform.PAN123
            matches("pan.baidu.com") -> NetDiskPlatform.BAIDU
            matches("pan.xunlei.com") -> NetDiskPlatform.XUNLEI
            matches("115.com") || matches("115cdn.com") || matches("115rc.com") -> NetDiskPlatform.PAN115
            matches("yun.139.com") -> NetDiskPlatform.C139
            matches("guangyapan.com") -> NetDiskPlatform.GUANGYA
            else -> null
        }
    }
}
