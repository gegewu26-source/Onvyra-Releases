package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.constx.onvyra.ui.components.ui.PagerTab
import com.constx.onvyra.ui.theme.LocalDarkMode

/** Text-only pills scoped to these two service settings pages. Swiping keeps the same pager state. */
@Composable
internal fun CompactMediaSpeechTabs(
    pagerState: PagerState,
    tabs: List<PagerTab>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val darkMode = LocalDarkMode.current
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = pagerState.targetPage == index
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) SettingsCompactAccentColor()
                    else if (darkMode) MaterialTheme.colorScheme.surfaceContainerHighest else Color(0xFFEFEFEF),
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.selectable(
                    selected = selected,
                    role = Role.Tab,
                    onClick = { if (!selected) scope.launch { pagerState.animateScrollToPage(index) } },
                ),
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 24.dp, vertical = 10.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun CompactMediaSpeechBadge(text: String) {
    Surface(
        color = SettingsCompactAccentColor().copy(alpha = 0.09f),
        contentColor = SettingsCompactAccentColor(),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}
