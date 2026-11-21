package com.constx.onvyra.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import com.constx.onvyra.R
import com.constx.onvyra.data.datastore.BackupReminderConfig
import com.constx.onvyra.ui.pages.backup.components.BackupOutlineGroup as CardGroup
import com.constx.onvyra.ui.pages.backup.components.BackupOutlineIcon as CardGroupIcon
import com.constx.onvyra.ui.components.ui.ConnectedChoiceButtons
import com.constx.onvyra.ui.components.ui.ConnectedChoiceLabel
import com.constx.onvyra.ui.components.ui.switchItem
import com.constx.onvyra.ui.pages.backup.BackupVM
import com.constx.onvyra.utils.toLocalDateTime
import java.time.Instant

private val ReminderIntervals = listOf(1, 3, 7, 14, 30)

@Composable
fun ReminderTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.backupReminderConfig

    fun updateConfig(update: BackupReminderConfig) {
        vm.updateSettings { it.copy(backupReminderConfig = update) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup {
            switchItem(
                checked = config.enabled,
                onCheckedChange = { updateConfig(config.copy(enabled = it)) },
                headlineContent = { Text(stringResource(R.string.backup_page_reminder_enable)) },
            )

            if (config.enabled) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_reminder_interval)) },
                    trailingContent = {
                        Text(
                            stringResource(R.string.backup_page_reminder_interval_days, config.intervalDays),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                ) {
                    // 五个按钮并排，按钮里只放数字，带单位的完整取值显示在标题行右侧
                    ConnectedChoiceButtons(
                        options = ReminderIntervals,
                        selected = config.intervalDays,
                        onSelect = { updateConfig(config.copy(intervalDays = it)) },
                    ) { days, selected ->
                        ConnectedChoiceLabel(text = days.toString(), selected = selected)
                    }
                }

                item(
                    headlineContent = {
                        Text(
                            if (config.lastBackupTime == 0L) {
                                stringResource(R.string.backup_page_reminder_no_record)
                            } else {
                                stringResource(
                                    R.string.backup_page_reminder_last_time,
                                    Instant.ofEpochMilli(config.lastBackupTime).toLocalDateTime()
                                )
                            }
                        )
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                )
            }
        }
    }
}
