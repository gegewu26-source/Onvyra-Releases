package com.constx.onvyra.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.constx.onvyra.Screen
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.utils.fileSizeToString
import com.constx.onvyra.utils.jsonPrimitiveOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Link01
import java.util.Locale

/** Uses only this call's public snapshot; account material and other chats' tasks stay local. */
internal class NetDiskToolUI(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector =
        if (toolName == "netdisk_parse") HugeIcons.Link01 else HugeIcons.Download01

    @Composable
    override fun title(context: ToolUIContext): String = when (toolName) {
        "netdisk_parse" -> "解析网盘分享"
        "netdisk_download" -> "提交网盘下载"
        else -> "查询网盘下载状态"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = result(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        NetDiskResultContent(context, preview = false)
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxHeight(0.8f).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title(context), style = MaterialTheme.typography.titleLarge)
            NetDiskResultContent(context, preview = true, onNavigate = onDismissRequest)
        }
    }

    companion object {
        val all: List<ToolUIRenderer> = listOf(
            NetDiskToolUI("netdisk_parse"),
            NetDiskToolUI("netdisk_download"),
            NetDiskToolUI("netdisk_download_status"),
        )
    }
}

private fun result(context: ToolUIContext): JsonObject? =
    (context.content as? JsonObject)?.takeIf { it.getStringContent("type") == "netdisk_result" }

private fun JsonObject.rows(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.text(key: String): String? =
    getStringContent(key)?.filterNot { it.isISOControl() }?.trim()?.take(400)?.takeIf { it.isNotEmpty() }

private fun JsonObject.bytes(key: String): Long? = this[key]?.jsonPrimitiveOrNull?.longOrNull

@Composable
private fun NetDiskResultContent(
    context: ToolUIContext,
    preview: Boolean,
    onNavigate: () -> Unit = {},
) {
    val result = result(context)
    if (result == null) {
        if (preview) Text(if (context.tool.isExecuted) "未获得可显示的网盘结果，请重新解析。" else "等待工具执行结果。")
        return
    }
    val navController = LocalNavController.current
    val files = result.rows("files")
    val tasks = result.rows("tasks")
    val errors = result.rows("errors")
    val status = result.text("status")
    val action = result.text("action")
    val limit = if (preview) 20 else 3
    val hasTasks = tasks.isNotEmpty() || (result["task_ids"] as? JsonArray)?.isNotEmpty() == true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val heading = when {
            status == "error" -> "操作未完成"
            action == "download" && !hasTasks -> "未提交下载任务"
            status == "partial" -> "部分下载任务已提交"
            status == "submitted" -> "下载任务已提交"
            action == "parse" -> "已解析 ${files.size} 项"
            tasks.isEmpty() -> "本次查询没有下载任务"
            else -> "下载任务状态"
        }
        Text(heading, style = MaterialTheme.typography.labelMedium,
            color = if (status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        result.text("message")?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (preview) 6 else 3,
                overflow = TextOverflow.Ellipsis)
        }
        if (action == "download") {
            val counts = listOfNotNull(
                result.bytes("enqueued")?.let { "新提交 $it" },
                result.bytes("skipped")?.let { "已存在 $it" },
                result.bytes("failed")?.let { "未提交 $it" },
            )
            if (counts.isNotEmpty()) Text(counts.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            result.text("destination_label")?.let {
                Text("下载目录：$it", style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        files.take(limit).forEach { file ->
            val name = file.text("name") ?: "未命名文件"
            val directory = file["is_directory"]?.jsonPrimitiveOrNull?.booleanOrNull == true
            val detail = if (directory) "文件夹" else file.bytes("size")?.takeIf { it >= 0 }?.fileSizeToString() ?: "未知大小"
            Text("$name · $detail", style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (files.size > limit) Text("另有 ${files.size - limit} 项", style = MaterialTheme.typography.labelSmall)
        tasks.take(limit).forEach { task ->
            val name = task.text("file_name") ?: "下载任务"
            val taskStatus = task.text("status")?.lowercase(Locale.ROOT)
            val downloaded = task.bytes("downloaded_bytes")?.takeIf { it >= 0 }?.fileSizeToString()
            val total = task.bytes("total_bytes")?.takeIf { it >= 0 }?.fileSizeToString() ?: "未知大小"
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$name · ${taskStatusText(taskStatus)}", style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(downloaded?.let { "$it / $total" }, task.text("destination_label"))
                if (detail.isNotEmpty()) Text(detail.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tasks.size > limit) Text("另有 ${tasks.size - limit} 个任务", style = MaterialTheme.typography.labelSmall)
        errors.take(if (preview) 5 else 1).forEach { error ->
            error.text("message")?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if ((result["unknown_task_ids"] as? JsonArray)?.isNotEmpty() == true) {
            Text("部分任务不属于当前聊天或已失效。", style = MaterialTheme.typography.bodySmall)
        }
        if (hasTasks) {
            Text("提交不代表完成；以上为调用时的状态，实时进度请查看传输。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(); navController.navigate(Screen.NetDiskDownloads) }) {
                Text("查看传输")
            }
        }
        if (status == "error" || errors.isNotEmpty()) {
            TextButton(onClick = { onNavigate(); navController.navigate(Screen.NetDisk) }) {
                Text("打开网盘解析 / 登录")
            }
        }
    }
}

private fun taskStatusText(status: String?): String = when (status) {
    "pending" -> "已提交，等待状态"
    "queued" -> "排队中"
    "probing" -> "检查下载信息"
    "downloading" -> "下载中"
    "paused" -> "已暂停"
    "saving" -> "正在保存"
    "completed" -> "已完成"
    "failed" -> "下载失败"
    "cancelled", "canceled" -> "已取消"
    else -> "状态待确认"
}
