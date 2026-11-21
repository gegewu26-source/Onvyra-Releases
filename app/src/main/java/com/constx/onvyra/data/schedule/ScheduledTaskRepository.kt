package com.constx.onvyra.data.schedule

import android.content.Context
import me.rerere.ai.provider.ProviderSetting
import com.constx.onvyra.AppScope
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.datastore.findModelById
import com.constx.onvyra.data.datastore.findProvider
import com.constx.onvyra.data.datastore.getAssistantById
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.bindConfig
import com.constx.onvyra.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

class ScheduledTaskRepository(
    context: Context,
    private val settingsStore: SettingsStore,
    @Suppress("UNUSED_PARAMETER") appScope: AppScope,
) {
    private val store = ScheduledTaskStore(File(context.filesDir, "scheduled_tasks/tasks.json"))
    private val scheduler: ScheduledTaskScheduler = WorkScheduledTaskScheduler(context)
    val tasks = store.tasks
    val error = store.error

    suspend fun awaitLoaded() = store.awaitLoaded()

    suspend fun restoreSchedules() {
        awaitLoaded()
        if (error.value != null) return
        tasks.value.filter { it.enabled && it.nextRunAtMillis != null }.forEach { scheduler.enqueue(it) }
    }

    suspend fun save(task: ScheduledTask) {
        require(task.title.isNotBlank()) { "请输入任务名称" }
        require(task.prompt.isNotBlank()) { "请输入提示词" }
        val now = System.currentTimeMillis()
        val next = nextScheduledTaskTime(task, now)
        require(next != null || !task.enabled) { "执行时间已过，请重新选择时间" }
        val snapshot = snapshotScheduledTask(task, settingsStore.awaitLoaded())
        val saved = store.update { current ->
            val previous = current.firstOrNull { it.id == task.id }
            check(previous == null || previous.revision == task.revision) { "任务已更新，请关闭编辑窗口后重试" }
            val updated = snapshot.copy(
                title = task.title.trim(),
                prompt = task.prompt.trim(),
                revision = (previous?.revision ?: 0) + 1,
                nextRunAtMillis = if (task.enabled) next else null,
                lastStatus = ScheduledTaskStatus.IDLE,
                lastMessage = "",
                lastRunToken = null,
                lastScheduledAtMillis = null,
            )
            (current.filterNot { it.id == task.id } + updated) to updated
        }
        scheduler.replace(saved)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val settings = settingsStore.awaitLoaded()
        val saved = store.update { current ->
            val task = current.firstOrNull { it.id == id } ?: error("任务不存在")
            if (enabled) requireScheduledTaskConfiguration(task, settings)
            val next = if (enabled) nextScheduledTaskTime(task, System.currentTimeMillis()) else null
            require(!enabled || next != null) { "执行时间已过，请编辑时间后再启用" }
            val updated = task.copy(
                enabled = enabled,
                revision = task.revision + 1,
                nextRunAtMillis = next,
                lastRunToken = null,
                lastStatus = ScheduledTaskStatus.IDLE,
                lastMessage = if (enabled) "已启用" else "已停用",
            )
            current.map { if (it.id == id) updated else it } to updated
        }
        scheduler.replace(saved)
    }

    suspend fun delete(id: String) {
        store.update { current -> current.filterNot { it.id == id } to Unit }
        scheduler.cancel(id)
    }

    internal suspend fun claim(id: String, revision: Long, atMillis: Long): ScheduledTask? =
        store.claim(id, revision, atMillis)

    internal suspend fun isCurrent(task: ScheduledTask): Boolean {
        awaitLoaded()
        return tasks.value.any {
            it.id == task.id && it.enabled && it.revision == task.revision &&
                it.lastRunToken == task.lastRunToken && it.lastStatus == ScheduledTaskStatus.RUNNING
        }
    }

    internal suspend fun finish(
        task: ScheduledTask,
        succeeded: Boolean,
        message: String,
        conversationId: String?,
    ) {
        val finished = store.finish(task, succeeded, message, conversationId)
        if (finished?.enabled == true) scheduler.enqueue(finished)
    }
}

internal fun scheduledFingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun snapshotScheduledTask(task: ScheduledTask, settings: Settings): ScheduledTask {
    val assistantId = Uuid.parse(task.assistantId)
    val assistant = settings.getAssistantById(assistantId) ?: error("选定助手已删除")
    val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: error("请先给助手配置聊天模型")
    val owner = model.findProvider(settings.providers, checkOverwrite = false) ?: error("模型提供商已删除")
    val provider = model.findProvider(settings.providers) ?: error("模型提供商已删除")
    check(owner.enabled && provider.enabled) { "模型提供商已停用" }
    return task.copy(
        modelId = model.id.toString(),
        providerId = owner.id.toString(),
        modelPublicId = model.modelId,
        modelFingerprint = scheduledFingerprint(JsonInstant.encodeToString(model)),
        providerFingerprint = scheduledFingerprint(JsonInstant.encodeToString<ProviderSetting>(provider.copyProvider(models = emptyList()))),
        conversationConfig = Conversation.ofId(Uuid.random(), assistantId).bindConfig(settings).config,
    )
}

internal fun requireScheduledTaskConfiguration(task: ScheduledTask, settings: Settings) {
    check(settings.getAssistantById(Uuid.parse(task.assistantId)) != null) { "选定助手已删除，请编辑任务" }
    val model = settings.findModelById(Uuid.parse(task.modelId)) ?: error("任务模型已删除，请编辑任务")
    val owner = model.findProvider(settings.providers, checkOverwrite = false) ?: error("任务提供商已删除")
    val provider = model.findProvider(settings.providers) ?: error("任务提供商已删除")
    check(owner.enabled && provider.enabled) { "任务提供商已停用" }
    check(owner.id.toString() == task.providerId && model.modelId == task.modelPublicId &&
        task.modelFingerprint == scheduledFingerprint(JsonInstant.encodeToString(model)) &&
        task.providerFingerprint == scheduledFingerprint(JsonInstant.encodeToString<ProviderSetting>(provider.copyProvider(models = emptyList())))
    ) { "任务模型或提供商配置已更改，请编辑并保存任务" }
    check(task.conversationConfig?.chatModelId?.toString() == task.modelId) { "任务模型快照无效，请编辑任务" }
}
