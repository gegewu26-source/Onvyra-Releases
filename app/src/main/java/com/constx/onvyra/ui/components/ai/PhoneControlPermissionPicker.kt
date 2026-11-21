package com.constx.onvyra.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import me.rerere.ui.components.ToggleSurface

/** A small anchored menu beside reasoning; the selected icon stays visible in the composer. */
@Composable
fun PhoneControlPermissionButton(
    mode: AndroidControlPermissionMode,
    onModeChange: (AndroidControlPermissionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current

    LaunchedEffect(opening) {
        if (!opening) return@LaunchedEffect
        // Opening a focusable popup while the composer is above the IME makes its anchor
        // move during the focus/keyboard handoff. Finish that handoff before positioning it.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        withTimeoutOrNull(1_000) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
        withFrameNanos { }
        expanded = true
        opening = false
    }
    BackHandler(enabled = opening || expanded) { opening = false; expanded = false }

    Box(modifier) {
        ToggleSurface(
            checked = mode == AndroidControlPermissionMode.FULL_ACCESS,
            onClick = {
                if (opening || expanded) { opening = false; expanded = false }
                else opening = true
            },
            modifier = Modifier.testTag("phone-control-permission-button"),
        ) {
            Icon(
                imageVector = mode.permissionIcon(),
                contentDescription = "任务权限：${mode.permissionLabel()}",
                modifier = Modifier.padding(8.dp).size(24.dp)
                    .testTag("phone-control-permission-icon-${mode.name}"),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { opening = false; expanded = false },
            // Keep input focus in the activity; the popup must not restart IME animation.
            properties = PopupProperties(focusable = false),
            modifier = Modifier.width(264.dp)
                .testTag("phone-control-permission-menu"),
        ) {
            AndroidControlPermissionMode.entries.forEach { option ->
                val isSelected = mode == option
                val activeColor = MaterialTheme.colorScheme.primary
                val defaultTextColor = MaterialTheme.colorScheme.onSurface
                val defaultIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                val defaultDescColor = MaterialTheme.colorScheme.onSurfaceVariant

                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = option.permissionLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) activeColor else defaultTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("phone-control-permission-label-${option.name}"),
                            )
                            Text(
                                text = option.permissionDescription(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) activeColor.copy(alpha = 0.85f) else defaultDescColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = option.permissionIcon(),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                                .testTag("phone-control-permission-option-icon-${option.name}"),
                            tint = if (isSelected) activeColor else defaultIconColor,
                        )
                    },
                    trailingIcon = {
                        Box(Modifier.size(24.dp)) {
                            if (isSelected) Icon(
                                imageVector = permissionSelectedIcon,
                                contentDescription = null,
                                tint = activeColor,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = if (isSelected) activeColor else defaultTextColor,
                        leadingIconColor = if (isSelected) activeColor else defaultIconColor,
                    ),
                    onClick = { opening = false; expanded = false; onModeChange(option) },
                    modifier = Modifier
                        .testTag("phone-control-permission-option-${option.name}")
                        .semantics { selected = isSelected }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .heightIn(min = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (isSelected) {
                                Modifier.background(activeColor.copy(alpha = 0.12f))
                            } else {
                                Modifier
                            }
                        ),
                )
            }
        }
    }
}

internal fun AndroidControlPermissionMode.permissionLabel(): String = when (this) {
    AndroidControlPermissionMode.CONFIRM_CHANGES -> "变更前确认"
    AndroidControlPermissionMode.AUTONOMOUS -> "自主模式"
    AndroidControlPermissionMode.FULL_ACCESS -> "完全访问"
}

private fun AndroidControlPermissionMode.permissionDescription(): String = when (this) {
    AndroidControlPermissionMode.CONFIRM_CHANGES -> "修改文件、执行操作前询问"
    AndroidControlPermissionMode.AUTONOMOUS -> "各类任务有风险时询问"
    AndroidControlPermissionMode.FULL_ACCESS -> "各类任务不再逐次询问"
}

private fun AndroidControlPermissionMode.permissionIcon(): ImageVector = when (this) {
    AndroidControlPermissionMode.CONFIRM_CHANGES -> permissionHandIcon
    AndroidControlPermissionMode.AUTONOMOUS -> permissionShieldCheckIcon
    AndroidControlPermissionMode.FULL_ACCESS -> permissionShieldAlertIcon
}

private fun permissionVector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

private val permissionSelectedIcon = permissionVector("PhoneControlSelected") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5f, 12f); lineTo(9.5f, 16.5f); lineTo(19f, 7f)
    }
}

private val permissionHandIcon = permissionVector("PhoneControlConfirmHand") {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(7f, 12f)
        lineTo(7f, 6f)
        curveTo(7f, 4.1f, 10f, 4.1f, 10f, 6f)
        lineTo(10f, 4.5f)
        curveTo(10f, 2.5f, 13f, 2.5f, 13f, 4.5f)
        lineTo(13f, 6f)
        curveTo(13f, 4.1f, 16f, 4.1f, 16f, 6f)
        lineTo(16f, 8.5f)
        curveTo(16f, 6.6f, 19f, 6.6f, 19f, 8.5f)
        lineTo(19f, 14.5f)
        curveTo(19f, 18.9f, 16.5f, 21.5f, 12.5f, 21.5f)
        lineTo(10.7f, 21.5f)
        curveTo(7.5f, 21.5f, 6.1f, 19.3f, 4.3f, 16.6f)
        lineTo(3f, 14.6f)
        curveTo(2.1f, 13.3f, 3.8f, 11.8f, 5f, 13f)
        lineTo(7f, 15f)
        lineTo(7f, 12f)
        moveTo(10f, 6f); lineTo(10f, 11f)
        moveTo(13f, 6f); lineTo(13f, 11f)
        moveTo(16f, 8.5f); lineTo(16f, 11.5f)
    }
}

private fun ImageVector.Builder.permissionShieldOutline() {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 2.5f)
        lineTo(20f, 6f)
        lineTo(20f, 12f)
        curveTo(20f, 16.4f, 16.8f, 19.5f, 12f, 21.5f)
        curveTo(7.2f, 19.5f, 4f, 16.4f, 4f, 12f)
        lineTo(4f, 6f)
        close()
    }
}

private val permissionShieldCheckIcon = permissionVector("PhoneControlAutonomousShield") {
    permissionShieldOutline()
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(8.3f, 11.5f); lineTo(10.7f, 14f); lineTo(15.8f, 9f)
    }
}

private val permissionShieldAlertIcon = permissionVector("PhoneControlFullAccessShield") {
    permissionShieldOutline()
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
        moveTo(12f, 7.5f); lineTo(12f, 12.2f)
        moveTo(12f, 15.5f); lineTo(12f, 15.6f)
    }
}
