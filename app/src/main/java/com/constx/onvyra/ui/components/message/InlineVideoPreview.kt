package com.constx.onvyra.ui.components.message

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.constx.onvyra.R
import com.constx.onvyra.data.media.VideoLink
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import com.constx.onvyra.data.media.VideoPreviewResolver
import com.constx.onvyra.utils.openUrl
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlin.math.roundToInt

private const val AUTOMATIC_SOURCE_LIMIT = 6
private const val AUTOMATIC_SOURCE_BUDGET_MS = 120_000L
private const val AUTOMATIC_SOURCE_EXHAUSTED = "已尝试可用片源，可让AI继续搜索其他来源"

private data class VideoGestureSeekPreview(
    val source: VideoPlaybackSource,
    val startMs: Long,
    val targetMs: Long,
    val durationMs: Long,
)

/** A message-local player with controls contained inside its video viewport. */
@Composable
internal fun InlineVideoPreview(
    link: VideoLink,
    modifier: Modifier = Modifier,
    resolveVideo: (suspend (VideoLink, Boolean) -> VideoPlaybackSource)? = null,
    initiallyEnhanced: Boolean = false,
    allowPlatformPlayer: Boolean = true,
    originalLink: String? = link.url,
    loadSourceOptions: (suspend () -> List<VideoSourceOption>)? = null,
    resolveSourceOption: (suspend (String) -> VideoPlaybackSource)? = null,
    onSourceSelected: ((String) -> Unit)? = null,
    playbackHttpClient: OkHttpClient? = null,
    onSourcesExhausted: (() -> Unit)? = null,
    sourceOptionsUpdates: Flow<List<VideoSourceOption>>? = null,
    episodeOptions: List<VideoEpisodeOption> = emptyList(),
    episodesLoading: Boolean = false,
    episodesError: Boolean = false,
    resolveEpisode: (suspend (Int) -> VideoPlaybackSource)? = null,
    onEpisodeSelected: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val httpClient = playbackHttpClient ?: if (resolveVideo == null) koinInject<OkHttpClient>() else remember { OkHttpClient() }
    val resolver = remember(context, httpClient) { VideoPreviewResolver(context.applicationContext, httpClient) }
    val controller = remember(link.url, context, httpClient) { VideoPlaybackController(context, httpClient) }
    val controls = remember(controller) { VideoControlsState() }
    val scope = rememberCoroutineScope()
    val currentSourceLoader by rememberUpdatedState(loadSourceOptions)
    val currentSourceResolver by rememberUpdatedState(resolveSourceOption)
    val currentSourceSelected by rememberUpdatedState(onSourceSelected)
    val currentSourcesExhausted by rememberUpdatedState(onSourcesExhausted)
    val currentEpisodeResolver by rememberUpdatedState(resolveEpisode)
    val currentEpisodeSelected by rememberUpdatedState(onEpisodeSelected)
    var recoveryNotified by remember(controller) { mutableStateOf(false) }
    var enhanced by remember(link.url, initiallyEnhanced) { mutableStateOf(initiallyEnhanced) }
    var attempt by remember(link.url) { mutableStateOf(0) }
    var resolving by remember(link.url) { mutableStateOf(true) }
    var resolutionError by remember(link.url) { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    var sourceOptions by remember(controller) { mutableStateOf(emptyList<VideoSourceOption>()) }
    var sourceOptionsLoading by remember(controller) { mutableStateOf(false) }
    var sourceOptionsError by remember(controller) { mutableStateOf(false) }
    var sourceOptionsAttempt by remember(controller) { mutableStateOf(0) }
    var sourceMenuRequested by remember(controller) { mutableStateOf(false) }
    val sharedSourceLookup = remember(controller, sourceOptionsUpdates, sourceOptionsAttempt) {
        sourceOptionsUpdates?.let { SharedVideoSourceLookup(scope, it) }
    }
    val currentSourceOptionsUpdates by rememberUpdatedState(sharedSourceLookup?.updates)
    DisposableEffect(sharedSourceLookup) {
        onDispose { sharedSourceLookup?.cancel() }
    }
    var switchingSource by remember(controller) { mutableStateOf(false) }
    var sourceSwitchError by remember(controller) { mutableStateOf<String?>(null) }
    var sourceSwitchJob by remember(controller) { mutableStateOf<Job?>(null) }
    var sourceSwitchGeneration by remember(controller) { mutableStateOf(0) }
    var resolutionJob by remember(controller) { mutableStateOf<Job?>(null) }
    var selectedSourceId by remember(controller) { mutableStateOf<String?>(null) }
    var automaticSwitching by remember(controller) { mutableStateOf(false) }
    var automaticExhausted by remember(controller) { mutableStateOf(false) }
    var gestureSeek by remember(controller) { mutableStateOf<VideoGestureSeekPreview?>(null) }
    val automaticallyTriedIds = remember(controller) { mutableSetOf<String>() }
    val failedSourceIds = remember(controller) { mutableSetOf<String>() }
    val sourceOptionsMutex = remember(controller) { Mutex() }
    val rootView = LocalView.current.rootView
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    LaunchedEffect(resumed, resolving, switchingSource, controller.source, controller.fullscreen) {
        // Leaving the viewport or replacing media must never commit a stale gesture.
        gestureSeek = null
        controls.dragging = false
        if (!resumed || resolving || switchingSource) controller.endTemporarySpeed()
    }

    LaunchedEffect(link.url, enhanced, attempt) {
        val thisJob = currentCoroutineContext()[Job]
        val generation = withContext(Dispatchers.Main.immediate) {
            sourceSwitchJob?.cancel()
            val nextGeneration = ++sourceSwitchGeneration
            resolutionJob = thisJob
            switchingSource = false
            automaticSwitching = false
            automaticExhausted = false
            automaticallyTriedIds.clear()
            failedSourceIds.clear()
            sourceSwitchError = null
            resolving = true
            resolutionError = null
            controller.pause()
            controls.interact()
            nextGeneration
        }
        try {
            val source = resolveVideo?.invoke(link, enhanced) ?: resolver.resolve(link, enhanced)
            withContext(Dispatchers.Main.immediate) {
                if (generation == sourceSwitchGeneration) { controller.load(source); controls.interact() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withContext(Dispatchers.Main.immediate) {
                if (generation == sourceSwitchGeneration) {
                    resolutionError = error.message?.take(160) ?: "视频解析失败，请重试或打开原链接"
                    controls.interact()
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (generation == sourceSwitchGeneration) { resolving = false; resolutionJob = null }
            }
        }
    }
    DisposableEffect(controller) {
        // The webpage still receives its own touch, including play and verification controls.
        controller.onVideoTouched = {
            if (controller.source?.kind == VideoPlaybackKind.EMBEDDED) {
                controller.refreshPlaybackState()
                controls.toggle()
            }
        }
        onDispose {
            controller.onVideoTouched = null
            controller.release()
        }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    resumed = false
                    controller.setActive(false)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, visible, controller.fullscreen) {
        withContext(Dispatchers.Main.immediate) { controller.setActive(resumed && (visible || controller.fullscreen)) }
    }
    LaunchedEffect(resumed, visible, controller.fullscreen, controls.shown, controller.source?.kind) {
        // A webpage can receive video metadata after its controls have hidden.
        val webpage = controller.source?.kind == VideoPlaybackKind.EMBEDDED
        if (resumed && (visible || controller.fullscreen) && (controls.shown || webpage)) {
            withContext(Dispatchers.Main.immediate) {
                while (isActive) {
                    controller.refreshPlaybackState()
                    delay(if (controls.shown) 500 else 2_000)
                }
            }
        }
    }
    LaunchedEffect(resolutionError, controller.error) {
        if (resolutionError != null || controller.error != null) controls.interact()
    }
    LaunchedEffect(controls.shown, controls.menu, controls.dragging, controls.interactionVersion,
        resolving, switchingSource, resolutionError, controller.error, controller.source, controller.view, automaticExhausted,
        controller.temporaryFastPlayback) {
        withContext(Dispatchers.Main.immediate) {
            if (controls.shown && controls.menu == null && !controls.dragging && !resolving && !switchingSource &&
                resolutionError == null && controller.error == null && !automaticExhausted && !controller.temporaryFastPlayback && controller.source != null && controller.view != null
            ) {
                delay(3_500)
                controls.shown = false
            }
        }
    }
    suspend fun loadOptions(): List<VideoSourceOption> = sourceOptionsMutex.withLock {
        val loader = currentSourceLoader ?: return@withLock emptyList()
        withContext(Dispatchers.Main.immediate) { sourceOptionsLoading = true; sourceOptionsError = false }
        try {
            // The catalog/discovery callback owns its IO dispatcher. Keep immediate cached
            // menus immediate rather than scheduling an unnecessary background hop.
            val options = loader().distinctBy { it.id }
            withContext(Dispatchers.Main.immediate) {
                sourceOptions = options.map { option ->
                    if (selectedSourceId != null) option.copy(selected = option.id == selectedSourceId) else option
                }
                if (selectedSourceId == null) selectedSourceId = options.firstOrNull { it.selected }?.id
            }
            sourceOptions
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            withContext(Dispatchers.Main.immediate) { sourceOptionsError = true }
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { sourceOptionsLoading = false }
        }
    }

    LaunchedEffect(controls.menu) {
        if (controls.menu in setOf(VideoControlsMenu.SOURCE, VideoControlsMenu.QUALITY)) sourceMenuRequested = true
    }
    // Keep one bounded lookup alive when switching between the two menus. A
    // menu animation must not cancel and restart a hundred-source search.
    LaunchedEffect(sourceMenuRequested, sourceOptionsAttempt, sharedSourceLookup) {
        if (sourceMenuRequested && currentSourceLoader != null) {
            if (sharedSourceLookup != null) {
                withContext(Dispatchers.Main.immediate) { sourceOptionsLoading = true; sourceOptionsError = false }
                try {
                    sharedSourceLookup.updates.collect { options ->
                            withContext(Dispatchers.Main.immediate) {
                                sourceOptions = options.map { option ->
                                    option.copy(selected = selectedSourceId?.let { it == option.id } ?: option.selected)
                                }
                            }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { withContext(Dispatchers.Main.immediate) { sourceOptionsError = true } }
                finally { withContext(NonCancellable + Dispatchers.Main.immediate) { sourceOptionsLoading = false } }
            } else {
                try { loadOptions() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The menu exposes its own retry action. */ }
            }
        }
    }

    suspend fun switchSource(id: String, generation: Int) {
        com.constx.onvyra.utils.UiCrashBreadcrumbs.mark(com.constx.onvyra.utils.UiCrashBreadcrumbs.Phase.VIDEO_SOURCE_SWITCH_BEGIN)
        val resolve = currentSourceResolver ?: error("No source resolver")
        val next = withContext(Dispatchers.Default) { resolve(id) }
        withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            if (generation != sourceSwitchGeneration) throw CancellationException("Source choice was superseded")
            controller.refreshPlaybackState()
            // Resolve first: failed lookups must leave a usable old stream completely intact.
            val position = controller.positionMs
            val intendedPlayback = controller.playbackRequested
            controller.load(next, startPositionMs = position, playWhenReady = intendedPlayback)
            selectedSourceId = id
            currentSourceSelected?.invoke(id)
            sourceOptions = sourceOptions.map { it.copy(selected = it.id == id) }
            resolutionError = null
            sourceSwitchError = null
            controls.interact()
            com.constx.onvyra.utils.UiCrashBreadcrumbs.mark(com.constx.onvyra.utils.UiCrashBreadcrumbs.Phase.VIDEO_SOURCE_SWITCH_COMMIT)
        }
    }

    val chooseSource: (String) -> Unit = { id ->
        sourceSwitchJob?.cancel()
        resolutionJob?.cancel()
        val generation = ++sourceSwitchGeneration
        resolving = false
        switchingSource = true
        automaticSwitching = false
        automaticExhausted = false
        sourceSwitchError = null
        controls.interact()
        sourceSwitchJob = scope.launch {
            try { switchSource(id, generation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                com.constx.onvyra.utils.UiCrashBreadcrumbs.mark(com.constx.onvyra.utils.UiCrashBreadcrumbs.Phase.VIDEO_SOURCE_SWITCH_FAILED)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == sourceSwitchGeneration) {
                        failedSourceIds += id
                        sourceSwitchError = "切换失败，请选择其他片源"
                        controls.interact()
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (generation == sourceSwitchGeneration) switchingSource = false
                }
            }
        }
    }

    val chooseEpisode: (Int) -> Unit = { number ->
        sourceSwitchJob?.cancel()
        resolutionJob?.cancel()
        val generation = ++sourceSwitchGeneration
        resolving = false
        switchingSource = true
        automaticSwitching = false
        automaticExhausted = false
        sourceSwitchError = null
        controls.interact()
        sourceSwitchJob = scope.launch {
            try {
                val resolve = currentEpisodeResolver ?: error("No episode resolver")
                val next = withContext(Dispatchers.Default) { resolve(number) }
                withContext(Dispatchers.Main.immediate) {
                    currentCoroutineContext().ensureActive()
                    if (generation != sourceSwitchGeneration) throw CancellationException("Episode choice was superseded")
                    // A new episode starts at zero; quality/source changes above keep the old position.
                    controller.refreshPlaybackState()
                    val play = controller.playbackRequested || controller.playbackEnded
                    controller.load(next, startPositionMs = 0L, playWhenReady = play)
                    selectedSourceId = null
                    sourceOptions = emptyList()
                    failedSourceIds.clear()
                    automaticallyTriedIds.clear()
                    currentEpisodeSelected?.invoke(number)
                    resolutionError = null
                    controls.interact()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (generation == sourceSwitchGeneration) {
                        sourceSwitchError = "该集暂时无法播放，当前视频已保留"
                        controls.interact()
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (generation == sourceSwitchGeneration) switchingSource = false
                }
            }
        }
    }

    LaunchedEffect(resolving, switchingSource, resolutionError, controller.error, automaticExhausted,
        selectedSourceId, sourceSwitchGeneration,
        loadSourceOptions != null && resolveSourceOption != null) {
        withContext(Dispatchers.Main.immediate) {
            val nativeFailure = controller.error != null && controller.source?.kind == VideoPlaybackKind.DIRECT
            if (resolving || switchingSource || automaticExhausted ||
                (resolutionError == null && !nativeFailure) || currentSourceLoader == null || currentSourceResolver == null
            ) return@withContext
            selectedSourceId?.let { failedSourceIds += it }
            sourceSwitchJob?.cancel()
            val generation = ++sourceSwitchGeneration
            switchingSource = true
            automaticSwitching = true
            sourceSwitchError = null
            controls.interact()
            // This scope outlives the error effect: load() clears the error while the same job
            // must still wait for decoding, or advance to the next source if decoding fails.
            sourceSwitchJob = scope.launch(Dispatchers.Main.immediate) {
                var recovered = false
                try {
                    withTimeoutOrNull(AUTOMATIC_SOURCE_BUDGET_MS) {
                        suspend fun tryOptions(options: List<VideoSourceOption>): Boolean {
                            options.filter { it.selected }.forEach { failedSourceIds += it.id }
                            for (option in options) {
                                currentCoroutineContext().ensureActive()
                                if (automaticallyTriedIds.size >= AUTOMATIC_SOURCE_LIMIT) break
                                if (option.selected || option.id == selectedSourceId || option.id in failedSourceIds ||
                                    option.id in automaticallyTriedIds) continue
                                automaticallyTriedIds += option.id
                                val loaded = try {
                                    withTimeoutOrNull(20_000) { switchSource(option.id, generation); true } == true
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { false }
                                if (!loaded) { failedSourceIds += option.id; continue }
                                val ready = withTimeoutOrNull(15_000) {
                                    while (controller.error == null && controller.player?.playbackState != Player.STATE_READY) delay(100)
                                    controller.error == null && controller.player?.playbackState == Player.STATE_READY
                                } == true
                                if (ready) return true
                                failedSourceIds += option.id
                            }
                            return false
                        }
                        val updates = currentSourceOptionsUpdates
                        if (updates != null) {
                            // A cached snapshot may contain only the failed current source.
                            // Wait for later matching catalogs instead of declaring it exhausted.
                            try {
                                updates.firstOrNull { options ->
                                    sourceOptions = options.map { option ->
                                        option.copy(selected = selectedSourceId?.let { it == option.id } ?: option.selected)
                                    }
                                    recovered = tryOptions(sourceOptions)
                                    recovered || automaticallyTriedIds.size >= AUTOMATIC_SOURCE_LIMIT
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { sourceOptionsError = true }
                        } else {
                            var options = sourceOptions
                            var optionLoads = 0
                            while (options.isEmpty() && optionLoads < 2) {
                                optionLoads++
                                options = try { withTimeoutOrNull(25_000) { loadOptions() }.orEmpty() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { emptyList() }
                            }
                            recovered = tryOptions(options)
                        }
                    }
                    if (!recovered && generation == sourceSwitchGeneration) {
                        automaticExhausted = true
                        sourceSwitchError = null
                        controller.pause()
                        controller.player?.stop()
                        controls.interact()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        if (generation == sourceSwitchGeneration) {
                            switchingSource = false
                            automaticSwitching = false
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(automaticExhausted, resolving, resolutionError, controller.error, resumed, visible, controller.fullscreen, currentSourcesExhausted) {
        val failedWithoutCatalog = currentSourceLoader == null && !resolving &&
            (resolutionError != null || controller.error != null)
        if ((automaticExhausted || failedWithoutCatalog) && resumed && (visible || controller.fullscreen) && !recoveryNotified && currentSourcesExhausted != null) {
            recoveryNotified = true
            currentSourcesExhausted?.invoke()
        }
    }

    @Composable
    fun PlaybackViewport(viewportModifier: Modifier, fullscreenWindow: Window? = null) {
        val screenAdjustment = remember(context, fullscreenWindow) {
            fullscreenWindow?.let { VideoScreenAdjustment(context, it) }
        }
        var screenPreview by remember(screenAdjustment) { mutableStateOf<VideoScreenAdjustmentPreview?>(null) }
        DisposableEffect(screenAdjustment) {
            onDispose {
                // A closing dialog should restore its own brightness, without a system setting write.
                try { screenAdjustment?.restoreWindowBrightness() } catch (_: Exception) { }
            }
        }
        Box(viewportModifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (controller.view != null) {
                VideoPlaybackHost(controller, Modifier.fillMaxSize()) {
                    controller.refreshPlaybackState()
                    controls.toggle()
                }
            }
            if (controller.view == null || controller.source?.kind != VideoPlaybackKind.EMBEDDED) {
                Box(Modifier.fillMaxSize().testTag("video-tap-surface")
                    .semantics {
                        contentDescription = "显示或隐藏播放控制"
                        onClick { controller.refreshPlaybackState(); controls.toggle(); true }
                    }
                    .videoPictureGestures(
                        canTransform = controller.source?.kind == VideoPlaybackKind.DIRECT,
                        onTap = {
                            // A confirmed single tap can arrive after a control button opened a
                            // menu during the double-tap interval. Do not dismiss that new menu.
                            if (controls.menu == null && !controls.dragging) {
                                controller.refreshPlaybackState()
                                controls.toggle()
                            }
                        },
                        onDoubleTap = {
                            if (controls.menu == null && !resolving && !switchingSource && controller.error == null) {
                                controller.togglePlay()
                                controls.interact()
                            }
                        },
                        onTransform = controller::transformPicture,
                        onTransformInteraction = { controls.interact() },
                        canSeek = !resolving && !switchingSource && controller.canSeek,
                        onSeekStart = {
                            controller.refreshPlaybackState()
                            val current = controller.source
                            if (controls.menu == null && !resolving && !switchingSource && resumed && controller.canSeek &&
                                current?.kind == VideoPlaybackKind.DIRECT && controller.durationMs > 0) {
                                controller.endTemporarySpeed()
                                gestureSeek = VideoGestureSeekPreview(current, controller.positionMs, controller.positionMs, controller.durationMs)
                                controls.dragging = true
                                controls.interact()
                                true
                            } else false
                        },
                        onSeekProgress = { fraction ->
                            gestureSeek?.let { preview ->
                                gestureSeek = preview.copy(targetMs = videoSwipeSeekTarget(preview.startMs, preview.durationMs, fraction))
                            }
                        },
                        onSeekEnd = { commit ->
                            val preview = gestureSeek
                            gestureSeek = null
                            controls.dragging = false
                            if (commit && preview != null && controller.source === preview.source && resumed &&
                                !resolving && !switchingSource && !controller.released) controller.seekTo(preview.targetMs)
                            controls.interact()
                        },
                        onHoldStart = {
                            (controls.menu == null && !resolving && !switchingSource && controller.beginTemporarySpeed()).also {
                                if (it) controls.interact()
                            }
                        },
                        onHoldEnd = { controller.endTemporarySpeed(); controls.interact() },
                        canAdjustVertical = controller.fullscreen && screenAdjustment != null,
                        onVerticalStart = { brightness ->
                            if (controls.menu == null && controller.fullscreen && resumed && screenAdjustment != null) {
                                controller.endTemporarySpeed()
                                screenPreview = try { screenAdjustment.begin(brightness) } catch (_: Exception) { null }
                                controls.interact()
                                screenPreview != null
                            } else false
                        },
                        onVerticalProgress = { delta ->
                            screenPreview?.let { preview ->
                                screenPreview = try { screenAdjustment?.update(preview, delta) } catch (_: Exception) { null }
                            }
                        },
                        onVerticalEnd = { screenPreview = null; controls.interact() },
                    ))
            }
            when {
                resolving -> VideoLoading(if (enhanced) "正在解析视频…" else "正在加载视频…")
                automaticSwitching -> VideoLoading("正在尝试其他片源…")
                resolutionError != null || controller.error != null || automaticExhausted -> Column(
                    Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 32.dp, bottom = 72.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (automaticExhausted) AUTOMATIC_SOURCE_EXHAUSTED else resolutionError ?: controller.error.orEmpty(), color = Color.White,
                        modifier = if (automaticExhausted) Modifier.testTag("video-auto-source-exhausted") else Modifier,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2)
                    TextButton(onClick = { attempt++; controls.interact() }, modifier = Modifier.testTag("video-retry")) { Text("重试") }
                }
                controller.loading -> VideoLoading("正在缓冲…")
            }
            VideoControlsOverlay(
                controller, controls,
                title = controller.source?.title ?: link.platform.displayName,
                enabled = !resolving && !automaticExhausted && resolutionError == null && controller.source != null && controller.error == null,
                hasSources = loadSourceOptions != null && resolveSourceOption != null,
                sourceOptions = sourceOptions,
                sourceOptionsLoading = sourceOptionsLoading,
                sourceOptionsError = sourceOptionsError,
                switchingSource = switchingSource,
                sourceSwitchError = sourceSwitchError,
                enhancedLabel = if (!allowPlatformPlayer) "重新解析" else if (enhanced) "平台播放器" else "增强解析",
                canOpenOriginal = originalLink != null,
                onRetrySources = { sourceOptionsAttempt++ },
                onChooseSource = chooseSource,
                episodeOptions = episodeOptions,
                episodesLoading = episodesLoading,
                episodesError = episodesError,
                onChooseEpisode = if (resolveEpisode == null) null else chooseEpisode,
                onEnhanced = { enhanced = if (allowPlatformPlayer) !enhanced else true; attempt++ },
                onOpenOriginal = { originalLink?.let(context::openUrl) },
                gestureSeekPositionMs = gestureSeek?.targetMs,
                modifier = Modifier.fillMaxSize().then(
                    if (controller.fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier,
                ),
            )
            gestureSeek?.let { preview ->
                Surface(color = Color.Black.copy(alpha = .78f), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.align(Alignment.Center).testTag("video-gesture-seek")) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val delta = preview.targetMs - preview.startMs
                        Text("${if (delta >= 0) "快进 +" else "后退 −"}${formatVideoTime(kotlin.math.abs(delta))}", color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                        Text("${formatVideoTime(preview.targetMs)} / ${formatVideoTime(preview.durationMs)}", color = Color.White,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (controller.temporaryFastPlayback) Surface(color = Color.Black.copy(alpha = .72f), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 38.dp).testTag("video-gesture-fast")) {
                Text("${controller.speed.toString().removeSuffix(".0")}× 加速中", color = Color.White,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            screenPreview?.let { preview ->
                Surface(color = Color.Black.copy(alpha = .78f), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.align(Alignment.Center).testTag(if (preview.brightness) "video-gesture-brightness" else "video-gesture-volume")) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${if (preview.brightness) "亮度" else "音量"} ${(preview.fraction * 100).roundToInt()}%", color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.padding(top = 6.dp).size(width = 96.dp, height = 3.dp).background(Color.White.copy(alpha = .25f))) {
                            Box(Modifier.fillMaxWidth(preview.fraction.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                        }
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("inline-video-preview")
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    val window = Rect(0f, 0f, rootView.width.toFloat(), rootView.height.toFloat())
                    visible = !bounds.isEmpty && bounds.overlaps(window)
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The message owns one fixed window. Metadata, errors, source selection and
                // control visibility only change its contents, never its height or chat position.
                val viewport = Modifier.fillMaxWidth().height(maxWidth / DEFAULT_VIDEO_ASPECT_RATIO)
                if (!controller.fullscreen) PlaybackViewport(viewport)
                else Box(viewport.background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("正在全屏播放", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // This is message-owned text, not model-generated copy or a claim that an ad
        // was detected. Keep it outside the fixed viewport and its transient controls.
        Text(
            text = stringResource(R.string.movie_ad_fraud_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                .testTag("video-ad-fraud-notice"),
        )
    }
    if (controller.fullscreen) {
        VideoFullscreenWindow(
            aspectRatio = controller.displayAspectRatio,
            onDismiss = { controller.setFullscreen(false); controls.interact() },
        ) { window ->
            PlaybackViewport(Modifier.fillMaxSize().testTag("video-fullscreen-player"), window)
        }
    }
}

@Composable
private fun VideoPlaybackHost(controller: VideoPlaybackController, modifier: Modifier, onCustomWebTouch: () -> Unit) {
    AndroidView(
        factory = { context -> VideoHostFrame(context).apply { controller.attachTo(this) } },
        modifier = modifier,
        update = { host ->
            controller.attachTo(host)
            host.customWebViewActive = controller.source?.kind == VideoPlaybackKind.EMBEDDED &&
                host.childCount > 0 && host.getChildAt(0) !== controller.view
            host.onCustomWebTap = onCustomWebTouch
        },
        onRelease = { host -> controller.detachFrom(host) },
    )
}

/** Observe Chromium's custom fullscreen child without consuming its own playback controls. */
private class VideoHostFrame(context: Context) : FrameLayout(context) {
    var customWebViewActive = false
    var onCustomWebTap: (() -> Unit)? = null
    private val tapSlop = ViewConfiguration.get(context).scaledTouchSlop * 2
    private var downX = 0f
    private var downY = 0f
    private var multiplePointers = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y; multiplePointers = false }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) multiplePointers = true
        val handled = super.dispatchTouchEvent(event)
        if (customWebViewActive && !multiplePointers && event.actionMasked == MotionEvent.ACTION_UP &&
            kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) <= tapSlop &&
            event.eventTime - event.downTime <= 500
        ) onCustomWebTap?.invoke()
        return handled || customWebViewActive
    }
}

@Composable
private fun VideoLoading(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}
