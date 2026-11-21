package me.rerere.workspace

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream

class RootfsInstallSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private val workspace = "test-workspace"

    @Test fun `an archive without a shell cannot replace a working rootfs`() {
        val manager = managerWithOldRootfs()
        val installedStages = mutableListOf<RootfsInstallStage>()
        withArchive(tarGz("etc/not-a-shell.txt" to "not rootfs")) { url ->
            assertThrows(IllegalArgumentException::class.java) {
                RootfsInstaller(manager).install(workspace, url) { installedStages += it.stage }
            }
        }
        assertOldEnvironment(manager)
        assertFalse(RootfsInstallStage.INSTALLED in installedStages)
    }

    @Test fun `an empty rootfs is not reported as a successful first installation`() {
        val manager = WorkspaceManager(temp.newFolder("workspaces"))
        withArchive(tarGz()) { url ->
            assertThrows(IllegalArgumentException::class.java) {
                RootfsInstaller(manager).install(workspace, url)
            }
        }
        assertFalse(manager.hasRootfs(workspace))
    }

    @Test fun `a failed staging patch preserves the old shell and workspace files`() {
        val manager = managerWithOldRootfs()
        // A non-empty directory where resolv.conf should be makes the real patch fail.
        val badPatch = tarGz("bin/sh" to "new shell", "etc/resolv.conf/keep.txt" to "not a config file")
        withArchive(badPatch) { url ->
            assertThrows(Exception::class.java) { RootfsInstaller(manager).install(workspace, url) }
        }
        assertOldEnvironment(manager)
    }

    @Test fun `successful installation commits the prepared environment and preserves workspace files`() {
        val manager = managerWithOldRootfs()
        val stages = mutableListOf<RootfsInstallStage>()
        withArchive(tarGz("bin/sh" to "new shell", "etc/os-release" to "NAME=Test Linux\n")) { url ->
            RootfsInstaller(manager).install(workspace, url) { stages += it.stage }
        }
        assertTrue(manager.hasRootfs(workspace))
        assertEquals("new shell", File(manager.linuxDir(workspace), "bin/sh").readText())
        assertFalse(File(manager.linuxDir(workspace), "old-only.txt").exists())
        assertTrue(File(manager.linuxDir(workspace), "etc/resolv.conf").readText().contains("nameserver"))
        assertEquals("my project", File(manager.filesDir(workspace), "keep.txt").readText())
        assertEquals(RootfsInstallStage.INSTALLED, stages.last())
        assertTrue(manager.tempDir(workspace).listFiles().orEmpty().none { it.name.startsWith("rootfs-") })
    }

    @Test fun `a failed final rename restores the previous rootfs`() {
        val parent = temp.newFolder("commit")
        val previous = File(parent, "linux").apply { mkdirs() }
        File(previous, "keep.txt").writeText("old")
        val staged = File(parent, "staged").apply { mkdirs() }
        File(staged, "new.txt").writeText("new")
        val backup = File(parent, "backup")
        val installer = RootfsInstaller(WorkspaceManager(temp.newFolder("manager")))
        assertThrows(IllegalStateException::class.java) {
            installer.commitRootfs(staged, previous, backup) { from, to ->
                if (from == staged) false else from.renameTo(to)
            }
        }
        assertEquals("old", File(previous, "keep.txt").readText())
        assertEquals("new", File(staged, "new.txt").readText())
        assertFalse(backup.exists())
    }

    @Test fun `an unsuccessful rollback keeps the backup recoverable`() {
        val parent = temp.newFolder("commit")
        val previous = File(parent, "linux").apply { mkdirs() }
        File(previous, "keep.txt").writeText("old")
        val staged = File(parent, "staged").apply { mkdirs() }
        val backup = File(parent, "backup")
        val installer = RootfsInstaller(WorkspaceManager(temp.newFolder("manager")))
        val error = assertThrows(IllegalStateException::class.java) {
            installer.commitRootfs(staged, previous, backup) { from, to ->
                if (from == previous) from.renameTo(to) else false
            }
        }
        assertEquals("old", File(backup, "keep.txt").readText())
        assertTrue(error.suppressed.any { it.message.orEmpty().contains(backup.absolutePath) })
    }

    private fun managerWithOldRootfs(): WorkspaceManager =
        WorkspaceManager(temp.newFolder("workspaces")).also { manager ->
            manager.ensureWorkspace(workspace)
            File(manager.linuxDir(workspace), "bin").mkdirs()
            File(manager.linuxDir(workspace), "bin/sh").writeText("old shell")
            File(manager.linuxDir(workspace), "old-only.txt").writeText("old environment")
            File(manager.filesDir(workspace), "keep.txt").writeText("my project")
        }

    private fun assertOldEnvironment(manager: WorkspaceManager) {
        assertTrue(manager.hasRootfs(workspace))
        assertEquals("old shell", File(manager.linuxDir(workspace), "bin/sh").readText())
        assertEquals("old environment", File(manager.linuxDir(workspace), "old-only.txt").readText())
        assertEquals("my project", File(manager.filesDir(workspace), "keep.txt").readText())
    }

    private fun withArchive(bytes: ByteArray, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rootfs.tar.gz") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/rootfs.tar.gz") }
        finally { server.stop(0) }
    }

    private fun tarGz(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().use { bytes ->
        GZIPOutputStream(bytes).use { output ->
            files.forEach { (name, content) -> output.writeEntry(name, content.toByteArray()) }
            output.write(ByteArray(1024))
        }
        bytes.toByteArray()
    }

    private fun OutputStream.writeEntry(name: String, data: ByteArray) {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
        header[156] = '0'.code.toByte()
        write(header)
        write(data)
        write(ByteArray((512 - data.size % 512) % 512))
    }
}
