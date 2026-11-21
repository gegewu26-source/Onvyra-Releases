package com.constx.onvyra.ui.components.message

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import com.constx.onvyra.data.media.VideoPlaybackKind
import kotlin.math.roundToInt

internal data class VideoSourceOption(val id: String, val label: String, val selected: Boolean = false,
    val qualityLabel: String? = null)
internal data class VideoEpisodeOption(val number: Int, val label: String, val selected: Boolean = false)

internal val VideoControlsTopSpace = 36.dp
internal val VideoControlsBottomSpace = 86.dp

internal enum class VideoControlsMenu { SPEED, QUALITY, SOURCE, EPISODE, MORE }
private val VideoSpeedSteps = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f)

@Stable
internal class VideoControlsState {
    var shown by mutableStateOf(true)
    var menu by mutableStateOf<VideoControlsMenu?>(null)
    var dragging by mutableStateOf(false)
    var interactionVersion by mutableLongStateOf(0L)

    fun interact() { shown = true; interactionVersion++ }
    fun open(next: VideoControlsMenu) { menu = next; interact() }
    fun closeMenu() { menu = null; interact() }
    fun toggle() {
        if (menu != null) closeMenu() else { shown = !shown; interactionVersion++ }
    }
}

/** All controls are laid over the video, including the compact inline player. */
@Composable
internal fun VideoControlsOverlay(
    controller: VideoPlaybackController,
    state: VideoControlsState,
    title: String,
    enabled: Boolean,
    hasSources: Boolean,
    sourceOptions: List<VideoSourceOption>,
    sourceOptionsLoading: Boolean,
    sourceOptionsError: Boolean,
    switchingSource: Boolean,
    sourceSwitchError: String?,
    enhancedLabel: String,
    canOpenOriginal: Boolean,
    onRetrySources: () -> Unit,
    onChooseSource: (String) -> Unit,
    onEnhanced: () -> Unit,
    onOpenOriginal: () -> Unit,
    episodeOptions: List<VideoEpisodeOption> = emptyList(),
    episodesLoading: Boolean = false,
    episodesError: Boolean = false,
    onChooseEpisode: ((Int) -> Unit)? = null,
    gestureSeekPositionMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val menuAnchors = remember(controller) { mutableStateMapOf<VideoControlsMenu, Rect>() }
    var overlayOrigin by remember(controller) { mutableStateOf(Offset.Zero) }
    fun Modifier.anchor(menu: VideoControlsMenu) = onGloballyPositioned { menuAnchors[menu] = it.boundsInWindow() }
    BackHandler(enabled = state.menu != null) { state.closeMenu() }
    if (!state.shown) return
    Box(modifier.fillMaxSize().testTag("video-controls")
        .onGloballyPositioned { overlayOrigin = it.positionInWindow() }) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .78f), Color.Transparent)))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), color = Color.White, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            VideoIconAction("播放选项", Modifier.size(36.dp).testTag("video-more").anchor(VideoControlsMenu.MORE), onClick = { state.open(VideoControlsMenu.MORE) }) {
                Icon(HugeIcons.MoreVertical, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val duration = controller.durationMs.coerceAtLeast(0L)
            var scrubFraction by remember(controller) { mutableStateOf(0f) }
            val fraction = if (duration > 0) (controller.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val displayedPosition = gestureSeekPositionMs ?: if (state.dragging) (scrubFraction * duration).toLong() else controller.positionMs
            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatVideoTime(displayedPosition, duration >= 3_600_000)} / ${if (duration > 0) formatVideoTime(duration, duration >= 3_600_000) else "--:--"}",
                    Modifier.testTag("video-time"), color = Color.White, fontSize = 11.sp,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (switchingSource) Text("正在切换…", color = Color.White.copy(alpha = .8f), fontSize = 10.sp)
            }
            val slider = remember(controller) { SliderState(value = 0f, trackRange = 0f..1f) }
            if (gestureSeekPositionMs != null && duration > 0) slider.value = (gestureSeekPositionMs.toFloat() / duration).coerceIn(0f, 1f)
            else if (!state.dragging) slider.value = fraction
            val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                inactiveTrackColor = Color.Transparent, disabledInactiveTrackColor = Color.Transparent)
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 6.dp)) {
                    val middle = size.height / 2
                    drawLine(Color.White.copy(alpha = .22f), Offset(0f, middle), Offset(size.width, middle), size.height, StrokeCap.Round)
                    val buffered = if (duration > 0) (controller.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    drawLine(Color.White.copy(alpha = .42f), Offset(0f, middle), Offset(size.width * buffered, middle), size.height, StrokeCap.Round)
                }
                Slider(
                    state = slider,
                    onValueChange = { value -> state.dragging = true; scrubFraction = value; slider.value = value; state.interact() },
                    onValueChangeFinished = {
                        controller.seekTo((slider.value * duration).toLong())
                        state.dragging = false
                        state.interact()
                    },
                    enabled = enabled && controller.canSeek && duration > 0,
                    modifier = Modifier.fillMaxWidth().height(24.dp).testTag("video-seek")
                        .semantics { contentDescription = "播放进度" },
                    colors = colors,
                    thumb = { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) },
                    track = { sliderState -> SliderDefaults.Track(
                        sliderState = sliderState, modifier = Modifier.height(3.dp), colors = colors,
                        thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                    ) },
                )
            }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                VideoIconAction(if (controller.playbackEnded) "重新播放" else if (controller.playbackRequested) "暂停" else "播放", Modifier.size(36.dp).testTag("video-play"), enabled,
                    onClick = { controller.togglePlay(); state.interact() }) {
                    Icon(if (controller.playbackRequested) HugeIcons.Pause else HugeIcons.Play, null,
                        tint = Color.White.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(20.dp))
                }
                VideoTextAction("${controller.speed.toString().removeSuffix(".0")}×", "播放倍速",
                    Modifier.weight(1f).testTag("video-speed").anchor(VideoControlsMenu.SPEED), enabled) { state.open(VideoControlsMenu.SPEED) }
                VideoTextAction(controller.selectedQualityLabel, "清晰度",
                    Modifier.weight(1.35f).testTag("video-quality").anchor(VideoControlsMenu.QUALITY), enabled) { state.open(VideoControlsMenu.QUALITY) }
                if (hasSources) VideoTextAction("换源", "切换片源", Modifier.weight(1f).testTag("video-source").anchor(VideoControlsMenu.SOURCE)) {
                    state.open(VideoControlsMenu.SOURCE)
                }
                if (onChooseEpisode != null) {
                    VideoTextAction("选集", "选择剧集", Modifier.weight(1f).testTag("video-episode").anchor(VideoControlsMenu.EPISODE)) {
                        state.open(VideoControlsMenu.EPISODE)
                    }
                    val current = episodeOptions.firstOrNull { it.selected }?.number
                    val next = episodeOptions.firstOrNull { current != null && it.number > current }
                    VideoTextAction("下一集", "播放下一集", Modifier.weight(1.25f).testTag("video-next-episode"),
                        enabled && !switchingSource && next != null) {
                        next?.let { onChooseEpisode(it.number); state.closeMenu() }
                    }
                }
                VideoIconAction(if (controller.fullscreen) "退出全屏" else "全屏",
                    Modifier.size(36.dp).testTag(if (controller.fullscreen) "video-exit-fullscreen" else "video-fullscreen"),
                    true,
                    onClick = { controller.setFullscreen(!controller.fullscreen); state.closeMenu() },
                ) { VideoFullscreenIcon(controller.fullscreen) }
            }
            if (sourceSwitchError != null) Text(sourceSwitchError, color = Color.White, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("video-source-error"))
        }
        val menu = state.menu
        if (menu != null) VideoMenuPanel(
            menu = menu,
            anchor = menuAnchors[menu],
            overlayOrigin = overlayOrigin,
            selectedSpeedIndex = VideoSpeedSteps.indexOf(controller.speed).coerceAtLeast(0),
            title = when (menu) {
                VideoControlsMenu.SPEED -> "播放倍速"
                VideoControlsMenu.QUALITY -> "清晰度"
                VideoControlsMenu.SOURCE -> "切换片源"
                VideoControlsMenu.EPISODE -> "选集"
                VideoControlsMenu.MORE -> "播放选项"
            },
            onClose = state::closeMenu,
            hasCatalogQualities = sourceOptions.isNotEmpty(),
        ) {
            when (menu) {
                VideoControlsMenu.SPEED -> {
                    if (controller.source?.kind == VideoPlaybackKind.EMBEDDED && controller.webRateConfirmed != true) {
                        VideoMenuHint("网页倍速取决于平台支持")
                    }
                    VideoSpeedSteps.forEach { rate ->
                        VideoMenuRow("${rate.toString().removeSuffix(".0")}×", rate == controller.speed,
                            Modifier.testTag("video-speed-option-$rate")) {
                            controller.setSpeed(rate); state.closeMenu()
                        }
                    }
                }
                VideoControlsMenu.QUALITY -> {
                    if (controller.source?.kind == VideoPlaybackKind.EMBEDDED) {
                        VideoMenuHint("此网页的清晰度由平台播放器提供，可尝试增强解析。")
                    } else {
                        VideoMenuRow("自动", controller.selectedQualityId == null, Modifier.testTag("video-quality-auto")) { controller.setQuality(null); state.closeMenu() }
                        controller.qualityOptions.forEach { option -> VideoMenuRow(option.label, controller.selectedQualityId == option.id,
                            Modifier.testTag("video-quality-option-${option.id}")) {
                            controller.setQuality(option.id); state.closeMenu()
                        } }
                        if (controller.qualityOptions.isEmpty()) VideoMenuHint(
                            if (controller.loading) "正在读取画质…" else "当前线路未提供多档画质",
                        )
                    }
                    val catalogQualities = sourceOptions
                    if (catalogQualities.isNotEmpty()) {
                        VideoMenuHint("来源提供的画质与线路")
                        catalogQualities.forEach { option ->
                            VideoMenuRow(option.qualityLabel ?: "线路 · ${option.label}", option.selected,
                                Modifier.testTag("video-catalog-quality-${option.id}")) {
                                onChooseSource(option.id); state.closeMenu()
                            }
                        }
                    } else if (hasSources && sourceOptionsLoading) VideoMenuHint("正在读取来源线路…")
                    if (sourceOptionsError) VideoMenuRow("重新加载", false, onClick = onRetrySources)
                }
                VideoControlsMenu.EPISODE -> {
                    when {
                        episodeOptions.isNotEmpty() -> episodeOptions.forEach { option ->
                            VideoMenuRow(option.label, option.selected, Modifier.testTag("video-episode-${option.number}")) {
                                onChooseEpisode?.invoke(option.number); state.closeMenu()
                            }
                        }
                        episodesLoading -> VideoMenuHint("正在读取剧集目录…")
                        episodesError -> VideoMenuHint("剧集目录暂时不可用，请重新打开播放器重试")
                        else -> VideoMenuHint("该来源未提供其他剧集")
                    }
                }
                VideoControlsMenu.SOURCE -> {
                    if (sourceOptionsLoading && sourceOptions.isEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            Text("查找中…", color = Color.White, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    sourceOptions.forEach { option -> VideoMenuRow(option.label, option.selected,
                        modifier = Modifier.testTag("video-source-option-${option.id}"), sourceLabel = true) { onChooseSource(option.id); state.closeMenu() } }
                    if (!sourceOptionsLoading && sourceOptions.isEmpty()) VideoMenuHint(
                        if (sourceOptionsError) "片源列表加载失败" else "暂无其他匹配片源",
                    )
                    if (sourceOptionsError) VideoMenuRow("重新加载", false, onClick = onRetrySources)
                }
                VideoControlsMenu.MORE -> {
                    VideoMenuRow(enhancedLabel, false, Modifier.testTag("video-enhanced")) { onEnhanced(); state.closeMenu() }
                    if (canOpenOriginal) VideoMenuRow("打开原链接", false) { onOpenOriginal(); state.closeMenu() }
                }
            }
        }
    }
}

