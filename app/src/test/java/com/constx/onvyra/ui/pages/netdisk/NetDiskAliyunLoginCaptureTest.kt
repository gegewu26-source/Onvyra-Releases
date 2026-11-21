package com.constx.onvyra.ui.pages.netdisk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

class NetDiskAliyunLoginCaptureTest {
    private val nonce = "a123456789012345678901234567890b"
    private val fresh = "new-final-access-token-123456789"
    private val old = "old-saved-account-token-12345678"

    @Test fun acceptsOnlyAFreshCallbackEnvelopeFromTheTrustedMainFrame() {
        val message = message()
        assertEquals(fresh, accept(message = message))
        assertEquals(fresh, accept(page = "https://www.alipan.com/drive/file/all", message = message))
        assertNull(accept(origin = "https://auth.alipan.com", message = message))
        assertNull(accept(origin = "https://www.alipan.com.evil.test", message = message))
        assertNull(accept(main = false, message = message))
        assertNull(accept(page = "https://auth.alipan.com/sign/callback", message = message))
        assertNull(accept(page = "https://www.alipan.com/", message = message))
        assertNull(accept(page = "http://www.alipan.com/sign/callback", message = message))
        assertNull(accept(page = "https://www.alipan.com:8443/sign/callback", message = message))
        assertNull(accept(page = "https://user@www.alipan.com/sign/callback", message = message))
        assertNull(accept(message = message(nonce = "different-flow-12345678901234567890")))
        assertNull(accept(message = message(path = "/nestedlogin")))
        assertNull(accept(message = message(kind = "old-token-read")))
    }

    @Test fun rejectsMalformedOrOversizedTokensWithoutReturningAnyCredential() {
        assertNull(accept(message = null))
        assertNull(accept(message = "not json"))
        assertNull(accept(message = "x".repeat(65_537)))
        assertNull(accept(message = message(token = "not-json")))
        assertNull(accept(message = message(token = "{\"access_token\":123456789012345678}")))
        assertNull(accept(message = message(token = "{\"refresh_token\":\"ignored-refresh-token\"}")))
        assertNull(accept(message = message(token = token("short"))))
        assertNull(accept(message = message(token = token("contains\nnewline-123456789"))))
        assertNull(accept(message = message(token = token("Bearer invalid-token-12345678"))))
        assertNull(accept(message = message(token = token("x".repeat(16_385)))))
    }

    @Test fun scriptObservesANewFinalWriteWithoutReadingTheExistingAccountToken() = withCallback { context, scope ->
        evaluate(context, scope, "window.localStorage.setItem('token', ${JsonPrimitive(token(old))});")
        evaluate(context, scope, NetDiskAliyunLoginCapture.documentStartScript(nonce))
        assertEquals("0", evaluate(context, scope, "messages.length"))
        evaluate(context, scope, "sessionStorage.setItem('token', ${JsonPrimitive(token(fresh))});")
        evaluate(context, scope, "window.localStorage.setItem('other', ${JsonPrimitive(token(fresh))});")
        assertEquals("0", evaluate(context, scope, "messages.length"))
        evaluate(context, scope, "window.localStorage.setItem('token', ${JsonPrimitive(token(fresh))});")
        assertEquals("1", evaluate(context, scope, "messages.length"))
        assertEquals(fresh, accept(message = evaluate(context, scope, "messages[0]")))
        assertEquals(token(fresh), evaluate(context, scope, "window.localStorage.entries.token"))
        assertEquals("0", evaluate(context, scope, "readCount"))
    }

    @Test fun authIntermediateDocumentsSubframesAndFailedWritesNeverSignalCompletion() {
        for (alteration in listOf("location.origin='https://auth.alipan.com';",
            "window.top={};", "location.pathname='/drive/file/all';")) {
            withCallback { context, scope ->
                evaluate(context, scope, alteration)
                evaluate(context, scope, NetDiskAliyunLoginCapture.documentStartScript(nonce))
                evaluate(context, scope, "window.localStorage.setItem('token', ${JsonPrimitive(token(fresh))});")
                assertEquals("0", evaluate(context, scope, "messages.length"))
            }
        }
        withCallback { context, scope ->
            evaluate(context, scope, NetDiskAliyunLoginCapture.documentStartScript(nonce))
            evaluate(context, scope, "failWrite=true;try{window.localStorage.setItem('token', ${JsonPrimitive(token(fresh))});}catch(_){}")
            assertEquals("0", evaluate(context, scope, "messages.length"))
        }
    }

    @Test fun topNavigationStaysWithinTheObservedLoginChain() {
        assertTrue(NetDiskAliyunLoginCapture.mayNavigateTop(NetDiskAliyunLoginCapture.LOGIN_URL))
        assertTrue(NetDiskAliyunLoginCapture.mayNavigateTop("https://passport.alipan.com/mini_login.htm"))
        assertTrue(NetDiskAliyunLoginCapture.mayNavigateTop("https://www.alipan.com/sign/callback?code=fixture"))
        assertFalse(NetDiskAliyunLoginCapture.mayNavigateTop("https://auth.alipan.com.evil.test/"))
        assertFalse(NetDiskAliyunLoginCapture.mayNavigateTop("https://api.aliyundrive.com/token/get"))
        assertFalse(NetDiskAliyunLoginCapture.mayNavigateTop("javascript:alert(1)"))
        assertFalse(NetDiskAliyunLoginCapture.mayNavigateTop("file:///tmp/login.html"))
        assertFalse(NetDiskAliyunLoginCapture.mayNavigateTop(null))
    }

    private fun token(access: String) = buildJsonObject { put("access_token", access) }.toString()

    private fun message(nonce: String = this.nonce, path: String = "/sign/callback",
        kind: String = "final-token-write", token: String = token(fresh)) = buildJsonObject {
        put("nonce", nonce); put("kind", kind); put("path", path); put("token", token)
    }.toString()

    private fun accept(origin: String = NetDiskAliyunLoginCapture.CALLBACK_ORIGIN, main: Boolean = true,
        page: String = "https://www.alipan.com/sign/callback?code=fixture", message: String?) =
        NetDiskAliyunLoginCapture.finalAccessToken(origin, main, page, message, nonce)

    private fun evaluate(context: Context, scope: Scriptable, script: String): String =
        Context.toString(context.evaluateString(scope, script, "aliyun-callback", 1, null))

    private fun withCallback(block: (Context, Scriptable) -> Unit) {
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            context.evaluateString(scope, """
                var messages=[],readCount=0,failWrite=false;
                function Storage(){this.entries={};}
                Storage.prototype.setItem=function(key,value){if(failWrite)throw Error('storage failure');this.entries[key]=value;};
                Storage.prototype.getItem=function(key){readCount++;return this.entries[key]||null;};
                var location={origin:'https://www.alipan.com',pathname:'/sign/callback'};
                var window={};window.top=window;window.localStorage=new Storage();
                var sessionStorage=new Storage();
                window.onvyraAliyunLoginResult={postMessage:function(value){messages.push(value);}};
            """.trimIndent(), "fixture", 1, null)
            block(context, scope)
        } finally { Context.exit() }
    }
}
