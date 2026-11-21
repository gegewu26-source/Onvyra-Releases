package com.constx.onvyra.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import com.constx.onvyra.R
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.utils.toFixed
import kotlin.time.Clock

/** Provider-reported usage and total turn time, including any tool calls. */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (!LocalSettings.current.displaySetting.showTokenUsage || message.role != MessageRole.ASSISTANT) return

    var now by remember(message.id) {
        mutableStateOf(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()))
    }
    LaunchedEffect(message.id, loading) {
        while (loading && isActive) {
            now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            delay(250)
        }
    }
    val stats = message.generationStats(now = now, loading = loading)

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                val usage = message.usage
                if (usage != null) {
                    if (usage.promptTokens > 0 || usage.completionTokens > 0 || usage.totalTokens == 0) {
                        StatsItem(
                            icon = { Icon(HugeIcons.Upload02, null, modifier = Modifier.size(12.dp)) },
                            content = { Text(stringResource(R.string.chat_stats_input, usage.promptTokens)) },
                        )
                        StatsItem(
                            icon = { Icon(HugeIcons.Download04, null, modifier = Modifier.size(12.dp)) },
                            content = { Text(stringResource(R.string.chat_stats_output, usage.completionTokens)) },
                        )
                    }
                    Text(stringResource(R.string.chat_stats_total, stats.totalTokens ?: 0L))
                    if (usage.cachedTokens > 0) {
                        Text(stringResource(R.string.chat_stats_cached, usage.cachedTokens))
                    }
                } else if (stats.showUsageNotice) {
                    Text(stringResource(if (loading) R.string.chat_stats_usage_pending else R.string.chat_stats_usage_missing))
                }

                stats.elapsedMillis?.let { elapsed ->
                    StatsItem(
                        icon = { Icon(HugeIcons.Clock02, null, modifier = Modifier.size(12.dp)) },
                        content = {
                            val label = if (loading) R.string.chat_stats_running else R.string.chat_stats_duration
                            Text(stringResource(label, (elapsed / 1000.0).toFixed(1)))
                        },
                    )
                }
                stats.averageTokensPerSecond?.let { speed ->
                    StatsItem(
                        icon = { Icon(HugeIcons.Zap, null, modifier = Modifier.size(12.dp)) },
                        content = { Text(stringResource(R.string.chat_stats_speed, speed.toFixed(1))) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        icon()
        content()
    }
}
