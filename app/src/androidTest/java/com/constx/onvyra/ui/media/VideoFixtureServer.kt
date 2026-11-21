package com.constx.onvyra.ui.media

import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Device-local HTTP fixture: no public platform, API account, or user data is needed. */
internal class VideoFixtureServer(videoAsset: String = "inline-video-test.mp4") : Closeable {
    val pageRequests = AtomicInteger()
    val slowPageRequests = AtomicInteger()
    private val socket = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool()
    private val video = InstrumentationRegistry.getInstrumentation().context.assets
        .open(videoAsset).use { it.readBytes() }
    private val audio = ByteBuffer.allocate(44 + 6 * 8_000 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(capacity() - 44)
        repeat(6 * 8_000) { putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 8_000) * 1000).toInt().toShort()) }
    }.array()
    val url: String = "http://127.0.0.1:${socket.localPort}"

    init {
        workers.execute {
            while (!socket.isClosed) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { runCatching { connection.use(::respond) } }
            }
        }
    }

    private fun respond(connection: Socket) {
        connection.soTimeout = 10_000
        val reader = connection.getInputStream().bufferedReader()
        val request = reader.readLine()?.split(' ') ?: return
        val path = request.getOrNull(1)?.substringBefore('?') ?: return
        if (path == "/slow") {
            slowPageRequests.incrementAndGet()
            Thread.sleep(30_000)
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val type: String
        val body: ByteArray
        when (path) {
            "/clip.mp4", "/clip-alt.mp4" -> { type = "video/mp4"; body = video }
            "/audio.wav" -> { type = "audio/wav"; body = audio }
            "/frame" -> {
                type = "text/html; charset=utf-8"
                body = """<!doctype html><html><body style="margin:0;background:#111">
                  <video id="clip" style="width:100%" controls loop preload="auto" src="$url/clip.mp4"></video>
                  <script>
                    const clip = document.getElementById('clip');
                    clip.addEventListener('ratechange', () => parent.postMessage({fixtureRate:clip.playbackRate}, '*'));
                    window.fixtureLoaded = true;
                  </script></body></html>""".toByteArray()
            }
            "/embedded" -> {
                type = "text/html; charset=utf-8"
                // localhost and 127.0.0.1 are different origins, exercising frame injection.
                val childUrl = url.replace("127.0.0.1", "localhost")
                body = """<!doctype html><html><body style="margin:0;background:#111">
                  <iframe style="border:0;width:100%;height:220px" src="$childUrl/frame" allow="fullscreen"></iframe>
                  <script>window.fixtureCounter=17;window.fixtureRate=1;
                    addEventListener('message', e => { if (e.data && e.data.fixtureRate) window.fixtureRate=e.data.fixtureRate; });
                  </script></body></html>""".toByteArray()
            }
            "/page" -> {
                pageRequests.incrementAndGet()
                type = "text/html; charset=utf-8"
                body = """<!doctype html><html><head><title>Device-local video</title></head>
                  <body><video controls src="$url/clip.mp4"></video></body></html>""".toByteArray()
            }
            else -> {
                connection.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length:0\r\nConnection:close\r\n\r\n".toByteArray())
                return
            }
        }
        val range = headers["range"]?.removePrefix("bytes=")?.substringBefore(',')
        val from = range?.substringBefore('-')?.toIntOrNull()?.coerceIn(0, body.lastIndex) ?: 0
        val through = range?.substringAfter('-')?.toIntOrNull()?.coerceIn(from, body.lastIndex) ?: body.lastIndex
        val out = connection.getOutputStream()
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val fields = buildString {
            append("HTTP/1.1 $status\r\nContent-Type:$type\r\nAccept-Ranges:bytes\r\n")
            if (range != null) append("Content-Range:bytes $from-$through/${body.size}\r\n")
            append("Content-Length:${through - from + 1}\r\nConnection:close\r\n\r\n")
        }
        out.write(fields.toByteArray())
        if (request.first() != "HEAD") out.write(body, from, through - from + 1)
        out.flush()
    }

    override fun close() {
        socket.close()
        workers.shutdownNow()
    }
}
