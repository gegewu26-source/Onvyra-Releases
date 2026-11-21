package com.constx.onvyra.data.netdisk

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class NetDiskResponse(val status: Int, val body: String, val headers: Headers, val url: String) {
    fun json(): JsonObject = try { Json.parseToJsonElement(body).jsonObject }
        catch (_: Exception) { throw NetDiskException("invalid_response", "网盘没有返回可识别的数据（HTTP $status），可能需要登录或完成网页验证。") }
}

/** Isolated, cancellable provider HTTP. Never inherits chat/API credentials or logs response bodies. */
class NetDiskHttp(client: OkHttpClient? = null) {
    private data class HeaderScope(val host: String, val anonymous: Boolean, val cookieEpoch: Long)
    private val cookieEpoch = AtomicLong()
    private val cookies = ArrayList<Cookie>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) = synchronized(cookies) {
            for (value in values) {
                cookies.removeAll { it.name == value.name && it.domain == value.domain && it.path == value.path }
                if (value.expiresAt > System.currentTimeMillis()) cookies.add(value)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
            cookies.filter { it.matches(url) }
        }
    }
    private val baseClient = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).cookieJar(CookieJar.NO_COOKIES)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val scope = request.tag(HeaderScope::class.java)
            val redirected = scope != null && request.url.host != scope.host
            if (redirected && request.method !in setOf("GET", "HEAD"))
                throw IOException("网盘接口重定向到了不同域名")
            val safe = request.newBuilder().apply {
                if (redirected) listOf("Cookie", "Authorization", "X-Share-Token", "X-CToken", "X-Token").forEach { removeHeader(it) }
                val values = linkedMapOf<String, String>()
                // An explicit empty Cookie means anonymous, even after another
                // request obtained account cookies on this provider transport.
                if (scope?.anonymous != true && scope?.cookieEpoch == cookieEpoch.get())
                    jar.loadForRequest(request.url).forEach { values[it.name] = it.value }
                if (!redirected && scope?.anonymous != true) request.header("Cookie")?.split(';')?.forEach { part ->
                    val pair = part.trim().split('=', limit = 2)
                    if (pair.size == 2) values[pair[0]] = pair[1]
                }
                if (values.isEmpty()) removeHeader("Cookie") else header("Cookie", values.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }.build()
            val response = chain.proceed(safe)
            synchronized(cookies) {
                if (scope?.cookieEpoch == cookieEpoch.get()) jar.saveFromResponse(request.url, Cookie.parseAll(request.url, response.headers))
            }
            response
        }.build()

    fun clearCookies() = synchronized(cookies) { cookieEpoch.incrementAndGet(); cookies.clear() }

    fun cookieHeader(url: String): String = url.toHttpUrlOrNull()?.let { target ->
        jar.loadForRequest(target).joinToString("; ") { "${it.name}=${it.value}" }
    }.orEmpty()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), followRedirects: Boolean = true): NetDiskResponse =
        request("GET", url, headers, null, followRedirects)

    suspend fun head(url: String, headers: Map<String, String> = emptyMap(), followRedirects: Boolean = true): NetDiskResponse =
        request("HEAD", url, headers, null, followRedirects)

    suspend fun postJson(url: String, body: JsonObject, headers: Map<String, String> = emptyMap(), followRedirects: Boolean = true): NetDiskResponse =
        request("POST", url, headers, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()), followRedirects)

    suspend fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap(), followRedirects: Boolean = true): NetDiskResponse =
        request("POST", url, headers, FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build(), followRedirects)

    suspend fun request(method: String, url: String, headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null, followRedirects: Boolean = true): NetDiskResponse {
        currentCoroutineContext().ensureActive()
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.username.isBlank() && it.password.isBlank() }
            ?: throw NetDiskException("invalid_url", "网盘返回了无效地址。")
        val request = Request.Builder().url(parsed).tag(HeaderScope::class.java,
            HeaderScope(parsed.host, headers.entries.any { it.key.equals("Cookie", true) && it.value.isEmpty() }, cookieEpoch.get()))
            .header("User-Agent", WEB_USER_AGENT).apply { headers.forEach { (key, value) -> header(key, value) } }
            .method(method, body).build()
        val client = if (followRedirects) baseClient else baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        NetDiskException("network", "连接网盘失败，请检查网络后重试。"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val source = it.body.source()
                            source.request(MAX_BODY_BYTES + 1)
                            if (source.buffer.size > MAX_BODY_BYTES) throw NetDiskException("response_too_large", "网盘响应过大，已停止读取。")
                            NetDiskResponse(it.code, source.readUtf8(), it.headers, it.request.url.toString())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024
    }
}
