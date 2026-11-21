package com.constx.onvyra.data.ai

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.android.Logging
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RequestLoggingInterceptorTest {
    @Before fun startClean() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After fun stopClean() {
        Logging.setRequestLoggingEnabled(false)
        Logging.clear()
    }

    @Test fun requestAndResponseCredentialsAreMaskedWithoutChangingNetworkHeadersOrConsumingTheBody() {
        var writes = 0
        val payload = "{\"prompt\":\"private conversation\",\"api_key\":\"body-secret\"}"
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = payload.toByteArray().size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { writes++; sink.writeUtf8(payload) }
        }
        val client = OkHttpClient.Builder().addInterceptor(RequestLoggingInterceptor()).addInterceptor { chain ->
            assertEquals("Bearer header-secret", chain.request().header("Authorization"))
            assertEquals("session=cookie-secret", chain.request().header("Cookie"))
            assertEquals("query-secret", chain.request().url.queryParameter("api_key"))
            val sent = Buffer()
            chain.request().body!!.writeTo(sent)
            assertEquals(payload, sent.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Set-Cookie", "session=response-secret").header("X-Request-Id", "request-123")
                .body("ok".toResponseBody()).build()
        }.build()
        client.newCall(Request.Builder().url("https://example.test/v1/chat?api_key=query-secret&model=fixture")
            .header("Authorization", "Bearer header-secret").header("Cookie", "session=cookie-secret")
            .header("X-Provider-Token", "custom-secret").post(body).build()).execute().close()
        assertEquals("Only the actual transport serializes the one-shot body", 1, writes)
        val entry = Logging.getRequestLogs().single()
        assertEquals("██", entry.requestHeaders["Authorization"])
        assertEquals("██", entry.requestHeaders["Cookie"])
        assertEquals("██", entry.requestHeaders["X-Provider-Token"])
        assertEquals("██", entry.responseHeaders["Set-Cookie"])
        assertEquals("request-123", entry.responseHeaders["X-Request-Id"])
        assertEquals(200, entry.responseCode)
        assertTrue(entry.url.contains("model=fixture"))
        assertTrue(entry.url.contains("api_key=REDACTED"))
        val summary = Json.parseToJsonElement(entry.requestBody!!).jsonObject
        assertEquals("application/json", summary.getValue("content_type").jsonPrimitive.content)
        assertEquals(payload.toByteArray().size.toString(), summary.getValue("content_length").jsonPrimitive.content)
        assertEquals("omitted", summary.getValue("body").jsonPrimitive.content)
        listOf("header-secret", "cookie-secret", "custom-secret", "query-secret", "response-secret", "private conversation", "body-secret")
            .forEach { assertFalse("Sensitive data entered a diagnostic log", entry.toString().contains(it)) }
    }

    @Test fun optedOutRequestLoggingCapturesNothingAndStillPerformsTheRequest() {
        Logging.setRequestLoggingEnabled(false)
        var called = false
        val client = OkHttpClient.Builder().addInterceptor(RequestLoggingInterceptor()).addInterceptor { chain ->
            called = true
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("OK")
                .body("".toResponseBody()).build()
        }.build()
        client.newCall(Request.Builder().url("https://example.test/").build()).execute().close()
        assertTrue(called)
        assertTrue(Logging.getRequestLogs().isEmpty())
    }

    @Test fun diagnosticUrlOmitsUserInfoFragmentAndEveryRepeatedSensitiveParameter() {
        val original = "https://owner:password-secret@example.test/path?q=first&access_token=one&ACCESS_TOKEN=two&q=second#token=fragment-secret".toHttpUrl()
        val redacted = RequestLogSanitizer.url(original).toHttpUrl()
        assertEquals("", redacted.username)
        assertEquals("", redacted.password)
        assertNull(redacted.fragment)
        assertEquals(listOf("first", "second"), redacted.queryParameterValues("q"))
        assertEquals("REDACTED", redacted.queryParameter("access_token"))
        assertEquals("REDACTED", redacted.queryParameter("ACCESS_TOKEN"))
        assertEquals("password-secret", original.password)
        assertEquals("one", original.queryParameter("access_token"))
    }

    @Test fun customCredentialHeadersAreMaskedCaseInsensitivelyAndOrdinaryMetadataRemains() {
        val headers = Headers.Builder().add("x-goog-api-key", "google-secret").add("X-Client-SECRET", "client-secret")
            .add("x_auth_TOKEN", "token-secret").add("Content-Type", "application/json")
            .add("X-Trace-Id", "trace-123").build()
        val redacted = RequestLogSanitizer.headers(headers)
        assertEquals("██", redacted["x-goog-api-key"])
        assertEquals("██", redacted["X-Client-SECRET"])
        assertEquals("██", redacted["x_auth_TOKEN"])
        assertEquals("application/json", redacted["Content-Type"])
        assertEquals("trace-123", redacted["X-Trace-Id"])
        assertEquals("google-secret", headers["x-goog-api-key"])
    }

    @Test fun failuresKeepExceptionTypeWithoutExposingUpstreamErrorText() {
        val client = OkHttpClient.Builder().addInterceptor(RequestLoggingInterceptor()).addInterceptor {
            throw IOException("upstream rejected secret-token at https://example.test/?key=error-secret")
        }.build()
        assertTrue(runCatching { client.newCall(Request.Builder().url("https://example.test/?key=query-secret").build()).execute() }
            .exceptionOrNull() is IOException)
        val entry = Logging.getRequestLogs().single()
        assertEquals("IOException", entry.error)
        assertFalse(entry.toString().contains("secret-token"))
        assertFalse(entry.toString().contains("error-secret"))
        assertFalse(entry.toString().contains("query-secret"))
    }

    @Test fun unknownLengthAndBrokenLengthMetadataDoNotReadTheBody() {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = throw IOException("length-secret")
            override fun isDuplex() = true
            override fun writeTo(sink: BufferedSink) = error("Logging must never consume a duplex stream")
        }
        val summary = RequestLogSanitizer.body(body)!!
        assertTrue(summary.contains("\"content_length\":-1"))
        assertFalse(summary.contains("length-secret"))
        assertNull(RequestLogSanitizer.body(null))
    }
}
