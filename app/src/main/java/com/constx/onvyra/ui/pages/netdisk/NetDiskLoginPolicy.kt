package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskPlatform
import java.net.URI

/** Only this provider's official HTTPS pages may become the login document. */
internal object NetDiskLoginPolicy {
    /** Each native form has its own implemented provider protocol. */
    fun supportsNativePassword(platform: NetDiskPlatform): Boolean = platform in setOf(
        NetDiskPlatform.PAN123, NetDiskPlatform.LANZOU, NetDiskPlatform.ILANZOU,
        NetDiskPlatform.XUNLEI, NetDiskPlatform.GUANGYA,
    )

    fun supportsNativeSms(platform: NetDiskPlatform): Boolean =
        platform == NetDiskPlatform.XUNLEI || platform == NetDiskPlatform.GUANGYA

    fun hasEmbeddedLogin(platform: NetDiskPlatform): Boolean =
        supportsNativePassword(platform) || platform == NetDiskPlatform.ALIYUN
    private val commonAssets = setOf("alicdn.com", "aliyuncs.com", "geetest.com", "geetest.cn", "gtimg.com", "gstatic.com")
    private fun domains(platform: NetDiskPlatform): Set<String> = when (platform) {
        NetDiskPlatform.QUARK -> setOf("quark.cn", "uc.cn", "ucweb.com", "sm.cn")
        NetDiskPlatform.UC -> setOf("uc.cn", "ucweb.com", "sm.cn")
        NetDiskPlatform.LANZOU -> setOf("woozooo.com", "lanzou.com", "lanzoux.com", "lanzoui.com", "lanzouu.com", "lanzouw.com", "lanzouy.com", "lanzouf.com", "lanzn.com")
        NetDiskPlatform.ILANZOU -> setOf("ilanzou.com")
        NetDiskPlatform.PAN123 -> setOf("123pan.com", "123pan.cn", "123865.com")
        NetDiskPlatform.ALIYUN -> setOf("alipan.com", "aliyundrive.com", "aliyun.com", "alibaba.com", "taobao.com")
        NetDiskPlatform.BAIDU -> setOf("baidu.com", "bdstatic.com", "baidubce.com", "bcebos.com")
        NetDiskPlatform.PAN115 -> setOf("115.com", "115cdn.com", "115rc.com")
        NetDiskPlatform.C139 -> setOf("139.com", "10086.cn", "10086.com", "cmpassport.com", "cmcc.com")
        NetDiskPlatform.XUNLEI -> setOf("xunlei.com", "xunlei.cn", "xlpan.com")
        NetDiskPlatform.GUANGYA -> setOf("guangyapan.com", "xunlei.com")
    }

    fun startUrl(platform: NetDiskPlatform): String = when (platform) {
        NetDiskPlatform.LANZOU -> "https://accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com"
        NetDiskPlatform.PAN123 -> "https://yun.123pan.cn/"
        NetDiskPlatform.QUARK -> "https://pan.quark.cn/?fr=pc&platform=pc"
        NetDiskPlatform.ALIYUN -> "https://www.alipan.com/sign/in"
        NetDiskPlatform.XUNLEI -> "https://pan.xunlei.com/login"
        NetDiskPlatform.C139 -> "https://yun.139.com/m/#/login"
        else -> platform.loginUrl
    }

    /** Open the provider's actual file workspace, reusing WebView's persisted website session. */
    fun accountUrl(platform: NetDiskPlatform): String = when (platform) {
        NetDiskPlatform.LANZOU -> "https://pc.woozooo.com/mydisk.php"
        NetDiskPlatform.ILANZOU -> "https://www.ilanzou.com/"
        NetDiskPlatform.PAN123 -> "https://yun.123pan.cn/"
        NetDiskPlatform.ALIYUN -> "https://www.alipan.com/drive/file/all"
        NetDiskPlatform.QUARK -> "https://pan.quark.cn/?fr=pc&platform=pc#/list/all"
        NetDiskPlatform.UC -> "https://drive.uc.cn/#/list/all"
        NetDiskPlatform.BAIDU -> "https://pan.baidu.com/disk/main#/index?category=all"
        NetDiskPlatform.XUNLEI -> "https://pan.xunlei.com/"
        NetDiskPlatform.PAN115 -> "https://115.com/?tab=wangpan&mode=wangpan"
        NetDiskPlatform.C139 -> "https://yun.139.com/w/#/main"
        NetDiskPlatform.GUANGYA -> "https://www.guangyapan.com/"
    }

