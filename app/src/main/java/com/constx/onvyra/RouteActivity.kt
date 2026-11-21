package com.constx.onvyra

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import me.rerere.ui.toast.Toaster
import me.rerere.ui.toast.rememberToasterState
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.db.DatabaseMigrationTracker
import com.constx.onvyra.data.db.MigrationState
import com.constx.onvyra.data.event.AppEvent
import com.constx.onvyra.data.event.AppEventBus
import com.constx.onvyra.data.repository.MediaCreationRepository
import com.constx.onvyra.service.MediaCreationForegroundService
import com.constx.onvyra.service.ChatKeepAliveService
import com.constx.onvyra.ui.activity.SafeModeActivity
import com.constx.onvyra.ui.components.ui.TTSController
import com.constx.onvyra.ui.components.support.AuthorSupportSchedule
import com.constx.onvyra.ui.components.support.StartupAuthorSupportPrompt
import com.constx.onvyra.ui.context.LocalASRState
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.ui.context.LocalSharedTransitionScope
import com.constx.onvyra.ui.context.LocalTTSState
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.hooks.readBooleanPreference
import com.constx.onvyra.ui.hooks.readStringPreference
import com.constx.onvyra.ui.hooks.rememberCustomAsrState
import com.constx.onvyra.ui.hooks.rememberCustomTtsState
import com.constx.onvyra.ui.pages.assistant.AssistantPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantBasicPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantDetailPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantExtensionsPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantLocalToolPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantMcpPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantMemoryPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantPromptPage
import com.constx.onvyra.ui.pages.assistant.detail.AssistantRequestPage
import com.constx.onvyra.ui.pages.backup.BackupPage
import com.constx.onvyra.ui.pages.chat.ChatPage
import com.constx.onvyra.ui.pages.debug.DebugPage
import com.constx.onvyra.ui.pages.extensions.ExtensionsPage
import com.constx.onvyra.ui.pages.netdisk.NetDiskPage
import com.constx.onvyra.ui.pages.netdisk.NetDiskDownloadsPage
import com.constx.onvyra.ui.pages.extensions.PromptPage
import com.constx.onvyra.ui.pages.extensions.QuickMessagesPage
import com.constx.onvyra.ui.pages.extensions.skills.SkillDetailPage
import com.constx.onvyra.ui.pages.memory.MemoryFilePage
import com.constx.onvyra.ui.pages.extensions.skills.SkillsPage
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspacePage
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspaceDetailPage
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import com.constx.onvyra.ui.pages.extensions.workspace.WorkspaceTerminalPage
import me.rerere.workspace.WorkspaceStorageArea
import com.constx.onvyra.ui.pages.favorite.FavoritePage
import com.constx.onvyra.ui.pages.history.HistoryPage
import com.constx.onvyra.ui.pages.imggen.ImageGenPage
import com.constx.onvyra.ui.pages.log.LogPage
import com.constx.onvyra.ui.pages.mediacreation.MediaCreationPage
import com.constx.onvyra.ui.pages.mediacreation.MediaCreationSessionsPage
import com.constx.onvyra.ui.pages.novel.NovelMainPage
import com.constx.onvyra.ui.pages.search.SearchPage
import com.constx.onvyra.ui.pages.schedule.ScheduledTasksPage
import com.constx.onvyra.ui.pages.setting.SettingAboutPage
import com.constx.onvyra.ui.pages.setting.SettingDonationPage
import com.constx.onvyra.ui.pages.setting.SettingAdvancedPage
import com.constx.onvyra.ui.pages.setting.SettingAndroidControlPage
import com.constx.onvyra.ui.pages.setting.SettingPreferencesPage
import com.constx.onvyra.ui.pages.setting.SettingPreferencesThemePage
import com.constx.onvyra.ui.pages.setting.SettingPreferencesNotificationPage
import com.constx.onvyra.ui.pages.setting.SettingPreferencesGeneralPage
import com.constx.onvyra.ui.pages.setting.SettingPreferencesNetworkPage
import com.constx.onvyra.ui.pages.setting.SettingPreferencesUIPage
import com.constx.onvyra.ui.pages.setting.SettingThemePage
import com.constx.onvyra.ui.pages.setting.SettingFilesPage
import com.constx.onvyra.ui.pages.setting.SettingMcpDetailPage
import com.constx.onvyra.ui.pages.setting.SettingMcpPage
import com.constx.onvyra.ui.pages.setting.SettingModelPage
import com.constx.onvyra.ui.pages.setting.SettingPage
import com.constx.onvyra.ui.pages.setting.SettingProviderDetailPage
import com.constx.onvyra.ui.pages.setting.SettingProviderPage
import com.constx.onvyra.ui.pages.setting.SettingSearchDetailPage
import com.constx.onvyra.ui.pages.setting.SettingSearchPage
import com.constx.onvyra.ui.pages.setting.SettingMediaPage
import com.constx.onvyra.ui.pages.setting.SettingSpeechPage
import com.constx.onvyra.ui.pages.setting.SettingWebPage
import com.constx.onvyra.ui.pages.share.handler.ShareHandlerPage
import com.constx.onvyra.ui.pages.stats.StatsPage
import com.constx.onvyra.ui.pages.translator.TranslatorPage
import com.constx.onvyra.ui.pages.webview.WebViewPage
import com.constx.onvyra.ui.theme.OnvyraTheme
import com.constx.onvyra.utils.CrashHandler
import com.constx.onvyra.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "com.constx.onvyra.action.TRANSLATE"
private const val ACTION_MEDIA_CREATION = "com.constx.onvyra.action.MEDIA_CREATION"

