package com.constx.onvyra.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Display values only. Category amounts may be estimates even when the total is provider-reported. */
data class ContextCapacityState(
    val usedTokens: Long? = null,
    val capacityTokens: Long = 0L,
    val usageEstimated: Boolean = false,
    val breakdown: List<ContextCapacityCategory> = emptyList(),
    val breakdownEstimated: Boolean = true,
    val cacheHitRate: Double? = null,
    val autoCompressionEnabled: Boolean = true,
    val changeTokens: Long? = null,
    val awaitingUsage: Boolean = false,
    val cumulativeTokens: Long? = null,
)

data class ContextCapacityCategory(
    val id: String,
    val label: String,
    val tokens: Long,
)

/** A compact context gauge with an anchored breakdown, beside the composer's task permission. */
@Composable
fun ContextCapacityIndicator(
    state: ContextCapacityState,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime
    val safeDrawing = WindowInsets.safeDrawing
    val topInset = safeDrawing.getTop(density)
    val bottomInset = safeDrawing.getBottom(density)
    val popupPosition = remember(density, topInset, bottomInset) {
        ContextCapacityPopupPosition(
            margin = with(density) { 12.dp.roundToPx() },
            gap = with(density) { 10.dp.roundToPx() },
            topInset = topInset,
            bottomInset = bottomInset,
        )
    }
    val popupWidth = (configuration.screenWidthDp - 24).coerceIn(1, 360).dp
    // A scrollable card also fits split-screen, landscape, and large accessibility fonts.
    val popupMaxHeight = with(density) {
        (configuration.screenHeightDp.dp.toPx().toInt() - topInset - bottomInset - 24.dp.roundToPx())
            .coerceAtLeast(1).toDp()
    }
    val blue = contextCapacityBlue()
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val progress = state.contextFraction()?.coerceIn(0.0, 1.0)?.toFloat()

    LaunchedEffect(opening) {
        if (!opening) return@LaunchedEffect
        // Complete the IME handoff first, so the menu's anchor cannot jump while opening.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        withTimeoutOrNull(1_000) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
        withFrameNanos { }
        expanded = true
        opening = false
    }
    BackHandler(enabled = opening || expanded) { opening = false; expanded = false }

    Box(modifier) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .testTag("context_capacity_indicator")
                .clickable(role = Role.Button) {
                    if (opening || expanded) { opening = false; expanded = false }
                    else opening = true
                }
                .semantics {
                    contentDescription = "上下文容量"
                    stateDescription = state.accessibilityDescription()
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(24.dp).clearAndSetSemantics { }) {
                val strokeWidth = 3.dp.toPx()
                val inset = strokeWidth / 2
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                if (progress != null && progress > 0f) {
                    drawArc(
                        color = blue,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }
        }
        if (expanded) {
            Popup(
                popupPositionProvider = popupPosition,
                onDismissRequest = { expanded = false; opening = false },
                // Own the outside gesture so tapping the anchor dismisses once
                // instead of forwarding that same click and opening it again.
                // The IME has already been hidden before the popup takes focus.
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                ContextCapacityCard(
                    state = state,
                    modifier = Modifier
                        .width(popupWidth)
                        .heightIn(max = popupMaxHeight)
                        .testTag("context_capacity_popup"),
                )
            }
        }
    }
}

