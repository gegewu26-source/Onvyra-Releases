package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import com.constx.onvyra.R
import com.constx.onvyra.ui.context.LocalNavController

// The six service landing pages deliberately share the user's compact outline design.
// The application-wide CardGroup and theme are left intact for other screens.
@Composable
internal fun SettingsCompactPageColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFFAFAFA)
    else MaterialTheme.colorScheme.background

@Composable
internal fun SettingsCompactCardColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color.White
    else MaterialTheme.colorScheme.surface

@Composable
internal fun SettingsCompactAccentColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFF0963E7)
    else MaterialTheme.colorScheme.primary

@Composable
internal fun SettingsCompactSoftColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFF3F3F3)
    else MaterialTheme.colorScheme.surfaceContainerHighest

@Composable
internal fun SettingsCompactTopBar(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val navigator = LocalNavController.current
    CenterAlignedTopAppBar(
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        },
        navigationIcon = {
            IconButton(
                onClick = { navigator.popBackStack() },
                modifier = Modifier.padding(start = 8.dp).size(44.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), CircleShape),
            ) {
                Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = SettingsCompactPageColor(),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
internal fun SettingsCompactGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = SettingsCompactCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsCompactSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SettingsCompactDivider(startIndent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
