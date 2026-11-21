package com.constx.onvyra.security

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.constx.onvyra.BuildConfig
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** A local resistance measure. It neither detects nor blocks Root, accessibility or emulators. */
internal object AppIntegrity {
    @Volatile private var verified = false

    fun initialize(context: Context) {
        verified = verify(context)
    }

    fun isTrusted(): Boolean = !BuildConfig.APP_INTEGRITY_ENFORCED || verified

    fun requireTrusted() {
        if (!isTrusted()) throw AppIntegrityException()
    }

    /** Recheck only on an explicit privilege unlock, never on each chat/network operation. */
    fun requireFreshTrust(context: Context) {
        if (!BuildConfig.APP_INTEGRITY_ENFORCED) return
        // A previously rejected package cannot regain trust through a later partial check.
        requireTrusted()
        verified = verify(context)
        requireTrusted()
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context): Boolean {
        if (!BuildConfig.APP_INTEGRITY_ENFORCED) return true
        return runCatching {
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return false
            val packageInfo = context.packageManager.getPackageInfo(context.packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            val signers = if (Build.VERSION.SDK_INT >= 28) packageInfo.signingInfo?.apkContentsSigners else packageInfo.signatures
            if (!hasTrustedSigningCertificate(BuildConfig.TRUSTED_SIGNING_CERT_SHA256,
                    signers?.map { it.toByteArray() }.orEmpty())) return false
            val assets = listOf(
                "security/private_media_access.json" to BuildConfig.PRIVATE_MEDIA_ASSET_SHA256,
                "mode_injections/wuzha.txt" to BuildConfig.BUILTIN_MODE_ASSET_SHA256,
            )
            assets.all { (path, digest) ->
                val bytes = context.assets.open(path).use { it.readBytes() }
                matchesSha256(digest, bytes)
            }
        }.getOrDefault(false)
    }

    fun blockIfUntrusted(activity: Activity): Boolean {
        if (isTrusted()) return false
        AlertDialog.Builder(activity)
            .setTitle("安装包校验失败")
            .setMessage("无法验证此安装包的签名或关键资源，请使用原始正式安装包。")
            .setCancelable(false)
            .setPositiveButton("关闭") { _, _ -> activity.finishAndRemoveTask() }
            .show()
        return true
    }
}

internal class AppIntegrityException : IOException("安装包完整性验证未通过。")

internal class AppIntegrityInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        AppIntegrity.requireTrusted()
        return chain.proceed(chain.request())
    }
}
