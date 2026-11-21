package com.constx.onvyra.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import me.rerere.ui.toast.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.datastore.findProvider
import com.constx.onvyra.data.datastore.getSelectedASRProvider
import com.constx.onvyra.data.files.FilesManager
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.Conversation
import com.constx.onvyra.data.model.pendingAskUserTool
import com.constx.onvyra.data.model.TemporaryChatMode
import com.constx.onvyra.utils.conversationDeviceLockAvailable
import com.constx.onvyra.utils.requestConversationShortcut
import com.constx.onvyra.data.repository.WorkspaceRepository
import com.constx.onvyra.service.ChatError
import com.constx.onvyra.ui.components.ai.ChatAttachmentPickerActions
import com.constx.onvyra.ui.components.ai.ChatInput
import com.constx.onvyra.ui.components.ai.FilesPicker
import com.constx.onvyra.ui.components.ai.SearchMode
import com.constx.onvyra.ui.components.ai.completion.WorkspaceCompletionProvider
import com.constx.onvyra.ui.components.ai.rememberChatAttachmentPickerActions
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.context.LocalVideoPlaybackRecovery
import java.util.concurrent.atomic.AtomicBoolean
import com.constx.onvyra.ui.hooks.ChatInputState
import com.constx.onvyra.ui.hooks.EditStateContent
import com.constx.onvyra.ui.hooks.useEditState
import com.constx.onvyra.utils.base64Decode
import com.constx.onvyra.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm) {
        if (vm.isTemporaryChatClosed()) navigateToChatPage(navController)
    }

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    // No chat content, previews, attachments or auto-play is composed before device authentication.
    if (ConversationPrivacyGate(conversation, onLeave = { navigateToChatPage(navController) })) return

    com.constx.onvyra.ui.components.update.AppUpdateHost(checkOnResume = true)

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Clear input focus so popup transitions cannot reopen the keyboard.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    val pageLifecycle = LocalLifecycleOwner.current.lifecycle
    val recoveryPageActive = remember(id, vm) { AtomicBoolean(true) }
    DisposableEffect(recoveryPageActive) {
        recoveryPageActive.set(true)
        onDispose { recoveryPageActive.set(false) }
    }
    DisposableEffect(vm) {
        onDispose { vm.leaveTemporaryChat() }
    }
    CompositionLocalProvider(LocalVideoPlaybackRecovery provides { preview ->
        vm.continueAfterVideoFailure(preview.playbackId) {
            recoveryPageActive.get() && pageLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }) {
    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        onBeforeNavigate = { drawerState.close() },
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        onBeforeNavigate = { drawerState.close() },
                    )
                }
            ) {
                ChatPageContent(
                    onStartVoiceMode = startVoiceMode,
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    assistant = assistant,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
    }
}

