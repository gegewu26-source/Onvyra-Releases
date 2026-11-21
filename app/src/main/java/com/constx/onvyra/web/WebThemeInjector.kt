package com.constx.onvyra.web

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlin.math.roundToInt
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.ui.theme.ColorMode
import com.constx.onvyra.ui.theme.findPresetTheme
import com.constx.onvyra.ui.theme.findThemeById

private const val THEME_PREFS = "onvyra.preferences"
private const val THEME_PREFS_COLOR_MODE = "colorMode"
private const val THEME_PREFS_AMOLED = "amoledDark"
private const val WEB_THEME_STORAGE_KEY = "vite-ui-theme"
private const val WEB_STATIC_INDEX = "static/index.html"

/**
 * Serves the remote-control SPA with the app's current Material You color scheme mapped onto
 * the web UI's shadcn variables, so the web page matches the phone app instead of the default
 * neutral-gray palette. The light/dark mode follows the app's color mode setting.
 */
fun Application.configureWebTheme(context: Context, settingsStore: SettingsStore) {
    intercept(ApplicationCallPipeline.Plugins) {
        when (call.request.path()) {
            "/", "/index.html" -> {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(webIndexHtml(context, settingsStore), ContentType.Text.Html)
                finish()
            }
            "/theme.css" -> {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(buildWebThemeCss(context, settingsStore), ContentType.Text.CSS)
                finish()
            }
        }
    }
}

private fun webIndexHtml(context: Context, settingsStore: SettingsStore): String {
    val index = context.classLoader.getResource(WEB_STATIC_INDEX)?.readBytes()
        ?.toString(Charsets.UTF_8)
        ?: return "Remote control UI assets are missing from this build."
    val modeScript = when (currentColorMode(context)) {
        ColorMode.LIGHT -> forceModeScript("light")
        ColorMode.DARK -> forceModeScript("dark")
        ColorMode.SYSTEM -> ""
    }
    val injection = """<link rel="stylesheet" href="/theme.css"/>$modeScript"""
    return index.replace("</head>", "$injection</head>", ignoreCase = true)
}

private fun forceModeScript(mode: String): String =
    """<script>try{localStorage.setItem("$WEB_THEME_STORAGE_KEY","$mode")}catch(e){}</script>"""

private fun buildWebThemeCss(context: Context, settingsStore: SettingsStore): String {
    val settings = settingsStore.settingsFlow.value
    val light = appColorScheme(context, settings, dark = false)
    val dark = appColorScheme(context, settings, dark = true)
    val amoled = isAmoledDarkEnabled(context)
    return buildString {
        append(":root{")
        append(shadcnVariables(light, amoled = false))
        append("}\n")
        append(":root.dark{")
        append(shadcnVariables(dark, amoled = amoled))
        append("}\n")
    }
}

private fun currentColorMode(context: Context): ColorMode {
    val raw = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .getString(THEME_PREFS_COLOR_MODE, null)
    return ColorMode.entries.firstOrNull { it.name == raw } ?: ColorMode.SYSTEM
}

private fun isAmoledDarkEnabled(context: Context): Boolean =
    context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .getBoolean(THEME_PREFS_AMOLED, false)

private fun appColorScheme(context: Context, settings: Settings, dark: Boolean): ColorScheme = when {
    settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    else -> (findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId))
        .getColorScheme(dark)
}

private fun shadcnVariables(scheme: ColorScheme, amoled: Boolean): String {
    val background = if (amoled) Color.Black else scheme.surface
    val variables = linkedMapOf(
        "background" to background,
        "foreground" to scheme.onSurface,
        "card" to scheme.surfaceContainerLow,
        "card-foreground" to scheme.onSurface,
        "popover" to scheme.surfaceContainer,
        "popover-foreground" to scheme.onSurface,
        "primary" to scheme.primary,
        "primary-foreground" to scheme.onPrimary,
        "secondary" to scheme.secondaryContainer,
        "secondary-foreground" to scheme.onSecondaryContainer,
        "muted" to scheme.surfaceContainerHigh,
        "muted-foreground" to scheme.onSurfaceVariant,
        "accent" to scheme.secondaryContainer,
        "accent-foreground" to scheme.onSecondaryContainer,
        "destructive" to scheme.error,
        "destructive-foreground" to scheme.onError,
        "border" to scheme.outlineVariant,
        "input" to scheme.outlineVariant,
        "ring" to scheme.primary,
        "chart-1" to scheme.primary,
        "chart-2" to scheme.tertiary,
        "chart-3" to scheme.secondary,
        "chart-4" to scheme.primaryContainer,
        "chart-5" to scheme.tertiaryContainer,
        "sidebar" to scheme.surfaceContainerLow,
        "sidebar-foreground" to scheme.onSurface,
        "sidebar-primary" to scheme.primary,
        "sidebar-primary-foreground" to scheme.onPrimary,
        "sidebar-accent" to scheme.secondaryContainer,
        "sidebar-accent-foreground" to scheme.onSecondaryContainer,
        "sidebar-border" to scheme.outlineVariant,
        "sidebar-ring" to scheme.primary,
    )
    return variables.entries.joinToString("") { (name, color) -> "--$name:${cssColor(color)};" }
}

private fun cssColor(color: Color): String {
    fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
    return "#%02x%02x%02x".format(channel(color.red), channel(color.green), channel(color.blue))
}