@Composable
private fun VideoIconAction(description: String, modifier: Modifier, enabled: Boolean = true,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier.semantics { contentDescription = description }
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun VideoTextAction(text: String, description: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.height(36.dp).semantics { contentDescription = description }
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = if (enabled) 1f else .45f), fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VideoMenuPanel(
    menu: VideoControlsMenu,
    anchor: Rect?,
    overlayOrigin: Offset,
    selectedSpeedIndex: Int,
    title: String,
    onClose: () -> Unit,
    hasCatalogQualities: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // Each list is attached to its own button, with the same narrow width in either mode.
    // Showing a menu never resizes or dims the video, and adds no separate title/close bar.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val requestedWidth = when (menu) {
            VideoControlsMenu.SPEED -> 64.dp
            VideoControlsMenu.MORE -> 88.dp
            VideoControlsMenu.QUALITY -> if (hasCatalogQualities) 200.dp else 88.dp
            VideoControlsMenu.SOURCE -> 112.dp
            VideoControlsMenu.EPISODE -> 140.dp
        }
        val panelWidth = minOf(requestedWidth, (maxWidth - 8.dp).coerceAtLeast(1.dp))
        val anchorTop = anchor?.let { with(density) { (it.top - overlayOrigin.y).toDp() } }
            ?.coerceIn(0.dp, maxHeight) ?: (maxHeight - 40.dp).coerceAtLeast(0.dp)
        val anchorCenter = anchor?.let { with(density) { (it.center.x - overlayOrigin.x).toDp() } } ?: maxWidth / 2
        val lastLeft = (maxWidth - panelWidth - 4.dp).coerceAtLeast(0.dp)
        val left = (anchorCenter - panelWidth / 2).coerceIn(minOf(4.dp, lastLeft), lastLeft)
        // The more button is in the top bar, so its existing actions open beneath it.
        val below = menu == VideoControlsMenu.MORE
        val top = anchor?.let { with(density) { (it.bottom - overlayOrigin.y).toDp() } }?.plus(4.dp) ?: 40.dp
        val bottom = (maxHeight - anchorTop + 4.dp).coerceIn(0.dp, maxHeight)
        val availableHeight = if (below) maxHeight - top - 4.dp else anchorTop - 8.dp
        val panelHeight = minOf(224.dp, availableHeight.coerceAtLeast(1.dp))
        val selectedOffset = if (menu == VideoControlsMenu.SPEED) (selectedSpeedIndex - 1).coerceAtLeast(0) else 0
        val scroll = remember(menu) { ScrollState(with(density) { (32.dp * selectedOffset).toPx().roundToInt() }) }
        Box(Modifier.fillMaxSize().testTag("video-options-dismiss")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
        Surface(color = Color(0xF51C1C1E), shape = RoundedCornerShape(6.dp),
            modifier = Modifier.align(if (below) AbsoluteAlignment.TopLeft else AbsoluteAlignment.BottomLeft)
                .absolutePadding(left = left, top = if (below) top else 0.dp, bottom = if (below) 0.dp else bottom)
                .width(panelWidth).heightIn(max = panelHeight)
                .testTag("video-options-panel")
                .semantics { paneTitle = title }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            Column(Modifier.testTag("video-options-scroll").verticalScroll(scroll).padding(vertical = 2.dp)) { content() }
        }
    }
}

