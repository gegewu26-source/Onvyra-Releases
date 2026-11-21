package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.download.netDiskPublishedFileName
import com.constx.onvyra.data.netdisk.download.netDiskPublishSize
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class NetDiskDownloadPublisherPolicyTest {
    private val id = "f55499be-e746-438a-9e34-039823fb5424"

    @Test fun longChineseAndEmojiNamesRemainValidUtf8AndKeepTheExtensionWithinTheFullBudget() {
        for (name in listOf("测试文件".repeat(100) + ".zip", "🦆".repeat(200) + ".mp4")) {
            val result = netDiskPublishedFileName(name, id)
            assertTrue(result.toByteArray(Charsets.UTF_8).size <= 200)
            assertTrue(result.startsWith("${id}_"))
            assertTrue(result.endsWith(name.substring(name.lastIndexOf('.'))))
            assertEquals(result, result.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        }
    }

    @Test fun pathAndControlCharactersCannotEscapeTheTasksUniqueOutputName() {
        assertEquals("${id}_file.txt", netDiskPublishedFileName("../../file.txt", id))
        assertEquals("${id}_file.txt", netDiskPublishedFileName("..\\..\\file.txt", id))
        val cleaned = netDiskPublishedFileName("a\n:b?.txt", id)
        assertFalse(cleaned.any { it == '\n' || it == ':' || it == '?' || it == '/' || it == '\\' })
        assertThrows(IOException::class.java) { netDiskPublishedFileName("file.txt", "../another-file") }
    }

    @Test fun distinctTasksNeverPublishTheSameDestinationEvenWhenFileNamesMatch() {
        assertNotEquals(netDiskPublishedFileName("同名.zip", id), netDiskPublishedFileName("同名.zip", "second-task"))
        assertTrue(netDiskPublishedFileName("....", id).endsWith("网盘文件"))
    }

    @Test fun zeroByteFilesAreValidButAnyKnownLengthMismatchStopsPublication() {
        assertEquals(0L, netDiskPublishSize(0, 0))
        assertEquals(42L, netDiskPublishSize(-1, 42))
        assertEquals(42L, netDiskPublishSize(42, 42))
        assertThrows(IOException::class.java) { netDiskPublishSize(42, 41) }
        assertThrows(IOException::class.java) { netDiskPublishSize(42, 43) }
    }
}
