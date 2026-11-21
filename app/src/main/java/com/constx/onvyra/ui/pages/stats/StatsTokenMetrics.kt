package com.constx.onvyra.ui.pages.stats

import java.text.NumberFormat
import java.util.Locale

internal enum class CacheHitRateUnavailable {
    NO_INPUT,
    INCONSISTENT_COUNTS,
}

internal data class CacheHitRate(
    val fraction: Double? = null,
    val unavailable: CacheHitRateUnavailable? = null,
)

/** Both inputs are lifetime sums, so large and small requests are weighted by their input tokens. */
internal fun calculateCacheHitRate(inputTokens: Long, cachedTokens: Long): CacheHitRate = when {
    inputTokens < 0 || cachedTokens < 0 || cachedTokens > inputTokens ->
        CacheHitRate(unavailable = CacheHitRateUnavailable.INCONSISTENT_COUNTS)
    inputTokens == 0L -> CacheHitRate(unavailable = CacheHitRateUnavailable.NO_INPUT)
    else -> CacheHitRate(fraction = cachedTokens.toDouble() / inputTokens.toDouble())
}

internal fun formatCacheHitRate(rate: CacheHitRate, locale: Locale = Locale.getDefault()): String {
    val fraction = rate.fraction ?: return "—"
    if (!fraction.isFinite() || fraction !in 0.0..1.0) return "—"
    return NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(fraction)
}
