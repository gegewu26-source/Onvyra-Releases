package com.constx.onvyra.data.netdisk

import org.junit.Assert.*
import org.junit.Test

class NetDiskLinkParserTest {
    @Test fun pastedMessagesExtractLinksPasswordsAndProviderWithoutUsingAnEarlierUnrelatedLink() {
        val result = NetDiskLinkParser.parse("说明 https://example.org 我分享了文件：https://pan.quark.cn/s/aB1234 提取码：9c7a")
        assertEquals(NetDiskPlatform.QUARK, result.platform)
        assertEquals("aB1234", result.shareId)
        assertEquals("9c7a", result.password)
    }
    @Test fun explicitPasswordOverridesUrlAndMessage() {
        val result = NetDiskLinkParser.parse("https://drive.uc.cn/s/abc?pwd=old1 提取码：old2", "new1")
        assertEquals("new1", result.password)
    }
    @Test fun ilanzouIsNotMisidentifiedAsLanzouAndNewLanznDomainsAreAccepted() {
        assertEquals(NetDiskPlatform.ILANZOU, NetDiskLinkParser.parse("https://www.ilanzou.com/s/aB_cd").platform)
        assertEquals(NetDiskPlatform.LANZOU, NetDiskLinkParser.parse("https://abc.lanzn.com/iXnuF4c8sqmb").platform)
        assertEquals("b12345", NetDiskLinkParser.parse("https://abc.lanzoux.com/b12345 密码:ab12").shareId)
    }
    @Test fun subdomainAndRedirectStyle123LinksAndAliFragmentPasswordsWork() {
        assertEquals("Abcd-EfgHi", NetDiskLinkParser.parse("https://123.share.123pan.cn/123pan/Abcd-EfgHi").shareId)
        assertEquals("Abcd-EfgHi", NetDiskLinkParser.parse("https://www.123pan.cn/api/srr?sk=Abcd-EfgHi&st=s").shareId)
        assertEquals("A1b2", NetDiskLinkParser.parse("https://www.alipan.com/s/abc123#pwd=A1b2").password)
    }
    @Test fun similarLookingUnrelatedHostsAndEmbeddedCredentialsAreRejected() {
        for (url in listOf("https://pan.quark.cn.evil.test/s/abc", "https://evilquark.cn/s/abc",
            "https://lanzoux.com.evil.test/iabc", "https://name:pass@pan.quark.cn/s/abc", "file:///s/a")) {
            assertThrows(NetDiskException::class.java) { NetDiskLinkParser.parse(url) }
        }
    }
    @Test fun downloadedNamesCannotEscapeTheChosenDownloadDirectory() {
        assertEquals("file.txt", safeNetDiskFileName("../../file.txt"))
        assertEquals("file.txt", safeNetDiskFileName("..\\..\\file.txt"))
        assertEquals("网盘文件", safeNetDiskFileName("...."))
        assertFalse(safeNetDiskFileName("a\nb?.txt").contains('\n'))
    }
    @Test fun additionalProvidersRecognizeRealUrlFormsIncludingFragmentAndCommandCodes() {
        val c139 = NetDiskLinkParser.parse("https://yun.139.com/shareweb/#/w/i/abcDEF123")
        assertEquals(NetDiskPlatform.C139, c139.platform); assertEquals("abcDEF123", c139.shareId)
        val p115 = NetDiskLinkParser.parse("https://115.com/s/sws8lxs36jf-n307/")
        assertEquals("sws8lxs36jf", p115.shareId); assertEquals("n307", p115.password)
        assertEquals("1Ab_cd", NetDiskLinkParser.parse("https://pan.baidu.com/share/init?surl=Ab_cd&pwd=abc1").shareId)
        assertEquals(NetDiskPlatform.XUNLEI, NetDiskLinkParser.parse("https://pan.xunlei.com/s/VOabc_DEF?pwd=aB12").platform)
        assertEquals(NetDiskPlatform.GUANGYA, NetDiskLinkParser.parse("https://www.guangyapan.com/s/abc-def").platform)
    }
}
