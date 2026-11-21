package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetDiskAccountFileDisplayTest {
    @Test fun namesUseNaturalNumbersWithoutIntegerOverflow() {
        val names = listOf("章节100000000000000000000.txt", "章节10.txt", "章节02.txt", "章节2.txt", "章节1.txt")
        assertEquals(listOf("章节1.txt", "章节2.txt", "章节02.txt", "章节10.txt", "章节100000000000000000000.txt"),
            displayedNetDiskAccountFiles(names.map { NetDiskFile(it, it) }).map { it.name })
    }

    @Test fun chineseNamesHaveAStableChineseOrder() {
        val files = listOf("中文.txt", "八月.txt", "阿姨.txt").map { NetDiskFile(it, it) }
        val expected = listOf("阿姨.txt", "八月.txt", "中文.txt")
        assertEquals(expected, displayedNetDiskAccountFiles(files).map { it.name })
        assertEquals(expected, displayedNetDiskAccountFiles(files.reversed()).map { it.name })
    }

    @Test fun foldersStayFirstEvenWhenSizeWouldPutThemLast() {
        val files = listOf(NetDiskFile("file", "a.txt", size = 50),
            NetDiskFile("huge-folder", "目录10", isDirectory = true, size = Long.MAX_VALUE),
            NetDiskFile("small-folder", "目录2", isDirectory = true, size = 0))
        NetDiskAccountFileSort.entries.forEach { sort ->
            assertTrue(sort.name, displayedNetDiskAccountFiles(files, sort = sort).take(2).all { it.isDirectory })
        }
        assertEquals(listOf("small-folder", "huge-folder", "file"),
            displayedNetDiskAccountFiles(files, sort = NetDiskAccountFileSort.SIZE_DESC).map { it.id })
    }

    @Test fun bothSortDirectionsAndEqualNameIdsAreDeterministic() {
        val files = listOf(NetDiskFile("z", "same.txt", size = 20), NetDiskFile("a", "same.txt", size = 20),
            NetDiskFile("small", "item10.txt", size = 1), NetDiskFile("big", "item2.txt", size = 100))
        assertEquals(listOf("small", "a", "z", "big"),
            displayedNetDiskAccountFiles(files, sort = NetDiskAccountFileSort.SIZE_ASC).map { it.id })
        assertEquals(listOf("big", "a", "z", "small"),
            displayedNetDiskAccountFiles(files, sort = NetDiskAccountFileSort.SIZE_DESC).map { it.id })
        assertEquals(listOf("a", "z", "small", "big"),
            displayedNetDiskAccountFiles(files, sort = NetDiskAccountFileSort.NAME_DESC).map { it.id })
        assertEquals(displayedNetDiskAccountFiles(files), displayedNetDiskAccountFiles(files.reversed()))
    }

    @Test fun typeFiltersRecognizeNamesAndMimeWithoutIncludingFolders() {
        val files = listOf(NetDiskFile("folder", "照片.JPG", isDirectory = true),
            NetDiskFile("doc", "小说.EPUB"), NetDiskFile("image", "照片.JPG"),
            NetDiskFile("video", "视频.mkv"), NetDiskFile("audio", "录音", mimeType = "audio/ogg"),
            NetDiskFile("archive", "备份.tar.gz"), NetDiskFile("other", "安装包.apk"))
        assertEquals(listOf("folder"), displayedNetDiskAccountFiles(files, NetDiskAccountFileFilter.FOLDERS).map { it.id })
        assertEquals(6, displayedNetDiskAccountFiles(files, NetDiskAccountFileFilter.FILES).size)
        mapOf(NetDiskAccountFileFilter.DOCUMENTS to "doc", NetDiskAccountFileFilter.IMAGES to "image",
            NetDiskAccountFileFilter.VIDEOS to "video", NetDiskAccountFileFilter.AUDIO to "audio",
            NetDiskAccountFileFilter.ARCHIVES to "archive", NetDiskAccountFileFilter.OTHER to "other").forEach { (filter, id) ->
            assertEquals(filter.name, listOf(id), displayedNetDiskAccountFiles(files, filter).map { it.id })
        }
    }

    @Test fun filteringDoesNotDiscardLoadedPagesAndNewPagesJoinTheWholeSort() {
        val firstPage = listOf(NetDiskFile("10", "file10.txt"), NetDiskFile("image", "photo.png"))
        val allPages = firstPage + NetDiskFile("2", "file2.txt")
        assertTrue(displayedNetDiskAccountFiles(allPages, NetDiskAccountFileFilter.AUDIO).isEmpty())
        assertEquals(listOf("2", "10"), displayedNetDiskAccountFiles(allPages, NetDiskAccountFileFilter.DOCUMENTS).map { it.id })
        assertEquals(3, allPages.size)
        assertEquals(listOf("2", "10", "image"), displayedNetDiskAccountFiles(allPages).map { it.id })
        assertEquals(listOf("10", "image"), firstPage.map { it.id })
    }
}