    fun host(url: String?): String? = runCatching { URI(url.orEmpty()).host?.lowercase() }.getOrNull()

    fun mayReadToken(platform: NetDiskPlatform, url: String?): Boolean {
        if (!mayLoad(platform, url, true)) return false
        val target = host(url) ?: return false
        val tokenDomains = when (platform) {
            NetDiskPlatform.PAN123 -> setOf("123pan.com", "123pan.cn", "123865.com")
            NetDiskPlatform.ILANZOU -> setOf("ilanzou.com")
            // auth.alipan.com holds an intermediate token before the www callback establishes
            // the file workspace. Import only the final website session.
            NetDiskPlatform.ALIYUN -> setOf("www.alipan.com", "www.aliyundrive.com")
            NetDiskPlatform.XUNLEI -> setOf("pan.xunlei.com")
            NetDiskPlatform.GUANGYA -> setOf("guangyapan.com")
            else -> return false
        }
        return tokenDomains.any { target == it || target.endsWith(".$it") }
    }

    fun mayLoad(platform: NetDiskPlatform, url: String?, mainDocument: Boolean): Boolean {
        val uri = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return false
        // Official SPAs use local blob/image/font URLs. They are resources, never navigation targets.
        if (!mainDocument) {
            if (uri.scheme.equals("blob", true)) return mayLoad(platform, uri.rawSchemeSpecificPart, true)
            if (uri.scheme.equals("data", true)) {
                val mime = uri.rawSchemeSpecificPart.substringBefore(';').substringBefore(',').lowercase()
                return mime.startsWith("image/") || mime.startsWith("font/") ||
                    mime in setOf("application/font-woff", "application/font-woff2", "application/vnd.ms-fontobject")
            }
            if (url == "about:blank" || url == "about:srcdoc") return true
        }
        if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        // static.xbase.cloud is linked directly by the current official Xunlei HTML. It must
        // load as a script/style origin, while it must not become a top-level login document.
        val providerAssets = when (platform) {
            NetDiskPlatform.XUNLEI -> setOf("static.xbase.cloud")
            NetDiskPlatform.GUANGYA -> setOf("static.xbase.cloud", "static.portal101.cn")
            NetDiskPlatform.ALIYUN -> setOf("ynuf.alipay.com", "ynuf.aliapp.org")
            else -> emptySet()
        }
        val allowed = domains(platform) + if (mainDocument) emptySet() else commonAssets + providerAssets
        return allowed.any { host == it || host.endsWith(".$it") }
    }

    fun cookieUrls(platform: NetDiskPlatform): List<String> = when (platform) {
        NetDiskPlatform.QUARK -> listOf("https://pan.quark.cn/", "https://drive-pc.quark.cn/")
        NetDiskPlatform.UC -> listOf("https://drive.uc.cn/", "https://pc-api.uc.cn/")
        NetDiskPlatform.LANZOU -> listOf("https://pc.woozooo.com/", "https://www.lanzou.com/")
        NetDiskPlatform.BAIDU -> listOf("https://pan.baidu.com/")
        NetDiskPlatform.PAN115 -> listOf("https://115.com/", "https://webapi.115.com/")
        NetDiskPlatform.C139 -> listOf("https://mail.10086.cn/", "https://yun.139.com/")
        else -> emptyList()
    }