@Composable
private fun VideoMenuRow(text: String, selected: Boolean, modifier: Modifier = Modifier, sourceLabel: Boolean = false, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().height(32.dp).semantics {
        this.selected = selected
        contentDescription = text
    }.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(text, Modifier.fillMaxWidth(), color = if (selected) Color(0xFF2FC8E7) else Color.White,
            fontSize = if (sourceLabel) 11.sp else 12.sp, lineHeight = if (sourceLabel) 12.sp else 14.sp,
            maxLines = if (sourceLabel) 2 else 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VideoMenuHint(text: String) {
    Text(text, color = Color.White.copy(alpha = .65f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun VideoFullscreenIcon(fullscreen: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val length = size.width * .34f
        val stroke = 1.7.dp.toPx()
        fun segment(x: Float, y: Float, horizontal: Float, vertical: Float) {
            drawLine(Color.White, Offset(x, y), Offset(x + horizontal * length, y), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(x, y), Offset(x, y + vertical * length), stroke, StrokeCap.Round)
        }
        if (!fullscreen) {
            segment(0f, 0f, 1f, 1f); segment(size.width, 0f, -1f, 1f)
            segment(0f, size.height, 1f, -1f); segment(size.width, size.height, -1f, -1f)
        } else {
            segment(length, length, -1f, -1f); segment(size.width - length, length, 1f, -1f)
            segment(length, size.height - length, -1f, 1f); segment(size.width - length, size.height - length, 1f, 1f)
        }
    }
}

internal fun formatVideoTime(milliseconds: Long, alwaysHours: Boolean = false): String {
    val total = milliseconds.coerceAtLeast(0L) / 1_000
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    val seconds = total % 60
    return if (alwaysHours || hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
}
