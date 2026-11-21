package com.constx.onvyra.data.repository

import java.util.concurrent.ConcurrentHashMap
import com.constx.onvyra.data.db.entity.WorkspaceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WorkspaceDisabledException : IllegalStateException("工作区已停用，请先在工作区列表中启用。")

internal fun requireEnabledWorkspace(workspace: WorkspaceEntity?): WorkspaceEntity =
    (workspace ?: error("Workspace not found")).also { if (!it.enabled) throw WorkspaceDisabledException() }

/** Tracks only command/install child jobs, so stopping one workspace never cancels a chat or another workspace. */
internal class WorkspaceOperationRegistry {
    private val lock = Mutex()
    private val transitions = ConcurrentHashMap<String, Mutex>()
    private val running = mutableMapOf<String, MutableSet<Job>>()

    suspend fun <T> run(id: String, checkEnabled: suspend () -> Unit, block: suspend () -> T): T = coroutineScope {
        val operation = currentCoroutineContext().job
        lock.withLock {
            checkEnabled()
            running.getOrPut(id) { mutableSetOf() }.add(operation)
        }
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    running[id]?.let { jobs ->
                        jobs.remove(operation)
                        if (jobs.isEmpty()) running.remove(id)
                    }
                }
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean, persist: suspend () -> Boolean,
        stopSessions: suspend () -> Unit): Boolean {
        val caller = currentCoroutineContext().job
        currentCoroutineContext().ensureActive()
        // Once the update starts, finishing its targeted shutdown must not depend on a screen staying open.
        return withContext(NonCancellable) { transitions.getOrPut(id) { Mutex() }.withLock {
            var jobs: List<Job> = emptyList()
            val changed = lock.withLock {
                persist().also { saved ->
                    if (saved && !enabled) jobs = running.remove(id).orEmpty().filter { it !== caller }
                }
            }
            if (changed && !enabled) {
                jobs.forEach { it.cancel(CancellationException("Workspace disabled")) }
                try { stopSessions() } finally { jobs.joinAll() }
            }
            changed
        } }
    }
}
