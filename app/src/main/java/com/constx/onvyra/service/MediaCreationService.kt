package com.constx.onvyra.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import com.constx.onvyra.AppScope
import com.constx.onvyra.MEDIA_CREATION_NOTIFICATION_CHANNEL_ID
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.ai.tools.ExistingProviderMediaSelection
import com.constx.onvyra.data.ai.tools.existingProviderMediaSelection
import com.constx.onvyra.data.ai.tools.mediaClient
import com.constx.onvyra.data.ai.prompts.mediaGenerationPromptFor
import com.constx.onvyra.data.files.decodeOriginalImage
import com.constx.onvyra.data.files.MediaCreationFiles
import com.constx.onvyra.data.files.FilesManager
import com.constx.onvyra.data.files.FileFolders
import com.constx.onvyra.data.files.imageFileExtension
import com.constx.onvyra.data.files.GifAnimationVerifier
import com.constx.onvyra.data.files.GifAnimationException
import com.constx.onvyra.data.ai.tools.supportsNativeChatImageOutput
import androidx.core.net.toUri
import com.constx.onvyra.data.remote.RemoteFileStore
import com.constx.onvyra.data.model.MediaCreationAsset
import com.constx.onvyra.data.model.MediaCreationOutput
import com.constx.onvyra.data.model.MediaCreationParams
import com.constx.onvyra.data.model.MediaCreationRecord
import com.constx.onvyra.data.model.MediaCreationStatus
import com.constx.onvyra.data.model.buildMediaGenerationRequest
import com.constx.onvyra.data.model.deriveMediaCreationTitle
import com.constx.onvyra.data.model.supportedBy
import com.constx.onvyra.data.repository.MediaCreationRepository
import com.constx.onvyra.utils.sendNotification
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid

private const val TAG = "MediaCreationService"

/**
 * 一次提交的内容。[assets] 可以指向任何已有的本地文件（草稿里的素材、别的记录的输入或输出），
 * 提交时会复制一份到新记录自己的目录下。
 *
 * [nodeId] 非空时新记录成为时间线上那一项的新版本，否则另起一项。
 */
data class MediaCreationSubmission(
    val sessionId: Uuid,
    val nodeId: Uuid? = null,
    val provider: MediaGenerationProviderSetting,
    val model: MediaGenerationModel,
    val prompt: String,
    val params: MediaCreationParams,
    val assets: List<MediaCreationAsset>,
)

/**
 * 媒体创作的生成任务。每条记录一个协程，互不阻塞；任务挂在应用级作用域上，离开页面后继续运行，
 * 并由 [MediaCreationForegroundService] 把进程留在前台。
 *
 * 一条记录的流程：上传素材（只有要求公网地址的接口需要）→ 提交 → 轮询到终态 → 把结果下载到本地。
 * 进度全部写进数据库，界面只需要观察记录。
 */
