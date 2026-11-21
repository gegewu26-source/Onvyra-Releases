package com.constx.onvyra.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguagesTest {
    @Test fun noAppOverrideRemainsSystemInsteadOfSelectingASpecificLanguage() {
        assertNull(appLanguageOption(""))
        assertNull(appLanguageOption(" "))
    }

    @Test fun externalSettingsRegionalLanguagesMatchTheirPickerEntries() {
        assertEquals("en", appLanguageOption("en-GB")?.tag)
        assertEquals("ko-KR", appLanguageOption("ko")?.tag)
        assertEquals("ja", appLanguageOption("ja-JP")?.tag)
        assertEquals("de", appLanguageOption("de-AT")?.tag)
        assertEquals("ar", appLanguageOption("ar-SA")?.tag)
    }

    @Test fun traditionalAndSimplifiedScriptChoicesStaySeparate() {
        assertEquals("zh-TW", appLanguageOption("zh-Hant")?.tag)
        assertEquals("zh-TW", appLanguageOption("zh-HK")?.tag)
        assertEquals("zh-CN", appLanguageOption("zh-Hans-SG")?.tag)
        assertEquals("zh-CN", appLanguageOption("zh-Hans-HK")?.tag)
        assertEquals("zh-TW", appLanguageOption("zh-Hant-CN")?.tag)
        assertEquals("zh-CN", appLanguageOption("zh-CN")?.tag)
        assertNull(appLanguageOption("fr-FR"))
    }
}
