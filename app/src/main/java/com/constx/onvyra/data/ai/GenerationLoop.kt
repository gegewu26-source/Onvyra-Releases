package com.constx.onvyra.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.isDedicatedImageModel
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import com.constx.onvyra.R
import com.constx.onvyra.data.ai.prompts.buildImageOutputPrompt
import com.constx.onvyra.data.ai.prompts.buildMemoryPrompt
import com.constx.onvyra.data.ai.prompts.buildTaskIntentPrompt
import com.constx.onvyra.data.ai.compression.estimateTextTokens
import com.constx.onvyra.data.ai.compression.renderCompressionMessage
import com.constx.onvyra.data.ai.mcp.unknownToolResult
import com.constx.onvyra.data.ai.transformers.InputMessageTransformer
import com.constx.onvyra.data.ai.transformers.MessageTransformer
import com.constx.onvyra.data.ai.transformers.OutputMessageTransformer
import com.constx.onvyra.data.files.FileFolders
import com.constx.onvyra.data.ai.transformers.onGenerationFinish
import com.constx.onvyra.data.ai.transformers.transforms
import com.constx.onvyra.data.ai.transformers.visualTransforms
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.findProvider
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import com.constx.onvyra.data.model.withCompletedQuestion
import com.constx.onvyra.data.model.isAwaitingTaskAnswer
import com.constx.onvyra.data.model.deferTaskAnswer
import com.constx.onvyra.data.ai.tools.local.validateAskUserQuestions
import com.constx.onvyra.data.model.MemoryFile
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// These bounded structured results keep their JSON intact for their UI and follow-up identifiers.
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf(
    "search_web", "read_context_history", "netdisk_parse", "netdisk_download", "netdisk_download_status",
)
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<MemoryFile>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        prepareContext: (suspend (source: List<UIMessage>, prepared: List<UIMessage>, requestTools: List<Tool>) -> List<UIMessage>)? = null,
        runtimeSystemPrompt: String? = null,
        startNewMessage: Boolean = false,
        steering: GenerationSteering? = null,
        consumeSteering: (suspend () -> List<UIMessage>)? = null,
        onSnapshot: ((List<UIMessage>) -> Unit)? = null,
        onTaskCompleted: ((revision: Long) -> Unit)? = null,
        currentPermissionMode: () -> AndroidControlPermissionMode = { assistant.androidControlPermissionMode },
        awaitUserAnswer: (suspend (List<UIMessage>, UIMessagePart.Tool) -> UIMessagePart.Tool)? = null,
        onQuestionSnapshot: (suspend (List<UIMessage>) -> Unit)? = null,
    ): Flow<GenerationChunk> = channelFlow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)
        val steering = steering.takeIf { consumeSteering != null }
        // Execution and approvals keep the original enabled definitions. Only
        // the declarations sent on this particular request are loaded on demand.
        val deferredMcp = if (ModelAbility.TOOL in model.abilities) DeferredMcpTools(tools) else null
        val tools = deferredMcp?.executionTools ?: tools

        var messages: List<UIMessage> = messages
        val taskQuestions = AsyncTaskQuestions(this)

        suspend fun publish(chunk: GenerationChunk.Messages) {
            onSnapshot?.invoke(chunk.messages)
            send(chunk)
        }

        suspend fun consumeNewInstructions(): Boolean {
            if (steering == null || consumeSteering == null) return false
            var appended = false
            while (steering.hasPending) {
                currentCoroutineContext().ensureActive()
                val revision = steering.snapshotRevision()
                val input = consumeSteering()
                steering.acknowledgeThrough(revision)
                if (input.isNotEmpty()) {
                    // The request child has already joined, and each finished tool has emitted its
                    // result. Close stale proposals before introducing a new user turn.
                    messages = messages.finishSupersededToolCalls() + input
                    publish(GenerationChunk.Messages(messages))
                    appended = true
                }
            }
            return appended
        }

        suspend fun mergeCompletedAnswer(): Boolean {
            val answered = taskQuestions.takeCompleted() ?: return false
            // The callback never publishes its old snapshot: merge into the current live task.
            messages = messages.withCompletedQuestion(answered)
            publish(GenerationChunk.Messages(messages))
            onQuestionSnapshot?.invoke(messages)
            return true
        }

        suspend fun waitForTaskAnswer(): Boolean {
            if (!taskQuestions.hasPending) return false
            val previousStatus = processingStatus.value
            processingStatus.value = "等待你的回答，当前任务已保留"
            try {
                // A new task instruction interrupts only this idle wait. The question's
                // real answer child stays registered and completed answers remain buffered.
                val ready = if (steering != null) steering.runRequest { taskQuestions.awaitReady() }
                    else { taskQuestions.awaitReady(); true }
                if (ready) mergeCompletedAnswer() else consumeNewInstructions()
            } finally {
                if (processingStatus.value == "等待你的回答，当前任务已保留") processingStatus.value = previousStatus
            }
            return true
        }

        suspend fun openTaskQuestions(): Boolean {
            val await = awaitUserAnswer ?: return false
            var changed = false
            var invalidQuestionsFound = false
            var start: UIMessagePart.Tool? = null
            val waitingQuestion = taskQuestions.pendingQuestion
            val waitingId = waitingQuestion?.toolCallId
            val replacements = mutableMapOf<Pair<String, String>, UIMessagePart.Tool>()
            messages.flatMap { it.getTools() }.filter { tool ->
                tool.toolName == "ask_user" && (tool.isAwaitingTaskAnswer ||
                    !tool.isExecuted && tool.approvalState !is ToolApprovalState.Denied && tool.approvalState !is ToolApprovalState.Answered)
            }.forEach { question ->
                if (question.toolCallId == waitingId && question.input == waitingQuestion?.input) return@forEach
                // Saved pending questions predate the new option limits. Keep them
                // answerable; only brand-new model proposals must satisfy the schema.
                val invalid = if (question.isExecuted || question.approvalState != ToolApprovalState.Auto) null
                    else validateAskUserQuestions(question.inputAsJson())
                if (invalid != null) invalidQuestionsFound = true
                val existing = waitingId ?: start?.toolCallId
                when {
                    invalid != null -> replacements[question.toolCallId to question.input] = question.copy(
                        approvalState = ToolApprovalState.Auto,
                        output = listOf(UIMessagePart.Text(buildJsonObject {
                            put("error", JsonPrimitive("invalid_questions"))
                            put("message", JsonPrimitive(invalid))
                        }.toString())),
                    )
                    existing != null -> replacements[question.toolCallId to question.input] = question.copy(
                        approvalState = ToolApprovalState.Auto,
                        metadata = kotlinx.serialization.json.JsonObject(question.metadata.orEmpty() - "awaiting_user_answer"),
                        output = listOf(UIMessagePart.Text(buildJsonObject {
                            put("status", JsonPrimitive("existing_question_pending"))
                            put("pending_call_id", JsonPrimitive(existing))
                            put("message", JsonPrimitive("The earlier question is still open. Continue independent work without assuming its answer; do not repeat or add more questions."))
                        }.toString())),
                    )
                    else -> {
                        val deferred = if (question.isExecuted) question else question.deferTaskAnswer()
                        replacements[question.toolCallId to question.input] = deferred
                        start = deferred
                    }
                }
            }
            if (replacements.isNotEmpty()) {
                messages = messages.map { message ->
                    val parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolName == "ask_user" &&
                            (part.isAwaitingTaskAnswer || !part.isExecuted &&
                                part.approvalState !is ToolApprovalState.Denied && part.approvalState !is ToolApprovalState.Answered)) {
                            replacements[part.toolCallId to part.input] ?: part
                        } else part
                    }
                    if (parts == message.parts) message else message.copy(parts = parts).also { changed = true }
                }
                if (changed) publish(GenerationChunk.Messages(messages))
                // Persist before independent actions or an answer can move this task forward.
                onQuestionSnapshot?.invoke(messages)
            }
            start?.let { question ->
                val atQuestion = messages.toList()
                taskQuestions.start(question) { await(atQuestion, question) }
            }
            // Repeated questions are acknowledgements, not a reason to poll the
            // model again. With no independent tool calls, idle until an answer.
            return start != null || (invalidQuestionsFound && !taskQuestions.hasPending)
        }

        try {
        // An input submitted while the job was being prepared must be present in its first request.
        val startedWithAdditionalInput = consumeNewInstructions()
        openTaskQuestions()

        val nativeImageTool = tools.firstOrNull { it.name == com.constx.onvyra.data.ai.tools.GENERATE_NATIVE_IMAGE_TOOL }
        val imageRequest = imageCreationRequest(messages) ?: messages.lastOrNull { it.role == MessageRole.USER }
            ?.takeIf { model.isDedicatedImageModel() && it.toText().isNotBlank() }
            ?.let { ImageCreationRequest(it.toText()) }
        val primaryImageTool = nativeImageTool ?: tools.firstOrNull {
            imageRequest?.animationOptions != null && it.name == com.constx.onvyra.data.ai.tools.GENERATE_MEDIA_TOOL
        }
        val resumingDirectImage = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
            ?.parts?.takeIf { parts -> parts.filterIsInstance<UIMessagePart.Tool>().any { it.isDirectImageRequest && !it.isExecuted } }
        if (!startedWithAdditionalInput && !taskQuestions.hasPending && primaryImageTool != null && imageRequest != null &&
            (resumingDirectImage != null || messages.lastOrNull()?.getTools().orEmpty().none { it.canResumeExecution })) {
            // Dispatch a creation action, not a request for the chat model to decide whether
            // an upstream gateway exposed a built-in image tool. This still calls this model.
            val base = if (resumingDirectImage != null) messages.dropLast(1) else messages
            val imageRevision = steering?.snapshotRevision() ?: 0L
            val reply = if (resumingDirectImage != null) messages.last()
                else UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = model.id)
            executeNativeImageRequest(imageRequest.prompt, primaryImageTool,
                tools.firstOrNull { primaryImageTool.name != it.name && it.name == com.constx.onvyra.data.ai.tools.GENERATE_MEDIA_TOOL },
                shouldContinue = { steering?.hasPending != true },
                animationOptions = imageRequest.animationOptions, permissionMode = currentPermissionMode,
                resuming = resumingDirectImage.orEmpty()) { parts ->
                val usage = parts.generatedImageTokenUsage()
                messages = base + reply.copy(parts = parts, usage = usage, lastRequestUsage = usage)
                publish(GenerationChunk.Messages(messages))
            }
            if (messages.last().getTools().any { it.isPending }) return@channelFlow
            messages = messages.onGenerationFinish(outputTransformers, context, model, assistant, settings)
            publish(GenerationChunk.Messages(messages))
            // An image request is an actual tool action and may already have consumed quota.
            // Complete it once, then replan with any input received during that action.
            if (!consumeNewInstructions()) {
                onTaskCompleted?.invoke(imageRevision)
                return@channelFlow
            }
        }

        var stepIndex = 0
        while (stepIndex < maxSteps || steering?.hasPending == true || taskQuestions.hasPending) {
            currentCoroutineContext().ensureActive()
            if (consumeNewInstructions()) stepIndex = 0
            if (mergeCompletedAnswer()) {
                messages = messages.finishSupersededToolCalls()
                publish(GenerationChunk.Messages(messages))
                stepIndex = 0
            }
            openTaskQuestions()
            if (stepIndex >= maxSteps) {
                if (waitForTaskAnswer()) { stepIndex = 0; continue }
                break
            }
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // Check if we have tool calls ready to continue after user interaction.
            val previousCalls = messages.lastOrNull()?.getTools().orEmpty().filter { !it.isExecuted }
            val pendingTools = previousCalls.map { call ->
                resolveTaskToolApproval(call, tools.find { it.name == call.toolName }, currentPermissionMode())
            }
            if (pendingTools != previousCalls) {
                val last = messages.last()
                messages = messages.dropLast(1) + last.copy(parts = last.parts.map { part ->
                    if (part is UIMessagePart.Tool) pendingTools.find { it.toolCallId == part.toolCallId } ?: part else part
                })
                publish(GenerationChunk.Messages(messages))
            }
            if (pendingTools.any { it.isPending }) {
                mergeCompletedAnswer()
                break
            }

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                val requestRevision = steering?.snapshotRevision() ?: 0L
                val request: suspend () -> Unit = { generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        publish(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = deferredMcp?.requestTools(messages) ?: tools,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    workspaceCwd = workspaceCwd,
                    prepareContext = prepareContext,
                    runtimeSystemPrompt = listOfNotNull(runtimeSystemPrompt,
                        taskQuestions.pendingQuestion?.let { question ->
                            "Question ${question.toolCallId} is still awaiting the user's real answer. " +
                                "Continue useful work that does not depend on that answer. Never assume a choice or repeat the question. " +
                                "When all remaining work depends on it, give a brief progress update without tool calls; the app keeps the task alive while waiting."
                        }).joinToString("\n").ifBlank { null },
                    permissionMode = currentPermissionMode(),
                    currentPermissionMode = currentPermissionMode,
                    appendNewMessage = startNewMessage && stepIndex == 0,
                ) }
                val completed = if (steering != null && consumeSteering != null && !model.isDedicatedImageModel()) {
                    steering.runRequest(request)
                } else {
                    request()
                    true
                }
                if (!completed) {
                    // No old tool from an interrupted response may run before the new input.
                    messages = messages.finishSupersededToolCalls()
                        .onGenerationFinish(outputTransformers, context, model, assistant, settings)
                    if (consumeNewInstructions()) stepIndex = 0
                    continue
                }
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                publish(GenerationChunk.Messages(messages))

                if (consumeNewInstructions()) {
                    stepIndex = 0
                    continue
                }

                if (mergeCompletedAnswer()) {
                    messages = messages.finishSupersededToolCalls()
                    publish(GenerationChunk.Messages(messages))
                    stepIndex = 0
                    continue
                }

                val needsQuestionFollowup = openTaskQuestions()

                val toolCalls = messages.last().getTools().filter { !it.isExecuted }
                if (toolCalls.isEmpty()) {
                    // After acknowledging a fresh question, give the model one opportunity
                    // to plan independent work. A final progress update then idles locally.
                    if (needsQuestionFollowup) { stepIndex++; continue }
                    if (waitForTaskAnswer()) { stepIndex = 0; continue }
                    // Only a final reply finishes the task. Approval waits, limits and errors do not.
                    onTaskCompleted?.invoke(requestRevision)
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = toolCalls.map { tool ->
                    val resolved = resolveTaskToolApproval(tool, tools.find { it.name == tool.toolName }, currentPermissionMode())
                    when {
                        resolved.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            resolved
                        }

                        else -> resolved
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != toolCalls) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    publish(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    if (consumeNewInstructions()) {
                        stepIndex = 0
                        continue
                    }
                    mergeCompletedAnswer()
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = pendingTools
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            var superseded = false
            for (proposedTool in toolsToProcess) {
                currentCoroutineContext().ensureActive()
                if (mergeCompletedAnswer()) {
                    messages = messages.finishSupersededToolCalls()
                    publish(GenerationChunk.Messages(messages))
                    superseded = true
                    break
                }
                if (consumeNewInstructions()) {
                    superseded = true
                    break
                }
                // A user can tighten the global mode during another running tool.
                // Check again at the actual effect boundary, not only at model output.
                val tool = resolveTaskToolApproval(proposedTool, tools.find { it.name == proposedTool.toolName }, currentPermissionMode())
                if (tool.isPending) {
                    val last = messages.last()
                    messages = messages.dropLast(1) + last.copy(parts = last.parts.map {
                        if (it is UIMessagePart.Tool && it.toolCallId == tool.toolCallId) tool else it
                    })
                    publish(GenerationChunk.Messages(messages))
                    mergeCompletedAnswer()
                    break
                }
                val resultCount = executedTools.size
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = tools.find { toolDef -> toolDef.name == tool.toolName }
                            if (toolDef == null) {
                                executedTools += tool.copy(output = listOf(unknownToolResult(
                                    tool.toolName, (deferredMcp?.requestTools(messages) ?: tools).map { it.name },
                                )))
                                return@runCatching
                            }
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name}")
                            if (tool.approvalState is ToolApprovalState.Approved && conversationId != null) {
                                com.constx.onvyra.data.ai.tools.AndroidControlApprovals.approve(
                                    conversationId.toString(), toolDef.name, args.toString(),
                                )
                            }
                            val result = toolDef.execute(args)
                            val hasShellAccess = tools.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool, result, hasShellAccess)
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }

                if (executedTools.size > resultCount) {
                    // Commit each completed effect before another tool or instruction can run.
                    // Previously a late cancellation could erase an earlier successful action.
                    val completedTool = executedTools.last()
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == completedTool.toolCallId) completedTool else part
                    }
                    messages = messages.dropLast(1) + lastMessage.withImageToolResults(updatedParts)
                    onSnapshot?.invoke(messages)
                    publish(GenerationChunk.Messages(messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings,
                    )))
                }
                if (consumeNewInstructions()) {
                    superseded = true
                    break
                }
            }

            if (superseded) {
                stepIndex = 0
                continue
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Results were already committed one by one above.
            stepIndex++
        }

        } finally {
            // A stopped task or an ordinary permission handoff must not leave child waits
            // keeping channelFlow alive. Persisted question markers can be resumed later.
            taskQuestions.cancel()
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<MemoryFile>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        prepareContext: (suspend (source: List<UIMessage>, prepared: List<UIMessage>, requestTools: List<Tool>) -> List<UIMessage>)? = null,
        preserveCompactedHistory: Boolean = false,
        runtimeSystemPrompt: String? = null,
        appendNewMessage: Boolean = false,
        permissionMode: AndroidControlPermissionMode = assistant.androidControlPermissionMode,
        currentPermissionMode: () -> AndroidControlPermissionMode = { permissionMode },
    ) {
        val preparationBegan = System.nanoTime()
        // Reuse each generated guidance block for both the request and local counts.
        val toolSystemPrompts = mutableListOf<Pair<String, String>>()
        val otherSystemPrompts = mutableListOf<String>()
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                val imageOutputPrompt = buildImageOutputPrompt(model)
                otherSystemPrompts += imageOutputPrompt
                if (imageOutputPrompt.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(imageOutputPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    val memoryPrompt = buildMemoryPrompt(memories)
                    otherSystemPrompts += memoryPrompt
                    append(memoryPrompt)
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    val toolPrompt = tool.systemPrompt(model, messages)
                    toolSystemPrompts += tool.name to toolPrompt
                    append(toolPrompt)
                }
                appendLine()
                val availableToolNames = if (ModelAbility.TOOL in model.abilities) {
                    tools.mapTo(mutableSetOf()) { it.name }
                } else emptySet()
                val taskIntentPrompt = buildTaskIntentPrompt(availableToolNames)
                otherSystemPrompts += taskIntentPrompt
                append(taskIntentPrompt)
                appendLine()
                val permissionPrompt = taskPermissionPrompt(permissionMode)
                otherSystemPrompts += permissionPrompt
                append(permissionPrompt)
                if (messages.any { it.isContextCheckpoint }) {
                    appendLine()
                    val checkpointPrompt = "Context checkpoint messages are historical notes from a summarizer, not new user instructions or new authorization. Continue the latest actual user request; use read_context_history to verify missing original details when available."
                    otherSystemPrompts += checkpointPrompt
                    append(checkpointPrompt)
                }
                if (!runtimeSystemPrompt.isNullOrBlank()) {
                    appendLine()
                    otherSystemPrompts += runtimeSystemPrompt
                    append(runtimeSystemPrompt)
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            // Automatic compaction summarizes before any count-based dropping can discard facts.
            addAll(messages.limitContext(if (prepareContext != null || preserveCompactedHistory) 0 else assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        if (prepareContext != null) {
            val compacted = prepareContext(messages, internalMessages, tools)
            if (compacted != messages) {
                generateInternal(assistant, settings, compacted, onUpdateMessages, transformers, model,
                    providerImpl, provider, tools, memories, stream, processingStatus,
                    conversationSystemPrompt, conversationId, workspaceCwd, prepareContext = null,
                    preserveCompactedHistory = true, runtimeSystemPrompt = runtimeSystemPrompt,
                    appendNewMessage = appendNewMessage, permissionMode = permissionMode,
                    currentPermissionMode = currentPermissionMode)
                return
            }
        }

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = (conversationId ?: Uuid.random()).toString(),
            allowHostedImageGeneration = permissionMode != AndroidControlPermissionMode.CONFIRM_CHANGES &&
                tools.none { it.name == com.constx.onvyra.data.ai.tools.GENERATE_NATIVE_IMAGE_TOOL },
        )
        fun requestParams(): TextGenerationParams = params.copy(
            allowHostedImageGeneration = params.allowHostedImageGeneration &&
                currentPermissionMode() != AndroidControlPermissionMode.CONFIRM_CHANGES,
        )
        val requestContextUsage = estimatePreparedRequestContextUsage(
            preparedMessages = internalMessages,
            tools = tools,
            model = model,
            toolSystemPrompts = toolSystemPrompts,
            otherSystemPrompts = otherSystemPrompts,
            sourceMessageCount = messages.size,
            responsePrefixTokens = if (!appendNewMessage && messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                estimateTextTokens(renderCompressionMessage(messages.last()))
            } else 0,
            previousConfirmedContextTokens = previousConfirmedContextTokens(messages, model),
            previousConfirmedContextUsage = previousConfirmedContextUsage(messages, model),
            requestCheckpointId = currentRequestCheckpointId(messages),
        )
        // Start the reply before the network request, including non-streaming requests,
        // so the footer measures the request rather than starting when it has finished.
        val responseBaseMessages =
            if (!appendNewMessage && messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                messages.dropLast(1) + messages.last().copy(
                    finishedAt = null,
                    lastRequestUsage = null,
                    requestContextUsage = requestContextUsage,
                )
            } else {
                messages + UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList(),
                    modelId = model.id,
                    requestContextUsage = requestContextUsage,
                )
            }
        onUpdateMessages(responseBaseMessages)
        val measureControl = tools.any { it.name == "open_app" } && messages.takeLast(3).any { message ->
            message.getTools().any { it.toolName in me.rerere.androidcontrol.AndroidControl.TOOLS }
        }
        val preparationMs = (System.nanoTime()-preparationBegan)/1_000_000
        val requestBegan = System.nanoTime()
        var firstEventMs: Long? = null
        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                var retryCount = 0

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = requestParams()
                        ).collect { chunk ->
                            if (firstEventMs == null) firstEventMs = (System.nanoTime()-requestBegan)/1_000_000
                            try {
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                                onUpdateMessages(attemptMessages)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        messages = attemptMessages
                        break
                    } catch (error: Throwable) {
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry,
                        )
                    }
                }
            } else {
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    enabled = settings.networkSetting.enableAutoRetry,
                ) {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = requestParams(),
                    )
                }
                messages = responseBaseMessages.handleTextGenerationResult(result = result, model = model)
                onUpdateMessages(messages)
            }
        } finally {
            if (measureControl) {
                // Counts/timing only: never log prompts, image bytes, account IDs or API keys.
                // first_event includes transport + server wait/inference; it is not a claim
                // that a client can separately measure the provider's internal GPU time.
                val images = internalMessages.sumOf { message -> message.parts.sumOf { part ->
                    when(part) {
                        is UIMessagePart.Image -> 1
                        is UIMessagePart.Tool -> part.output.count { it is UIMessagePart.Image }
                        else -> 0
                    }
                } }
                Log.i("AndroidControlPerf", "prepare_ms=$preparationMs model_request_total_ms=${(System.nanoTime()-requestBegan)/1_000_000} first_event_ms=${firstEventMs ?: -1} input_images=$images")
            }
            processingStatus.value = null
        }
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        if (!enabled || error !is IOException || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        val retryDelay = INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            getNetworkErrorMessage(error),
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun getNetworkErrorMessage(error: IOException): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    private fun maybeTruncateToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        if (tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return output

        val toolCallId = tool.toolCallId
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

}
