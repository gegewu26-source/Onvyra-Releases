package com.constx.onvyra.ui.pages.netdisk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.constx.onvyra.data.netdisk.*
import com.constx.onvyra.ui.context.LocalToaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01

internal data class NetDiskAccountUiState(
    val folders: List<NetDiskFolder> = listOf(NetDiskFolder("", "我的文件")),
    val files: List<NetDiskFile> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val busyFileId: String? = null,
    val error: String? = null,
    val download: NetDiskDownload? = null,
)

/** Read-only account listing. No mutating operations are exposed to this UI. */
internal class NetDiskAccountViewModel(
    private val platform: NetDiskPlatform,
    private val repository: NetDiskAccountRepository,
) : ViewModel() {
    var state by mutableStateOf(NetDiskAccountUiState())
        private set
    private var revision = -1
    private var generation = 0L
    private var operation: Job? = null

    fun credentialsChanged(value: Int) {
        if (value == revision) return
        revision = value
        state = NetDiskAccountUiState()
        load(state.folders)
    }
    fun refresh() = load(state.folders)
    fun back() { if (state.folders.size > 1) load(state.folders.dropLast(1)) }
    fun folder(index: Int) { if (index in state.folders.indices) load(state.folders.take(index + 1)) }
    fun more() { if (state.nextCursor != null) load(state.folders, state.nextCursor) }
    fun dismissDownload() { state = state.copy(download = null) }
    fun open(file: NetDiskFile) {
        if (file.isDirectory) load(state.folders + NetDiskFolder(file.id, file.name))
        else run(file.id) { state.copy(download = repository.resolve(platform, file)) }
    }
    private fun load(path: List<NetDiskFolder>, cursor: String? = null) {
        val previous = if (cursor == null) emptyList() else state.files
        run {
            val listing = repository.list(platform, path.last().id, cursor)
            state.copy(folders = path, files = (previous + listing.files).distinctBy { it.id },
                nextCursor = listing.nextCursor, loaded = true, download = null)
        }
    }
    private fun run(file: String? = null, block: suspend () -> NetDiskAccountUiState) {
        operation?.cancel()
        val ticket = ++generation
        state = state.copy(loading = true, error = null, busyFileId = file)
        operation = viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { block() }
                if (ticket == generation) state = next.copy(loading = false, error = null, busyFileId = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == generation) state = state.copy(loading = false, busyFileId = null,
                    error = (error as? NetDiskException)?.message?.take(300) ?: "读取网盘失败，请刷新重试。")
            } finally { if (ticket == generation) operation = null }
        }
    }
}

