package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.netdisk.NetDiskChatCoordinator
import com.constx.onvyra.data.netdisk.NetDiskHttp
import com.constx.onvyra.data.netdisk.NetDiskLinkParser
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadStatus
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadTask
import com.constx.onvyra.data.netdisk.providers.Pan123NetDiskProvider
import com.constx.onvyra.netdisk.download.NetDiskEngineRequest
import com.constx.onvyra.netdisk.download.TurboNetDiskEngine
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Protocol replies are fixtures; the Tool, coordinator, 123 parser and TurboDL bytes path are real. */
class NetDiskChatDownloadIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `chat download reaches the real provider and engine and only completion reports saved bytes`() = runBlocking {
        FileFixture().use { fixture ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val protocolCalls = CopyOnWriteArrayList<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    protocolCalls += request.url.encodedPath
                    val json = when (request.url.encodedPath) {
                        "/b/api/share/get" -> {
                            assertEquals("chat-fixture", request.url.queryParameter("shareKey"))
                            assertEquals("a1b2", request.url.queryParameter("SharePwd"))
                            """{"code":0,"data":{"ShareName":"聊天下载测试","Next":"-1","InfoList":[{"FileId":"42","FileName":"chat-fixture.bin","Size":${fixture.bytes.size},"Type":0,"S3KeyFlag":"node","Etag":"fixture"}]}}"""
                        }
                        "/b/api/share/download/info" ->
                            """{"code":0,"data":{"DownloadURL":"${fixture.url}?signature=fixture-private"}}"""
                        else -> error("Unexpected provider route: ${request.url.encodedPath}")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(json.toResponseBody()).build()
                }.build()
                val provider = Pan123NetDiskProvider(NetDiskHttp(client))
                val tasks = ConcurrentHashMap<String, NetDiskDownloadTask>()
                val saved = ConcurrentHashMap<String, File>()
                val failures = ConcurrentHashMap<String, Throwable>()
                val submissions = AtomicInteger()
                val engine = TurboNetDiskEngine()
                val coordinator = NetDiskChatCoordinator(
                    open = { text, password -> provider.open(NetDiskLinkParser.parse(text, password), "") },
                    list = { session, directory, cursor -> provider.list(session, directory, cursor, "") },
                    resolve = { session, file -> provider.resolve(session, file, "") },
                    enqueue = { link ->
                        submissions.incrementAndGet()
                        val id = UUID.randomUUID().toString()
                        tasks[id] = NetDiskDownloadTask(id, link.fileName, status = NetDiskDownloadStatus.QUEUED,
                            destinationLabel = "已选择的测试目录")
                        scope.launch {
                            try {
                                val result = engine.download(NetDiskEngineRequest(id, link.url, link.headers,
                                    File(temporary.root, id), link.fileName, connections = 1)) { progress ->
                                    tasks.computeIfPresent(id) { _, previous -> previous.copy(
                                        status = NetDiskDownloadStatus.DOWNLOADING,
                                        downloadedBytes = progress.downloadedBytes, totalBytes = progress.totalBytes) }
                                }
                                saved[id] = result
                                tasks.computeIfPresent(id) { _, previous -> previous.copy(
                                    status = NetDiskDownloadStatus.COMPLETED, downloadedBytes = result.length(),
                                    totalBytes = result.length()) }
                            } catch (error: Throwable) {
                                failures[id] = error
                                tasks.computeIfPresent(id) { _, previous -> previous.copy(status = NetDiskDownloadStatus.FAILED) }
                            }
                        }
                        id
                    },
                    tasks = { tasks.values.toList() },
                    destinationLabel = { "已选择的测试目录" },
                )
                val tools = createNetDiskTools(coordinator, "conversation-fixture").associateBy { it.name }
                val submitted = tools.getValue("netdisk_download").payload(buildJsonObject {
                    put("share_text", "请下载 https://www.123pan.com/s/chat-fixture 提取码：a1b2")
                })
                assertEquals("submitted", submitted.getValue("status").jsonPrimitive.content)
                assertEquals(1, submissions.get())
                assertTrue(protocolCalls.contains("/b/api/share/get"))
                assertTrue(protocolCalls.contains("/b/api/share/download/info"))
                assertFalse(submitted.toString().contains("fixture-private"))
                assertFalse(submitted.toString().contains("DownloadURL"))
                val id = submitted.getValue("tasks").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
                assertEquals("已选择的测试目录", tasks.getValue(id).destinationLabel)

                withTimeout(20_000) {
                    while (tasks[id]?.status !in setOf(NetDiskDownloadStatus.COMPLETED, NetDiskDownloadStatus.FAILED)) delay(20)
                }
                failures[id]?.let { throw AssertionError("Real download failed", it) }
                assertEquals(NetDiskDownloadStatus.COMPLETED, tasks.getValue(id).status)
                val result = saved.getValue(id)
                assertEquals(fixture.bytes.size.toLong(), result.length())
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(fixture.bytes),
                    MessageDigest.getInstance("SHA-256").digest(result.readBytes()))
                assertTrue(fixture.gets.get() > 0)
                val status = tools.getValue("netdisk_download_status").payload(buildJsonObject { })
                val completed = status.getValue("tasks").jsonArray.single().jsonObject
                assertEquals("completed", completed.getValue("status").jsonPrimitive.content)
                assertFalse(status.toString().contains("fixture-private"))
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun Tool.payload(args: JsonObject): JsonObject =
        Json.parseToJsonElement(execute(args).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }).jsonObject

    private class FileFixture : AutoCloseable {
        val bytes = ByteArray(32 * 1024) { ((it * 31L + 17) % 251).toByte() }
        val gets = AtomicInteger()
        private val executor = Executors.newCachedThreadPool { Thread(it, "chat-download-fixture").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@FileFixture.executor
            createContext("/payload.bin") { exchange -> exchange.use { serve(it) } }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/payload.bin"

        private fun serve(exchange: HttpExchange) {
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.responseHeaders.set("ETag", "\"chat-fixture\"")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.set("Content-Length", bytes.size.toString())
                exchange.sendResponseHeaders(200, -1)
                return
            }
            gets.incrementAndGet()
            val range = exchange.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val end = minOf(range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex, bytes.lastIndex)
            if (start !in bytes.indices || end < start) {
                exchange.responseHeaders.set("Content-Range", "bytes */${bytes.size}")
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val count = end - start + 1
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${bytes.size}")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, count.toLong())
            exchange.responseBody.write(bytes, start, count)
        }

        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}