class MediaCreationService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val repository: MediaCreationRepository,
    private val manager: MediaGenerationManager,
    private val remoteFileStore: RemoteFileStore,
    private val okHttpClient: OkHttpClient,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
) {
    private val jobs = ConcurrentHashMap<Uuid, Job>()

    /** Runs in the chat's coroutine and foreground lifetime, without a studio session or record. */
    suspend fun generateChatImage(
        provider: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        prompt: String,
        params: MediaCreationParams,
    ): List<UIMessagePart> {
        com.constx.onvyra.security.AppIntegrity.requireTrusted()
        require(model.kind == MediaKind.IMAGE)
        val settings = settingsStore.awaitLoaded()
        val bridge = settings.existingProviderMediaSelection(model.id, MediaKind.IMAGE)
        check(bridge != null || settings.providers.none { owner -> owner.models.any { it.id == model.id } }) {
            "所选模型尚未声明生图能力，请选择图片输出模型或专用生图模型。"
        }
        check(bridge == null || bridge.model.modelId == model.modelId)
        val setting = bridge?.mediaProvider ?: settings.mediaGenerationProviders.find { it.id == provider.id }
            ?: error("所选生图服务商已停用或删除。")
        val client = bridge?.mediaClient(okHttpClient) ?: okHttpClient
        val activeManager = if (bridge != null) MediaGenerationManager(client) else manager
        val capabilities = setting.capabilities(MediaKind.IMAGE) ?: error("所选接口不支持图片生成。")
        val generationPrompt = settings.mediaGenerationPromptFor(MediaKind.IMAGE, prompt)
        val animation = params.takeIf { it.outputFormat == "gif" }?.let {
            try { gifAnimationPlan(it, model.modelId,
                setting is MediaGenerationProviderSetting.OpenAI && bridge?.model?.supportsNativeChatImageOutput(bridge.provider) != true)
            } catch (error: IllegalArgumentException) { throw GifAnimationException(error.message ?: "GIF参数无效。") }
        }
        val requestParams = if (animation?.mode == "storyboard") params.copy(
            count = 1, outputFormat = "png".takeIf { "png" in capabilities.imageOutputFormats },
            resolution = if (animation.frames == 8) "1536x768" else "1024x1024",
            frameCount = null, frameDurationMs = null, animationMode = "storyboard",
        ) else if (animation != null) params.copy(count = 1, frameCount = animation.frames, frameDurationMs = animation.delayMs) else params
        val requestPrompt = if (animation?.mode == "storyboard") animation.storyboardPrompt(generationPrompt) else generationPrompt
        return runChatImageTask(
            create = {
                if (bridge != null) createExistingImage(bridge, requestParams, requestPrompt)
                else activeManager.create(setting, model,
                    buildMediaGenerationRequest(requestPrompt, requestParams, emptyList(), emptyList(), capabilities)).getOrThrow()
            },
            query = { taskId -> activeManager.query(setting, model, taskId).getOrThrow() },
            save = { output ->
                val bytes = output.data ?: output.url?.let { url ->
                    withContext(Dispatchers.IO) {
                        val temp = File.createTempFile("chat-image-", ".part", context.cacheDir)
                        try {
                            download(setting, url, temp, attempts = 1, client = client)
                            check(temp.length() in 1..(64L * 1024 * 1024)) { "图片结果过大或为空。" }
                            temp.readBytes()
                        } finally { temp.delete() }
                    }
                } ?: error("生图接口未返回图片。")
                check(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024)
                val finalBytes = if (animation?.mode == "storyboard") withContext(Dispatchers.Default) {
                    try { composeStoryboardGif(bytes, animation) }
                    catch (error: IllegalArgumentException) { throw GifAnimationException(error.message ?: "模型分镜无法合成为动画。") }
                } else bytes
                val gif = finalBytes.size >= 6 && finalBytes.copyOfRange(0, 6).decodeToString() in setOf("GIF87a", "GIF89a")
                val info = if (animation != null) withContext(Dispatchers.Default) {
                    try { GifAnimationVerifier.verify(finalBytes).also {
                        if (it.frames != animation.frames) throw GifAnimationException("接口返回的GIF帧数与请求不一致，没有将错误结果当作成功。")
                    } } catch (error: IllegalArgumentException) { throw GifAnimationException(error.message ?: "接口没有返回有效的多帧GIF。") }
                } else null
                val mime = if (gif) "image/gif" else output.mimeType.substringBefore(';')
                check(mime.startsWith("image/")) { "所选接口未返回图片。" }
                val file = filesManager.saveManagedFromBytes(FileFolders.UPLOAD, finalBytes,
                    "generated-${Uuid.random()}.${imageFileExtension(mime)}", mime)
                UIMessagePart.Image(filesManager.getFile(file).toUri().toString(), metadata = animation?.let {
                    buildJsonObject { put("onvyra_animation", buildJsonObject {
                        put("format", "gif"); put("mode", it.mode)
                        put("locally_assembled", it.mode == "storyboard")
                        put("frame_count", info!!.frames)
                        put("frame_duration_ms", info.delaysMs.first())
                        put("requested_frame_duration_ms", it.delayMs)
                        put("frame_delays_ms", JsonArray(info.delaysMs.map(::JsonPrimitive)))
                        put("width", info.width); put("height", info.height)
                        put("model", model.modelId); put("generation_requests", 1)
                    }) }
                })
            },
        )
    }

    /**
     * 新建一条记录并开始生成。提交过程放在应用级作用域里，调用方中途被取消也不会留下半条记录。
     */
    suspend fun submit(submission: MediaCreationSubmission): MediaCreationRecord = inAppScope {
        val capabilities = submission.provider.capabilities(submission.model.kind)
            ?: error(context.getString(R.string.media_creation_error_kind_unsupported, submission.provider.name))
        val recordId = Uuid.random()
        val assets = submission.assets.supportedBy(capabilities)
        val record = MediaCreationRecord(
            id = recordId,
            sessionId = submission.sessionId,
            nodeId = submission.nodeId ?: Uuid.random(),
            providerId = submission.provider.id,
            providerName = submission.provider.name,
            modelId = submission.model.modelId,
            kind = submission.model.kind,
            prompt = submission.prompt.trim(),
            params = submission.params.supportedBy(capabilities),
            inputs = copyInputs(submission.sessionId, recordId, assets),
        )
        // 先登记任务再写入记录：恢复逻辑把「进行中但没有任务」的记录当作被中断，不能让它看到这个空档
        val job = register(record.id, record.sessionId)
            ?: error(context.getString(R.string.media_creation_error_task_exists))
        try {
            repository.insertRecord(record)
            repository.updateSession(record.sessionId) { session ->
                session.copy(
                    title = session.title.ifBlank { deriveMediaCreationTitle(record.prompt) },
                    updateAt = Instant.now(),
                )
            }
        } catch (e: Throwable) {
            jobs.remove(record.id, job)
            job.cancel()
            withContext(Dispatchers.IO) { repository.recordDir(record.sessionId, record.id).deleteRecursively() }
            throw e
        }
        job.start()
        record
    }

    /**
     * 重新执行失败或已取消的记录。服务端还有任务时接着查询，否则重新提交。
     */
    suspend fun retry(recordId: Uuid) = inAppScope {
        val sessionId = repository.getRecord(recordId)?.sessionId ?: return@inAppScope
        // 和 submit 一样先登记任务，再把记录改成进行中
        val job = register(recordId, sessionId) ?: return@inAppScope
        var started = false
        try {
            val record = repository.updateRecord(recordId) { record ->
                if (record.status == MediaCreationStatus.SUCCEEDED) {
                    record
                } else {
                    record.copy(
                        status = if (record.taskId != null) MediaCreationStatus.RUNNING else MediaCreationStatus.PREPARING,
                        error = null,
                        outputs = emptyList(),
                    )
                }
            }
            if (record != null && record.status != MediaCreationStatus.SUCCEEDED) {
                job.start()
                started = true
            }
        } finally {
            if (!started) {
                jobs.remove(recordId, job)
                job.cancel()
            }
        }
    }

    /**
     * 停止等待一条记录。已经提交给服务端的任务无法撤回，[MediaCreationRecord.taskId] 会保留，之后可以重试接着查询。
     */
    suspend fun cancel(recordId: Uuid) = inAppScope {
        jobs[recordId]?.cancelAndJoin()
        repository.updateRecord(recordId) { record ->
            if (record.status.isActive) record.copy(status = MediaCreationStatus.CANCELLED) else record
        }
    }

    suspend fun deleteRecord(recordId: Uuid) = inAppScope {
        jobs[recordId]?.cancelAndJoin()
        repository.getRecord(recordId)?.let { repository.deleteRecord(it) }
    }

    suspend fun deleteSession(sessionId: Uuid) = inAppScope {
        repository.getRecordsOfSession(sessionId).forEach { jobs[it.id]?.cancelAndJoin() }
        repository.deleteSession(sessionId)
    }

    /**
     * 进程启动后接管上次没跑完的记录：有任务 ID 的接着查询，其余的在提交前就被中断了，标记为失败。
     */
    fun resumePending() {
        appScope.launch {
            runCatching {
                repository.getActiveRecords().forEach { record ->
                    if (jobs.containsKey(record.id)) return@forEach
                    if (record.taskId != null) {
                        register(record.id, record.sessionId)?.start()
                    } else {
                        repository.updateRecord(record.id) {
                            it.copy(
                                status = MediaCreationStatus.FAILED,
                                error = context.getString(R.string.media_creation_error_interrupted),
                            )
                        }
                    }
                }
            }.onFailure {
                Log.e(TAG, "resumePending failed", it)
            }
        }
    }

    // 记录已有任务在跑时返回 null
    private fun register(recordId: Uuid, sessionId: Uuid): Job? {
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            val foreground = MediaCreationForegroundService.acquire(context, recordId, sessionId)
            try {
                run(recordId)
            } finally {
                jobs.remove(recordId, coroutineContext.job)
                if (foreground) MediaCreationForegroundService.release(context, recordId)
            }
        }
        if (jobs.putIfAbsent(recordId, job) != null) {
            job.cancel()
            return null
        }
        return job
    }

    private suspend fun run(recordId: Uuid) {
        try {
            generate(recordId)
        } catch (e: Throwable) {
            // 被取消时记录的状态由取消方负责
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Generation $recordId failed", e)
            repository.updateRecord(recordId) {
                it.copy(status = MediaCreationStatus.FAILED, error = e.toDisplayMessage())
            }
        }
        repository.getRecord(recordId)?.let(::notifyFinished)
    }

    private suspend fun generate(recordId: Uuid) {
        val record = repository.getRecord(recordId) ?: return
        val settings = settingsStore.awaitLoaded()
        val bridge = settings.existingProviderMediaSelection(record.providerId, record.kind)
            ?.takeIf { it.model.modelId == record.modelId }
        check(bridge != null || settings.providers.none { provider -> provider.models.any { it.id == record.providerId } }) {
            "所选媒体模型或服务商已停用或发生变更，请重新选择模型"
        }
        val setting = bridge?.mediaProvider?.let { provider ->
            // A stable key per record keeps async polling on the same account after process recreation.
            val keys = provider.apiKey.split(Regex("[\\s,]+")).filter(String::isNotBlank).distinct()
            if (keys.isEmpty()) provider else provider.copyProvider(apiKey = keys[Math.floorMod(record.id.hashCode(), keys.size)])
        } ?: settings.mediaGenerationProviders.find { it.id == record.providerId }
            ?: error(context.getString(R.string.media_creation_error_provider_deleted, record.providerName))
        val model = bridge?.mediaModel ?: MediaGenerationModel(modelId = record.modelId, kind = record.kind)
        val client = bridge?.mediaClient(okHttpClient) ?: okHttpClient
        val activeManager = if (bridge != null) MediaGenerationManager(client) else manager
        val generationPrompt = settings.mediaGenerationPromptFor(record.kind, record.prompt)

        val task = if (record.taskId != null) {
            awaitTask(setting, model, record.id, record.taskId, since = record.updateAt, activeManager = activeManager)
        } else {
            val created = if (bridge != null && record.kind == MediaKind.IMAGE) {
                require(record.inputs.isEmpty()) { "Images delegated from a configured provider currently accept a text prompt only" }
                repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.RUNNING) }
                createExistingImage(bridge, record.params, generationPrompt, record.id)
            }
                else create(setting, model, record, activeManager, generationPrompt)
            if (created.isTerminal) {
                created
            } else {
                // 任务已经在服务端跑起来了：就算此刻被取消也要把任务 ID 记下来，否则再也找不回它
                withContext(NonCancellable) {
                    repository.updateRecord(record.id) {
                        it.copy(taskId = created.id, status = created.status.toCreationStatus(it.status))
                    }
                }
                delay(POLL_INTERVAL)
                awaitTask(setting, model, record.id, created.id, since = Instant.now(), activeManager = activeManager)
            }
        }

        if (task.status != MediaGenerationStatus.SUCCEEDED) {
            // 服务端的任务已经结束，没有再查询的意义
            repository.updateRecord(record.id) {
                it.copy(status = MediaCreationStatus.FAILED, taskId = null, error = task.failureMessage(context))
            }
            return
        }

        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.DOWNLOADING) }
        val outputs = downloadOutputs(setting, record, task.outputs, client)
        repository.updateRecord(record.id) {
            if (outputs.isEmpty()) {
                it.copy(
                    status = MediaCreationStatus.FAILED,
                    taskId = null,
                    error = context.getString(R.string.media_creation_error_no_output),
                )
            } else {
                it.copy(status = MediaCreationStatus.SUCCEEDED, outputs = outputs, error = null)
            }
        }
    }

    private suspend fun create(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        record: MediaCreationRecord,
        activeManager: MediaGenerationManager = manager,
        generationPrompt: String = record.prompt,
    ): MediaGenerationTask {
        val capabilities = setting.capabilities(record.kind)
            ?: error(context.getString(R.string.media_creation_error_kind_unsupported, setting.name))
        val inputUrls = if (capabilities.requiresRemoteInputs && record.inputs.isNotEmpty()) {
            check(remoteFileStore.isConfigured) { context.getString(R.string.media_creation_error_upload_required) }
            repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.PREPARING) }
            record.inputs.map { remoteFileStore.upload(repository.resolve(it.path)).getOrThrow().url }
        } else {
            record.inputs.map { repository.resolve(it.path).absolutePath }
        }
        repository.updateRecord(record.id) { it.copy(status = MediaCreationStatus.RUNNING) }
        val request = buildMediaGenerationRequest(generationPrompt, record.params, record.inputs, inputUrls, capabilities)
        return activeManager.create(setting, model, request).getOrElse {
            currentCoroutineContext().ensureActive()
            throw it
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun createExistingImage(selection: ExistingProviderMediaSelection,
        params: MediaCreationParams, generationPrompt: String, taskId: Uuid = Uuid.random()): MediaGenerationTask {
        val outputs = mutableListOf<MediaGenerationOutput>()
        var tokenUsage: me.rerere.ai.core.TokenUsage? = null
        var imageSource: JsonObject? = null
        val sourceModel = selection.model
        val provider = selection.provider
        val animationRequest = params.outputFormat == "gif" || params.animationMode == "storyboard"
        val cleanBodies = sourceModel.customBodies.filterNot {
            animationRequest && it.key in setOf("output_format", "frame_count", "frame_duration_ms", "stream", "partial_images", "output_compression", "n")
        }
        if (sourceModel.supportsNativeChatImageOutput(provider) || provider is ProviderSetting.Google) {
            require(params.outputFormat != "gif") { "此聊天生图接口未声明 GIF 输出能力，请使用支持 GIF 的专用图片接口。" }
            val model = sourceModel.copy(outputModalities = (sourceModel.outputModalities + Modality.IMAGE).distinct())
            val result = providerManager.getProviderByType(provider).generateText(provider,
                listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(generationPrompt)))),
                TextGenerationParams(model = model, customHeaders = model.customHeaders,
                    customBody = cleanBodies, includeReasoning = false, forceImageGeneration = true))
            tokenUsage = result.usage
            result.message.parts.filterIsInstance<UIMessagePart.Image>().forEach { image ->
                if (imageSource == null) imageSource = image.metadata?.get("onvyra_image_source") as? JsonObject
                if (image.url.startsWith("data:image")) {
                    val original = decodeOriginalImage(image.url)
                    outputs += MediaGenerationOutput(data = original.bytes, mimeType = original.mimeType)
                } else if (image.url.startsWith("https://") || image.url.startsWith("http://")) {
                    outputs += MediaGenerationOutput(url = image.url, mimeType = "image/png")
                }
            }
        } else {
            require(provider is ProviderSetting.OpenAI) { "The selected provider has no image generation implementation" }
            val body = cleanBodies.toMutableList()
            params.outputFormat?.let { body += CustomBody("output_format", JsonPrimitive(it)) }
            params.frameCount?.let { body += CustomBody("frame_count", JsonPrimitive(it)) }
            params.frameDurationMs?.let { body += CustomBody("frame_duration_ms", JsonPrimitive(it)) }
            if (animationRequest) {
                body += CustomBody("stream", JsonPrimitive(false))
                body += CustomBody("n", JsonPrimitive(1))
            }
            providerManager.getProviderByType(provider).generateImage(provider,
                ImageGenerationParams(model = sourceModel, prompt = generationPrompt,
                    numOfImages = params.count ?: 1, size = params.resolution ?: "auto",
                    customHeaders = sourceModel.customHeaders, customBody = body)).collect { image ->
                if (!image.partial && image.usage != null) tokenUsage = image.usage
                if (!image.partial && imageSource == null) imageSource = image.metadata?.get("onvyra_image_source") as? JsonObject
                if (!image.partial) outputs += MediaGenerationOutput(
                    data = Base64.decode(image.data.substringAfter("base64,")), mimeType = image.mimeType)
            }
        }
        check(outputs.isNotEmpty()) { "所选模型未返回图片，请确认该模型及其接口支持生图" }
        return MediaGenerationTask(id = taskId.toString(), provider = provider.name,
            model = (imageSource?.get("actual_image_model") as? JsonPrimitive)?.content ?: sourceModel.modelId,
            status = MediaGenerationStatus.SUCCEEDED, outputs = outputs,
            usage = tokenUsage?.let { MediaGenerationUsage(totalTokens = it.totalTokens.toLong(),
                inputTokens = it.promptTokens.toLong(), outputTokens = it.completionTokens.toLong(),
                cachedTokens = it.cachedTokens.toLong()) },
            metadata = JsonObject(imageSource?.let { mapOf("onvyra_image_source" to it) }.orEmpty()))
    }

    /**
     * 轮询到终态。网络抖动和服务端的临时错误不代表任务失败，退避后继续查询；
     * 接口明确拒绝（4xx）、连续失败太多次或等待超时才放弃，此时记录仍保留任务 ID，可以重试。
     *
     * 时限从 [since] 算起。接着查询已有任务时传记录最后一次变化的时间，而不是现在：
     * 否则进程每重启一次时限就续一次，一个始终不结束的任务会被永远查下去。
     */
    private suspend fun awaitTask(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        recordId: Uuid,
        taskId: String,
        since: Instant,
        activeManager: MediaGenerationManager = manager,
    ): MediaGenerationTask {
        val deadline = since + MAX_WAIT.toJavaDuration()
        var failures = 0
        while (true) {
            val result = activeManager.query(setting, model, taskId)
            currentCoroutineContext().ensureActive()
            result.onSuccess { task ->
                failures = 0
                if (task.isTerminal) return task
                repository.updateRecord(recordId) { it.copy(status = task.status.toCreationStatus(it.status)) }
            }.onFailure { error ->
                failures++
                Log.w(TAG, "Query $taskId failed ($failures)", error)
                if (!error.isRetryable() || failures >= MAX_QUERY_FAILURES) throw error
            }
            check(Instant.now() < deadline) { context.getString(R.string.media_creation_error_timeout) }
            delay(pollDelay(failures))
        }
    }

    private suspend fun copyInputs(
        sessionId: Uuid,
        recordId: Uuid,
        assets: List<MediaCreationAsset>,
    ): List<MediaCreationAsset> = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) return@withContext emptyList()
        val dir = repository.recordDir(sessionId, recordId)
        try {
            dir.mkdirs()
            assets.mapIndexed { index, asset ->
                val source = repository.resolve(asset.path)
                check(source.isFile) { context.getString(R.string.media_creation_error_asset_missing) }
                val target = File(dir, "in_$index.${source.extension.ifEmpty { "bin" }}")
                source.copyTo(target, overwrite = true)
                asset.copy(path = repository.relativePath(target))
            }
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private suspend fun downloadOutputs(
        setting: MediaGenerationProviderSetting,
        record: MediaCreationRecord,
        outputs: List<MediaGenerationOutput>,
        client: OkHttpClient = okHttpClient,
    ): List<MediaCreationOutput> = withContext(Dispatchers.IO) {
        val dir = repository.recordDir(record.sessionId, record.id).apply { mkdirs() }
        // 清掉上一次没下完的残留
        dir.listFiles { file -> file.name.startsWith(OUTPUT_PREFIX) }?.forEach { it.delete() }

        outputs.mapIndexedNotNull { index, output ->
            val isVideo = output.mimeType.startsWith("video/")
            val file = File(dir, "$OUTPUT_PREFIX$index.${output.fileExtension()}")
            val data = output.data
            val url = output.url
            when {
                data != null -> file.writeBytes(data)
                url != null -> download(setting, url, file, client = client)
                else -> return@mapIndexedNotNull null
            }

            if (isVideo) {
                val poster = File(dir, "$OUTPUT_PREFIX${index}_poster.jpg")
                    .takeIf { MediaCreationFiles.extractFrame(file, it, last = false) }
                // 尾帧只是「续写」时的捷径，下载不到就等用的时候再从视频里抽
                val lastFrame = output.lastFrameUrl?.let { lastFrameUrl ->
                    File(dir, "$OUTPUT_PREFIX${index}_last.${lastFrameUrl.urlExtension() ?: "jpg"}")
                        .takeIf { runCatching { download(setting, lastFrameUrl, it, attempts = 1, client = client) }.isSuccess }
                }
                val info = MediaCreationFiles.videoInfo(file)
                MediaCreationOutput(
                    path = repository.relativePath(file),
                    mimeType = output.mimeType,
                    width = info?.width,
                    height = info?.height,
                    durationSeconds = output.durationSeconds ?: info?.durationSeconds,
                    posterPath = poster?.let(repository::relativePath),
                    lastFramePath = lastFrame?.let(repository::relativePath),
                )
            } else {
                val info = MediaCreationFiles.imageInfo(file)
                MediaCreationOutput(
                    path = repository.relativePath(file),
                    mimeType = output.mimeType,
                    width = info?.width,
                    height = info?.height,
                )
            }
        }
    }

    private suspend fun download(
        setting: MediaGenerationProviderSetting,
        url: String,
        target: File,
        attempts: Int = DOWNLOAD_ATTEMPTS,
        client: OkHttpClient = okHttpClient,
    ) {
        // 个别厂商的结果地址没有签名，要带着密钥去取
        val request = Request.Builder().url(url).headers(setting.downloadHeaders(url).toHeaders()).build()
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadOnce(request, target, client)
                return
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt >= attempts) throw e
                Log.w(TAG, "Download failed ($attempt/$attempts)", e)
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun downloadOnce(request: Request, target: File, client: OkHttpClient = okHttpClient) {
        val partial = File(target.parentFile, target.name + MediaCreationFiles.PARTIAL_SUFFIX)
        val call = client.newCall(request)
        try {
            coroutineScope {
                // 断流时线程卡在 read() 里，走不到 ensureActive()：取消时关掉连接，让 read() 立刻抛出来
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                call.await().use { response ->
                    check(response.isSuccessful) {
                        context.getString(R.string.media_creation_error_download_failed, response.code)
                    }
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                canceller.cancel()
            }
            check(partial.renameTo(target)) { context.getString(R.string.media_creation_error_save_failed) }
        } finally {
            partial.delete()
        }
    }

    private fun notifyFinished(record: MediaCreationRecord) {
        if (record.status != MediaCreationStatus.SUCCEEDED && record.status != MediaCreationStatus.FAILED) return
        // 应用在前台时时间线上已经能看到结果
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val isVideo = record.kind == MediaKind.VIDEO
        val succeeded = record.status == MediaCreationStatus.SUCCEEDED
        val titleRes = when {
            isVideo && succeeded -> R.string.media_creation_notification_video_succeeded
            isVideo -> R.string.media_creation_notification_video_failed
            succeeded -> R.string.media_creation_notification_image_succeeded
            else -> R.string.media_creation_notification_image_failed
        }
        context.sendNotification(
            channelId = MEDIA_CREATION_NOTIFICATION_CHANNEL_ID,
            notificationId = record.id.hashCode(),
        ) {
            title = context.getString(titleRes)
            content = record.error?.takeIf { record.status == MediaCreationStatus.FAILED }
                ?: record.prompt.ifBlank { record.modelId }
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = MediaCreationForegroundService.sessionPendingIntent(context, record.sessionId.toString())
        }
    }

    private suspend fun <T> inAppScope(block: suspend () -> T): T = appScope.async { block() }.await()

    companion object {
        private const val OUTPUT_PREFIX = "out_"
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val MAX_QUERY_FAILURES = 20
        private val POLL_INTERVAL = 5.seconds
        private val MAX_POLL_INTERVAL = 60.seconds
        private val MAX_WAIT = 24.hours

        // 查询成功时按固定间隔轮询，连续失败时逐步放慢
        private fun pollDelay(failures: Int): Duration =
            (POLL_INTERVAL * (1 shl failures.coerceIn(0, 4))).coerceAtMost(MAX_POLL_INTERVAL)
    }
}

private fun MediaGenerationStatus.toCreationStatus(current: MediaCreationStatus): MediaCreationStatus = when (this) {
    MediaGenerationStatus.QUEUED -> MediaCreationStatus.QUEUED
    MediaGenerationStatus.RUNNING -> MediaCreationStatus.RUNNING
    // 认不出的状态当作还在进行，保持界面上原来的显示
    else -> current.takeIf { it == MediaCreationStatus.QUEUED } ?: MediaCreationStatus.RUNNING
}

private fun MediaGenerationTask.failureMessage(context: Context): String {
    val reason = error?.let { error -> listOfNotNull(error.code, error.message).joinToString("：") }
    return reason?.takeIf(String::isNotBlank) ?: when (status) {
        MediaGenerationStatus.CANCELLED -> context.getString(R.string.media_creation_error_cancelled_remotely)
        MediaGenerationStatus.EXPIRED -> context.getString(R.string.media_creation_error_expired)
        else -> context.getString(R.string.media_creation_status_failed)
    }
}

// 4xx 是接口明确拒绝（密钥错误、任务不存在等），重试没有意义；限流和超时除外
private fun Throwable.isRetryable(): Boolean =
    this !is MediaGenerationApiException || statusCode !in 400..499 || statusCode == 408 || statusCode == 429

private fun Throwable.toDisplayMessage(): String = when (this) {
    is MediaGenerationApiException -> listOfNotNull("HTTP $statusCode", code, message).joinToString(" · ")
    else -> message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
}

private fun MediaGenerationOutput.fileExtension(): String = when (mimeType.lowercase()) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/svg+xml" -> "svg"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    "video/quicktime" -> "mov"
    else -> url?.urlExtension() ?: if (mimeType.startsWith("video/")) "mp4" else "png"
}

// 结果地址通常带签名参数，扩展名在 path 末尾
private fun String.urlExtension(): String? =
    substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
