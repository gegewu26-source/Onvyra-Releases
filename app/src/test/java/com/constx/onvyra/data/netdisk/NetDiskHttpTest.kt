package com.constx.onvyra.data.netdisk

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NetDiskHttpTest {
    private class Fixture : AutoCloseable {
        val executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = this@Fixture.executor; start() }
        fun url(path: String, host: String = "localhost") = "http://$host:${server.address.port}$path"
        fun route(path: String, block: (HttpExchange) -> Unit) { server.createContext(path) { exchange -> exchange.use(block) } }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private fun HttpExchange.reply(text: String, status: Int = 200) {
        val bytes = text.toByteArray(); sendResponseHeaders(status, bytes.size.toLong()); responseBody.write(bytes)
    }

    @Test fun providerBusinessFailuresKeepTheirStatusAndJsonInsteadOfBecomingSuccess() = runBlocking {
        Fixture().use { fixture ->
            fixture.route("/missing") { it.reply("""{"code":41006,"message":"分享不存在"}""", 404) }
            val response = NetDiskHttp().get(fixture.url("/missing"))
            assertEquals(404, response.status)
            assertEquals("分享不存在", response.json()["message"].toString().trim('"'))
        }
    }

    @Test fun cookieJarRetainsSeparateSetCookieHeadersAndExplicitAnonymousCallsDoNotInheritThem() = runBlocking {
        Fixture().use { fixture ->
            fixture.route("/set") {
                it.responseHeaders.add("Set-Cookie", "session=abc; Path=/; Expires=Wed, 21 Oct 2099 07:28:00 GMT")
                it.responseHeaders.add("Set-Cookie", "second=def; Path=/")
                it.reply("{}")
            }
            fixture.route("/echo") { it.reply(it.requestHeaders.getFirst("Cookie").orEmpty()) }
            fixture.route("/redirect-anonymous") {
                it.responseHeaders.add("Location", "/echo"); it.sendResponseHeaders(302, -1)
            }
            val http = NetDiskHttp()
            http.get(fixture.url("/set"))
            assertTrue(http.get(fixture.url("/echo")).body.contains("session=abc"))
            assertTrue(http.get(fixture.url("/echo")).body.contains("second=def"))
            assertEquals("", http.get(fixture.url("/echo"), mapOf("Cookie" to "")).body)
            assertEquals("", http.get(fixture.url("/redirect-anonymous"), mapOf("Cookie" to "")).body)
            assertTrue(http.get(fixture.url("/echo"), mapOf("Cookie" to "session=new")).body.contains("session=new"))
            http.clearCookies()
            assertEquals("", http.get(fixture.url("/echo")).body)
        }
    }

    @Test fun redirectsToAnotherHostNeverForwardAccountOrShareCredentials() = runBlocking {
        Fixture().use { fixture ->
            fixture.route("/redirect") {
                it.responseHeaders.add("Location", fixture.url("/target", "127.0.0.1")); it.sendResponseHeaders(302, -1)
            }
            fixture.route("/target") { it.reply(listOf("Cookie", "Authorization", "X-Share-Token", "X-CToken")
                .mapNotNull(it.requestHeaders::getFirst).joinToString("|")) }
            val response = NetDiskHttp().get(fixture.url("/redirect"), mapOf("Cookie" to "session=private",
                "Authorization" to "Bearer private", "X-Share-Token" to "private", "X-CToken" to "private"))
            assertEquals("", response.body)
        }
    }

    @Test fun aCancelledScreenRequestCancelsTheUnderlyingCallPromptly() = runBlocking {
        Fixture().use { fixture ->
            fixture.route("/slow") { Thread.sleep(2_000); it.reply("{}") }
            val began = System.nanoTime()
            try { withTimeout(100) { NetDiskHttp().get(fixture.url("/slow")) }; fail("Expected cancellation") }
            catch (_: TimeoutCancellationException) { }
            assertTrue((System.nanoTime() - began) / 1_000_000 < 1_500)
        }
    }
}
