package com.constx.onvyra.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.ui.finishReasoning
import com.constx.onvyra.data.ai.compression.invalidateChangedContextCheckpoints
import com.constx.onvyra.data.model.Conversation
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlin.time.Clock

private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
) {
    // 会话状态
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()
    private val initializationMutex = Mutex()
    private val metadataMutex = Mutex()
    private var metadataWasExplicitlyUpdated = false
    private val chatConfigurationMutex = Mutex()
    private var chatConfigurationOwner: Uuid? = null
    private var pendingChatConfiguration: Job? = null
    private val persistenceMutex = Mutex()
    internal val compressionMutex = Mutex()
    // Guarded by compressionMutex. A failed summary should not be retried at every tool boundary.
    internal var failedAutoCompressionRun: Any? = null
    @Volatile
    private var initialized = false
    val messageQueue = MessageQueue()
    val steering = com.constx.onvyra.data.ai.GenerationSteering()
    internal val questionAnswers = QuestionAnswerRegistry()
    @Volatile internal var questionAnswerRun: Any? = null

    // 页面切换和 SSE 重连只加载一次；活跃 session 的内存状态始终优先。
    suspend fun initialize(load: suspend () -> Conversation) {
        initializationMutex.withLock {
            if (initialized) return
            val conversation = load()
            synchronized(this) {
                // 加载挂起期间可能已经通过保存或编辑写入了更新的状态。
                if (!initialized) updateConversation(conversation)
            }
        }
    }

    @Synchronized
    fun updateConversation(conversation: Conversation) {
        require(conversation.id == id)
        _state.value = invalidateChangedContextCheckpoints(_state.value, conversation)
        initialized = true
    }

    /** Whole snapshots and translation-only commits must reach storage in the same order. */
    internal suspend fun <T> withPersistence(block: suspend () -> T): T =
        persistenceMutex.withLock { block() }

    // 元数据先应用到最新内存状态；落库只更新对应列，不能用旧消息快照覆盖流式输出。
    internal suspend fun updateMetadata(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        metadataMutex.withLock {
            // Metadata commits and generation/title/translation saves share one storage order.
            withPersistence {
                val updated = synchronized(this) {
                    update(state.value).also {
                        metadataWasExplicitlyUpdated = true
                        updateConversation(it)
                    }
                }
                persist(updated)
            }
        }
    }

    /** A suspended generation/title snapshot cannot undo an explicit menu action. */
    @Synchronized
    internal fun preserveMetadata(snapshot: Conversation): Conversation {
        if (!metadataWasExplicitlyUpdated) return snapshot
        val current = state.value
        return snapshot.copy(
            assistantId = current.assistantId,
            folderId = current.folderId,
            isPinned = current.isPinned,
            isArchived = current.isArchived,
            isLocked = current.isLocked,
        )
    }

    /** Register before starting the UI coroutine so an immediate send cannot overtake it. */
    @Synchronized
    internal fun trackChatConfigurationUpdate(job: Job) {
        pendingChatConfiguration = job
    }

    internal suspend fun awaitChatConfigurationUpdates() {
        while (true) {
            val pending = synchronized(this) { pendingChatConfiguration }
            pending?.join()
            // Direct service callers also use this mutex, without a ViewModel job to register.
            chatConfigurationMutex.withLock { }
            if (synchronized(this) { pending === pendingChatConfiguration }) return
        }
    }

    /** Only explicit chat controls share the whole-save/translation persistence order. */
    internal suspend fun updateChatConfiguration(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        chatConfigurationMutex.withLock {
            withPersistence {
                val updated = synchronized(this) {
                    update(state.value).also {
                        chatConfigurationOwner = it.assistantId
                        updateConversation(it)
                    }
                }
                persist(updated)
            }
        }
    }

    /** Message/title snapshots cannot undo a newer explicit chat-control change. */
    @Synchronized
    internal fun preserveChatConfiguration(snapshot: Conversation): Conversation {
        val current = state.value
        if (chatConfigurationOwner != current.assistantId || snapshot.assistantId != current.assistantId) {
            return snapshot
        }
        return snapshot.copy(
            config = current.config,
            modeInjectionIds = current.modeInjectionIds,
            lorebookIds = current.lorebookIds,
            // A workspace change invalidates its old directory; a manual cd in the same workspace stays valid.
            workspaceCwd = if (snapshot.config?.workspaceId != current.config?.workspaceId) {
                current.workspaceCwd
            } else snapshot.workspaceCwd,
        )
    }

    // 失败和取消也必须保存已收到的内容，且保存完成前不能释放生成任务。
    suspend fun finishGeneration(
        messageId: Uuid? = null,
        discardInlineImageData: Boolean = false,
        save: suspend (Conversation) -> Unit,
    ): Conversation =
        withContext(NonCancellable) {
            val conversation = synchronized(this@ConversationSession) {
                val current = state.value
                val finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                current.copy(
                    messageNodes = current.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { message ->
                            // Failed/cancelled native streams can leave data: placeholders or
                            // partial previews. Do not let the no-inline-data storage guard
                            // replace the actual provider/decoder error with Failed requirement.
                            val safeMessage = if (discardInlineImageData && message.id == messageId)
                                message.copy(parts = message.parts.filterNot {
                                    it is me.rerere.ai.ui.UIMessagePart.Image && it.url.startsWith("data:")
                                }) else message
                            val finished = safeMessage.finishReasoning()
                            if (message.id == messageId) finished.copy(finishedAt = finishedAt) else finished
                        })
                    },
                    updateAt = Instant.now(),
                ).also(::updateConversation)
            }
            save(conversation)
            conversation
        }

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    private var generationRun: Any = Any()
    private var generationRunStopped = false
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
                messageQueue.state.value.messages.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    internal fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
    }

    internal fun release(): Int = refCount.decrementAndGet().also {
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        if (cancelPrevious) {
            generationRun = Any()
            generationRunStopped = false
        } else if (generationRunStopped) {
            // An approval continuation from a closed phone task must never restart it.
            job?.cancel()
            return
        }
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    @Synchronized
    internal fun canAcceptToolDecision(): Boolean = !generationRunStopped && _generationJob.value?.isCancelled != true

    /** Identity survives approval resumptions, but a new user request gets a new identity. */
    @Synchronized
    internal fun generationRunToken(): Any = generationRun

    @Synchronized
    internal fun isGenerationRun(token: Any): Boolean = generationRun === token

    /** Called synchronously by the owned phone window's close action; performs no I/O. */
    @Synchronized
    internal fun cancelGenerationRun(token: Any): List<Job>? {
        if (generationRun !== token || generationRunStopped) return null
        generationRunStopped = true
        messageQueue.pause()
        return cancelJobs()
    }

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // Cancel waiters first so a predecessor finishing cannot start the next approval.
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        questionAnswerRun = null
        questionAnswers.clear()
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
