package me.rerere.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class InstalledAppLookupTest {
    private val apps = listOf(ControlApp("com.tencent.mm", "微信"), ControlApp("com.tencent.mobileqq", "QQ"),
        ControlApp("example.reader", "测试阅读器"), ControlApp("example.reader.lite", "测试阅读器极速版"))

    @Test fun packageAndLocalizedNameUseActualInstalledApplication() {
        assertEquals("com.tencent.mm", matchControlApps("com.tencent.mm", apps).single().packageName)
        assertEquals("com.tencent.mm", matchControlApps("WeChat", apps).single().packageName)
        assertEquals("com.tencent.mobileqq", matchControlApps("ＱＱ", apps).single().packageName)
        assertEquals("example.reader", matchControlApps("测试阅读器", apps).single().packageName)
    }

    @Test fun ambiguousLabelsAreReturnedForChoiceRatherThanRandomlySelected() {
        assertEquals(2, matchControlApps("阅读器", apps).size)
    }

    @Test fun aliasesCannotInventAnUninstalledPackage() {
        assertTrue(matchControlApps("微信", apps.filterNot { it.packageName == "com.tencent.mm" }).isEmpty())
        assertTrue(matchControlApps("unknown application", apps).isEmpty())
        assertTrue(matchControlApps("", apps).isEmpty())
    }
}
