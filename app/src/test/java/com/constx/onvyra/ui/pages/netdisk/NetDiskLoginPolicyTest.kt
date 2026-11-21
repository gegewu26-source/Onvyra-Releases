package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskPlatform
import org.junit.Assert.*
import org.junit.Test

class NetDiskLoginPolicyTest {
    @Test fun allElevenPlatformsOnlyExposeAnActuallyImplementedNativePasswordEndpoint() {
        assertEquals(11, NetDiskPlatform.entries.size)
        assertEquals(setOf(NetDiskPlatform.PAN123, NetDiskPlatform.LANZOU, NetDiskPlatform.ILANZOU,
            NetDiskPlatform.XUNLEI, NetDiskPlatform.GUANGYA),
            NetDiskPlatform.entries.filter(NetDiskLoginPolicy::supportsNativePassword).toSet())
        NetDiskPlatform.entries.filterNot(NetDiskLoginPolicy::supportsNativePassword).forEach {
            assertTrue(it.name, NetDiskLoginPolicy.mayLoad(it, NetDiskLoginPolicy.startUrl(it), true))
        }
    }

    @Test fun smsAndEmbeddedComponentsAreClassifiedSeparatelyFromNativePasswordProtocols() {
        assertEquals(setOf(NetDiskPlatform.XUNLEI, NetDiskPlatform.GUANGYA),
            NetDiskPlatform.entries.filter(NetDiskLoginPolicy::supportsNativeSms).toSet())
        assertTrue(NetDiskLoginPolicy.hasEmbeddedLogin(NetDiskPlatform.ALIYUN))
        assertFalse(NetDiskLoginPolicy.supportsNativePassword(NetDiskPlatform.ALIYUN))
        assertEquals("https://pan.xunlei.com/login", NetDiskLoginPolicy.startUrl(NetDiskPlatform.XUNLEI))
        assertTrue(NetDiskLoginPolicy.startUrl(NetDiskPlatform.ALIYUN).endsWith("/sign/in"))
    }

    @Test fun aliyunOnlyImportsTheFinalWebsiteTokenAfterItsOwnCallback() {
        assertTrue(NetDiskLoginPolicy.mayReadToken(NetDiskPlatform.ALIYUN, "https://www.alipan.com/sign/callback?code=fixture"))
        assertFalse(NetDiskLoginPolicy.mayReadToken(NetDiskPlatform.ALIYUN, "https://auth.alipan.com/v2/oauth/authorize"))
        assertFalse(NetDiskLoginPolicy.mayReadToken(NetDiskPlatform.ALIYUN, "https://passport.alipan.com/mini_login.htm"))
    }

