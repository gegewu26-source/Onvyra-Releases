package com.constx.onvyra.ui.components.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constx.onvyra.R
import com.constx.onvyra.data.update.AppUpdateRelease
import com.constx.onvyra.data.update.AppUpdateRepository
import com.constx.onvyra.data.update.AppUpdateUiState
import com.constx.onvyra.ui.context.LocalToaster
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.ui.toast.ToastType

/** Composed only after the chat privacy gate, and visible only on the resumed page. */
@Composable
internal fun AppUpdateHost(checkOnResume: Boolean = false) {
    val context = LocalContext.current
    val repository = remember(context) { AppUpdateRepository.get(context) }
    val state by repository.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumed = true
        if (checkOnResume) repository.check()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }
    if (!resumed) return
    val toaster = LocalToaster.current
    when (val current = state) {
        AppUpdateUiState.Idle -> Unit
        is AppUpdateUiState.Release -> UpdateReleaseDialog(
            release = current.release,
            newer = current.newer,
            onDismiss = { if (current.newer) repository.remindLater(current.release) else repository.dismiss() },
            onIgnore = { repository.ignore(current.release) },
            onDownload = { download ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(download.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    repository.remindLater(current.release)
                } catch (_: Exception) {
                    toaster.show(context.getString(R.string.update_page_open_failed), type = ToastType.Error)
                }
            },
        )
        is AppUpdateUiState.Message -> AlertDialog(
            onDismissRequest = repository::dismiss,
            title = { Text(stringResource(R.string.update_page_check_updates)) },
            text = { Text(stringResource(if (current.failed) R.string.update_page_check_failed else R.string.update_page_up_to_date)) },
            confirmButton = {
                TextButton(onClick = { repository.dismiss(); if (current.failed) repository.check(manual = true) }) {
                    Text(stringResource(if (current.failed) R.string.update_page_retry else R.string.update_page_done))
                }
            },
            dismissButton = if (current.failed) ({ TextButton(onClick = repository::dismiss) { Text(stringResource(R.string.common_cancel)) } }) else null,
        )
    }
}

@Composable
internal fun UpdateReleaseDialog(
    release: AppUpdateRelease,
    newer: Boolean,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    onDownload: (com.constx.onvyra.data.update.UpdateDownload) -> Unit,
) {
    val download = remember(release) { release.downloadFor(Build.SUPPORTED_ABIS.toList()) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(HugeIcons.Download01, contentDescription = null,
                            modifier = Modifier.padding(15.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (newer) R.string.update_page_new_version else R.string.update_page_release_notes),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(release.versionName, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (release.title.isNotBlank()) Text(release.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (release.summary.isNotBlank()) Text(release.summary, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(release.publishedAt.take(10), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    release.changes.forEach { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(when (group.type) {
                                "feature" -> R.string.update_page_features
                                "fix" -> R.string.update_page_fixes
                                "improvement" -> R.string.update_page_improvements
                                else -> R.string.update_page_notices
                            }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            group.items.forEach { item ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("•", color = MaterialTheme.colorScheme.primary)
                                    Text(item, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    if (newer && download == null) Text(stringResource(R.string.update_page_no_compatible_package),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (newer && download != null) Text(stringResource(R.string.update_page_browser_download,
                        "%.1f".format(download.sizeBytes / (1024.0 * 1024.0))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (newer) {
                    Button(onClick = { download?.let(onDownload) }, enabled = download != null,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_page_download)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onIgnore) { Text(stringResource(R.string.update_page_ignore)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_page_later)) }
                    }
                } else {
                    FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.update_page_done))
                    }
                }
            }
        }
    }
}
