package com.constx.onvyra.security

import com.constx.onvyra.data.media.ExternalVideoEndpointConfig
import java.io.IOException
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A shared, fail-closed policy. Every operation checks the current grant, including cache reads. */
internal object PrivateMediaAccess {
    @Volatile
    private var verifier: () -> Boolean = { false }

    internal fun installVerifier(verify: () -> Boolean) {
        verifier = verify
    }

    fun hasAccess(): Boolean = AppIntegrity.isTrusted() && runCatching { verifier() }.getOrDefault(false)

    fun isRestrictedSource(id: String): Boolean = id.trim().lowercase(Locale.ROOT) in sourceIds

    fun isRestrictedUrl(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    fun isAllowed(sourceId: String? = null, url: String? = null): Boolean =
        (!(sourceId?.let(::isRestrictedSource) ?: false) && !(url?.let(::isRestrictedUrl) ?: false)) || hasAccess()

    fun requireAccess() {
        if (!hasAccess()) throw PrivateMediaAccessDeniedException()
    }

    fun requireAllowed(sourceId: String? = null, url: String? = null) {
        if (!isAllowed(sourceId, url)) throw PrivateMediaAccessDeniedException()
    }

    private val sourceIds = setOf("jable", "missav", "supjav", "hanime1")
    private val hosts = ExternalVideoEndpointConfig.restrictedHosts
}

internal class PrivateMediaAccessDeniedException : IOException("当前功能不可用")
