package com.constx.onvyra.data.netdisk

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NetDiskCredentialEpochTest {
    @Test fun clearingCredentialsCannotBeUndoneByALateResponseFromTheOldAccount() = runBlocking {
        withTimeout(10_000) {
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = executor
            server.createContext("/old-account") { exchange ->
                exchange.use {
                    received.countDown()
                    if (release.await(5, TimeUnit.SECONDS)) {
                        it.responseHeaders.add("Set-Cookie", "__puus=old-account; Path=/")
                        val body = "{}".toByteArray()
                        it.sendResponseHeaders(200, body.size.toLong())
                        it.responseBody.write(body)
                    }
                }
            }
            server.createContext("/echo") { exchange -> exchange.use {
                val body = it.requestHeaders.getFirst("Cookie").orEmpty().toByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            } }
            server.start()
            try {
                val origin = "http://localhost:${server.address.port}"
                val http = NetDiskHttp()
                val pending = async(Dispatchers.Default) {
                    http.get("$origin/old-account", mapOf("Cookie" to "__puus=old-account"))
                }
                assertTrue("The old account request must be in flight", received.await(3, TimeUnit.SECONDS))
                http.clearCookies()
                release.countDown()
                assertEquals(200, pending.await().status)
                assertEquals("An old response must not restore a cleared account cookie", "", http.cookieHeader("$origin/echo"))
                assertEquals("__pugs=guest", http.get("$origin/echo", mapOf("Cookie" to "__pugs=guest")).body)
            } finally {
                release.countDown()
                server.stop(0)
                executor.shutdownNow()
            }
        }
    }
}
