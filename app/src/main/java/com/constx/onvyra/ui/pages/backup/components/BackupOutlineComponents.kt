package com.constx.onvyra.ui.pages.backup.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import com.constx.onvyra.ui.components.ui.CardGroupScope

private data class BackupOutlineItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val headline: (@Composable () -> Unit)?,
    val supporting: (@Composable () -> Unit)?,
    val leading: (@Composable () -> Unit)?,
    val trailing: (@Composable () -> Unit)?,
    val form: (@Composable ColumnScope.() -> Unit)? = null,
)

private class BackupOutlineScope : CardGroupScope {
    val items = mutableListOf<BackupOutlineItem>()

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
        items += BackupOutlineItem(onClick, modifier, headlineContent, supportingContent, leadingContent, trailingContent)
    }

    override fun formItem(
        modifier: Modifier,
        headlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        items += BackupOutlineItem(null, modifier, headlineContent, supportingContent, leadingContent, trailingContent, content)
    }
}

// This presentation is confined to the backup screen; shared settings components stay unchanged.
@Composable
internal fun BackupOutlineGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    showSupportingContent: Boolean = true,
    content: CardGroupScope.() -> Unit,
) {
    val scope = BackupOutlineScope()
    scope.content()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    Column(Modifier.padding(horizontal = 4.dp)) { title() }
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
                    val supporting = item.supporting.takeIf { showSupportingContent }
                    val rowModifier = item.modifier
                        .fillMaxWidth()
                        .then(item.onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                    Column(
                        modifier = rowModifier
                            .heightIn(min = if (supporting != null) 64.dp else 52.dp)
                            .padding(horizontal = 16.dp, vertical = if (supporting != null) 8.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (item.headline != null || item.leading != null || item.trailing != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item.leading?.invoke()
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    item.headline?.let { ProvideTextStyle(MaterialTheme.typography.bodyLarge, it) }
                                    supporting?.let {
                                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, it)
                                        }
                                    }
                                }
                                if (item.trailing != null) {
                                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                        item.trailing.invoke()
                                    }
                                } else if (item.onClick != null) {
                                    Icon(
                                        HugeIcons.ArrowRight01,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        item.form?.invoke(this)
                    }
                    if (index != scope.items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BackupOutlineIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurface,
    )
}
