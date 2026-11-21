package com.constx.onvyra.ui.pages.schedule

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import com.constx.onvyra.Screen
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.schedule.ScheduledTask
import com.constx.onvyra.data.schedule.ScheduledTaskRepeat
import com.constx.onvyra.data.schedule.ScheduledTaskRepository
import com.constx.onvyra.data.schedule.ScheduledTaskStatus
import com.constx.onvyra.ui.components.nav.BackButton
import com.constx.onvyra.ui.components.ui.CardGroup
import com.constx.onvyra.ui.components.ui.switchItem
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.ui.theme.CustomColors
import com.constx.onvyra.utils.plus
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

@Composable
fun ScheduledTasksPage(repository: ScheduledTaskRepository = koinInject()) {
    val tasks by repository.tasks.collectAsStateWithLifecycle()
    val repositoryError by repository.error.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var loaded by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var busyTasks by remember { mutableStateOf(emptySet<String>()) }
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ScheduledTask?>(null) }
    var deleteTarget by remember { mutableStateOf<ScheduledTask?>(null) }

    LaunchedEffect(repository) {
        try {
            repository.awaitLoaded()
            loaded = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            operationError = error.message ?: "定时任务加载失败"
        }
    }

    fun changeEnabled(task: ScheduledTask, enabled: Boolean) {
        scope.launch {
            busyTasks = busyTasks + task.id
            try {
                repository.setEnabled(task.id, enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationError = error.message ?: "任务状态修改失败"
            } finally {
                busyTasks = busyTasks - task.id
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("定时任务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            if (loaded && repositoryError == null) {
                FloatingActionButton(onClick = {
                    editTarget = null
                    showEditor = true
                }) {
                    Icon(HugeIcons.Add01, contentDescription = "新建定时任务")
                }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("timing_hint") {
                Text(
                    "到时由指定助手执行，结果保存在聊天记录中。系统省电可能延迟执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val error = repositoryError ?: operationError
            if (error != null) {
                item("error") {
                    TaskError(error, onDismiss = if (repositoryError == null) {
                        { operationError = null }
                    } else null)
                }
            }
            if (!loaded && error == null) {
                item("loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (loaded && tasks.isEmpty() && repositoryError == null) {
                item("empty") {
                    CardGroup {
                        item(
                            headlineContent = { Text("还没有定时任务") },
                            supportingContent = { Text("新建任务，选择助手、提示词和执行时间。") },
                        )
                    }
                }
            }
            items(tasks, key = { it.id }) { task ->
                val assistantName = settings.assistants.find { it.id.toString() == task.assistantId }
                    ?.name?.ifBlank { "未命名助手" } ?: "助手已删除"
                ScheduledTaskCard(
                    task = task,
                    assistantName = assistantName,
                    busy = task.id in busyTasks,
                    onEnabledChange = { changeEnabled(task, it) },
                    onEdit = {
                        editTarget = task
                        showEditor = true
                    },
                    onDelete = { deleteTarget = task },
                    onOpenResult = {
                        task.lastConversationId?.let { id ->
                            nav.navigate(Screen.Chat(id)) { launchSingleTop = true }
                        }
                    },
                )
            }
        }
    }

    if (showEditor) {
        ScheduledTaskEditor(
            initialTask = editTarget,
            assistants = settings.assistants,
            defaultAssistantId = settings.assistantId.toString(),
            onDismiss = { showEditor = false },
            onSave = { repository.save(it) },
        )
    }

    deleteTarget?.let { task ->
        var deleting by remember(task.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("删除定时任务") },
            text = { Text("删除“${task.title}”并取消后续执行？") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            try {
                                repository.delete(task.id)
                                deleteTarget = null
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                operationError = error.message ?: "删除失败"
                                deleteTarget = null
                            } finally {
                                deleting = false
                            }
                        }
                    },
                ) { Text(if (deleting) "删除中…" else "删除") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTask,
    assistantName: String,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenResult: () -> Unit,
) {
    CardGroup {
        switchItem(
            checked = task.enabled,
            enabled = !busy,
            onCheckedChange = onEnabledChange,
            headlineContent = { Text(task.title, style = MaterialTheme.typography.titleSmallEmphasized) },
            supportingContent = { Text(assistantName) },
        )
        item(
            onClick = onEdit,
            headlineContent = {
                val nextRun = task.nextRunAtMillis
                Text(
                    when {
                        !task.enabled -> "已停用"
                        nextRun != null -> "下次 · ${formatTaskDate(nextRun)}"
                        task.repeat == ScheduledTaskRepeat.ONCE && task.lastStatus == ScheduledTaskStatus.SUCCEEDED -> "已完成"
                        else -> "执行时间 · ${formatTaskDate(task.firstRunAtMillis)}"
                    },
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.repeat.label())
                    Text(task.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
        )
        if (task.lastStatus != ScheduledTaskStatus.IDLE || task.lastMessage.isNotBlank()) {
            item(
                headlineContent = {
                    Text(
                        task.lastStatus.label(),
                        color = if (task.lastStatus == ScheduledTaskStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = if (task.lastMessage.isNotBlank()) {
                    { Text(task.lastMessage, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                } else null,
            )
        }
        formItem {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (task.lastConversationId != null) {
                    TextButton(onClick = onOpenResult) { Text("查看结果") }
                }
                TextButton(enabled = !busy, onClick = onEdit) { Text("编辑") }
                TextButton(enabled = !busy, onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun ScheduledTaskEditor(
    initialTask: ScheduledTask?,
    assistants: List<Assistant>,
    defaultAssistantId: String,
    onDismiss: () -> Unit,
    onSave: suspend (ScheduledTask) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialId = initialTask?.id
    var title by remember(initialId) { mutableStateOf(initialTask?.title ?: "") }
    var prompt by remember(initialId) { mutableStateOf(initialTask?.prompt ?: "") }
    var assistantId by remember(initialId) {
        mutableStateOf(initialTask?.assistantId ?: defaultAssistantId)
    }
    var firstRunAtMillis by remember(initialId) {
        mutableStateOf(initialTask?.firstRunAtMillis ?: nextTaskHour())
    }
    var repeat by remember(initialId) {
        mutableStateOf(initialTask?.repeat ?: ScheduledTaskRepeat.ONCE)
    }
    var assistantMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val assistantName = assistants.find { it.id.toString() == assistantId }
        ?.name?.ifBlank { "未命名助手" } ?: "请选择助手"

    fun pickDate() {
        val selected = Calendar.getInstance().apply { timeInMillis = firstRunAtMillis }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                firstRunAtMillis = Calendar.getInstance().apply {
                    timeInMillis = firstRunAtMillis
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }.timeInMillis
            },
            selected.get(Calendar.YEAR),
            selected.get(Calendar.MONTH),
            selected.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    fun pickTime() {
        val selected = Calendar.getInstance().apply { timeInMillis = firstRunAtMillis }
        TimePickerDialog(
            context,
            { _, hour, minute ->
                firstRunAtMillis = Calendar.getInstance().apply {
                    timeInMillis = firstRunAtMillis
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            },
            selected.get(Calendar.HOUR_OF_DAY),
            selected.get(Calendar.MINUTE),
            true,
        ).show()
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
        title = { Text(if (initialTask == null) "新建定时任务" else "编辑定时任务") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CardGroup {
                    formItem {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(80) },
                            label = { Text("任务名称") },
                            singleLine = true,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(
                        onClick = { if (!saving) assistantMenu = true },
                        headlineContent = { Text("执行助手") },
                        supportingContent = { Text(assistantName) },
                        trailingContent = {
                            Box {
                                TextButton(
                                    enabled = !saving && assistants.isNotEmpty(),
                                    onClick = { assistantMenu = true },
                                ) { Text("选择") }
                                DropdownMenu(expanded = assistantMenu, onDismissRequest = { assistantMenu = false }) {
                                    assistants.forEach { assistant ->
                                        DropdownMenuItem(
                                            text = { Text(assistant.name.ifBlank { "未命名助手" }) },
                                            onClick = {
                                                assistantId = assistant.id.toString()
                                                assistantMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                    formItem {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("到时执行的提示词") },
                            minLines = 3,
                            maxLines = 7,
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                CardGroup {
                    formItem(headlineContent = { Text("首次执行") }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = ::pickDate, enabled = !saving, modifier = Modifier.weight(1f)) {
                                Text(formatTaskDate(firstRunAtMillis, "yyyy-MM-dd"))
                            }
                            OutlinedButton(onClick = ::pickTime, enabled = !saving) {
                                Text(formatTaskDate(firstRunAtMillis, "HH:mm"))
                            }
                        }
                    }
                    formItem(headlineContent = { Text("重复") }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScheduledTaskRepeat.entries.forEach { option ->
                                FilterChip(
                                    selected = repeat == option,
                                    onClick = { repeat = option },
                                    label = { Text(option.label()) },
                                    enabled = !saving,
                                )
                            }
                        }
                    }
                }
                error?.let { TaskError(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    error = when {
                        title.isBlank() -> "请输入任务名称"
                        prompt.isBlank() -> "请输入需要执行的提示词"
                        assistants.none { it.id.toString() == assistantId } -> "请选择有效助手"
                        repeat == ScheduledTaskRepeat.ONCE && firstRunAtMillis <= System.currentTimeMillis() -> "请选择未来的执行时间"
                        else -> null
                    }
                    if (error != null) return@TextButton
                    val task = initialTask?.copy(
                        title = title.trim(),
                        assistantId = assistantId,
                        prompt = prompt.trim(),
                        firstRunAtMillis = firstRunAtMillis,
                        repeat = repeat,
                    ) ?: ScheduledTask(
                        id = Uuid.random().toString(),
                        title = title.trim(),
                        assistantId = assistantId,
                        prompt = prompt.trim(),
                        firstRunAtMillis = firstRunAtMillis,
                        repeat = repeat,
                    )
                    scope.launch {
                        saving = true
                        try {
                            onSave(task)
                            onDismiss()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = failure.message ?: "任务保存失败"
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun TaskError(message: String, onDismiss: (() -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

private fun nextTaskHour(): Long = Calendar.getInstance().apply {
    add(Calendar.HOUR_OF_DAY, 1)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatTaskDate(time: Long, pattern: String = "MM月dd日 E HH:mm"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))

private fun ScheduledTaskRepeat.label(): String = when (this) {
    ScheduledTaskRepeat.ONCE -> "一次"
    ScheduledTaskRepeat.DAILY -> "每日"
    ScheduledTaskRepeat.WEEKLY -> "每周"
}

private fun ScheduledTaskStatus.label(): String = when (this) {
    ScheduledTaskStatus.IDLE -> "等待执行"
    ScheduledTaskStatus.RUNNING -> "正在执行"
    ScheduledTaskStatus.SUCCEEDED -> "执行完成"
    ScheduledTaskStatus.FAILED -> "执行失败"
}
