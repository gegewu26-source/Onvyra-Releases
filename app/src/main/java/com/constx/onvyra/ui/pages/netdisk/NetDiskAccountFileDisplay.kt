package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskFile
import java.text.Collator
import java.util.Locale

internal enum class NetDiskAccountFileFilter(val label: String) {
    ALL("全部"), FOLDERS("文件夹"), FILES("所有文件"), DOCUMENTS("文档"), IMAGES("图片"),
    VIDEOS("视频"), AUDIO("音频"), ARCHIVES("压缩包"), OTHER("其他文件"),
}

internal enum class NetDiskAccountFileSort(val label: String) {
    NAME_ASC("名称升序"), NAME_DESC("名称降序"), SIZE_ASC("大小升序"), SIZE_DESC("大小降序"),
}

/** Display policy only: it never changes the provider's listing, accumulated pages, or cursor. */
internal fun displayedNetDiskAccountFiles(
    files: List<NetDiskFile>,
    filter: NetDiskAccountFileFilter = NetDiskAccountFileFilter.ALL,
    sort: NetDiskAccountFileSort = NetDiskAccountFileSort.NAME_ASC,
): List<NetDiskFile> {
    // A fixed Chinese locale keeps ordering independent of the device's current UI language.
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply { strength = Collator.SECONDARY }
    val comparator = Comparator<NetDiskFile> { left, right ->
        when {
            left.isDirectory != right.isDirectory -> if (left.isDirectory) -1 else 1
            else -> {
                // Folder sizes usually have no meaning, so size sorting keeps folders in name order.
                val order = when {
                    sort == NetDiskAccountFileSort.NAME_DESC -> compareNaturalNames(right.name, left.name, collator)
                    sort == NetDiskAccountFileSort.SIZE_ASC && !left.isDirectory -> left.size.compareTo(right.size)
                    sort == NetDiskAccountFileSort.SIZE_DESC && !left.isDirectory -> right.size.compareTo(left.size)
                    else -> compareNaturalNames(left.name, right.name, collator)
                }
                if (order != 0) order else {
                    val nameOrder = compareNaturalNames(left.name, right.name, collator)
                    if (nameOrder != 0) nameOrder else left.id.compareTo(right.id)
                }
            }
        }
    }
    return files.asSequence().filter { file ->
        when (filter) {
            NetDiskAccountFileFilter.ALL -> true
            NetDiskAccountFileFilter.FILES -> !file.isDirectory
            else -> netDiskAccountFileKind(file) == filter
        }
    }.sortedWith(comparator).toList()
}

internal fun netDiskAccountFileKind(file: NetDiskFile): NetDiskAccountFileFilter {
    if (file.isDirectory) return NetDiskAccountFileFilter.FOLDERS
    val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "txt", "md", "markdown", "pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods",
        "csv", "ppt", "pptx", "odp", "epub", "mobi", "azw", "azw3", "djvu", "json", "xml",
        "yaml", "yml", "log", "srt", "ass", "html", "htm" -> NetDiskAccountFileFilter.DOCUMENTS
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "tif", "tiff" -> NetDiskAccountFileFilter.IMAGES
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "mpeg", "mpg", "3gp", "rmvb" -> NetDiskAccountFileFilter.VIDEOS
        "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "aiff", "ape" -> NetDiskAccountFileFilter.AUDIO
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst" -> NetDiskAccountFileFilter.ARCHIVES
        else -> when {
            file.mimeType.startsWith("image/", ignoreCase = true) -> NetDiskAccountFileFilter.IMAGES
            file.mimeType.startsWith("video/", ignoreCase = true) -> NetDiskAccountFileFilter.VIDEOS
            file.mimeType.startsWith("audio/", ignoreCase = true) -> NetDiskAccountFileFilter.AUDIO
            file.mimeType.startsWith("text/", ignoreCase = true) || file.mimeType == "application/pdf" -> NetDiskAccountFileFilter.DOCUMENTS
            else -> NetDiskAccountFileFilter.OTHER
        }
    }
}

private fun compareNaturalNames(left: String, right: String, collator: Collator): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftDigit = left[leftIndex] in '0'..'9'
        val rightDigit = right[rightIndex] in '0'..'9'
        if (leftDigit != rightDigit) return if (leftDigit) -1 else 1
        if (leftDigit && rightDigit) {
            var leftEnd = leftIndex
            var rightEnd = rightIndex
            while (leftEnd < left.length && left[leftEnd] in '0'..'9') leftEnd++
            while (rightEnd < right.length && right[rightEnd] in '0'..'9') rightEnd++
            var leftSignificant = leftIndex
            var rightSignificant = rightIndex
            while (leftSignificant < leftEnd && left[leftSignificant] == '0') leftSignificant++
            while (rightSignificant < rightEnd && right[rightSignificant] == '0') rightSignificant++
            // Compare digit lengths and text instead of parsing, which also handles huge numbers.
            val lengthOrder = (leftEnd - leftSignificant).compareTo(rightEnd - rightSignificant)
            if (lengthOrder != 0) return lengthOrder
            val numberOrder = left.substring(leftSignificant, leftEnd).compareTo(right.substring(rightSignificant, rightEnd))
            if (numberOrder != 0) return numberOrder
            val zeroOrder = (leftEnd - leftIndex).compareTo(rightEnd - rightIndex)
            if (zeroOrder != 0) return zeroOrder
            leftIndex = leftEnd
            rightIndex = rightEnd
        } else {
            var leftEnd = leftIndex + 1
            var rightEnd = rightIndex + 1
            while (leftEnd < left.length && left[leftEnd] !in '0'..'9') leftEnd++
            while (rightEnd < right.length && right[rightEnd] !in '0'..'9') rightEnd++
            val textOrder = collator.compare(left.substring(leftIndex, leftEnd), right.substring(rightIndex, rightEnd))
            if (textOrder != 0) return textOrder
            leftIndex = leftEnd
            rightIndex = rightEnd
        }
    }
    val endOrder = (left.length - leftIndex).compareTo(right.length - rightIndex)
    return if (endOrder != 0) endOrder else left.compareTo(right)
}
