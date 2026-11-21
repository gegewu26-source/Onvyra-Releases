package com.constx.onvyra.data.ai

import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.RequestBody

/** Redacts copies for diagnostics. The actual network request is never changed. */
internal object RequestLogSanitizer {
    const val REDACTED = "██"
    val standardSensitiveHeaders = listOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-API-Key", "API-Key", "ApiKey",
        "X-Goog-API-Key", "X-Auth-Token", "X-Access-Token", "Access-Token", "X-Session-Token",
        "X-CSRF-Token", "X-XSRF-Token", "X-Client-Secret", "X-Signature",
    )
    val standardSensitiveQueryParameters = listOf(
        "key", "api_key", "apikey", "apiKey", "access_token", "token", "auth", "authorization",
        "password", "passwd", "secret", "signature", "sig", "credential", "session", "sessionid", "jwt", "code",
    )

    private fun normalized(name: String): String = name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun sensitive(name: String): Boolean {
        val key = normalized(name)
        return key in setOf("key", "auth", "code", "sig", "jwt", "session", "sessionid") ||
            listOf("authorization", "cookie", "apikey", "token", "secret", "password", "passwd", "signature", "credential")
                .any(key::contains)
    }

    fun headers(headers: Headers): Map<String, String> = headers.names().associateWith { name ->
        if (sensitive(name)) REDACTED else headers.values(name).joinToString(", ")
    }

    fun url(url: HttpUrl): String {
        val builder = url.newBuilder().username("").password("").fragment(null).query(null)
        repeat(url.querySize) { index ->
            val name = url.queryParameterName(index)
            builder.addQueryParameter(name, if (sensitive(name)) "REDACTED" else url.queryParameterValue(index))
        }
        return builder.build().toString()
    }

    /** Never serializes a request twice, consumes one-shot streams, or stores prompts and uploads. */
    fun body(body: RequestBody?): String? = body?.let {
        buildJsonObject {
            put("content_type", runCatching { it.contentType()?.let { type -> "${type.type}/${type.subtype}" } }.getOrNull() ?: "unknown")
            put("content_length", runCatching { it.contentLength() }.getOrDefault(-1L))
            put("body", "omitted")
        }.toString()
    }
}
