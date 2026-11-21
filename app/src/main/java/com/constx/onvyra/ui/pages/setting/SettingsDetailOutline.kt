package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.constx.onvyra.ui.components.ui.CardGroupScope

private data class SettingsDetailItem(
    val modifier: Modifier,
    val onClick: (() -> Unit)? = null,
    val overline: (@Composable () -> Unit)? = null,
    val headline: (@Composable () -> Unit)? = null,
    val supporting: (@Composable () -> Unit)? = null,
    val leading: (@Composable () -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null,
    val colors: ListItemColors? = null,
    val form: (@Composable ColumnScope.() -> Unit)? = null,
)

/** Accepts the existing DSL, including switchItem, without altering controls or their callbacks. */
private class SettingsDetailScope : CardGroupScope {
    val items = mutableListOf<SettingsDetailItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        headlineContent: @Composable () -> Unit,
    ) {
        items += SettingsDetailItem(modifier, onClick, overlineContent, headlineContent,
            supportingContent, leadingContent, trailingContent, colors)
    }

    override fun formItem(
        modifier: Modifier,
        headlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        items += SettingsDetailItem(modifier = modifier, headline = headlineContent,
            supporting = supportingContent, leading = leadingContent, trailing = trailingContent,
            form = content)
    }
}

/** Detail-page counterpart of SettingsHomeGroup. Modifier/title order matches CardGroup. */
@Composable
internal fun SettingsDetailGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = SettingsDetailScope().apply(content)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    Box(Modifier.padding(horizontal = 4.dp)) { title() }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column {
                scope.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = if (item.leading != null) 52.dp else 16.dp, end = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                    val rowModifier = item.modifier.fillMaxWidth()
                        .then(item.colors?.let { Modifier.background(it.containerColor) } ?: Modifier)
                        .then(item.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    val form = item.form
                    if (form == null) {
                        SettingsDetailRow(item, rowModifier.padding(horizontal = 16.dp))
                    } else {
                        Column(
                            modifier = rowModifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (item.headline != null || item.supporting != null || item.leading != null || item.trailing != null) {
                                SettingsDetailRow(item, Modifier.fillMaxWidth(), formHeader = true)
                            }
                            form()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDetailRow(item: SettingsDetailItem, modifier: Modifier, formHeader: Boolean = false) {
    val colors = item.colors
    Row(
        modifier = modifier.heightIn(min = if (formHeader) 0.dp else if (item.supporting != null) 64.dp else 52.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.leading?.let { leading ->
            CompositionLocalProvider(LocalContentColor provides (colors?.leadingContentColor ?: MaterialTheme.colorScheme.onSurface)) {
                Box(contentAlignment = Alignment.Center) { leading() }
            }
        }
        Column(
            modifier = Modifier.weight(1f).then(if (formHeader) Modifier else Modifier.padding(vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item.overline?.let { overline ->
                CompositionLocalProvider(LocalContentColor provides (colors?.overlineContentColor ?: MaterialTheme.colorScheme.onSurfaceVariant)) {
                    ProvideTextStyle(MaterialTheme.typography.labelSmall, overline)
                }
            }
            item.headline?.let { headline ->
                CompositionLocalProvider(LocalContentColor provides (colors?.contentColor ?: MaterialTheme.colorScheme.onSurface)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyLarge, headline)
                }
            }
            item.supporting?.let { supporting ->
                CompositionLocalProvider(LocalContentColor provides (colors?.supportingContentColor ?: MaterialTheme.colorScheme.onSurfaceVariant)) {
                    // Keep the caller's text untruncated, including accessibility and setup instructions.
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, supporting)
                }
            }
        }
        item.trailing?.let { trailing ->
            CompositionLocalProvider(
                LocalContentColor provides (colors?.trailingContentColor ?: MaterialTheme.colorScheme.onSurfaceVariant),
                LocalMinimumInteractiveComponentSize provides 48.dp,
            ) {
                // Keep the original Switch callback/enabled state and its complete 48 dp touch target.
                Box(Modifier.sizeIn(minHeight = 48.dp), contentAlignment = Alignment.Center) { trailing() }
            }
        }
    }
}

/** Signature-compatible icon adapter; setting detail pages use the landing page's plain 24 dp icons. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SettingsDetailIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Icon(icon, contentDescription = null, modifier = modifier.size(24.dp), tint = contentColor)
}

@Composable
internal fun SettingsDetailValue(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** Compact switch appearance with a separate full-size touch target. */
internal fun CardGroupScope.settingsDetailSwitchItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
) = item(
    modifier = modifier,
    onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
    supportingContent = supportingContent,
    leadingContent = leadingContent,
    headlineContent = headlineContent,
    trailingContent = {
        Box(
            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
            contentAlignment = Alignment.Center,
        ) {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.scale(0.82f))
        }
    },
)
