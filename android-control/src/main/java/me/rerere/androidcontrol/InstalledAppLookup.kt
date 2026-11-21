package me.rerere.androidcontrol

import android.content.Context
import android.content.Intent
import java.text.Normalizer
import java.util.Locale

internal data class ControlApp(val packageName: String, val label: String)

/** Resolve actual launchers before asking the model to guess a package name. */
@Suppress("DEPRECATION")
internal fun installedControlApps(context: Context): List<ControlApp> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(launcher, 0).mapNotNull { entry ->
        val packageName = entry.activityInfo?.packageName ?: return@mapNotNull null
        if (packageName == context.packageName) return@mapNotNull null
        ControlApp(packageName, runCatching { entry.loadLabel(context.packageManager).toString() }.getOrDefault(packageName))
    }.distinctBy { it.packageName }
}

internal fun matchControlApps(query: String, installed: List<ControlApp>): List<ControlApp> {
    val normalized = normalizeAppName(query)
    if (normalized.isEmpty()) return emptyList()
    installed.filter { it.packageName.equals(query.trim(), ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { return it }
    installed.filter { normalizeAppName(it.label) == normalized }.takeIf { it.isNotEmpty() }?.let { return it }
    // These aliases still require the actual package to exist on this Android user.
    val alias = when (normalized) {
        "wechat", "微信" -> "com.tencent.mm"
        "qq" -> "com.tencent.mobileqq"
        else -> null
    }
    installed.filter { it.packageName == alias }.takeIf { it.isNotEmpty() }?.let { return it }
    if (normalized.length < 2) return emptyList()
    return installed.filter { normalizeAppName(it.label).contains(normalized) }.sortedBy { it.label }.take(8)
}

private fun normalizeAppName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Z}]+"), "").trim()
