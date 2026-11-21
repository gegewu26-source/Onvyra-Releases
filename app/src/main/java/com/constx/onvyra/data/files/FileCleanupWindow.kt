package com.constx.onvyra.data.files

internal enum class FileCleanupUnit(val millis: Long, val label: String) {
    HOURS(3_600_000L, "小时"),
    DAYS(86_400_000L, "天"),
    MONTHS(2_592_000_000L, "月（30天）"),
}

/** Reject zero, fractions and values that would overflow when converted to milliseconds. */
internal fun cleanupDurationMillis(amountText: String, unit: FileCleanupUnit): Long? {
    val amount = amountText.trim().toLongOrNull() ?: return null
    if (amount <= 0 || amount > Long.MAX_VALUE / unit.millis) return null
    return amount * unit.millis
}

/** "Within" includes both ends. Invalid/future timestamps are not selected. */
internal fun matchesCleanupWindow(createdAt: Long, nowMillis: Long, durationMillis: Long): Boolean =
    durationMillis > 0 && createdAt >= 0 && nowMillis >= createdAt && nowMillis - createdAt <= durationMillis
