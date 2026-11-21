package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.download.NetDiskDownloadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

/** Chat owns opaque references; provider sessions, account material and signed URLs never leave this service. */
class NetDiskChatCoordinator(
    private val open: suspend (String, String) -> NetDiskSession,
    private val list: suspend (NetDiskSession, String, String?) -> NetDiskListing,
    private val resolve: suspend (NetDiskSession, NetDiskFile) -> NetDiskDownload,
    private val enqueue: suspend (NetDiskDownload) -> String,
    private val tasks: () -> List<NetDiskDownloadTask>,
    private val destinationLabel: () -> String,
    private val destinationKey: () -> String = destinationLabel,
    private val enqueueWithDestination: (suspend (NetDiskDownload, String) -> String)? = null,
) {
    private data class Entry(val file: NetDiskFile, val path: String)
    private data class Page(val directory: String, val raw: String)
    private class Session(val id: String, val owner: String, val value: NetDiskSession) {
        val mutex = Mutex()
        val entries = LinkedHashMap<String, Entry>()
        val entryKeys = HashMap<Pair<String, Boolean>, String>()
        val pages = HashMap<String, Page>()
        val pageKeys = HashMap<Pair<String, String>, String>()
    }
    private data class Source(val owner: String, val platform: NetDiskPlatform, val share: String,
        val file: String, val destination: String)
    private data class Directory(val id: String, val path: String, val depth: Int)
    private data class Failure(val code: String, val message: String, val path: String? = null)

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>(16, 0.75f, true)
    private val owners = LinkedHashMap<String, String>()
    private val sources = LinkedHashMap<Source, String>()
    // Different sessions may refer to the same share. Serialize their deduplication and enqueue boundary.
    private val downloadMutex = Mutex()

    suspend fun parse(owner: String, shareText: String? = null, password: String = "",
        sessionId: String? = null, directoryId: String = "", cursor: String? = null): JsonObject =
        safe("parse") {
            val session = session(owner, shareText, password, sessionId)
            session.mutex.withLock {
                val directory = directory(session, directoryId)
                val page = cursor?.let { session.pages[it]?.takeIf { value -> value.directory == directoryId }
                    ?: throw NetDiskException("invalid_cursor", "目录分页引用已失效，请重新读取目录。") }
                val listing = list(session.value, directory.first, page?.raw)
                val files = listing.files.map { entry(session, it, childPath(directory.second, it.name)) }
                val next = listing.nextCursor?.takeIf { it.isNotBlank() }?.let { raw ->
                    if (raw == page?.raw) throw NetDiskException("pagination_cycle", "网盘返回了重复分页，已停止读取。")
                    pageHandle(session, directoryId, raw)
                }
                result("parse", "ready") {
                    put("session_id", session.id); put("platform", session.value.share.platform.name.lowercase())
                    put("platform_name", session.value.share.platform.label)
                    put("title", safeText(listing.title ?: session.value.title)); put("directory_id", directoryId)
                    put("files", JsonArray(files.map { (id, item) -> fileJson(id, item) }))
                    if (next != null) put("next_cursor", next)
                }
            }
        }

    suspend fun download(owner: String, shareText: String? = null, password: String = "",
        sessionId: String? = null, fileIds: List<String>? = null, allowTransfer: Boolean = false): JsonObject =
        safe("download") {
            if (fileIds != null && (fileIds.isEmpty() || fileIds.size > MAX_FILES))
                throw NetDiskException("invalid_selection", "请选择 1 至 200 个已解析的文件或文件夹。")
            val session = session(owner, shareText, password, sessionId)
            session.mutex.withLock {
                val failures = ArrayList<Failure>()
                val candidates = LinkedHashMap<String, Entry>()
                val queue = ArrayDeque<Directory>()
                if (fileIds == null) queue.add(Directory("", "", 0))
                else fileIds.distinct().forEach { id ->
                    val selected = session.entries[id]
                        ?: throw NetDiskException("file_reference_expired", "文件引用已失效，请先解析并选择文件。")
                    if (selected.file.isDirectory) queue.add(Directory(selected.file.id, selected.path, 0))
                    else candidates.putIfAbsent(selected.file.id, selected)
                }
                val visited = HashSet<String>()
                var pages = 0
                var exhausted = false
                while (queue.isNotEmpty() && !exhausted) {
                    currentCoroutineContext().ensureActive()
                    val directory = queue.removeFirst()
                    if (!visited.add(directory.id)) continue
                    if (directory.depth > MAX_DEPTH) {
                        failures.add(failure("depth_limit", directory.path)); continue
                    }
                    var cursor: String? = null
                    val cursors = HashSet<String>()
                    do {
                        if (pages >= MAX_PAGES) {
                            failures.add(failure("page_limit", directory.path)); exhausted = true; break
                        }
                        pages++
                        val listing = try { list(session.value, directory.id, cursor) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failures.add(failure(error, directory.path)); break }
                        for (file in listing.files) {
                            val item = Entry(file, childPath(directory.path, file.name))
                            try { entry(session, file, item.path) }
                            catch (error: NetDiskException) {
                                failures.add(failure(error, item.path)); exhausted = true; break
                            }
                            if (file.isDirectory) {
                                if (file.id !in visited) queue.add(Directory(file.id, item.path, directory.depth + 1))
                            } else if (file.id !in candidates) {
                                if (candidates.size >= MAX_FILES) {
                                    failures.add(failure("batch_file_limit", item.path)); exhausted = true; break
                                }
                                candidates[file.id] = item
                            }
                        }
                        if (exhausted) break
                        cursor = listing.nextCursor?.takeIf(String::isNotBlank)
                        if (cursor != null && !cursors.add(cursor)) {
                            failures.add(failure("pagination_cycle", directory.path)); break
                        }
                        // Some providers hide their hard page cap by returning a null final cursor.
                        if (pages == MAX_PAGES && cursor == null && listing.files.isNotEmpty()) {
                            failures.add(failure("page_limit", directory.path)); exhausted = true; break
                        }
                    } while (cursor != null)
                }
                val destination = safeText(destinationLabel())
                val destinationIdentity = destinationKey()
                val submitted = LinkedHashSet<String>()
                var enqueued = 0
                var skipped = 0
                val approved = if (allowTransfer) session.value.copy(extra = JsonObject(session.value.extra +
                    ("allow_transfer" to JsonPrimitive(true)))) else session.value
                downloadMutex.withLock {
                    for (item in candidates.values) {
                        currentCoroutineContext().ensureActive()
                        if (destinationKey() != destinationIdentity) {
                            failures.add(failure("directory_changed", item.path)); break
                        }
                        val key = Source(owner, session.value.share.platform, session.value.share.shareId, item.file.id, destinationIdentity)
                        val previous = synchronized(lock) { sources[key] }
                        val previousStatus = previous?.let { id -> tasks().firstOrNull { it.id == id }?.status?.name }
                        if (previous != null && previousStatus in setOf("QUEUED", "PROBING", "DOWNLOADING", "SAVING", "PAUSED", "COMPLETED")) {
                            submitted.add(previous); skipped++; continue
                        }
                        try {
                            val link = resolve(approved, item.file)
                            if (destinationKey() != destinationIdentity)
                                throw NetDiskException("directory_changed", "下载目录已变更，请重新提交下载。")
                            // Once a task is created it must be registered to this owner even if chat is cancelled.
                            val id = withContext(NonCancellable) {
                                (enqueueWithDestination?.invoke(link, destinationIdentity) ?: enqueue(link)).also { taskId -> synchronized(lock) {
                                    val existingOwner = owners[taskId]
                                    if (existingOwner != null && existingOwner != owner)
                                        throw NetDiskException("task_owner_conflict", "该文件已有其他聊天的下载任务，请在传输页查看。")
                                    owners[taskId] = owner; sources[key] = taskId
                                } }
                            }
                            pruneOwnership()
                            submitted.add(id); enqueued++
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { failures.add(failure(error, item.path)) }
                    }
                }
                val partial = failures.isNotEmpty()
                result("download", if (partial && submitted.isEmpty()) "error" else if (partial) "partial" else "submitted") {
                    put("session_id", session.id); put("destination_label", destination)
                    put("task_ids", JsonArray(submitted.map(::JsonPrimitive)))
                    put("tasks", snapshots(owner, submitted.toList()))
                    put("enqueued", enqueued); put("skipped", skipped); put("failed", failures.size)
                    put("partial", partial); put("files_found", candidates.size)
                    put("errors", JsonArray(failures.take(MAX_FILES).map(::failureJson)))
                    if (partial && submitted.isEmpty()) put("error_code", failures.first().code)
                    put("message", when {
                        partial && submitted.isEmpty() -> failures.first().message
                        partial -> "部分文件未提交，请根据错误补充登录、授权或缩小选择后重试。已提交任务继续下载。"
                        submitted.isEmpty() -> "此选择中没有可下载的文件。"
                        else -> "已提交下载任务，可查询任务状态；文件保存到当前下载目录，分享子目录将平铺保存。"
                    })
                }
            }
        }

    suspend fun status(owner: String, taskIds: List<String>? = null): JsonObject = safe("status") {
        checkOwner(owner)
        if (taskIds != null && taskIds.size > MAX_FILES)
            throw NetDiskException("invalid_selection", "一次最多查询 200 个下载任务。")
        val requested = taskIds?.distinct() ?: synchronized(lock) { owners.filterValues { it == owner }.keys.toList() }
        val known = requested.filter { synchronized(lock) { owners[it] == owner } }
        result("status", "ready") {
            put("tasks", snapshots(owner, known))
            put("unknown_task_ids", JsonArray((requested - known.toSet()).map { JsonPrimitive(safeText(it)) }))
            put("message", "仅显示本次运行中由当前聊天提交的任务；pending 表示暂未取得任务快照。")
        }
    }

    private suspend fun session(owner: String, text: String?, password: String, id: String?): Session {
        checkOwner(owner)
        if (id != null && !text.isNullOrBlank()) throw NetDiskException("invalid_arguments", "分享文本和会话引用只能选择一项。")
        if (id != null) return synchronized(lock) { sessions[id]?.takeIf { it.owner == owner } }
            ?: throw NetDiskException("session_expired", "解析会话已失效，请重新提供分享链接。")
        if (text.isNullOrBlank() || text.length > 32_768 || password.length > 64)
            throw NetDiskException("invalid_arguments", "请提供有效的分享链接和提取码。")
        val value = open(text, password)
        val created = Session(handle(), owner, value)
        synchronized(lock) {
            sessions[created.id] = created
            while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        }
        return created
    }

    private fun directory(session: Session, id: String): Pair<String, String> {
        if (id.isBlank()) return "" to ""
        val entry = session.entries[id]?.takeIf { it.file.isDirectory }
            ?: throw NetDiskException("directory_reference_expired", "文件夹引用已失效，请重新解析。")
        return entry.file.id to entry.path
    }

    private fun entry(session: Session, file: NetDiskFile, path: String): Pair<String, Entry> {
        val key = file.id to file.isDirectory
        val id = session.entryKeys[key] ?: run {
            if (session.entries.size >= MAX_ENTRIES) throw NetDiskException("entry_limit", "解析条目过多，请缩小分享范围。")
            handle().also { session.entryKeys[key] = it }
        }
        val value = Entry(file, path)
        session.entries[id] = value
        return id to value
    }

    private fun pageHandle(session: Session, directory: String, raw: String): String {
        val key = directory to raw
        return session.pageKeys[key] ?: run {
            if (session.pages.size >= MAX_ENTRIES) throw NetDiskException("entry_limit", "解析分页过多，请重新解析分享。")
            handle().also { session.pageKeys[key] = it; session.pages[it] = Page(directory, raw) }
        }
    }

    private fun snapshots(owner: String, ids: List<String>): JsonArray {
        val available = tasks().associateBy { it.id }
        return JsonArray(ids.filter { synchronized(lock) { owners[it] == owner } }.map { id ->
            val task = available[id]
            buildJsonObject {
                put("id", id); put("status", task?.status?.name?.lowercase() ?: "pending")
                if (task != null) {
                    put("file_name", safeText(task.fileName)); put("downloaded_bytes", task.downloadedBytes)
                    put("total_bytes", task.totalBytes); put("speed_bytes_per_second", task.speedBytesPerSecond)
                    put("destination_label", safeText(task.destinationLabel ?: "下载目录"))
                    if (task.errorMessage != null) put("message", "下载尚未完成，请到传输页查看详情或重试。")
                }
            }
        })
    }

    private fun pruneOwnership() {
        val present = tasks().mapTo(HashSet()) { it.id }
        synchronized(lock) {
            val absent = owners.keys.filter { it !in present }.iterator()
            while (owners.size > MAX_OWNED_TASKS && absent.hasNext()) owners.remove(absent.next())
            // The real manager retains at most 200 records. Keep memory bounded for injected adapters too.
            while (owners.size > MAX_OWNED_TASKS) owners.remove(owners.keys.first())
            sources.entries.removeAll { it.value !in owners }
            while (sources.size > MAX_OWNED_TASKS * 2) sources.remove(sources.keys.first())
        }
    }

    private suspend fun safe(action: String, block: suspend () -> JsonObject): JsonObject = withContext(Dispatchers.IO) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failed = failure(error)
            result(action, "error") { put("error_code", failed.code); put("message", failed.message) }
        }
    }

    private fun failure(error: Exception, path: String? = null): Failure =
        failure((error as? NetDiskException)?.reason ?: "operation_failed", path)

    private fun failure(code: String, path: String? = null): Failure {
        val message = when (code) {
            "auth_required", "invalid_credential" -> "需要登录或更新网盘账号，请在网盘页面完成登录后重试。"
            "verification_required" -> "网盘要求官方验证，请到网盘页面完成验证后重试。"
            "password_required", "invalid_password" -> "分享提取码缺失或不正确，请补充后重试。"
            "transfer_required" -> "该文件需要转存到网盘账号，确认会占用云盘空间后可设置 allow_transfer=true 重试。"
            "share_expired" -> "分享已失效，请提供有效分享链接。"
            "share_token_expired" -> "分享访问凭证已失效，请重新解析链接。"
            "unsupported_link", "unsupported_platform" -> "未识别到当前支持的网盘分享链接。"
            "network" -> "连接网盘失败，请检查网络后重试。"
            "rate_limited" -> "网盘暂时限流，请稍后重试。"
            "directory_access", "directory_invalid", "storage_permission" -> "下载目录不可写，请到解析页面选择目录或授予存储权限。"
            "directory_changed" -> "下载目录已变更，请重新提交下载。"
            "download_service_unavailable" -> "系统未允许后台下载，请保持应用在前台后重试。"
            "download_history_full" -> "下载记录已满，请先在传输页移除已结束的记录。"
            "download_store_failed", "download_store_unavailable" -> "下载记录无法保存或读取，请检查手机存储空间。"
            "page_limit" -> "本次已达到 100 页读取边界，无法确认是否已读完；请解析并选择更小的子目录核对或继续。"
            "batch_file_limit" -> "本次最多提交 200 个文件，请缩小文件或目录选择后继续。"
            "file_limit" -> "网盘限制了此文件的直接下载，请登录网盘后重试或在官方页面查看文件权限。"
            "transfer_pending" -> "网盘尚未确认转存结果，云盘中的转存文件保留，请查看转存状态后重试。"
            "invalid_response" -> "网盘未返回可识别的结果，请稍后重试或到官方页面检查分享状态。"
            "depth_limit" -> "目录超过 32 层，请解析并选择更深层的子目录继续。"
            "pagination_cycle" -> "网盘返回重复分页，已停止读取以避免重复下载。"
            "session_expired" -> "解析会话已失效或不属于当前聊天，请重新解析分享。"
            "file_reference_expired", "directory_reference_expired", "invalid_cursor" -> "文件或分页引用已失效，请重新解析相应目录。"
            "invalid_arguments", "invalid_selection" -> "参数或文件选择无效，请按解析结果中的引用重试。"
            "entry_limit" -> "分享条目过多，请缩小分享或目录范围。"
            "task_owner_conflict" -> "该文件已有其他聊天的下载任务，请在传输页查看。"
            else -> "网盘暂时无法完成此操作，请到解析或传输页面查看并重试。"
        }
        val safeCode = code.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "operation_failed"
        return Failure(safeCode, message, path?.let(::safeText))
    }

    private fun failureJson(value: Failure): JsonObject = buildJsonObject {
        put("code", value.code); put("message", value.message); value.path?.let { put("path", it) }
    }
    private fun fileJson(id: String, value: Entry): JsonObject = buildJsonObject {
        put("id", id); put("name", safeText(value.file.name)); put("is_directory", value.file.isDirectory)
        put("size", value.file.size.coerceAtLeast(0)); put("path", safeText(value.path))
    }
    private fun result(action: String, status: String, content: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "netdisk_result"); put("action", action); put("status", status); content()
    }
    private fun checkOwner(owner: String) {
        if (owner.isBlank() || owner.length > 256) throw NetDiskException("invalid_arguments", "聊天引用无效。")
    }
    private fun childPath(parent: String, name: String) = (parent + "/" + safeText(name).replace('/', '_').replace('\\', '_')).take(2048)
    private fun safeText(value: String) = value.filterNot(Char::isISOControl).take(2048)
    private fun handle() = UUID.randomUUID().toString()

    companion object {
        private const val MAX_SESSIONS = 32
        private const val MAX_ENTRIES = 10_000
        private const val MAX_FILES = 200
        private const val MAX_PAGES = 100
        private const val MAX_DEPTH = 32
        private const val MAX_OWNED_TASKS = 400
    }
}
