package me.rerere.androidcontrol.ui

import android.content.Context
import android.content.Intent

/** Keep notification entry on the host's settings page; standalone module hosts use the fallback. */
internal fun controlSettingsIntent(context: Context): Intent {
    val host = Intent().setClassName(context.packageName, "${context.packageName}.RouteActivity")
        .putExtra("open_android_control", true)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return if (host.resolveActivity(context.packageManager) != null) host
    else Intent(context, AndroidControlActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
