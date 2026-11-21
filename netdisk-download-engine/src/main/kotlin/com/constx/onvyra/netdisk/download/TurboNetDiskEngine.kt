package com.constx.onvyra.netdisk.download

import dev.turbodl.core.AdaptiveConcurrencyMode
import dev.turbodl.core.DownloadRequest
import dev.turbodl.core.TaskState
import dev.turbodl.core.TransferPhase
import dev.turbodl.core.TurboClient
import dev.turbodl.core.TurboConfig
import dev.turbodl.core.TurboEvent
import dev.turbodl.core.UnverifiableResumePolicy
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class NetDiskEngineRequest(
    val id: String,
    val url: String,
    val headers: Map<String, String>,
    val workDirectory: File,
    val fileName: String,
    val connections: Int = 8,
    val knownSize: Long = -1L,
    val expectedSha256: String? = null,
)

data class NetDiskEngineProgress(
    val downloadedBytes: Long,
    val totalBytes: Long = -1L,
    val bytesPerSecond: Long = 0L,
    val activeConnections: Int = 0,
    val mode: String = "",
    val verifying: Boolean = false,
)

/** Real TurboDL core host adapter; its files and sidecars stay under the caller's private task directory. */
class TurboNetDiskEngine {
    suspend fun download(request: NetDiskEngineRequest,
        onProgress: (NetDiskEngineProgress) -> Unit): File = withContext(Dispatchers.IO) {
        require(request.id.isNotBlank() && request.id.length <= 256) { "下载任务编号无效" }
        val parsed = request.url.toHttpUrlOrNull()
        require(parsed != null && parsed.username.isBlank() && parsed.password.isBlank()) { "下载地址无效" }
        require(request.headers.all { (key, value) ->
            key.isNotBlank() && key.none(Char::isISOControl) && value.none { it == '\r' || it == '\n' || it == '\u0000' }
        }) { "下载请求头无效" }
        val directory = request.workDirectory.canonicalFile
        check((directory.isDirectory || directory.mkdirs()) && directory.isDirectory) { "无法创建下载工作目录" }
        // A fixed internal basename prevents an untrusted cloud filename from selecting a filesystem path.
        val destination = File(directory, "payload.part")
        val checkpoints = File(directory, ".turbodl")
        val connections = request.connections.coerceIn(1, 32)
        val client = TurboClient(TurboConfig(
            maxConnectionsPerTask = connections,
            maxConcurrentTasks = 1,
            maxTotalConnections = connections,
            maxConnectionsPerHost = connections,
            maxRetries = 3,
            minSegmentSize = 256L * 1024,
            maxSegmentsPerTask = 512,
            adaptiveConcurrency = true,
            adaptiveConcurrencyMode = AdaptiveConcurrencyMode.DOWNWARD,
            slowStart = false,
            skipProbeWhenSizeKnown = false,
            trustWeakValidator = false,
            warmUpConnections = false,
            workDir = checkpoints,
            ioBufferTotalBudgetBytes = 16 * 1024 * 1024,
            maxIdleConnections = connections,
            progressIntervalMs = 150,
        ))
        var taskId = 0L
        try {
            coroutineScope {
                val mode = AtomicReference("PROBING")
                val metadata = launch(start = CoroutineStart.UNDISPATCHED) {
                    // This client is private to one invocation: no task filtering race with submit().
                    client.events.collect { event ->
                        if (event is TurboEvent.Metadata) mode.set(if (event.supportsRange && event.totalBytes > 0 && connections > 1)
                            "RANGE" else "SINGLE_STREAM")
                    }
                }
                val progress = launch(start = CoroutineStart.UNDISPATCHED) {
                    client.progress.collect { entries ->
                        entries.values.firstOrNull()?.let { value ->
                            val details = client.taskDetails(value.taskId)
                            onProgress(NetDiskEngineProgress(
                                value.downloadedBytes, value.totalBytes, value.speedBytesPerSec,
                                details?.activeRequests ?: value.activeConnections,
                                mode.get(), details?.phase == TransferPhase.VERIFYING || value.state == TaskState.MERGING,
                            ))
                        }
                    }
                }
                val completed = try {
                    taskId = client.submit(DownloadRequest(
                        url = request.url, destination = destination, headers = request.headers.toMap(),
                        knownSize = request.knownSize, expectedSha256 = request.expectedSha256,
                        connectionsOverride = connections, stableKey = request.id,
                    ), UnverifiableResumePolicy.FAIL, checkpoints)
                    val completed = client.await(taskId).getOrThrow()
                    client.awaitSettled(taskId)
                    check(client.progress.value[taskId]?.state == TaskState.COMPLETED && completed.isFile &&
                        completed.canonicalFile == destination.canonicalFile) { "下载尚未完整完成" }
                    if (request.knownSize >= 0L) check(completed.length() == request.knownSize) { "下载文件大小与分享信息不一致" }
                    completed
                } finally {
                    withContext(NonCancellable) { metadata.cancelAndJoin(); progress.cancelAndJoin() }
                }
                // Finish observers before the final callback so no queued old
                // snapshot can overwrite completion with a lower byte count.
                onProgress(NetDiskEngineProgress(completed.length(), completed.length(), 0L, 0, mode.get(), false))
                completed
            }
        } catch (cancelled: CancellationException) {
            // Cancellation is pause, never cancel/delete: preserve a flushed checkpoint for the next invocation.
            withContext(NonCancellable + Dispatchers.IO) {
                if (taskId > 0L) { client.pause(taskId); client.awaitSettled(taskId) }
            }
            throw cancelled
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (taskId > 0L) client.awaitSettled(taskId)
                client.shutdown()
            }
        }
    }
}
