package com.constx.onvyra.ui.pages.backup.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Tick01
import com.constx.onvyra.R
import com.constx.onvyra.ui.components.ai.ExtensionEmptyState
import com.constx.onvyra.ui.pages.backup.components.BackupOutlineGroup as CardGroup
import com.constx.onvyra.ui.pages.backup.components.BackupOutlineIcon as CardGroupIcon
import com.constx.onvyra.ui.components.ui.CardGroupScope
import com.constx.onvyra.ui.components.ui.ConnectedChoiceLabel
import com.constx.onvyra.ui.components.ui.ConnectedMultiChoiceButtons
import com.constx.onvyra.utils.UiState
import com.constx.onvyra.utils.fileSizeToString
import com.constx.onvyra.utils.toLocalDateTime
import java.time.Instant

// 列表项开头的图标，操作进行中时换成进度圈
@Composable
internal fun BackupActionIcon(
    icon: ImageVector,
    loading: Boolean = false,
) {
    if (loading) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else {
        CardGroupIcon(icon)
    }
}

// 「备份内容」多选项，本地、WebDAV、S3 共用
internal fun <T> CardGroupScope.backupContentItem(
    options: List<T>,
    selected: List<T>,
    onChange: (List<T>) -> Unit,
    label: @Composable (T) -> String,
) = formItem(
    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
) {
    ConnectedMultiChoiceButtons(
        options = options,
        selected = selected,
        onToggle = { option, checked ->
            onChange(if (checked) selected + option else selected - option)
        },
    ) { option, isSelected ->
        ConnectedChoiceLabel(
            text = label(option),
            selected = isSelected,
            icon = HugeIcons.Tick01,
        )
    }
}

// WebDAV / S3 页顶部的状态分组：上次备份时间，以及进入备份文件列表的入口
@Composable
internal fun RemoteBackupStatus(
    lastBackupTime: Long,
    filesState: UiState<List<*>>,
    onShowFiles: () -> Unit,
) {
    val lastBackupText = if (lastBackupTime == 0L) {
        stringResource(R.string.backup_page_reminder_no_record)
    } else {
        stringResource(
            R.string.backup_page_reminder_last_time,
            Instant.ofEpochMilli(lastBackupTime).toLocalDateTime()
        )
    }
    val fileCount = when (filesState) {
        is UiState.Success -> filesState.data.size.toString()
        UiState.Loading -> "..."
        UiState.Idle, is UiState.Error -> "-"
    }

    CardGroup {
        item(
            headlineContent = { Text(lastBackupText) },
            leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
        )
        item(
            onClick = onShowFiles,
            headlineContent = { Text(stringResource(R.string.backup_page_restore)) },
            supportingContent = { Text("${stringResource(R.string.backup_page_files)}: $fileCount") },
            leadingContent = { CardGroupIcon(HugeIcons.CloudDownload) },
            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
        )
    }
}

// WebDAV / S3 页共用的外壳：可滚动表单与底部连接、备份操作
@Composable
internal fun RemoteBackupLayout(
    isBackingUp: Boolean,
    onTestConnection: () -> Unit,
    onBackup: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )

        AnimatedVisibility(
            visible = !WindowInsets.isImeVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onTestConnection,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(HugeIcons.Connect, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_page_test_connection))
                    }
                    Button(
                        onClick = onBackup,
                        enabled = !isBackingUp,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isBackingUp) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                        } else {
                            Icon(HugeIcons.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isBackingUp) stringResource(R.string.backup_page_backing_up)
                            else stringResource(R.string.backup_page_backup_now)
                        )
                    }
                }
            }
        }
    }
}

internal class BackupFileInfo(
    val name: String,
    val size: Long,
    val lastModified: Instant,
)

// 远程备份文件列表，可以恢复或删除其中一份
@Composable
internal fun <T> BackupFilesSheet(
    title: String,
    state: UiState<List<T>>,
    // 正在恢复的那份备份的文件名，恢复期间所有按钮都不可点
    restoringName: String?,
    info: (T) -> BackupFileInfo,
    onDelete: (T) -> Unit,
    onRestore: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            when (state) {
                is UiState.Success -> {
                    val files = state.data
                    if (files.isEmpty()) {
                        ExtensionEmptyState(
                            message = stringResource(R.string.setting_files_page_no_files),
                            icon = HugeIcons.FileZip,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(files) { index, file ->
                                val fileInfo = info(file)
                                BackupFileItem(
                                    info = fileInfo,
                                    index = index,
                                    count = files.size,
                                    isRestoring = restoringName == fileInfo.name,
                                    enabled = restoringName == null,
                                    onDelete = { onDelete(file) },
                                    onRestore = { onRestore(file) },
                                )
                            }
                        }
                    }
                }

                is UiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.backup_page_loading_failed, state.error.message ?: ""),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                UiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator()
                    }
                }

                UiState.Idle -> {}
            }
        }
    }
}

@Composable
private fun BackupFileItem(
    info: BackupFileInfo,
    index: Int,
    count: Int,
    isRestoring: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CardGroupIcon(HugeIcons.FileZip)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = info.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "${info.lastModified.toLocalDateTime()} · ${info.size.fileSizeToString()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.backup_page_delete))
                }
                FilledTonalButton(
                    onClick = onRestore,
                    enabled = enabled,
                ) {
                    if (isRestoring) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(
                        if (isRestoring) {
                            stringResource(R.string.backup_page_restoring)
                        } else {
                            stringResource(R.string.backup_page_restore_now)
                        }
                    )
                }
            }
        }
    }
}
