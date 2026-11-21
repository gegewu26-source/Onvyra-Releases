package com.constx.onvyra.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WorkspaceRootfsInstallStateTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun manager() = WorkspaceManager(temporary.newFolder())
    private fun installShell(manager: WorkspaceManager) {
        File(manager.linuxDir("sample"), "bin").mkdirs()
        File(manager.linuxDir("sample"), "bin/sh").writeText("#!/bin/sh\n")
    }

    @Test fun `startup recovers a killed update when the old shell survived`() {
        val manager = manager(); manager.ensureWorkspace("sample"); installShell(manager)
        assertEquals("READY", workspaceShellStateAfterIntegrity("INSTALLING", manager.hasRootfs("sample")))
    }

    @Test fun `startup clears a killed first install when no shell exists`() {
        val manager = manager(); manager.ensureWorkspace("sample")
        assertEquals("DISABLED", workspaceShellStateAfterIntegrity("INSTALLING", manager.hasRootfs("sample")))
        assertEquals("DISABLED", workspaceShellStateAfterIntegrity("READY", manager.hasRootfs("sample")))
    }

    @Test fun `startup retains disabled or broken choices rather than silently enabling them`() {
        val manager = manager(); manager.ensureWorkspace("sample"); installShell(manager)
        assertEquals("DISABLED", workspaceShellStateAfterIntegrity("DISABLED", manager.hasRootfs("sample")))
        assertEquals("BROKEN", workspaceShellStateAfterIntegrity("BROKEN", manager.hasRootfs("sample")))
    }

    @Test fun `failed update preserves readiness when the original shell still exists`() = runBlocking {
        val manager = manager(); manager.ensureWorkspace("sample"); installShell(manager)
        val states = mutableListOf<String>(); val failure = IOException("download failed")
        val error = runCatching {
            runWorkspaceRootfsInstallation("READY", { manager.hasRootfs("sample") }, { states.add(it) }) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, error)
        assertEquals(listOf("INSTALLING", "READY"), states)
        assertTrue(manager.hasRootfs("sample"))
    }

    @Test fun `a failed first install is broken and keeps its original error`() = runBlocking {
        val manager = manager(); manager.ensureWorkspace("sample")
        val states = mutableListOf<String>(); val failure = IOException("invalid archive")
        val error = runCatching {
            runWorkspaceRootfsInstallation("DISABLED", { manager.hasRootfs("sample") }, { states.add(it) }) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, error)
        assertEquals(listOf("INSTALLING", "BROKEN"), states)
    }

    @Test fun `cancelled first install returns to disabled`() = runBlocking {
        val manager = manager(); manager.ensureWorkspace("sample")
        val states = mutableListOf<String>(); val cancellation = CancellationException("cancel")
        val error = runCatching {
            runWorkspaceRootfsInstallation("DISABLED", { manager.hasRootfs("sample") }, { states.add(it) }) { throw cancellation }
        }.exceptionOrNull()
        assertSame(cancellation, error)
        assertEquals(listOf("INSTALLING", "DISABLED"), states)
    }

    @Test fun `cancelled replacement restores actual ready state instead of stale broken state`() = runBlocking {
        val manager = manager(); manager.ensureWorkspace("sample"); installShell(manager)
        val states = mutableListOf<String>()
        val error = runCatching {
            runWorkspaceRootfsInstallation("BROKEN", { manager.hasRootfs("sample") }, { states.add(it) }) {
                throw CancellationException("cancel replacement")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(listOf("INSTALLING", "READY"), states)
    }

    @Test fun `failure cannot report ready when the old shell no longer exists`() = runBlocking {
        val manager = manager(); manager.ensureWorkspace("sample"); installShell(manager)
        val states = mutableListOf<String>()
        runCatching {
            runWorkspaceRootfsInstallation("READY", { manager.hasRootfs("sample") }, { states.add(it) }) {
                manager.linuxDir("sample").deleteRecursively()
                throw IOException("replacement failed")
            }
        }
        assertEquals(listOf("INSTALLING", "BROKEN"), states)
    }

    @Test fun `successful install records progress then readiness`() = runBlocking {
        val manager = manager(); manager.ensureWorkspace("sample")
        val states = mutableListOf<String>()
        runWorkspaceRootfsInstallation("DISABLED", { manager.hasRootfs("sample") }, { states.add(it) }) {
            installShell(manager)
        }
        assertEquals(listOf("INSTALLING", "READY"), states)
        assertTrue(manager.hasRootfs("sample"))
    }

    @Test fun `interrupted download remains cancellation and restores the surviving shell`() = runBlocking {
        val manager = manager(); manager.ensureWorkspace("sample"); installShell(manager)
        val states = mutableListOf<String>(); val failure = InterruptedException("interrupted")
        val error = runCatching {
            runWorkspaceRootfsInstallation("READY", { manager.hasRootfs("sample") }, { states.add(it) }) { throw failure }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertSame(failure, error?.cause)
        assertEquals(listOf("INSTALLING", "READY"), states)
    }

    @Test fun `a restoration storage failure does not hide the install error`() = runBlocking {
        val failure = IOException("original download failure")
        val storageFailure = IOException("status storage failure")
        val error = runCatching {
            runWorkspaceRootfsInstallation("READY", { true }, {
                if (it != WorkspaceShellStatus.INSTALLING.name) throw storageFailure
            }) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, error)
        assertTrue(error!!.suppressed.contains(storageFailure))
    }
}
