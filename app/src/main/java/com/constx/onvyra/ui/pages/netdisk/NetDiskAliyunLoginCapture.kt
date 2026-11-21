package com.constx.onvyra.ui.pages.netdisk

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Only the final www callback token belongs to the same session as the user's file workspace. */
internal object NetDiskAliyunLoginCapture {
    const val CALLBACK_ORIGIN = "https://www.alipan.com"
    const val MESSAGE_OBJECT = "onvyraAliyunLoginResult"
    const val LOGIN_URL = "https://auth.alipan.com/v2/oauth/authorize?client_id=25dzX3vbYqktVxyX" +
        "&redirect_uri=https%3A%2F%2Fwww.alipan.com%2Fsign%2Fcallback&response_type=code&login_type=custom" +
        "&state=%7B%22origin%22%3A%22https%3A%2F%2Fwww.alipan.com%22%7D" +
        "#/nestedlogin?keepLogin=false&hidePhoneCode=true&isMobile=true"

    fun mayNavigateTop(url: String?): Boolean = trustedUri(url)?.host in
        setOf("auth.alipan.com", "passport.alipan.com", "www.alipan.com")

    private fun trustedUri(value: String?): URI? = runCatching { URI(value.orEmpty()) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.rawUserInfo == null && it.port in listOf(-1, 443)
    }

    /** Neither auth.alipan.com's intermediate token nor an old token read is an accepted completion. */
    fun finalAccessToken(sourceOrigin: String, mainFrame: Boolean, pageUrl: String?,
        message: String?, nonce: String): String? {
        if (!mainFrame || sourceOrigin != CALLBACK_ORIGIN || message == null || message.length > 65_536) return null
        val page = trustedUri(pageUrl) ?: return null
        if (page.host != "www.alipan.com" || (page.path != "/sign/callback" &&
                page.path != "/drive" && !page.path.startsWith("/drive/"))) return null
        val envelope = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null
        fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (nonce.length !in 24..128 || envelope.text("nonce") != nonce ||
            envelope.text("kind") != "final-token-write" || envelope.text("path") != "/sign/callback") return null
        val stored = envelope.text("token") ?: return null
        val token = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull() ?: return null
        return token.text("access_token")?.takeIf { value ->
            value.length in 16..16_384 && value.none { it.isWhitespace() || it.isISOControl() }
        }
    }

    /** Installed before callback JS. It observes a new successful storage write; it never reads old storage. */
    fun documentStartScript(nonce: String): String {
        require(nonce.matches(Regex("[A-Za-z0-9_-]{24,128}")))
        val quotedNonce = JsonPrimitive(nonce).toString()
        return """
            (function() { try {
              if (window.top !== window || location.origin !== '$CALLBACK_ORIGIN' ||
                  location.pathname !== '/sign/callback') return;
              var original = Storage.prototype.setItem;
              Storage.prototype.setItem = function(key, value) {
                var result = original.apply(this, arguments);
                if (this === window.localStorage && key === 'token' && typeof value === 'string' && value.length <= 49152) {
                  try {
                    var parsed = JSON.parse(value);
                    if (parsed && typeof parsed.access_token === 'string' && parsed.access_token.length >= 16 &&
                        parsed.access_token.length <= 16384 && !/\s/.test(parsed.access_token)) {
                      window.$MESSAGE_OBJECT.postMessage(JSON.stringify({kind:'final-token-write',
                        nonce:$quotedNonce, path:'/sign/callback', token:value}));
                    }
                  } catch (_) {}
                }
                return result;
              };
            } catch (_) {} })()
        """.trimIndent()
    }
}
