package com.constx.onvyra.security

import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.ui.pages.novel.ReaderRuntimeCompatibilityProbe
import com.constx.onvyra.ui.pages.setting.components.providerTypeDisplayName
import com.constx.onvyra.utils.JsonInstant
import kotlin.reflect.full.primaryConstructor
import me.rerere.ai.provider.ProviderSetting
import me.rerere.search.SearchServiceOptions

/** Network-free installed-APK regression probe. It reads no user settings, keys or conversations. */
internal object ReleaseRuntimeCompatibilityProbe {
    /** Saves only a synthetic fixture, verifies the published row, then removes its own fixture. */
    @JvmStatic
    fun verifyGalleryExport(context: Context): String {
        AppIntegrity.requireTrusted()
        val fixture = java.io.File.createTempFile("gallery_export_probe_", ".png", context.cacheDir)
        var savedUri: android.net.Uri? = null
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                fixture.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val result = com.constx.onvyra.data.files.GalleryMediaExporter(context.applicationContext)
                .export(fixture, "image/png", "Onvyra_gallery_probe_${System.currentTimeMillis()}.png")
            savedUri = result.uri
            check(result.uri.scheme == "content")
            check(result.mimeType == "image/png" && result.sizeBytes == fixture.length())
            val resolver = context.contentResolver
            val restored = checkNotNull(resolver.openInputStream(result.uri)).use { it.readBytes() }
            check(restored.contentEquals(fixture.readBytes()))
            val columns = mutableListOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                android.provider.MediaStore.MediaColumns.MIME_TYPE)
            if (android.os.Build.VERSION.SDK_INT >= 29) columns += android.provider.MediaStore.MediaColumns.IS_PENDING
            checkNotNull(resolver.query(result.uri, columns.toTypedArray(), null, null, null)).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == result.displayName && cursor.getString(1) == result.mimeType)
                if (android.os.Build.VERSION.SDK_INT >= 29) check(cursor.getInt(2) == 0)
            }
            return "onvyra-gallery-export-ok"
        } finally {
            try {
                savedUri?.let { check(context.contentResolver.delete(it, null, null) == 1) }
            } finally {
                fixture.delete()
            }
        }
    }

    @JvmStatic
    fun verify(context: Context): String {
        check(AppIntegrity.isTrusted()) { "Package integrity was not accepted" }
        val encoded = JsonInstant.encodeToString(Settings.serializer(), Settings())
        val decoded = JsonInstant.decodeFromString(Settings.serializer(), encoded)
        check(decoded.providers.isNotEmpty())
        SearchServiceOptions.TYPES.keys.forEach { type ->
            checkNotNull(type.primaryConstructor).callBy(emptyMap())
        }
        check(providerTypeDisplayName(ProviderSetting.OpenAI::class) == "OpenAI")
        check(providerTypeDisplayName(ProviderSetting.Google::class) == "Google")
        check(providerTypeDisplayName(ProviderSetting.Claude::class) == "Claude")
        com.artifex.mupdf.fitz.Context.init()
        check(com.artifex.mupdf.fitz.Context.getVersion().major > 0)
        val algorithm = Algorithm.HMAC256("public-runtime-probe-fixture")
        val token = JWT.create().withClaim("probe", true).sign(algorithm)
        check(JWT.require(algorithm).build().verify(token).getClaim("probe").asBoolean() == true)
        check(ReaderRuntimeCompatibilityProbe.verify() == "reader-js-optimized-ok")
        check(!PrivateMediaAuthorization.get(context).hasAccess()) { "Fresh QA installation must start locked" }
        return "onvyra-release-runtime-ok"
    }
}
