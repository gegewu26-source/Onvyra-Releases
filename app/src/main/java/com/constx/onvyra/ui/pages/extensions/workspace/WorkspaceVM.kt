package com.constx.onvyra.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.constx.onvyra.data.db.entity.WorkspaceEntity
import com.constx.onvyra.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val _busyWorkspaceIds = MutableStateFlow<Set<String>>(emptySet())
    val busyWorkspaceIds = _busyWorkspaceIds.asStateFlow()
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError = _operationError.asStateFlow()

    fun clearOperationError() { _operationError.value = null }

    fun setEnabled(workspace: WorkspaceEntity, enabled: Boolean) {
        if (workspace.id in _busyWorkspaceIds.value) return
        _busyWorkspaceIds.update { it + workspace.id }
        viewModelScope.launch {
            try {
                if (!repository.setEnabled(workspace.id, enabled)) {
                    _operationError.value = "工作区已不存在，请刷新后重试。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _operationError.value = "工作区状态未能更新，请稍后重试。" }
            finally { _busyWorkspaceIds.update { it - workspace.id } }
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            runCatching { repository.create(name) }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            terminalSessionManager.closeWorkspace(workspace.root)
            repository.delete(workspace.id)
        }
    }
}