    @Test fun guangyaOfficialFingerprintSdkIsAResourceOnly() {
        val sdk = "https://static.portal101.cn/dist/web/v3.0.0/fp.min.js"
        assertTrue(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.GUANGYA, sdk, false))
        assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.GUANGYA, sdk, true))
        assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, sdk, false))
    }

    @Test fun aliyunOfficialVerificationAssetsCanLoadWithoutBecomingLoginDocuments() {
        for (resource in listOf("https://ynuf.alipay.com/service/um.json", "https://ynuf.aliapp.org/service/um.json")) {
            assertTrue(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.ALIYUN, resource, false))
            assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.ALIYUN, resource, true))
            assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, resource, false))
        }
    }

    @Test fun aNativeAccountLoginAlwaysHasAMatchingPersonalFilesReader() {
        NetDiskPlatform.entries.filter(NetDiskLoginPolicy::supportsNativePassword).forEach {
            assertTrue(it.name, com.constx.onvyra.data.netdisk.NetDiskAccountRepository.isNativeSupported(it))
        }
    }

    @Test fun ucCsrfCookieAloneIsNotAnAuthenticatedSession() {
        assertNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.UC, listOf("ctoken=csrf-only")))
        assertNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.UC, listOf("__pus=user-only")))
        assertNotNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.UC,
            listOf("ctoken=csrf; __pus=account", "__puus=session")))
    }

    @Test fun ilanzouTokenSamplingIsConfinedToItsOwnOfficialOrigin() {
        assertTrue(NetDiskLoginPolicy.mayReadToken(NetDiskPlatform.ILANZOU, "https://www.ilanzou.com/"))
        assertFalse(NetDiskLoginPolicy.mayReadToken(NetDiskPlatform.ILANZOU, "https://www.ilanzou.com.evil.example/"))
        assertFalse(NetDiskLoginPolicy.mayReadToken(NetDiskPlatform.ILANZOU, "https://pan.quark.cn/"))
        assertNotNull(NetDiskLoginPolicy.tokenScript(NetDiskPlatform.ILANZOU))
    }
    @Test fun officialHttpsLoginCannotBecomeAnArbitraryNativeBrowser() {
        assertTrue(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, "https://pan.quark.cn/", true))
        assertTrue(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, "https://account.uc.cn/login", true))
        for (url in listOf("http://pan.quark.cn/", "https://pan.quark.cn.evil.example/",
            "https://evil.example/pan.quark.cn", "https://pan.quark.cn:8443/",
            "https://user:password@pan.quark.cn/", "file:///data/data/", "content://private/file",
            "javascript:alert(1)", "https://127.0.0.1/", "https://[::1]/")) {
            assertFalse(url, NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, url, true))
            assertFalse(url, NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, url, false))
        }
    }

    @Test fun captchaAssetsCannotBecomeTopLevelLoginDocuments() {
        assertTrue(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, "https://static.geetest.com/captcha.js", false))
        assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, "https://static.geetest.com/", true))
    }

    @Test fun everyAccountEntryTargetsItsOfficialFileWorkspace() {
        NetDiskPlatform.entries.forEach { platform ->
            val url = NetDiskLoginPolicy.accountUrl(platform)
            assertTrue(platform.name, NetDiskLoginPolicy.mayLoad(platform, url, true))
        }
        assertEquals("https://www.alipan.com/drive/file/all", NetDiskLoginPolicy.accountUrl(NetDiskPlatform.ALIYUN))
        assertTrue(NetDiskLoginPolicy.accountUrl(NetDiskPlatform.BAIDU).contains("/disk/main"))
        assertTrue(NetDiskLoginPolicy.accountUrl(NetDiskPlatform.LANZOU).contains("/mydisk.php"))
        assertFalse(NetDiskLoginPolicy.accountUrl(NetDiskPlatform.C139).contains("login"))
    }

    @Test fun officialXunleiStaticScriptsLoadWithoutBecomingLoginDocuments() {
        val asset = "https://static.xbase.cloud/main.js"
        assertTrue(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.XUNLEI, asset, false))
        assertTrue(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.GUANGYA, asset, false))
        assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.XUNLEI, asset, true))
        assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, asset, false))
        assertFalse(NetDiskLoginPolicy.mayLoad(NetDiskPlatform.XUNLEI, "https://static.xbase.cloud.evil.example/a.js", false))
    }

    @Test fun localSpaResourcesDoNotGrantLocalFileOrNavigationAccess() {
        for (resource in listOf("blob:https://pan.quark.cn/8d123", "data:image/png;base64,iVBORw0KGgo=", "data:font/woff2;base64,d09GMg==", "about:blank")) {
            assertTrue(resource, NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, resource, false))
            assertFalse(resource, NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, resource, true))
        }
        for (blocked in listOf("blob:https://evil.example/id", "blob:file:///data/private", "data:text/html,<script>alert(1)</script>",
            "data:application/javascript,alert(1)", "content://private/file", "file:///data/private")) {
            assertFalse(blocked, NetDiskLoginPolicy.mayLoad(NetDiskPlatform.QUARK, blocked, false))
        }
    }

    @Test fun guestTrackingCookiesAreNotMistakenForAuthenticatedAccounts() {
        assertNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.QUARK, listOf("tracking=abc; __pugs=guest")))
        assertEquals("__puus=login; extra=value", NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.QUARK,
            listOf("__puus=login", "extra=value")))
        assertNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.PAN115, listOf("UID=user; CID=cid")))
        assertNotNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.PAN115, listOf("UID=user; CID=cid; SEID=seid")))
        assertNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.LANZOU, listOf("ylogin=user")))
        assertNotNull(NetDiskLoginPolicy.mergeLoginCookies(NetDiskPlatform.LANZOU, listOf("ylogin=user; phpdisk_info=session")))
    }

    @Test fun tokenExtractionUsesKnownProviderKeysOnly() {
        assertTrue(NetDiskLoginPolicy.tokenScript(NetDiskPlatform.PAN123)!!.contains("authorToken"))
        assertTrue(NetDiskLoginPolicy.tokenScript(NetDiskPlatform.ALIYUN)!!.contains("access_token"))
        assertNull(NetDiskLoginPolicy.tokenScript(NetDiskPlatform.QUARK))
    }
}
