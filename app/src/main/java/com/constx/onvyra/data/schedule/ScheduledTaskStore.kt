package com.constx.onvyra.data.schedule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.constx.onvyra.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Independent private file; never rewrites the user's existing settings datastore. */
class ScheduledTaskStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var loadFailure: Throwable? = null
    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks = _tasks.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    suspend fun awaitLoaded() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked() {
        if (loaded) return
        try {
            val original = if (file.exists()) JsonInstant.decodeFromString<List<ScheduledTask>>(file.readText()) else emptyList()
            require(original.map { it.id }.distinct().size == original.size) { "任务编号重复" }
            // A persisted claim from a previous process must never re-submit the same prompt.
            val recovered = original.map { task ->
                if (task.lastStatus != ScheduledTaskStatus.RUNNING) task else {
                    val next = if (task.enabled) nextScheduledTaskTime(task, now()) else null
                    task.copy(
                        enabled = task.enabled && next != null,
                        nextRunAtMillis = next,
                        lastStatus = ScheduledTaskStatus.FAILED,
                        lastMessage = "上次执行被中断，未重复提交；可查看已保存的聊天",
                    )
                }
            }
            if (recovered != original) writeLocked(recovered)
            _tasks.value = recovered
        } catch (failure: Exception) {
            loadFailure = failure
            _error.value = "定时任务读取失败，已保留原文件：${failure.message ?: "格式错误"}"
        } finally {
            loaded = true
        }
    }

    suspend fun <T> update(block: (List<ScheduledTask>) -> Pair<List<ScheduledTask>, T>): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                loadLocked()
                check(loadFailure == null) { _error.value ?: "任务文件读取失败" }
                val (updated, result) = block(_tasks.value)
                if (updated != _tasks.value) {
                    writeLocked(updated)
                    _tasks.value = updated
                }
                result
            }
        }

    internal suspend fun claim(id: String, revision: Long, atMillis: Long): ScheduledTask? = update { current ->
        val task = current.firstOrNull { it.id == id }
        if (task == null || !task.enabled || task.revision != revision ||
            task.nextRunAtMillis != atMillis || task.lastRunToken == task.token(atMillis)
        ) return@update current to null
        val claimed = task.copy(
            lastRunToken = task.token(atMillis),
            lastScheduledAtMillis = atMillis,
            lastStatus = ScheduledTaskStatus.RUNNING,
            lastMessage = "正在执行",
            lastConversationId = task.conversationId(atMillis),
        )
        current.map { if (it.id == id) claimed else it } to claimed
    }

    internal suspend fun finish(
        task: ScheduledTask,
        succeeded: Boolean,
        message: String,
        conversationId: String?,
    ): ScheduledTask? = update { current ->
        val latest = current.firstOrNull { it.id == task.id }
        if (latest == null || latest.revision != task.revision || latest.lastRunToken != task.lastRunToken ||
            latest.lastStatus != ScheduledTaskStatus.RUNNING
        ) return@update current to null
        val next = nextScheduledTaskTime(latest, maxOf(now(), task.lastScheduledAtMillis ?: 0))
        val updated = latest.copy(
            enabled = latest.enabled && next != null,
            nextRunAtMillis = next,
            lastStatus = if (succeeded) ScheduledTaskStatus.SUCCEEDED else ScheduledTaskStatus.FAILED,
            lastMessage = message.take(400),
            lastConversationId = conversationId,
        )
        current.map { if (it.id == task.id) updated else it } to updated
    }

    private fun writeLocked(tasks: List<ScheduledTask>) {
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "无法创建任务目录" } }
        val temp = File(file.parentFile, "${file.name}.new")
        FileOutputStream(temp).use { stream ->
            stream.write(JsonInstant.encodeToString(tasks).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
