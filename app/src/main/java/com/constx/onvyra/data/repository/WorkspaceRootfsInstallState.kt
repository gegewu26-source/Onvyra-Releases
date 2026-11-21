package com.constx.onvyra.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceShellStatus

/** Recover a stored in-progress install after process death, while preserving disabled choices. */
internal fun workspaceShellStateAfterIntegrity(status: String, hasRootfs: Boolean): String = when {
    status == WorkspaceShellStatus.INSTALLING.name ->
        if (hasRootfs) WorkspaceShellStatus.READY.name else WorkspaceShellStatus.DISABLED.name
    status == WorkspaceShellStatus.READY.name && !hasRootfs -> WorkspaceShellStatus.DISABLED.name
    else -> status
}

/** Persist installation progress without disabling a Rootfs which survived a failed update. */
internal suspend fun runWorkspaceRootfsInstallation(
    previousStatus: String,
    hasUsableRootfs: suspend () -> Boolean,
    setStatus: suspend (String) -> Unit,
    install: suspend () -> Unit,
) {
    setStatus(WorkspaceShellStatus.INSTALLING.name)
    try {
        install()
        setStatus(WorkspaceShellStatus.READY.name)
    } catch (error: Throwable) {
        val cancelled = error is CancellationException || error is InterruptedException
        withContext(NonCancellable) {
            try {
                val restoredStatus = when {
                    hasUsableRootfs() -> WorkspaceShellStatus.READY
                    cancelled && previousStatus == WorkspaceShellStatus.DISABLED.name -> WorkspaceShellStatus.DISABLED
                    else -> WorkspaceShellStatus.BROKEN
                }
                setStatus(restoredStatus.name)
            } catch (stateError: Throwable) {
                // Keep the original download/extract/cancellation error visible to the caller.
                if (stateError !== error) error.addSuppressed(stateError)
            }
        }
        if (error is InterruptedException) {
            throw CancellationException("Rootfs install cancelled").also { it.initCause(error) }
        }
        throw error
    }
}