    fun mergeLoginCookies(platform: NetDiskPlatform, cookies: List<String>): String? {
        val pairs = linkedMapOf<String, String>()
        cookies.forEach { value -> value.split(';').forEach { part ->
            val key = part.substringBefore('=', "").trim()
            val token = part.substringAfter('=', "").trim()
            if (key.isNotEmpty() && token.isNotEmpty()) pairs[key] = token
        } }
        val authenticated = when (platform) {
            NetDiskPlatform.QUARK -> "__puus" in pairs || "__pus" in pairs
            NetDiskPlatform.UC -> "__pus" in pairs && "__puus" in pairs
            NetDiskPlatform.LANZOU -> "ylogin" in pairs && "phpdisk_info" in pairs
            NetDiskPlatform.BAIDU -> "BDUSS" in pairs
            NetDiskPlatform.PAN115 -> listOf("UID", "CID", "SEID").all { it in pairs }
            NetDiskPlatform.C139 -> "authorization" in pairs || ("Os_SSo_Sid" in pairs && "RMKEY" in pairs)
            else -> false
        }
        return if (authenticated) pairs.entries.joinToString("; ") { "${it.key}=${it.value}" } else null
    }

    /** Fixed keys on the provider's current official origin; no page-supplied code is evaluated. */
    fun tokenScript(platform: NetDiskPlatform): String? = when (platform) {
        NetDiskPlatform.ILANZOU -> """
            (() => { try {
              const raw = localStorage.getItem('disk-pc-vuex');
              const state = raw ? JSON.parse(raw) : null;
              const c = state && state.common;
              const token = c && c.appToken;
              const uuid = c && c.uuid;
              if (typeof token !== 'string' || token.length < 16 || token.length > 8192 ||
                  typeof uuid !== 'string' || !/^[A-Za-z0-9_-]{8,128}${'$'}/.test(uuid)) return '';
              return JSON.stringify({appToken:token, uuid:uuid});
            } catch (_) { return ''; } })()
        """.trimIndent()
        NetDiskPlatform.PAN123 -> """
            (() => { try {
              let v = localStorage.getItem('authorToken');
              if (!v) return '';
              try { const p = JSON.parse(v); v = typeof p === 'string' ? p : (p.access_token || p.token || p.authorToken || ''); } catch (_) {}
              return typeof v === 'string' && v.length <= 16384 ? v : '';
            } catch (_) { return ''; } })()
        """.trimIndent()
        NetDiskPlatform.ALIYUN -> """
            (() => { try {
              const raw = localStorage.getItem('token');
              const p = raw ? JSON.parse(raw) : null;
              const v = (p && p.access_token) || localStorage.getItem('access_token') || '';
              return typeof v === 'string' && v.length <= 16384 ? v : '';
            } catch (_) { return ''; } })()
        """.trimIndent()
        NetDiskPlatform.XUNLEI -> """
            (() => { try {
              const client = 'Xqp0kJBXWhwaTpB6';
              const parse = s => { try { return JSON.parse(s || 'null'); } catch (_) { return null; } };
              const sub = (localStorage.getItem('current_sub') || '').replace(/^"|"${'$'}/g, '');
              let c = parse(localStorage.getItem('credentials_' + client + (sub ? '@' + sub : '')))
                  || parse(localStorage.getItem('credentials_' + client));
              if (!c) return '';
              c = c.credentials || c;
              const token = c.access_token || c.accessToken || '';
              if (!token || typeof token !== 'string') return '';
              const rawCaptcha = localStorage.getItem('captcha_' + client);
              const captcha = parse(rawCaptcha);
              const device = (document.cookie || '').split(';').map(x => x.trim()).find(x => x.startsWith('deviceid='));
              return JSON.stringify({access_token: token, user_id: c.user_id || c.sub || sub || '',
                device_id: device ? decodeURIComponent(device.slice(9)) : (c.device_id || ''),
                captcha_token: captcha ? (captcha.token || captcha.captcha_token || '') : (rawCaptcha || ''), client_id: client});
            } catch (_) { return ''; } })()
        """.trimIndent()
        NetDiskPlatform.GUANGYA -> """
            (() => { try {
              const raw = localStorage.getItem('credentials_aMe-8VSlkrbQXpUR');
              if (!raw) return '';
              const c = JSON.parse(raw); const v = c.access_token || c.accessToken || '';
              return typeof v === 'string' && v.length <= 16384 ? JSON.stringify({access_token:v,device_id:c.device_id || c.did || ''}) : '';
            } catch (_) { return ''; } })()
        """.trimIndent()
        else -> null
    }
}
