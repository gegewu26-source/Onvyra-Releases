package com.constx.onvyra.ui.pages.extensions.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

/** A late directory result must not replace the listing for a newer location. */
internal class WorkspaceFileListingLoader(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<WorkspaceDetailState>,
    private val listFiles: suspend (WorkspaceStorageArea, String) -> List<WorkspaceFileEntry>,
) {
    private var revision = 0L
    private var job: Job? = null

    @Synchronized
    fun refresh() {
        val location = state.value
        val request = Request(++revision, location.area, location.path)
        job?.cancel()
        state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try {
                val entries = listFiles(request.area, request.path)
                updateIfCurrent(request) { it.copy(entries = entries, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                updateIfCurrent(request) {
                    it.copy(entries = emptyList(), loading = false,
                        error = error.message ?: "加载工作区文件失败")
                }
            }
        }
    }

    @Synchronized
    private fun updateIfCurrent(request: Request, update: (WorkspaceDetailState) -> WorkspaceDetailState) {
        if (request.revision != revision) return
        state.update { current ->
            if (current.area == request.area && current.path == request.path) update(current) else current
        }
    }

    private data class Request(val revision: Long, val area: WorkspaceStorageArea, val path: String)
}
