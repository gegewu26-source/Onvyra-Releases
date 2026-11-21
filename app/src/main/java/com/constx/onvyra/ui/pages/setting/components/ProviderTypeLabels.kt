package com.constx.onvyra.ui.pages.setting.components

import kotlin.reflect.KClass
import me.rerere.ai.provider.ProviderSetting

/** User-visible names must remain stable when R8 renames Kotlin/JVM classes. */
internal fun providerTypeDisplayName(type: KClass<out ProviderSetting>): String = when (type) {
    ProviderSetting.OpenAI::class -> "OpenAI"
    ProviderSetting.Google::class -> "Google"
    ProviderSetting.Claude::class -> "Claude"
    else -> "Unknown"
}