@Composable
internal fun NetDiskAccountContent(
    platform: NetDiskPlatform,
    repository: NetDiskRepository,
    credentialRevision: Int,
    onLogin: () -> Unit,
    onWeb: () -> Unit,
    onDownload: (NetDiskDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(platform, repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NetDiskAccountViewModel(platform, NetDiskAccountRepository(repository)) as T
        }
    }
    val vm: NetDiskAccountViewModel = viewModel(key = "netdisk-personal-${platform.name}", factory = factory)
    val state = vm.state
    var fileFilter by rememberSaveable(platform) { mutableStateOf(NetDiskAccountFileFilter.ALL) }
    var fileSort by rememberSaveable(platform) { mutableStateOf(NetDiskAccountFileSort.NAME_ASC) }
    val displayedFiles = remember(state.files, fileFilter, fileSort) {
        displayedNetDiskAccountFiles(state.files, fileFilter, fileSort)
    }
    val clipboard = LocalClipboardManager.current
    val toaster = LocalToaster.current
    LaunchedEffect(credentialRevision) { vm.credentialsChanged(credentialRevision) }
    BackHandler(enabled = state.folders.size > 1, onBack = vm::back)
    Column(modifier.fillMaxSize().testTag("netdisk-account-files")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("我的文件", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (state.folders.size > 1) IconButton(onClick = vm::back, enabled = !state.loading) {
                Icon(HugeIcons.ArrowUp01, "返回上一级")
            }
            TextButton(onClick = onWeb) { Text("网页版") }
            IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(HugeIcons.Refresh01, "刷新文件目录") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            state.folders.forEachIndexed { index, entry ->
                if (index > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.folder(index) }, enabled = !state.loading) {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        NetDiskAccountFileControls(fileFilter, fileSort, displayedFiles.size, state.files.size,
            onFilter = { fileFilter = it }, onSort = { fileSort = it })
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.error?.let { message -> item("error") {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("netdisk-account-error"))
                        Row {
                            TextButton(onClick = vm::refresh, enabled = !state.loading) { Text("重试") }
                            TextButton(onClick = onLogin) { Text("账号与登录") }
                        }
                    }
                }
            } }
            if (!state.loading && state.loaded && state.error == null && displayedFiles.isEmpty()) item("empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center) {
                    val message = when {
                        state.files.isEmpty() && state.nextCursor == null -> "此目录暂无文件"
                        state.nextCursor != null -> if (fileFilter == NetDiskAccountFileFilter.ALL) {
                            "已加载内容暂无文件，可继续加载"
                        } else "已加载内容没有匹配项，可继续加载"
                        else -> "当前筛选没有匹配的文件"
                    }
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("netdisk-account-empty"))
                }
            }
            if (state.loading && state.files.isEmpty()) item("loading") {
                Text("正在读取本人网盘文件…", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            itemsIndexed(displayedFiles, key = { _, file -> file.id }) { _, file ->
                Surface(onClick = { vm.open(file) }, enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().testTag(if (file.isDirectory) "netdisk-account-folder" else "netdisk-account-file"),
                    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (file.isDirectory) HugeIcons.Folder01 else HugeIcons.File01, null,
                            Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (file.isDirectory) "文件夹" else "${netDiskAccountFileKind(file).label} · ${netDiskDownloadSize(file.size)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.busyFileId == file.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(if (file.isDirectory) HugeIcons.ArrowRight01 else HugeIcons.Download01, null, Modifier.size(18.dp))
                    }
                }
            }
            if (state.nextCursor != null) item("more") {
                OutlinedButton(onClick = vm::more, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
            }
        }
    }
    state.download?.let { link ->
        NetDiskDownloadDialog(link, onDismiss = vm::dismissDownload,
            onDownload = { vm.dismissDownload(); onDownload(link) },
            onCopy = { clipboard.setText(AnnotatedString(link.url)); toaster.show("已复制直链") })
    }
}

@Composable
private fun NetDiskAccountFileControls(
    filter: NetDiskAccountFileFilter,
    sort: NetDiskAccountFileSort,
    visibleCount: Int,
    loadedCount: Int,
    onFilter: (NetDiskAccountFileFilter) -> Unit,
    onSort: (NetDiskAccountFileSort) -> Unit,
) {
    var filterMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            FilledTonalButton(onClick = { filterMenu = true }, modifier = Modifier.testTag("netdisk-account-filter"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${filter.label} ▾", maxLines = 1)
            }
            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                NetDiskAccountFileFilter.entries.forEach { entry ->
                    DropdownMenuItem(text = { Text(entry.label) }, onClick = { filterMenu = false; onFilter(entry) })
                }
            }
        }
        Box {
            FilledTonalButton(onClick = { sortMenu = true }, modifier = Modifier.testTag("netdisk-account-sort"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${sort.label} ▾", maxLines = 1)
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                NetDiskAccountFileSort.entries.forEach { entry ->
                    DropdownMenuItem(text = { Text(entry.label) }, onClick = { sortMenu = false; onSort(entry) })
                }
            }
        }
        Text(if (filter == NetDiskAccountFileFilter.ALL) "已加载 $loadedCount 项" else "$visibleCount / $loadedCount 项",
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
