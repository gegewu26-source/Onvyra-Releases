package com.constx.onvyra.data.netdisk

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetDiskCookieScopesTest {
    @Test fun `account change stops follow up requests of an old multi request operation`() = runBlocking {
        val reachedFirstResponse = CompletableDeferred<Unit>()
        val continueOldOperation = CompletableDeferred<Unit>()
        var secondRequestSent = false
        var saved = "account-A"
        val scopes = NetDiskCookieScopes()
        val oldOperation = async(Dispatchers.IO) {
            try {
                scopes.withCredentials({ saved }) { credential ->
                    assertEquals("account-A", credential)
                    reachedFirstResponse.complete(Unit)
                    continueOldOperation.await()
                    secondRequestSent = true
                    error("The old provider operation must stop before its next request")
                }
            } catch (error: NetDiskException) {
                error.reason
            }
        }
        reachedFirstResponse.await()
        scopes.updateCredentials { saved = "account-B" }
        continueOldOperation.complete(Unit)
        assertEquals("invalid_credential", oldOperation.await())
        assertFalse(secondRequestSent)
        assertEquals("account-B", scopes.withCredentials({ saved }) { it })
    }

    @Test fun `changing page credentials invalidates chat cookies and late old account responses`() = runBlocking {
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool { Thread(it, "netdisk-cookie-scope-test").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/set") { exchange -> exchange.use {
                it.responseHeaders.add("Set-Cookie", "account=old; Path=/")
                it.sendResponseHeaders(200, -1)
            } }
            createContext("/late") { exchange -> exchange.use {
                waiting.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                it.responseHeaders.add("Set-Cookie", "late_old_account=retired; Path=/")
                it.sendResponseHeaders(200, -1)
            } }
            createContext("/echo") { exchange -> exchange.use {
                val bytes = it.requestHeaders.getFirst("Cookie").orEmpty().toByteArray()
                if (bytes.isEmpty()) it.sendResponseHeaders(200, -1)
                else { it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.write(bytes) }
            } }
            start()
        }
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val chat = NetDiskHttp()
            val page = NetDiskHttp()
            val scopes = NetDiskCookieScopes()
            scopes.register(listOf(chat))
            scopes.register(listOf(page))
            chat.get("$origin/set")
            page.get("$origin/set")
            assertTrue(chat.get("$origin/echo").body.contains("account=old"))
            assertTrue(page.get("$origin/echo").body.contains("account=old"))
            val late = async(Dispatchers.IO) { chat.get("$origin/late") }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            var credentialsChanged = false
            scopes.updateCredentials { credentialsChanged = true }
            assertTrue(credentialsChanged)
            release.countDown()
            late.await()
            assertEquals("", chat.get("$origin/echo").body)
            assertEquals("", page.get("$origin/echo").body)
            chat.get("$origin/set")
            assertTrue(chat.get("$origin/echo").body.contains("account=old"))
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
