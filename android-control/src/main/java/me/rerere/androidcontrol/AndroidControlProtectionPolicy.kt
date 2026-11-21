package me.rerere.androidcontrol

/** An explicit grant and a visible notification are both required, even while idle. */
internal enum class ControlProtectionMode { OFF, STANDBY, TASK }

internal fun controlProtectionMode(
    enabled: Boolean,
    accessibilityAuthorized: Boolean,
    notificationsAllowed: Boolean,
    activeTask: Boolean,
): ControlProtectionMode = when {
    !enabled || !accessibilityAuthorized || !notificationsAllowed -> ControlProtectionMode.OFF
    activeTask -> ControlProtectionMode.TASK
    else -> ControlProtectionMode.STANDBY
}

/** A retained Kotlin instance alone cannot establish a working system connection. */
internal fun accessibilityConnectionIsLive(lifecycleConnected: Boolean, frameworkInfoAvailable: () -> Boolean): Boolean =
    accessibilityConnectionIsLive(lifecycleConnected, frameworkInfoAvailable, { true })

internal fun accessibilityConnectionIsLive(lifecycleConnected: Boolean, frameworkInfoAvailable: () -> Boolean,
    systemReportsBound: () -> Boolean): Boolean =
    lifecycleConnected && runCatching { frameworkInfoAvailable() && systemReportsBound() }.getOrDefault(false)
