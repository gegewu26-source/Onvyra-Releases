package com.constx.onvyra.ui.pages.netdisk

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadManager
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadStatus
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadTask
import com.constx.onvyra.ui.context.LocalToaster
import java.text.DecimalFormat
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Settings03

@Composable
fun NetDiskDownloadsPage() = NetDiskPage(initialTab = NetDiskTab.TRANSFER)

/** Embedded in the net-disk workspace so the three main tabs remain available. */
@Composable
internal fun NetDiskTransfersContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { NetDiskDownloadManager.get(context.applicationContext) }
    val tasks by manager.tasks.collectAsState()
    val connections by manager.connections.collectAsState()
    val storageError by manager.storageError.collectAsState()
    val toaster = LocalToaster.current
    var settings by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var removeTarget by remember { mutableStateOf<NetDiskDownloadTask?>(null) }
    var pendingResume by remember { mutableStateOf<String?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = pendingResume
        pendingResume = null
        if (granted && id != null) manager.resume(id)
        else if (!granted) toaster.show("未获得存储权限，下载仍保持暂停")
    }
    fun action(block: () -> Unit) {
        try { block() } catch (_: Exception) { toaster.show("操作未完成，请重试") }
    }
    fun resume(task: NetDiskDownloadTask) {
        if (task.destinationTreeUri == null && Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingResume = task.id
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else action { manager.resume(task.id) }
    }
    val visible = tasks.filter { task -> matchesDownloadFilter(task, filter) }
    Column(modifier.fillMaxSize().testTag("netdisk-downloads-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp)) {
                Text("下载", Modifier.padding(horizontal = 32.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                catch (_: Exception) { toaster.show("系统没有提供下载目录入口") }
            }) { Icon(HugeIcons.Folder01, "打开下载目录") }
            IconButton(onClick = { settings = true }, modifier = Modifier.testTag("netdisk-download-settings")) {
                Icon(HugeIcons.Settings03, "下载设置")
            }
        }
        SecondaryScrollableTabRow(
            selectedTabIndex = filter.ordinal,
            edgePadding = 16.dp,
            minTabWidth = 76.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)) },
        ) {
            DownloadFilter.entries.forEach { choice ->
                val count = tasks.count { matchesDownloadFilter(it, choice) }
                Tab(selected = filter == choice, onClick = { filter = choice },
                    modifier = Modifier.testTag("netdisk-transfer-filter-${choice.name.lowercase()}"),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(if (choice == DownloadFilter.ALL) choice.label else "${choice.label} $count",
                        fontWeight = if (filter == choice) FontWeight.SemiBold else FontWeight.Normal) })
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            storageError?.takeIf(String::isNotBlank)?.let { message -> item("storage-error") {
                Surface(color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().testTag("netdisk-download-storage-error")) {
                    Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }
            if (visible.isEmpty()) item("empty") {
                Box(Modifier.fillMaxWidth().fillParentMaxHeight(0.86f).testTag("netdisk-transfer-empty"),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TransferEmptyTray()
                        Text(when (filter) {
                            DownloadFilter.ALL -> "暂无任务"
                            DownloadFilter.ACTIVE -> "暂无进行中的任务"
                            DownloadFilter.COMPLETED -> "暂无已完成的任务"
                            DownloadFilter.FAILED -> "暂无失败的任务"
                        }, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(visible, key = { _, task -> task.id }) { index, task ->
                NetDiskDownloadRow(task, index, visible.size,
                    onPause = { action { manager.pause(task.id) } },
                    onResume = { resume(task) },
                    onCancel = { action { manager.cancel(task.id) } },
                    onOpen = {
                        try { manager.open(task.id) }
                        catch (_: Exception) { toaster.show("无法打开文件，请确认文件仍存在，或到下载目录中查看") }
                    },
                    onRemove = { removeTarget = task })
            }
        }
    }
    if (settings) DownloadConnectionsDialog(connections,
        onDismiss = { settings = false },
        onSelect = { value -> action { manager.setConnections(value) }; settings = false })
    removeTarget?.let { task ->
        AlertDialog(onDismissRequest = { removeTarget = null },
            title = { Text("移除下载记录") },
            text = { Text(if (task.status == NetDiskDownloadStatus.COMPLETED)
                "只移除“${task.fileName}”的下载记录，已保存的文件会保留。"
                else "移除“${task.fileName}”的下载记录，并清理此任务未完成的缓存。已保存的文件会保留。") },
            confirmButton = { TextButton(onClick = {
                action { manager.remove(task.id) }; removeTarget = null
            }) { Text("移除记录") } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("保留") } })
    }
}

private enum class DownloadFilter(val label: String) { ALL("全部"), ACTIVE("进行中"), COMPLETED("已完成"), FAILED("失败") }

private fun matchesDownloadFilter(task: NetDiskDownloadTask, filter: DownloadFilter): Boolean = when (filter) {
    DownloadFilter.ALL -> true
    DownloadFilter.ACTIVE -> task.status in inProgressDownloadStatuses || task.status == NetDiskDownloadStatus.PAUSED
    DownloadFilter.COMPLETED -> task.status == NetDiskDownloadStatus.COMPLETED
    DownloadFilter.FAILED -> task.status == NetDiskDownloadStatus.FAILED
}

@Composable
private fun TransferEmptyTray() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    Canvas(Modifier.size(76.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val outline = Path().apply {
            moveTo(w * 0.08f, h * 0.43f)
            lineTo(w * 0.30f, h * 0.15f)
            quadraticBezierTo(w * 0.34f, h * 0.11f, w * 0.39f, h * 0.11f)
            lineTo(w * 0.65f, h * 0.11f)
            quadraticBezierTo(w * 0.69f, h * 0.11f, w * 0.72f, h * 0.15f)
            lineTo(w * 0.92f, h * 0.43f)
            lineTo(w * 0.92f, h * 0.79f)
            quadraticBezierTo(w * 0.92f, h * 0.86f, w * 0.85f, h * 0.86f)
            lineTo(w * 0.15f, h * 0.86f)
            quadraticBezierTo(w * 0.08f, h * 0.86f, w * 0.08f, h * 0.79f)
            close()
        }
        val opening = Path().apply {
            moveTo(w * 0.08f, h * 0.45f)
            lineTo(w * 0.37f, h * 0.45f)
            cubicTo(w * 0.37f, h * 0.69f, w * 0.63f, h * 0.69f, w * 0.63f, h * 0.45f)
            lineTo(w * 0.92f, h * 0.45f)
        }
        drawPath(outline, color, style = stroke)
        drawPath(opening, color, style = stroke)
    }
}

internal val inProgressDownloadStatuses = setOf(NetDiskDownloadStatus.QUEUED, NetDiskDownloadStatus.PROBING,
    NetDiskDownloadStatus.DOWNLOADING, NetDiskDownloadStatus.SAVING)

@Composable
internal fun NetDiskDownloadRow(task: NetDiskDownloadTask, index: Int, count: Int,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit) {
    val progress = when {
        task.status == NetDiskDownloadStatus.COMPLETED -> 1f
        task.totalBytes > 0L -> (task.downloadedBytes.toDouble() / task.totalBytes).coerceIn(0.0, 1.0).toFloat()
        else -> null
    }
    Surface(Modifier.fillMaxWidth().testTag("netdisk-download-task-${task.id}"),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(task.fileName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(netDiskDownloadStatusLabel(task.status), style = MaterialTheme.typography.labelMedium,
                    color = if (task.status == NetDiskDownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else if (task.status in inProgressDownloadStatuses) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${netDiskDownloadSize(task.downloadedBytes)} / ${netDiskDownloadSize(task.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            if (task.status == NetDiskDownloadStatus.DOWNLOADING) Text(
                "${netDiskDownloadSize(task.speedBytesPerSecond)}/s · ${task.activeConnections.coerceAtLeast(0)} 个连接",
                modifier = Modifier.testTag("netdisk-download-rate-${task.id}"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (task.status == NetDiskDownloadStatus.PROBING) Text("正在检查文件大小和分片支持…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.status == NetDiskDownloadStatus.PAUSED) Text("断点已保留，点击继续下载。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            task.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (task.status) {
                    NetDiskDownloadStatus.QUEUED, NetDiskDownloadStatus.PROBING, NetDiskDownloadStatus.DOWNLOADING -> {
                        FilledTonalButton(onClick = onPause) { Text("暂停") }
                        TextButton(onClick = onCancel) { Text("取消下载") }
                    }
                    NetDiskDownloadStatus.SAVING -> Text("正在保存到下载目录…", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NetDiskDownloadStatus.PAUSED -> {
                        FilledTonalButton(onClick = onResume) { Text("继续") }
                        TextButton(onClick = onCancel) { Text("取消下载") }
                    }
                    NetDiskDownloadStatus.FAILED -> {
                        FilledTonalButton(onClick = onResume) { Text("重试") }
                        TextButton(onClick = onRemove) { Text("移除记录") }
                    }
                    NetDiskDownloadStatus.COMPLETED -> {
                        FilledTonalButton(onClick = onOpen) { Text("打开文件") }
                        TextButton(onClick = onRemove) { Text("移除记录") }
                    }
                    NetDiskDownloadStatus.CANCELLED -> TextButton(onClick = onRemove) { Text("移除记录") }
                }
            }
        }
    }
}

@Composable
private fun DownloadConnectionsDialog(current: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("分片连接数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择每个任务允许使用的最大连接数。对新任务和下一次继续下载生效。",
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 4, 8, 16, 32).forEach { value ->
                        FilterChip(selected = current == value, onClick = { onSelect(value) }, label = { Text(value.toString()) })
                    }
                }
                Text("默认 8，同时下载最多 2 个任务。下载目录可在“解析”页选择，新任务会使用创建时选定的目录。网盘不支持分片时会使用单连接；增加连接数不一定更快。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

internal fun netDiskDownloadStatusLabel(status: NetDiskDownloadStatus): String = when (status) {
    NetDiskDownloadStatus.QUEUED -> "等待下载"
    NetDiskDownloadStatus.PROBING -> "准备下载"
    NetDiskDownloadStatus.DOWNLOADING -> "下载中"
    NetDiskDownloadStatus.PAUSED -> "已暂停"
    NetDiskDownloadStatus.SAVING -> "正在保存"
    NetDiskDownloadStatus.COMPLETED -> "已完成"
    NetDiskDownloadStatus.FAILED -> "下载失败"
    NetDiskDownloadStatus.CANCELLED -> "已取消"
}

internal fun netDiskDownloadSize(bytes: Long): String {
    if (bytes < 0L) return "未知大小"
    if (bytes == 0L) return "0 B"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return "${DecimalFormat("0.##").format(size)} ${units[unit]}"
}
