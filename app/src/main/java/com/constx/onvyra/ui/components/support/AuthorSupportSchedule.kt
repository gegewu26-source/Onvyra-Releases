package com.constx.onvyra.ui.components.support

import android.content.Context

/** The reminder is based on an actually displayed dialog, never on a background launch. */
object AuthorSupportSchedule {
    const val INTERVAL_MILLIS = 10L * 24 * 60 * 60 * 1_000

    fun isDue(lastShownAt: Long?, now: Long): Boolean {
        if (now < 0) return false
        if (lastShownAt == null || lastShownAt < 0) return true
        if (now < lastShownAt) return false
        return now - lastShownAt >= INTERVAL_MILLIS
    }

    fun isNormalLaunch(action: String?, categories: Set<String>?, hasData: Boolean, hasRoutedPayload: Boolean): Boolean =
        !hasData && !hasRoutedPayload &&
            (action == null || action == "android.intent.action.MAIN") &&
            (categories.isNullOrEmpty() || categories == setOf("android.intent.category.LAUNCHER"))
}

internal class AuthorSupportLaunchStore(context: Context, preferenceName: String = "author_support_launch_v1") {
    private val preferences = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun isDue(now: Long): Boolean {
        val lastShown = if (preferences.contains("last_shown_at")) {
            runCatching { preferences.getLong("last_shown_at", 0L) }.getOrNull()
        } else null
        if (lastShown != null && now >= 0 && lastShown > now) {
            // A corrected device clock starts a fresh quiet interval; it must neither
            // cause an immediate repeat nor keep the reminder suppressed for years.
            preferences.edit().putLong("last_shown_at", now).apply()
            return false
        }
        return AuthorSupportSchedule.isDue(lastShown, now)
    }

    fun recordShown(now: Long) {
        if (now >= 0) preferences.edit().putLong("last_shown_at", now).apply()
    }
}
