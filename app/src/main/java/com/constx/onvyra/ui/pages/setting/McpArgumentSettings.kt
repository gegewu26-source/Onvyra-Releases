package com.constx.onvyra.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import com.constx.onvyra.data.ai.mcp.*

@Composable
internal fun McpArgumentSettings(tool: McpTool, onSave: (JsonObject) -> Unit) {
    val fields = remember(tool.inputSchema) { tool.editableArgumentSchemas() }
    if (fields.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) {
        Text("设置默认参数" + if (tool.defaultArguments.isNotEmpty()) "（${tool.defaultArguments.size} 项）" else "")
    }
    if (!open) return
    var values by remember(tool.defaultArguments) {
        mutableStateOf(tool.validSavedArguments().mapValues { it.value.jsonPrimitive.content })
    }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("默认参数 · ${tool.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("打开开关后填写要记住的值。关闭该项恢复服务器默认；这里只保存偏好，不运行工具。", style = MaterialTheme.typography.bodySmall)
                fields.forEach { (key, schema) ->
                    val active = key in values
                    val type = schema["type"]?.jsonPrimitive?.content
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(argumentLabel(key), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Switch(checked = active, onCheckedChange = { checked ->
                                values = if (checked) values + (key to (schema["default"] as? JsonPrimitive)?.content.orEmpty().ifEmpty { if (type == "boolean") "false" else "" }) else values - key
                                error = ""
                            })
                        }
                        if (active) {
                            if (type == "boolean") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (values[key] == "true") "开" else "关", Modifier.weight(1f))
                                    Switch(checked = values[key] == "true", onCheckedChange = { values = values + (key to it.toString()) })
                                }
                            } else {
                                OutlinedTextField(
                                    value = values[key].orEmpty(),
                                    onValueChange = { values = values + (key to it); error = "" },
                                    singleLine = true,
                                    label = { Text(key) },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (Regex("(?i)password|secret|token|key").containsMatchIn(key)) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                                    supportingText = {
                                        Text((schema["enum"] as? JsonArray)?.joinToString(" / ") { it.jsonPrimitive.content }
                                            ?: schema["description"]?.jsonPrimitive?.content.orEmpty())
                                    },
                                )
                            }
                        }
                    }
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { JsonObject(values.mapValues { (key, value) -> parseMcpArgument(value, fields.getValue(key)) }) }
                    .onSuccess { onSave(it); open = false }
                    .onFailure { error = it.message.orEmpty() }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
}

private fun argumentLabel(key: String): String = when (key) {
    "timeout_seconds", "timeoutSeconds" -> "单次任务时限（秒）"
    "max_job_seconds" -> "全局任务时限（秒）"
    "max_concurrent_jobs" -> "同时运行的任务数"
    "concurrency", "threads" -> "单任务并发数"
    "rate_limit" -> "请求速率"
    "password_file" -> "密码字典路径"
    "username_file" -> "用户名字典路径"
    "template_directory" -> "模板目录"
    "port" -> "端口"
    else -> key
}
