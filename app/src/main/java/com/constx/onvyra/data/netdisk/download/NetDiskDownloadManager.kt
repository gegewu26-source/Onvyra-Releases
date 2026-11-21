package com.constx.onvyra.data.netdisk.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.constx.onvyra.data.netdisk.NetDiskDownload
import com.constx.onvyra.data.netdisk.NetDiskException
import com.constx.onvyra.netdisk.download.NetDiskEngineRequest
import com.constx.onvyra.netdisk.download.TurboNetDiskEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Application-owned download lifecycle; leaving a Compose page does not cancel downloads. */
class NetDiskDownloadManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = NetDiskDownloadStore(app)
    private val publisher = NetDiskDownloadPublisher(app)
    private val ready = CompletableDeferred<Unit>()
    private val lock = Any()
    private val storageMutex = Mutex()
    private val entries = LinkedHashMap<String, StoredNetDiskDownload>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val controls = ConcurrentHashMap<String, Mutex>()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val mutableTasks = MutableStateFlow<List<NetDiskDownloadTask>>(emptyList())
    val tasks: StateFlow<List<NetDiskDownloadTask>> = mutableTasks.asStateFlow()
    private val preferences = app.getSharedPreferences("netdisk-download-options", Context.MODE_PRIVATE)
    private val mutableConnections = MutableStateFlow(preferences.getInt("connections", 8).takeIf { it in CONNECTIONS } ?: 8)
    val connections: StateFlow<Int> = mutableConnections.asStateFlow()
    private val mutableWorking = MutableStateFlow(false)
    internal val working: StateFlow<Boolean> = mutableWorking.asStateFlow()
    private val mutableStorageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = mutableStorageError.asStateFlow()
    private val root = File(app.noBackupFilesDir, "netdisk-download-work")
    @Volatile private var serviceAttached = false
    private var serviceLease = 0L

    init {
        scope.launch {
            try {
                val restored = store.read()
                require(restored.size <= MAX_RECORDS && restored.map { it.task.id }.distinct().size == restored.size)
                for (record in restored) {
                    workDirectory(record.task.id) // Reject invalid persisted task paths before using them.
                    val checked = if (record.task.outputUri != null && record.task.status !in setOf(NetDiskDownloadStatus.SAVING, NetDiskDownloadStatus.COMPLETED))
                        publisher.reconcile(record.task.copy(status = NetDiskDownloadStatus.SAVING)) else publisher.reconcile(record.task)
                    val reconciled = if (checked.status == NetDiskDownloadStatus.COMPLETED || record.task.status in setOf(NetDiskDownloadStatus.SAVING, NetDiskDownloadStatus.COMPLETED)) checked
                        else record.task.copy(outputUri = checked.outputUri)
                    val task = reconciled.copy(status = if (reconciled.status in ACTIVE) NetDiskDownloadStatus.PAUSED
                        else reconciled.status, speedBytesPerSecond = 0L, activeConnections = 0)
                    synchronized(lock) { entries[task.id] = record.copy(task = task, generation = record.generation + 1) }
                }
                synchronized(lock) { publishLocked() }
                persistNow()
                ready.complete(Unit)
            } catch (_: Exception) {
                val failure = NetDiskException("download_store_unavailable", "下载记录无法读取，请保留应用数据后重试。")
                mutableStorageError.value = failure.message
                ready.completeExceptionally(failure)
            }
        }
        scope.launch {
            for (ignored in writes) {
                delay(250)
                try { ready.await(); persistNow() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableStorageError.value = "下载进度暂未保存，请检查手机存储空间。" }
            }
        }
    }

    fun setConnections(value: Int) {
        require(value in CONNECTIONS) { "请选择 1、4、8、16 或 32 个连接。" }
        preferences.edit().putInt("connections", value).apply()
        mutableConnections.value = value
    }

    suspend fun enqueue(link: NetDiskDownload, expectedDestinationKey: String? = null): String {
        ready.await()
        val destination = NetDiskDestinationStore.get(app).current.value
        if (expectedDestinationKey != null &&
            expectedDestinationKey != (destination.treeUri ?: "default_download_directory"))
            throw NetDiskException("directory_changed", "下载目录已变更，请重新提交下载。")
        withContext(Dispatchers.IO) { NetDiskDestinationStore.get(app).ensureAccess(destination.treeUri) }
        val parsed = link.url.toHttpUrlOrNull()
        require(parsed != null && parsed.username.isBlank() && parsed.password.isBlank()) { "下载地址无效，请重新解析。" }
        require(link.headers.size <= 64 && link.headers.all { (key, value) -> key.isNotBlank() && key.none(Char::isISOControl) &&
            value.length <= 65_536 && value.none { it == '\r' || it == '\n' || it == '\u0000' } }) { "下载请求头无效。" }
        if (destination.treeUri == null && Build.VERSION.SDK_INT <= 28 && app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            throw NetDiskException("storage_permission", "请先允许保存文件到下载目录。")
        val name = netDiskPublishedFileName(link.fileName, "display").removePrefix("display_")
        var createdNew = false
        val id = synchronized(lock) {
            entries.values.firstOrNull { it.url == link.url && it.task.fileName == name && it.task.status in ACTIVE &&
                it.task.destinationTreeUri == destination.treeUri }?.task?.id
                ?: run {
                    if (entries.size >= MAX_RECORDS) throw NetDiskException("download_history_full", "下载记录已达上限，请移除已结束的任务记录。")
                    val key = UUID.randomUUID().toString()
                    entries[key] = StoredNetDiskDownload(NetDiskDownloadTask(key, name, link.mimeType,
                        connectionLimit = mutableConnections.value, destinationTreeUri = destination.treeUri,
                        destinationLabel = destination.label), link.url, link.headers.toMap())
                    createdNew = true
                    publishLocked()
                    key
                }
        }
        if (!createdNew && serviceAttached && jobs.containsKey(id)) return id
        try { persistNow() }
        catch (_: Exception) {
            synchronized(lock) { if (createdNew) entries.remove(id); publishLocked() }
            throw NetDiskException("download_store_failed", "无法保存下载任务，请检查手机存储空间。")
        }
        if (!NetDiskDownloadService.start(app)) {
            mutate(id) { it.copy(task = it.task.copy(status = NetDiskDownloadStatus.FAILED,
                errorMessage = "系统未允许启动后台下载，请保持应用在前台后重试。")) }
            persistNow()
            throw NetDiskException("download_service_unavailable", "系统未允许启动后台下载，请保持应用在前台后重试。")
        }
        return id
    }

    fun pause(id: String) = control(id) {
        val record = synchronized(lock) { entries[id] } ?: return@control
        if (record.task.status !in ACTIVE) return@control
        mutate(id) { it.copy(generation = it.generation + 1, task = it.task.copy(status = NetDiskDownloadStatus.PAUSED,
            speedBytesPerSecond = 0, activeConnections = 0)) }
        jobs[id]?.cancelAndJoin()
        reconcilePublication(id)
        persistNow()
        pump()
    }

    fun resume(id: String) = control(id) {
        jobs[id]?.cancelAndJoin()
        if (reconcilePublication(id)) { persistNow(); return@control }
        val record = synchronized(lock) { entries[id] } ?: return@control
        if (record.task.status !in setOf(NetDiskDownloadStatus.PAUSED, NetDiskDownloadStatus.FAILED)) return@control
        mutate(id) { it.copy(generation = it.generation + 1, task = it.task.copy(status = NetDiskDownloadStatus.QUEUED,
            errorMessage = null, speedBytesPerSecond = 0, activeConnections = 0, connectionLimit = mutableConnections.value)) }
        persistNow()
        if (!NetDiskDownloadService.start(app)) mutate(id) { it.copy(task = it.task.copy(status = NetDiskDownloadStatus.FAILED,
            errorMessage = "系统未允许启动后台下载，请在应用前台重试。")) }
    }

    fun cancel(id: String) = control(id) {
        val record = synchronized(lock) { entries[id] } ?: return@control
        if (record.task.status == NetDiskDownloadStatus.COMPLETED) return@control
        mutate(id) { it.copy(generation = it.generation + 1, task = it.task.copy(status = NetDiskDownloadStatus.CANCELLED,
            speedBytesPerSecond = 0, activeConnections = 0, errorMessage = null)) }
        jobs[id]?.cancelAndJoin()
        if (reconcilePublication(id)) { deleteWork(id); persistNow(); pump(); return@control }
        publisher.discardPending(record.task)
        deleteWork(id)
        persistNow()
        pump()
    }

    fun remove(id: String) = control(id) {
        val record = synchronized(lock) { entries[id] } ?: return@control
        if (record.task.status in ACTIVE) return@control
        jobs[id]?.cancelAndJoin()
        publisher.discardPending(record.task)
        deleteWork(id)
        synchronized(lock) { entries.remove(id); publishLocked() }
        persistNow()
    }

    fun open(id: String) {
        val task = mutableTasks.value.firstOrNull { it.id == id && it.status == NetDiskDownloadStatus.COMPLETED }
            ?: throw NetDiskException("download_not_complete", "文件尚未下载完成。")
        val uri = task.outputUri?.let(Uri::parse) ?: throw NetDiskException("download_file_missing", "文件已不在原位置。")
        val type = if (task.mimeType == "application/octet-stream") android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(task.fileName.substringAfterLast('.', "").lowercase()) ?: task.mimeType else task.mimeType
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    internal fun serviceStarting(): Long = synchronized(lock) { ++serviceLease }

    internal fun serviceStarted(lease: Long) {
        synchronized(lock) { if (lease != serviceLease) return; serviceAttached = true }
        scope.launch { try { ready.await(); pump() } catch (_: Exception) { mutableWorking.value = false } }
    }

    internal fun serviceStopped(lease: Long, message: String? = null) {
        synchronized(lock) {
            if (lease != serviceLease) return
            serviceAttached = false
        }
        scope.launch {
            try {
                ready.await()
                val stoppingJobs = synchronized(lock) {
                    if (lease != serviceLease || serviceAttached) return@launch
                    entries.replaceAll { _, record -> if (record.task.status in ACTIVE) record.copy(generation = record.generation + 1,
                        task = record.task.copy(status = NetDiskDownloadStatus.PAUSED, speedBytesPerSecond = 0,
                            activeConnections = 0, errorMessage = message)) else record }
                    publishLocked()
                    jobs.values.toList()
                }
                stoppingJobs.forEach { it.cancel() }
                stoppingJobs.joinAll()
                persistNow()
            } catch (_: Exception) { }
        }
    }

    /** Claim retirement atomically with the queue. A later enqueue belongs to the next service start. */
    internal fun retireIdleService(lease: Long): Boolean = synchronized(lock) {
        if (lease != serviceLease || jobs.isNotEmpty() || entries.values.any { it.task.status == NetDiskDownloadStatus.QUEUED }) false
        else { serviceAttached = false; true }
    }

    internal fun serviceDetached(lease: Long) = synchronized(lock) {
        if (lease == serviceLease) serviceAttached = false
    }

    internal fun pauseAll() { mutableTasks.value.filter { it.status in ACTIVE }.forEach { pause(it.id) } }

    /** A pause/cancel arriving just after atomic publication cannot hide an already saved file. */
    private suspend fun reconcilePublication(id: String): Boolean {
        val task = synchronized(lock) { entries[id]?.task } ?: return false
        if (task.outputUri == null) return false
        val checked = publisher.reconcile(task.copy(status = NetDiskDownloadStatus.SAVING))
        mutate(id) { it.copy(task = if (checked.status == NetDiskDownloadStatus.COMPLETED) checked
            else it.task.copy(outputUri = checked.outputUri)) }
        return checked.status == NetDiskDownloadStatus.COMPLETED
    }

    private fun control(id: String, action: suspend () -> Unit) {
        scope.launch {
            try { ready.await(); controls.getOrPut(id) { Mutex() }.withLock { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutate(id) { it.copy(task = it.task.copy(errorMessage = safeDownloadError(error))) } }
        }
    }

    private fun pump() {
        synchronized(lock) {
            if (!serviceAttached) return
            for (entry in entries.values.toList()) {
                if (jobs.size >= 2) break
                if (entry.task.status != NetDiskDownloadStatus.QUEUED || jobs.containsKey(entry.task.id)) continue
                val next = entry.copy(generation = entry.generation + 1, task = entry.task.copy(status = NetDiskDownloadStatus.PROBING))
                entries[entry.task.id] = next
                val job = scope.launch(start = CoroutineStart.LAZY) { run(next) }
                jobs[entry.task.id] = job
                job.start()
            }
            publishLocked()
        }
        writes.trySend(Unit)
    }

    private suspend fun run(record: StoredNetDiskDownload) {
        val id = record.task.id
        val job = currentCoroutineContext().job
        fun update(transform: (NetDiskDownloadTask) -> NetDiskDownloadTask) = mutate(id) {
            if (it.generation != record.generation || it.task.status !in ACTIVE) it else it.copy(task = transform(it.task))
        }
        try {
            val directory = workDirectory(id)
            val completed = TurboNetDiskEngine().download(NetDiskEngineRequest(id, record.url, record.headers,
                directory, record.task.fileName, record.task.connectionLimit)) { progress ->
                update { it.copy(status = NetDiskDownloadStatus.DOWNLOADING, downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes, speedBytesPerSecond = progress.bytesPerSecond,
                    activeConnections = progress.activeConnections, mode = progress.mode) }
            }
            currentCoroutineContext().ensureActive()
            check(completed.canonicalPath.startsWith(directory.canonicalPath + File.separator) && completed.isFile)
            update { it.copy(status = NetDiskDownloadStatus.SAVING, downloadedBytes = completed.length(), totalBytes = completed.length(),
                speedBytesPerSecond = 0, activeConnections = 0) }
            persistNow()
            val task = synchronized(lock) { entries.getValue(id).task }
            val uri = publisher.publish(task, completed) { allocated, pending ->
                update { it.copy(outputUri = allocated, publicationPending = pending) }; persistNow()
            }
            update { it.copy(status = NetDiskDownloadStatus.COMPLETED, outputUri = uri, errorMessage = null, publicationPending = false,
                speedBytesPerSecond = 0, activeConnections = 0) }
            persistNow()
            deleteWork(id)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { update { it.copy(status = NetDiskDownloadStatus.PAUSED,
                speedBytesPerSecond = 0, activeConnections = 0) }; persistBestEffort() }
            throw cancelled
        } catch (error: Exception) {
            update { it.copy(status = NetDiskDownloadStatus.FAILED, errorMessage = safeDownloadError(error),
                speedBytesPerSecond = 0, activeConnections = 0) }
            persistBestEffort()
        } finally {
            jobs.remove(id, job)
            synchronized(lock) { publishLocked() }
            pump()
        }
    }

    private fun mutate(id: String, transform: (StoredNetDiskDownload) -> StoredNetDiskDownload) {
        synchronized(lock) { val old = entries[id] ?: return; entries[id] = transform(old); publishLocked() }
        writes.trySend(Unit)
    }

    private fun publishLocked() {
        mutableTasks.value = entries.values.map { it.task }.sortedByDescending { it.createdAt }
        mutableWorking.value = jobs.isNotEmpty() || entries.values.any { it.task.status == NetDiskDownloadStatus.QUEUED }
    }

    private suspend fun persistNow() = storageMutex.withLock {
        store.write(synchronized(lock) { entries.values.toList() })
        mutableStorageError.value = null
    }

    private suspend fun persistBestEffort() {
        try { persistNow() }
        catch (_: Exception) { mutableStorageError.value = "下载进度暂未保存，请检查手机存储空间。" }
    }

    private fun workDirectory(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "下载任务编号无效" }
        val destination = File(root, id).canonicalFile
        check(destination.parentFile == root.canonicalFile)
        return destination
    }

    private fun deleteWork(id: String) { workDirectory(id).takeIf(File::exists)?.deleteRecursively() }

    companion object {
        val CONNECTIONS = listOf(1, 4, 8, 16, 32)
        private const val MAX_RECORDS = 200
        internal val ACTIVE = setOf(NetDiskDownloadStatus.QUEUED, NetDiskDownloadStatus.PROBING,
            NetDiskDownloadStatus.DOWNLOADING, NetDiskDownloadStatus.SAVING)
        @Volatile private var instance: NetDiskDownloadManager? = null
        fun get(context: Context): NetDiskDownloadManager = instance ?: synchronized(this) {
            instance ?: NetDiskDownloadManager(context).also { instance = it }
        }
    }
}

internal fun safeDownloadError(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        Regex("(?<![0-9])(401|403|410)(?![0-9])").containsMatchIn(message) -> "网盘下载地址或登录信息已失效，请重新解析文件获得新链接。"
        message.contains("resume", true) || message.contains("validator", true) || message.contains("etag", true) ->
            "资源或续传校验信息已变化，为避免损坏已停止下载。请移除本任务后重新解析下载。"
        message.contains("429") || message.contains("503") -> "网盘暂时限流，已保留断点，请稍后继续。"
        else -> message.replace(Regex("https?://[^\\s]+"), "[下载地址]")
            .replace(Regex("(?i)(Bearer\\s+|(?:Cookie|Authorization)[=: ]+)[^\\r\\n]+"), "[登录信息]")
            .take(240).ifBlank { "下载失败，已保留可用断点，请稍后重试。" }
    }
}
