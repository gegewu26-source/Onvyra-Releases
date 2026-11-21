package com.constx.onvyra.data.media

import com.constx.onvyra.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Optional build-local routing. Public builds ship without private endpoints. */
internal object ExternalVideoEndpointConfig {
    private val restrictedAliases = listOf(
        "jable_mirror", "jable_primary",
        "missav_primary", "missav_mirror_1", "missav_mirror_2", "missav_mirror_3",
        "missav_mirror_4", "missav_mirror_5", "missav_legacy_1", "missav_legacy_2",
        "missav_legacy_3", "missav_legacy_4",
        "supjav_primary", "hanime1_primary", "hanime1_mirror_1", "hanime1_mirror_2",
        "hanime1_alternate", "missav_stream", "hanime1_restricted_1",
        "hanime1_restricted_2", "hanime1_stream_backup",
    )

    private val hosts = (runCatching {
        Json.parseToJsonElement(BuildConfig.PRIVATE_VIDEO_ENDPOINTS_JSON) as? JsonObject
    }.getOrNull() ?: emptyMap()).mapNotNull { (alias, value) ->
        val text = (value as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val url = (if ("://" in text) text else "https://$text").toHttpUrlOrNull()
            ?: return@mapNotNull null
        if (!url.isHttps || url.port != 443 || url.encodedPath != "/" ||
            url.encodedQuery != null || url.encodedFragment != null ||
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()
        ) return@mapNotNull null
        alias to url.host.trimEnd('.')
    }.toMap()

    fun configuredHost(alias: String): String? = hosts[alias]

    fun host(alias: String): String = configuredHost(alias)
        ?: "${alias.replace('_', '-')}.private-media.invalid"

    fun matchesHost(value: String, alias: String): Boolean {
        val expected = host(alias)
        return value == expected || value.endsWith(".$expected")
    }

    fun mirrors(vararg aliases: String): List<String> =
        aliases.mapNotNull(::configuredHost).ifEmpty { aliases.map(::host) }

    val restrictedHosts: Set<String> = restrictedAliases.mapTo(mutableSetOf(), ::host).apply {
        addAll(hosts.values)
    }
}
