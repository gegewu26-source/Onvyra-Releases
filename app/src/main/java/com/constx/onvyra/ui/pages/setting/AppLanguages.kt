package com.constx.onvyra.ui.pages.setting

import java.util.Locale

internal data class AppLanguageOption(val tag: String, val nativeName: String)

/** Native names keep the picker usable even when the current language is unfamiliar. */
internal val AppLanguageOptions = listOf(
    AppLanguageOption("zh-CN", "简体中文"),
    AppLanguageOption("zh-TW", "繁體中文"),
    AppLanguageOption("en", "English"),
    AppLanguageOption("ja", "日本語"),
    AppLanguageOption("ko-KR", "한국어"),
    AppLanguageOption("de", "Deutsch"),
    AppLanguageOption("ar", "العربية"),
    AppLanguageOption("ru", "Русский"),
)

/** System settings may use region/script variants of an entry in our picker. */
internal fun appLanguageOption(tag: String): AppLanguageOption? {
    if (tag.isBlank()) return null
    val locale = Locale.forLanguageTag(tag)
    val canonical = when (locale.language) {
        "zh" -> when {
            locale.script == "Hant" -> "zh-TW"
            locale.script == "Hans" -> "zh-CN"
            locale.country in setOf("TW", "HK", "MO") -> "zh-TW"
            else -> "zh-CN"
        }
        "ko" -> "ko-KR"
        else -> locale.language
    }
    return AppLanguageOptions.firstOrNull { it.tag == canonical }
}
