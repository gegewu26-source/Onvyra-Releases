package com.constx.onvyra.ui.pages.netdisk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.constx.onvyra.data.netdisk.NetDiskDownload
import com.constx.onvyra.data.netdisk.NetDiskFile
import com.constx.onvyra.data.netdisk.NetDiskException
import com.constx.onvyra.data.netdisk.NetDiskPlatform
import com.constx.onvyra.data.netdisk.NetDiskRepository
import com.constx.onvyra.data.netdisk.NetDiskSession
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class NetDiskFolder(val id: String, val title: String)

internal data class NetDiskParseHistory(val input: String, val password: String, val platform: NetDiskPlatform)

internal data class NetDiskUiState(
    val session: NetDiskSession? = null,
    val files: List<NetDiskFile> = emptyList(),
    val folders: List<NetDiskFolder> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val operation: String = "",
    val busyFileId: String? = null,
    val error: String? = null,
    val download: NetDiskDownload? = null,
    val transferFile: NetDiskFile? = null,
)

/** Share sessions and signed URLs stay in memory; credentials belong to the encrypted repository. */
internal class NetDiskViewModel(val repository: NetDiskRepository, private val downloads: NetDiskDownloadManager) : ViewModel() {
    var input by mutableStateOf("")
    var password by mutableStateOf("")
    var history by mutableStateOf(emptyList<NetDiskParseHistory>())
        private set
    var state by mutableStateOf(NetDiskUiState())
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var credentialRevision by mutableStateOf(0)
        private set
    var downloadNavigationId by mutableStateOf<String?>(null)
        private set
    private var operation: Job? = null
    private var generation = 0L
    private var enqueuingDownload = false

    fun detectedPlatform(): NetDiskPlatform? = runCatching { repository.detect(input, password).platform }.getOrNull()

    fun useHistory(entry: NetDiskParseHistory) {
        cancel()
        input = entry.input
        password = entry.password
        state = NetDiskUiState()
    }

    fun clearHistory() { history = emptyList() }

    fun parse() {
        val text = input.trim()
        val code = password.trim()
        if (text.isEmpty()) {
            state = state.copy(error = "请先粘贴网盘分享链接")
            return
        }
        detectedPlatform()?.let { platform ->
            val entry = NetDiskParseHistory(text, code, platform)
            history = (listOf(entry) + history.filterNot { it.input == text && it.password == code }).take(20)
        }
        state = NetDiskUiState()
        launchOperation("正在读取分享…") {
            val session = repository.open(text, code)
            val listing = repository.list(session)
            state.copy(session = session, files = listing.files,
                folders = listOf(NetDiskFolder("", listing.title?.takeIf(String::isNotBlank)
                    ?: session.title.takeIf(String::isNotBlank) ?: "分享文件")),
                nextCursor = listing.nextCursor, download = null)
        }
    }

    fun openDirectory(file: NetDiskFile) {
        if (!file.isDirectory) return resolve(file)
        val session = state.session ?: return
        val target = state.folders + NetDiskFolder(file.id, file.name)
        launchOperation("正在打开文件夹…") {
            val listing = repository.list(session, file.id)
            state.copy(files = listing.files, folders = target, nextCursor = listing.nextCursor, download = null)
        }
    }

    fun folder(index: Int) {
        val session = state.session ?: return
        val target = state.folders.take(index + 1)
        val directory = target.lastOrNull() ?: return
        launchOperation("正在读取文件夹…") {
            val listing = repository.list(session, directory.id)
            state.copy(files = listing.files, folders = target, nextCursor = listing.nextCursor, download = null)
        }
    }

    fun refresh() {
        if (state.session == null) parse() else folder(state.folders.lastIndex)
    }

    fun more() {
        val session = state.session ?: return
        val cursor = state.nextCursor ?: return
        val directory = state.folders.lastOrNull()?.id.orEmpty()
        val previous = state.files
        launchOperation("正在加载更多…") {
            val listing = repository.list(session, directory, cursor)
            state.copy(files = (previous + listing.files).distinctBy { it.id to it.name },
                nextCursor = listing.nextCursor)
        }
    }

    fun resolve(file: NetDiskFile) {
        val session = state.session ?: return
        launchOperation("正在获取下载链接…", file.id) {
            try { state.copy(download = repository.resolve(session, file), transferFile = null) }
            catch (error: NetDiskException) {
                if (error.reason != "transfer_required") throw error
                state.copy(transferFile = file, download = null)
            }
        }
    }

    fun dismissTransfer() { state = state.copy(transferFile = null) }

    fun confirmTransfer() {
        val file = state.transferFile ?: return
        val session = state.session ?: return
        state = state.copy(transferFile = null)
        launchOperation("正在转存并获取下载链接…", file.id) {
            val approved = session.copy(extra = JsonObject(session.extra + ("allow_transfer" to JsonPrimitive(true))))
            state.copy(download = repository.resolve(approved, file), transferFile = null)
        }
    }

    fun dismissDownload() { state = state.copy(download = null) }
    fun clearNotice() { notice = null }
    fun consumeDownloadNavigation() { downloadNavigationId = null }

    fun download(link: NetDiskDownload) {
        if (enqueuingDownload) return
        enqueuingDownload = true
        state = state.copy(download = null)
        viewModelScope.launch {
            try {
                downloadNavigationId = downloads.enqueue(link)
                notice = "已加入高速下载"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(error = error.message?.take(300) ?: "暂时无法创建下载任务")
            } finally { enqueuingDownload = false }
        }
    }

    suspend fun saveCredential(platform: NetDiskPlatform, value: String) {
        cancel()
        state = state.copy(download = null, transferFile = null)
        withContext(Dispatchers.IO) { repository.saveCredential(platform, value) }
        state = NetDiskUiState()
        credentialRevision++
        notice = if (value.isBlank()) "已清除本地凭据" else "已保存到本机"
    }

    fun cancel() {
        generation++
        operation?.cancel()
        operation = null
        state = state.copy(loading = false, busyFileId = null, operation = "")
    }

    private fun launchOperation(label: String, fileId: String? = null,
        block: suspend () -> NetDiskUiState) {
        operation?.cancel()
        val ticket = ++generation
        state = state.copy(loading = true, operation = label, busyFileId = fileId, error = null)
        operation = viewModelScope.launch {
            try {
                val next = block()
                if (ticket == generation) state = next.copy(loading = false, operation = "", busyFileId = null, error = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == generation) state = state.copy(loading = false, operation = "", busyFileId = null,
                    error = error.message?.take(500) ?: "解析失败，请稍后重试")
            } finally {
                if (ticket == generation) operation = null
            }
        }
    }
}