@Composable
private fun ChatPageContent(
    onStartVoiceMode: () -> Unit,
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    assistant: Assistant,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val context = LocalContext.current
    val temporaryMode by vm.temporaryChatMode.collectAsStateWithLifecycle()
    val contextCapacity by vm.contextCapacity.collectAsStateWithLifecycle()
    val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
    val steeringPresentation = remember(conversation.messageNodes, messageQueue) {
        presentSteeringMessages(conversation, messageQueue)
    }
    val hasMessages = conversation.messageNodes.isNotEmpty() &&
        !(conversation.newConversation && conversation.currentMessages == assistant.presetMessages)
    val pendingQuestionTool = remember(conversation.messageNodes) { conversation.pendingAskUserTool() }
    val pendingQuestionState = rememberPendingQuestionState(conversation.id.toString(), pendingQuestionTool)
    val answeringQuestion = pendingQuestionState?.capturesComposer == true && !inputState.isEditing()

    fun moveToQuestion(index: Int) {
        val questionState = pendingQuestionState ?: return
        questionState.rememberText(inputState.textContent.text.toString())
        questionState.moveTo(index)
        inputState.setMessageText(questionState.textForCurrentQuestion())
    }

    fun submitQuestionAnswer() {
        val tool = pendingQuestionTool ?: return
        val questionState = pendingQuestionState ?: return
        if (questionState.submitting || questionState.currentQuestion == null) return
        val text = inputState.textContent.text.toString()
        if (!questionState.canAnswerCurrent(text)) return
        questionState.rememberText(text)
        if (!questionState.isLastQuestion) {
            questionState.moveTo(questionState.questionIndex + 1)
            inputState.setMessageText(questionState.textForCurrentQuestion())
            return
        }
        questionState.firstUnansweredIndex()?.let { index ->
            questionState.moveTo(index)
            inputState.setMessageText(questionState.textForCurrentQuestion())
            toaster.show("请先回答这一题", type = ToastType.Warning)
            return
        }
        val answerPayload = questionState.beginSubmission() ?: return
        val answerAttachments = inputState.messageContent.filter {
            it is UIMessagePart.Image || it is UIMessagePart.Document
        }
        val retainedAttachments = inputState.messageContent.filterNot {
            it is UIMessagePart.Image || it is UIMessagePart.Document
        }
        if (!vm.handleToolAnswer(tool.toolCallId, answerPayload, answerAttachments)) {
            questionState.rejectSubmission()
            toaster.show("问题已更新，请检查后重试", type = ToastType.Warning)
            return
        }
        questionState.acceptSubmission()
        inputState.clearInput()
        inputState.messageContent = retainedAttachments
        if (retainedAttachments.any { it is UIMessagePart.Audio || it is UIMessagePart.Video }) {
            toaster.show("音视频附件已保留在输入框", type = ToastType.Warning)
        }
    }

    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    var showFilesSheet by remember { mutableStateOf(false) }
    var showTemporarySettings by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var showUploadedFiles by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val attachmentPickerActions = rememberChatAttachmentPickerActions(
        inputState = inputState,
        setting = setting,
        onAttachmentAdded = { showFilesSheet = false },
    )
    val allowAudioVideoAttachments =
        currentChatModel?.findProvider(setting.providers) is ProviderSetting.Google

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    assistant = assistant,
                    chatModel = currentChatModel,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    hasMessages = hasMessages,
                    temporaryMode = temporaryMode,
                    onTemporaryToggle = {
                        vm.setTemporaryChatMode(if (temporaryMode == TemporaryChatMode.OFF)
                            TemporaryChatMode.NON_PERSONALIZED else TemporaryChatMode.OFF)
                    },
                    onAction = { action ->
                        when (action) {
                            ChatHeaderAction.SHARE -> showShareSheet = true
                            ChatHeaderAction.PIN -> vm.updatePinnedStatus(conversation)
                            ChatHeaderAction.LOCK -> {
                                if (!conversation.isLocked && !conversationDeviceLockAvailable(context)) {
                                    toaster.show("请先在系统设置中设置锁屏密码或生物识别", type = ToastType.Warning)
                                } else vm.setConversationLocked(!conversation.isLocked)
                            }
                            ChatHeaderAction.PROJECT -> showProjectSheet = true
                            ChatHeaderAction.FILES -> showUploadedFiles = true
                            ChatHeaderAction.SEARCH -> previewMode = true
                            ChatHeaderAction.SHORTCUT -> {
                                val requested = runCatching { requestConversationShortcut(context, conversation) }.getOrDefault(false)
                                toaster.show(if (requested) "已向桌面发送添加请求" else "当前桌面不支持添加快捷方式",
                                    type = if (requested) ToastType.Success else ToastType.Warning)
                            }
                            ChatHeaderAction.ARCHIVE -> scope.launch {
                                try {
                                    vm.setConversationArchived(!conversation.isArchived)
                                    navigateToChatPage(navController)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { toaster.show("归档操作失败，请重试", type = ToastType.Error) }
                            }
                            ChatHeaderAction.DELETE -> showDeleteDialog = true
                        }
                    },
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
                Column {
                    if (answeringQuestion && pendingQuestionState != null) {
                        PendingQuestionCard(
                            state = pendingQuestionState,
                            draftText = inputState.textContent.text.toString(),
                            onSelectOption = { option ->
                                pendingQuestionState.selectOption(option)
                                if (pendingQuestionState.currentQuestion?.selectionType != "multi") {
                                    pendingQuestionState.rememberText("")
                                    inputState.setMessageText("")
                                }
                            },
                            onPrevious = { moveToQuestion(pendingQuestionState.questionIndex - 1) },
                            onConfirm = { submitQuestionAnswer() },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    ChatInput(
                        contextCapacity = contextCapacity,
                        onStartVoiceMode = onStartVoiceMode,
                        voiceState = voiceState,
                        onStopVoiceMode = vm.voiceSession::stop,
                        state = inputState,
                        messageQueue = steeringPresentation.queueForPanel,
                        onRemoveQueuedMessage = vm::removeQueuedMessage,
                        onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                        onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
                        onResumeMessageQueue = vm::resumeMessageQueue,
                        loading = loadingJob != null || answeringQuestion,
                        settings = setting,
                        assistant = assistant,
                        chatModel = currentChatModel,
                        hazeState = hazeState,
                        completionProviders = completionProviders,
                        onCancelClick = {
                            vm.stopGeneration()
                        },
                        onUpdateSearchMode = { mode ->
                            vm.updateSearch(
                                enableWebSearch = mode == SearchMode.LOCAL,
                                builtInSearch = mode == SearchMode.BUILT_IN,
                            )
                        },
                        onSendClick = {
                            if (pendingQuestionState?.capturesComposer == true && !inputState.isEditing()) {
                                submitQuestionAnswer()
                                return@ChatInput
                            }
                            if (currentChatModel == null) {
                                toaster.show("请先选择模型", type = ToastType.Error)
                                return@ChatInput
                            }
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                vm.handleMessageSend(inputState.getContents())
                                scope.launch {
                                    delay(100.milliseconds)
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                            inputState.clearInput()
                        },
                        onLongSendClick = {
                            if (pendingQuestionState?.capturesComposer == true && !inputState.isEditing()) {
                                submitQuestionAnswer()
                                return@ChatInput
                            }
                            if (inputState.isEditing()) {
                                vm.handleMessageEdit(
                                    parts = inputState.getContents(),
                                    messageId = inputState.editingMessage!!,
                                )
                            } else {
                                vm.handleMessageSend(content = inputState.getContents(), answer = false)
                                scope.launch {
                                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                                }
                            }
                            inputState.clearInput()
                        },
                        onUpdateChatModel = vm::setChatModel,
                        onUpdateAssistant = vm::updateAssistant,
                        onUpdateSearchService = { index ->
                            vm.updateSettings { it.copy(searchServiceSelected = index) }
                        },
                        onMoreClick = {
                            showFilesSheet = true
                        },
                    )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            if (temporaryMode != TemporaryChatMode.OFF && !hasMessages) {
                Box(Modifier.fillMaxSize().padding(innerPadding)) {
                    TemporaryChatWelcome(mode = temporaryMode, onSettings = { showTemporarySettings = true })
                }
            } else {
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                pendingSteeringMessages = steeringPresentation.pendingNodes,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        try {
                            val fork = vm.forkMessage(message = it)
                            navigateToChatPage(navController, chatId = fork.id)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            toaster.show(error.message ?: "无法创建会话分支", type = ToastType.Warning)
                        }
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) {
                                    newNode
                                } else {
                                    node
                                }
                            }
                        ))
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
            }
        }

        ChatExportSheet(visible = showShareSheet, onDismissRequest = { showShareSheet = false },
            conversation = conversation, selectedMessages = conversation.currentMessages)
        if (showTemporarySettings) TemporaryChatSettingsSheet(mode = temporaryMode,
            onSelect = vm::setTemporaryChatMode, onDismiss = { showTemporarySettings = false })
        if (showProjectSheet) ConversationProjectSheet(conversation = conversation,
            onMove = { vm.moveConversationToProject(it) }, onDismiss = { showProjectSheet = false },
            onError = { toaster.show(it, type = ToastType.Error) })
        if (showUploadedFiles) ConversationUploadedFilesSheet(conversation = conversation,
            onDismiss = { showUploadedFiles = false })
        if (showDeleteDialog) AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除聊天？") },
            text = { Text("删除后无法恢复此聊天。") },
            confirmButton = { TextButton(onClick = {
                showDeleteDialog = false
                scope.launch {
                    vm.stopGeneration()
                    vm.deleteConversation(conversation).join()
                    navigateToChatPage(navController)
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                chatModel = currentChatModel,
                vm = vm,
                attachmentPickerActions = attachmentPickerActions,
                onStartVoiceMode = onStartVoiceMode,
                onDismiss = { showFilesSheet = false },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    chatModel: Model?,
    vm: ChatVM,
    attachmentPickerActions: ChatAttachmentPickerActions,
    onStartVoiceMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showCompressDialog = false
        onDismiss()
    }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.size(32.dp, 4.dp), shape = RoundedCornerShape(2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant) {}
            }
        },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            chatModel = chatModel,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = vm::updateAssistant,
            onUpdateExtensions = vm::updateExtensions,
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = attachmentPickerActions.onTakePicture,
            onPickImage = attachmentPickerActions.onPickImage,
            onPickVideo = attachmentPickerActions.onPickVideo,
            onPickAudio = attachmentPickerActions.onPickAudio,
            onPickFile = attachmentPickerActions.onPickFile,
            onSketch = {
                // Give the drawing surface a single stable window, without the attachment sheet or IME.
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                dismissAll()
                attachmentPickerActions.onSketch()
            },
            onStartVoiceMode = if (
                setting.getSelectedASRProvider()?.supportsServerVadVoiceMode == true &&
                voiceState.phase == VoicePhase.Off
            ) {
                {
                    dismissAll()
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onStartVoiceMode()
                }
            } else null,
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    assistant: Assistant,
    chatModel: Model?,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    hasMessages: Boolean,
    temporaryMode: TemporaryChatMode,
    onTemporaryToggle: () -> Unit,
    onAction: (ChatHeaderAction) -> Unit,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }, modifier = Modifier.size(44.dp),
                ) {
                    Icon(HugeIcons.Menu03, "打开聊天列表")
                }
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val provider = chatModel?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chatModel != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${chatModel.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        },
        actions = {
            if (previewMode) IconButton(onClick = onClickMenu) { Icon(HugeIcons.Cancel01, "关闭聊天查找") }
            else ChatHeaderActions(hasMessages = hasMessages, temporaryMode = temporaryMode,
                conversationTitle = conversation.title, isPinned = conversation.isPinned, isLocked = conversation.isLocked,
                isArchived = conversation.isArchived, onTemporaryToggle = onTemporaryToggle,
                onNewChat = onNewChat, onAction = onAction)
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
