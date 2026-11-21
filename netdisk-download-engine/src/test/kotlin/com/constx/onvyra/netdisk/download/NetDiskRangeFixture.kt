package com.constx.onvyra.netdisk.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real loopback HTTP server; production engine tests never replace the downloading code. */
internal class NetDiskRangeFixture(initialPayload: ByteArray = payload(8 * 1024 * 1024)) : AutoCloseable {
    enum class Mode { RANGE, IGNORE_RANGE, WRONG_RANGE, TRUNCATED, UNAUTHORIZED, FORBIDDEN, RATE_LIMITED }
    data class Seen(val method: String, val range: String?, val ifRange: String?, val cookie: String?,
        val authorization: String?, val atNanos: Long)

    @Volatile var bytes: ByteArray = initialPayload
    @Volatile var etag: String = "\"fixture-a\""
    @Volatile var mode = Mode.RANGE
    @Volatile var delayPerBlockMs = 0L
    @Volatile var rateLimitResponses = 1
    @Volatile var redirectTo: String? = null
    @Volatile var headStatus = 200
    val requests = CopyOnWriteArrayList<Seen>()
    val maxActiveTransfers = AtomicInteger()
    val activeTransfers = AtomicInteger()
    val transferredBodyBytes = AtomicLong()
    val rateLimitsServed = AtomicInteger()
    private val executor = Executors.newCachedThreadPool { Thread(it, "netdisk-range-fixture").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@NetDiskRangeFixture.executor
        createContext("/file.bin") { exchange -> exchange.use { serve(it) } }
        start()
    }
    val url get() = "http://127.0.0.1:${server.address.port}/file.bin"
    fun urlWithHost(host: String) = "http://$host:${server.address.port}/file.bin"

    private fun serve(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")
        val ifRange = exchange.requestHeaders.getFirst("If-Range")
        requests += Seen(exchange.requestMethod, range, ifRange, exchange.requestHeaders.getFirst("Cookie"),
            exchange.requestHeaders.getFirst("Authorization"), System.nanoTime())
        val body = bytes
        val validator = etag
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("ETag", validator)
        exchange.responseHeaders.set("Content-Type", "application/octet-stream")
        redirectTo?.let { destination ->
            exchange.responseHeaders.set("Location", destination)
            exchange.sendResponseHeaders(302, -1)
            return
        }
        when (mode) {
            Mode.UNAUTHORIZED -> { exchange.sendResponseHeaders(401, -1); return }
            Mode.FORBIDDEN -> { exchange.sendResponseHeaders(403, -1); return }
            Mode.RATE_LIMITED -> if (rateLimitsServed.getAndIncrement() < rateLimitResponses) {
                exchange.responseHeaders.set("Retry-After", "1")
                exchange.sendResponseHeaders(429, -1)
                return
            }
            else -> Unit
        }
        if (exchange.requestMethod == "HEAD") {
            exchange.responseHeaders.set("Content-Length", body.size.toString())
            exchange.sendResponseHeaders(headStatus, -1)
            return
        }
        val parsed = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
        val start = parsed?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val requestedEnd = parsed?.groupValues?.get(2)?.toIntOrNull() ?: body.lastIndex
        val end = minOf(requestedEnd, body.lastIndex)
        val honorRange = parsed != null && mode != Mode.IGNORE_RANGE && (ifRange == null || ifRange == validator)
        if (honorRange && (start >= body.size || start > end)) {
            exchange.responseHeaders.set("Content-Range", "bytes */${body.size}")
            exchange.sendResponseHeaders(416, -1)
            return
        }
        val offset = if (honorRange) start else 0
        val count = if (honorRange) end - start + 1 else body.size
        if (honorRange) exchange.responseHeaders.set("Content-Range",
            "bytes ${if (mode == Mode.WRONG_RANGE) start + 1 else start}-$end/${body.size}")
        if (count == 0) {
            exchange.responseHeaders.set("Content-Length", "0")
            exchange.sendResponseHeaders(200, -1)
            return
        }
        exchange.sendResponseHeaders(if (honorRange) 206 else 200, count.toLong())
        val isTransfer = count > 1024
        if (isTransfer) {
            val active = activeTransfers.incrementAndGet()
            maxActiveTransfers.updateAndGet { previous -> maxOf(previous, active) }
        }
        try {
            val limit = if (mode == Mode.TRUNCATED && isTransfer) maxOf(1, count / 2) else count
            var sent = 0
            while (sent < limit) {
                val amount = minOf(16 * 1024, limit - sent)
                exchange.responseBody.write(body, offset + sent, amount)
                exchange.responseBody.flush()
                transferredBodyBytes.addAndGet(amount.toLong())
                sent += amount
                if (delayPerBlockMs > 0 && isTransfer) Thread.sleep(delayPerBlockMs)
            }
        } catch (_: IOException) {
            // Pausing/cancelling must close the real client connection.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally { if (isTransfer) activeTransfers.decrementAndGet() }
    }

    override fun close() { server.stop(0); executor.shutdownNow() }

    companion object {
        fun payload(size: Int, version: Int = 1): ByteArray = ByteArray(size) { index ->
            ((index * 17L + (index / 251) * 31L + version * 43L) % 251).toByte()
        }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
