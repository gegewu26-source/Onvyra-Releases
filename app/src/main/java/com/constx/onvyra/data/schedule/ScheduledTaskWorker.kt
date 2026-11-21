package com.constx.onvyra.data.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.datastore.getAssistantById
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.repository.ConversationRepository
import com.constx.onvyra.service.ChatService
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

internal interface ScheduledTaskScheduler {
    suspend fun enqueue(task: ScheduledTask)
    suspend fun replace(task: ScheduledTask)
    suspend fun cancel(id: String)
}

internal class WorkScheduledTaskScheduler(context: Context) : ScheduledTaskScheduler {
    private val manager = WorkManager.getInstance(context.applicationContext)
    private fun tag(id: String) = "onvyra-scheduled-task-$id"

    override suspend fun enqueue(task: ScheduledTask) = withContext(Dispatchers.IO) {
        val at = task.nextRunAtMillis
        if (!task.enabled || at == null) return@withContext
        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInputData(workDataOf("taskId" to task.id, "revision" to task.revision, "scheduledAt" to at))
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(tag(task.id))
            .build()
        manager.enqueueUniqueWork(task.token(at), ExistingWorkPolicy.KEEP, request).result.get()
        Unit
    }

    override suspend fun replace(task: ScheduledTask) {
        cancel(task.id)
        enqueue(task)
    }

    override suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        manager.cancelAllWorkByTag(tag(id)).result.get()
        Unit
    }
}

/** Reflection-compatible Worker constructor; Koin resolves the existing app pipeline after startup. */
class ScheduledTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("taskId") ?: return Result.failure()
        val revision = inputData.getLong("revision", -1)
        val at = inputData.getLong("scheduledAt", -1)
        val koin = GlobalContext.get()
        val repository = koin.get<ScheduledTaskRepository>()
        val task = repository.claim(id, revision, at) ?: return Result.success()
        val conversationId = Uuid.parse(task.conversationId(at))
        try {
            val chatService = koin.get<ChatService>()
            val settingsStore = koin.get<SettingsStore>()
            check(repository.isCurrent(task)) { "任务已停用或修改" }
            val settings = settingsStore.awaitLoaded()
            requireScheduledTaskConfiguration(task, settings)
            val assistant = settings.getAssistantById(Uuid.parse(task.assistantId)) ?: error("选定助手已删除")
            val conversation = Conversation.ofId(conversationId, assistant.id)
                .copy(
                    title = task.title,
                    config = task.conversationConfig,
                    modeInjectionIds = assistant.modeInjectionIds,
                    lorebookIds = assistant.lorebookIds,
                ).updateCurrentMessages(assistant.presetMessages)
            withTimeout(TimeUnit.MINUTES.toMillis(8)) {
                chatService.executeScheduledPrompt(conversation, task.prompt) { latest ->
                    check(repository.tasks.value.any { it.id == task.id && it.enabled && it.revision == task.revision }) {
                        "任务已停用或修改"
                    }
                    requireScheduledTaskConfiguration(task, latest)
                }
            }
            repository.finish(task, true, "执行完成，结果已保存到聊天", conversationId.toString())
            return Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val persisted = runCatching { koin.get<ConversationRepository>().getConversationById(conversationId) }.getOrNull()
                repository.finish(task, false, "执行被中断，可查看已保存内容", persisted?.id?.toString())
            }
            throw cancelled
        } catch (failure: Exception) {
            val persisted = runCatching { koin.get<ConversationRepository>().getConversationById(conversationId) }.getOrNull()
            repository.finish(task, false, failure.message ?: "执行失败，请检查模型连接或任务配置", persisted?.id?.toString())
            // No automatic retry, provider fallback or duplicate prompt submission.
            return Result.success()
        }
    }
}