class RouteActivity : AppCompatActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val mediaCreationRepository by inject<MediaCreationRepository>()
    private var navStack: MutableList<NavKey>? = null
    private val pendingIntents = ArrayDeque<Intent>()
    private var supportLaunchEligible by mutableStateOf(false)
    private var supportLaunchKey by mutableIntStateOf(0)
    private var supportSettingsReady by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        if (!com.constx.onvyra.security.AppIntegrity.isTrusted()) return
        lifecycleScope.launch {
            lifecycle.withResumed { }
            val settings = settingsStore.awaitLoaded()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                supportSettingsReady = true
                if (settings.enableChatKeepAlive && android.provider.Settings.canDrawOverlays(this@RouteActivity)) {
                    ChatKeepAliveService.start(this@RouteActivity)
                } else {
                    ChatKeepAliveService.stop(this@RouteActivity)
                }
            }
        }
    }

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (com.constx.onvyra.security.AppIntegrity.blockIfUntrusted(this)) return
        supportLaunchKey = savedInstanceState?.getInt("author_support_launch_key", 0) ?: 0
        supportLaunchEligible = isNormalSupportLaunch(intent)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            OnvyraTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        supportLaunchKey += 1
        supportLaunchEligible = isNormalSupportLaunch(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("author_support_launch_key", supportLaunchKey)
        super.onSaveInstanceState(outState)
    }

    private fun isNormalSupportLaunch(intent: Intent): Boolean = AuthorSupportSchedule.isNormalLaunch(
        action = intent.action,
        categories = intent.categories,
        hasData = intent.data != null,
        hasRoutedPayload = listOf(
            "conversationId", "open_android_control", "open_netdisk_downloads", MediaCreationForegroundService.EXTRA_MEDIA_SESSION_ID,
            Intent.EXTRA_TEXT, Intent.EXTRA_STREAM, Intent.EXTRA_PROCESS_TEXT,
        ).any(intent::hasExtra),
    )

    private fun handleIntent(intent: Intent) {
        val backStack = navStack ?: run {
            // Compose 尚未创建导航栈，待就绪后处理。
            pendingIntents.addLast(intent)
            return
        }
        val destination = when (intent.action) {
            ACTION_TRANSLATE -> Screen.Translator
            ACTION_MEDIA_CREATION -> {
                openNewMediaCreation(backStack)
                null
            }
            Intent.ACTION_SEND -> Screen.ShareHandler(
                text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
                streamUri = intent.getStringExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_PROCESS_TEXT -> Screen.ShareHandler(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
            )
            else -> mediaCreationDestination(intent)
                ?: intent.getStringExtra("conversationId")?.let { Screen.Chat(it) }
                ?: (if (intent.getBooleanExtra("open_netdisk_downloads", false)) Screen.NetDiskDownloads else null)
                ?: (if (intent.getBooleanExtra("open_android_control", false)) Screen.SettingAndroidControl else null)
        }
        if (destination != null && backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    // 快捷方式没有指定会话，打开一个空会话
    private fun openNewMediaCreation(backStack: MutableList<NavKey>) {
        lifecycleScope.launch {
            val destination = Screen.MediaCreation(mediaCreationRepository.newSession().id.toString())
            if (backStack.lastOrNull() != destination) backStack.add(destination)
        }
    }

    // 媒体创作的通知带着会话 ID，点击后直接打开那个会话
    private fun mediaCreationDestination(intent: Intent): Screen? {
        val sessionId = intent.getStringExtra(MediaCreationForegroundService.EXTRA_MEDIA_SESSION_ID) ?: return null
        return runCatching { Uuid.parse(sessionId) }.getOrNull()?.let { Screen.MediaCreation(it.toString()) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.ChatGenerationUpdate -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        val startScreen = Screen.Chat(
            id = if (readBooleanPreference("create_new_conversation_on_start", true)) {
                Uuid.random().toString()
            } else {
                readStringPreference(
                    "lastConversationId",
                    Uuid.random().toString()
                ) ?: Uuid.random().toString()
            }
        )

        val backStack = rememberNavBackStack(startScreen)
        SideEffect {
            navStack = backStack
            while (pendingIntents.isNotEmpty()) {
                handleIntent(pendingIntents.removeFirst())
            }
        }

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onBack = { backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) }
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    image = key.streamUri
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.MemoryFile> { key ->
                                MemoryFilePage(memoryId = key.memoryId, path = key.path)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.MediaCreationSessions> {
                                MediaCreationSessionsPage()
                            }

                            entry<Screen.ScheduledTasks> {
                                ScheduledTasksPage()
                            }

                            entry<Screen.MediaCreation> { key ->
                                MediaCreationPage(key.id)
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingPreferences> {
                                SettingPreferencesPage()
                            }

                            entry<Screen.SettingAdvanced> {
                                SettingAdvancedPage()
                            }

                            entry<Screen.SettingAndroidControl> {
                                SettingAndroidControlPage()
                            }

                            entry<Screen.SettingPreferencesTheme> {
                                SettingPreferencesThemePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id, openModels = key.openModels)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingSpeech> {
                                SettingSpeechPage()
                            }

                            entry<Screen.SettingMedia> {
                                SettingMediaPage()
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingMcpDetail> { key ->
                                SettingMcpDetailPage(id = Uuid.parse(key.serverId))
                            }

                            entry<Screen.SettingDonate> {
                                SettingDonationPage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Debug> {
                                if (BuildConfig.DEBUG) {
                                    DebugPage()
                                } else {
                                    // A restored navigation stack must not reopen developer actions in a release build.
                                    LaunchedEffect(Unit) {
                                        backStack.removeAll { it == Screen.Debug }
                                        if (backStack.isEmpty()) backStack.add(Screen.SettingAbout)
                                    }
                                }
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.NetDisk> {
                                NetDiskPage()
                            }

                            entry<Screen.NetDiskDownloads> {
                                NetDiskDownloadsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(key.id)
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }

                            entry<Screen.Novel> {
                                NovelMainPage()
                            }
                        }
                    )
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Toaster(
                        state = toastState,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    StartupAuthorSupportPrompt(
                        eligible = supportLaunchEligible && backStack.lastOrNull() is Screen.Chat,
                        launchKey = supportLaunchKey,
                        ready = supportSettingsReady && migrationState !is MigrationState.Migrating,
                    )
                }
            }
        }
    }
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    /** 记忆库 [memoryId] 里的一个文件，[path] 为 null 时新建 */
    @Serializable
    data class MemoryFile(val memoryId: String, val path: String? = null) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data object MediaCreationSessions : Screen

    @Serializable
    data object ScheduledTasks : Screen

    @Serializable
    data class MediaCreation(val id: String) : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingAdvanced : Screen

    @Serializable
    data object SettingAndroidControl : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String, val openModels: Boolean = false) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMedia : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data class SettingMcpDetail(val serverId: String) : Screen

    @Serializable
    data object SettingDonate : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object NetDisk : Screen

    @Serializable
    data object NetDiskDownloads : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen

    @Serializable
    data object Novel : Screen
}