@Composable
private fun ContextCapacityCard(state: ContextCapacityState, modifier: Modifier = Modifier) {
    val blue = contextCapacityBlue()
    val colors = MaterialTheme.colorScheme
    val fraction = state.contextFraction()
    val progress = fraction?.coerceIn(0.0, 1.0)?.toFloat()
    val categoryTotal = state.breakdown.sumOf { it.tokens.coerceAtLeast(0L).toDouble() }
    val cacheRate = state.cacheHitRate?.takeIf { it.isFinite() && it in 0.0..1.0 }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "上下文容量",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 2,
                )
                Text(
                    text = state.capacitySummary(),
                    modifier = Modifier.weight(1f).testTag("context_capacity_summary"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Canvas(Modifier.fillMaxWidth().height(7.dp).clearAndSetSemantics { }) {
                drawRoundRect(colors.surfaceContainerHighest, cornerRadius = CornerRadius(size.height / 2))
                if (progress != null && progress > 0f) {
                    drawRoundRect(
                        blue,
                        size = Size((size.width * progress).coerceAtLeast(size.height.coerceAtMost(size.width)), size.height),
                        cornerRadius = CornerRadius(size.height / 2),
                    )
                }
            }
            Text(
                "满格对应自动压缩触发阈值；这里只统计当前上下文",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            if (state.awaitingUsage) {
                Text(
                    if (state.usedTokens != null) "接口尚未返回本次用量，暂显示上次确认值"
                    else "等待接口返回本次用量",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("context_capacity_waiting"),
                )
            }
            state.changeTokens?.takeUnless { state.awaitingUsage }?.let { change ->
                Text(
                    text = when {
                        change > 0L -> "较上次请求增加 ${formatExactContextTokens(change)} tokens"
                        change < 0L -> "较上次请求减少 ${formatExactContextTokens(-change)} tokens"
                        else -> "与上次请求相同"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.testTag("context_capacity_change"),
                )
            }
            if (!state.autoCompressionEnabled) {
                Text(
                    "自动压缩已关闭，圆环仅显示阈值参考",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (state.breakdown.isEmpty()) {
                Text(
                    if (state.awaitingUsage) "本次用量回报后显示占用分类" else "下次发送后可查看占用分类",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    state.breakdown.forEachIndexed { index, category ->
                        val count = category.tokens.coerceAtLeast(0L)
                        val share = if (categoryTotal > 0.0) count / categoryTotal else null
                        val dotColor = lerp(blue, colors.surfaceContainerHigh, (index.coerceAtMost(5) * 0.12f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                            Text(
                                text = category.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${formatExactContextTokens(count)} · ${share?.let(::formatContextPercent) ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (state.breakdownEstimated) {
                    Text(
                        "分类为本地估算",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "累计请求用量",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    state.cumulativeTokens?.let { "${formatExactContextTokens(it)} tokens" } ?: "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    modifier = Modifier.testTag("context_capacity_cumulative"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "平均缓存命中率",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    cacheRate?.let(::formatContextPercent) ?: "暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    modifier = Modifier.testTag("context_capacity_cache_rate"),
                )
            }
        }
    }
}

@Composable
private fun contextCapacityBlue(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.35f) Color(0xFF6AB0FF) else Color(0xFF1688FF)

private fun ContextCapacityState.contextFraction(): Double? =
    usedTokens?.takeIf { it >= 0L }?.takeIf { capacityTokens > 0L }?.let { it.toDouble() / capacityTokens }

internal fun ContextCapacityState.capacitySummary(): String {
    val used = usedTokens?.takeIf { it >= 0L } ?: return if (capacityTokens > 0L) {
        "${if (awaitingUsage) "等待用量回报" else "暂无数据"} / ${formatExactContextTokens(capacityTokens)}"
    } else "暂无数据 · 尚未设置容量"
    val estimate = if (awaitingUsage) "上次确认 " else if (usageEstimated) "≈" else ""
    val capacity = if (capacityTokens > 0L) formatExactContextTokens(capacityTokens) else "—"
    val percentage = contextFraction()?.let { "（${formatContextPercent(it)}）" }.orEmpty()
    return "$estimate${formatExactContextTokens(used)}/$capacity $percentage"
}

private fun ContextCapacityState.accessibilityDescription(): String {
    val used = usedTokens?.takeIf { it >= 0L }
    return buildString {
        if (used == null) append("暂无用量数据")
        else {
            if (usageEstimated) append("估算")
            append("已使用 $used tokens")
        }
        if (capacityTokens > 0L) append("，满格 $capacityTokens tokens") else append("，尚未设置容量")
        contextFraction()?.let { append("，已使用 ${formatContextPercent(it)}") }
        if (!autoCompressionEnabled) append("，自动压缩已关闭")
        if (awaitingUsage) append("，等待本次用量回报，当前为上次确认值")
        append("，点击查看占用明细")
    }
}

private fun formatContextPercent(fraction: Double): String =
    String.format(Locale.ROOT, "%.2f%%", fraction * 100.0)

internal fun formatExactContextTokens(tokens: Long): String =
    String.format(Locale.ROOT, "%,d", tokens.coerceAtLeast(0L))

/** Prefer the space above the composer, then keep the entire card inside the visible window. */
private class ContextCapacityPopupPosition(
    private val margin: Int,
    private val gap: Int,
    private val topInset: Int,
    private val bottomInset: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = margin.coerceAtMost((windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val right = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(left)
        val desiredX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else anchorBounds.left
        val top = (topInset + margin).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        val bottom = (windowSize.height - bottomInset - popupContentSize.height - margin).coerceAtLeast(top)
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val desiredY = when {
            above >= top -> above
            below <= bottom -> below
            else -> above
        }
        return IntOffset(desiredX.coerceIn(left, right), desiredY.coerceIn(top, bottom))
    }
}
