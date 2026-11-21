package com.constx.onvyra.ui.pages.backup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import com.constx.onvyra.R
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.pages.backup.components.BackupDialog
import com.constx.onvyra.ui.pages.backup.tabs.ImportExportTab
import com.constx.onvyra.ui.pages.backup.tabs.ReminderTab
import com.constx.onvyra.ui.pages.backup.tabs.S3Tab
import com.constx.onvyra.ui.pages.backup.tabs.WebDavTab
import org.koin.androidx.compose.koinViewModel

private enum class BackupTab {
    LOCAL, WEBDAV, S3, REMINDER;

    val label: String
        @Composable get() = when (this) {
            LOCAL -> stringResource(R.string.backup_page_import_export)
            WEBDAV -> "WebDAV"
            S3 -> "S3"
            REMINDER -> stringResource(R.string.backup_page_reminder)
        }
}

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val pagerState = rememberPagerState { BackupTab.entries.size }
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    var showRestartDialog by remember { mutableStateOf(false) }
    val pageColor = MaterialTheme.colorScheme.background

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.backup_page_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(48.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                CircleShape,
                            ),
                    ) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = pageColor,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = pageColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackupTab.entries.forEachIndexed { index, tab ->
                    val isSelected = pagerState.targetPage == index
                    Surface(
                        onClick = {
                            if (!isSelected) scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = Role.Tab; selected = isSelected },
                        shape = CircleShape,
                        color = if (isSelected) pageColor else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = if (isSelected) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        } else null,
                    ) {
                        Text(
                            tab.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { page ->
                when (BackupTab.entries[page]) {
                    BackupTab.LOCAL -> ImportExportTab(vm = vm, onShowRestartDialog = { showRestartDialog = true })
                    BackupTab.WEBDAV -> WebDavTab(vm = vm, onShowRestartDialog = { showRestartDialog = true })
                    BackupTab.S3 -> S3Tab(vm = vm, onShowRestartDialog = { showRestartDialog = true })
                    BackupTab.REMINDER -> ReminderTab(vm = vm)
                }
            }
        }
    }

    if (showRestartDialog) {
        BackupDialog()
    }
}
