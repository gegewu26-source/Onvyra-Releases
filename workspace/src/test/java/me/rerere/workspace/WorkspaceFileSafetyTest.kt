package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceFileSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private val files = WorkspaceFileSystem()

    @Test fun `deleting canonical aliases of the files root is rejected`() {
        val root = temp.newFolder("files")
        val sentinel = File(root, "keep.txt").apply { writeText("keep") }
        for (path in listOf("", ".", "./", "/", "dir/..", "./dir/../")) {
            assertThrows(path, IllegalArgumentException::class.java) {
                files.delete(root, path, recursive = true)
            }
            assertTrue(root.isDirectory)
            assertEquals("keep", sentinel.readText())
        }
    }

    @Test fun `moving to the same canonical path does not delete the source`() {
        val root = temp.newFolder("files")
        val source = File(root, "original.txt").apply { writeText("original") }
        for (overwrite in listOf(false, true)) {
            val entry = files.move(root, "original.txt", "./original.txt", overwrite)
            assertEquals("original.txt", entry.path)
            assertEquals("original", source.readText())
        }
    }

    @Test fun `moving a directory to its own alias preserves its contents`() {
        val root = temp.newFolder("files")
        val directory = File(root, "project").apply { mkdirs() }
        File(directory, "readme.txt").writeText("project contents")
        val entry = files.move(root, "project", "./project/", overwrite = true)
        assertTrue(entry.isDirectory)
        assertEquals("project contents", File(directory, "readme.txt").readText())
    }

    @Test fun `moving or replacing a canonical files root is rejected before mutation`() {
        val root = temp.newFolder("files")
        val source = File(root, "keep.txt").apply { writeText("keep") }
        for (path in listOf("", ".", "./", "/", "dir/..")) {
            assertThrows(IllegalArgumentException::class.java) {
                files.move(root, path, "elsewhere", overwrite = true)
            }
            assertThrows(IllegalArgumentException::class.java) {
                files.move(root, "keep.txt", path, overwrite = true)
            }
            assertEquals("keep", source.readText())
        }
    }

    @Test fun `ordinary moves and confirmed overwrites keep working`() {
        val root = temp.newFolder("files")
        files.writeText(root, "source.txt", "new")
        files.writeText(root, "target.txt", "old")
        assertThrows(IllegalArgumentException::class.java) {
            files.move(root, "source.txt", "target.txt", overwrite = false)
        }
        assertEquals("new", files.readText(root, "source.txt"))
        assertEquals("old", files.readText(root, "target.txt"))
        assertEquals("target.txt", files.move(root, "source.txt", "target.txt", overwrite = true).path)
        assertFalse(File(root, "source.txt").exists())
        assertEquals("new", files.readText(root, "target.txt"))
    }
}
