package com.constx.onvyra.data.netdisk.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.constx.onvyra.data.netdisk.NetDiskException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Publishes only verified completed engine files. No saved user file is ever overwritten or deleted. */
internal class NetDiskDownloadPublisher(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val relativePath get() = "${Environment.DIRECTORY_DOWNLOADS}/Onvyra/网盘/"
    private val authority get() = "${app.packageName}.fileprovider"

    suspend fun publish(task: NetDiskDownloadTask, file: File, onAllocated: suspend (String, Boolean) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (!file.isFile) throw IOException("下载缓存文件不存在，请重新下载。")
            val expected = netDiskPublishSize(task.totalBytes, file.length())
            currentCoroutineContext().ensureActive()
            if (task.destinationTreeUri != null) publishTree(task, file, expected, onAllocated)
            else if (Build.VERSION.SDK_INT >= 29) publishMedia(task, file, expected, onAllocated)
            else publishLegacy(task, file, expected, onAllocated)
        }

    suspend fun reconcile(task: NetDiskDownloadTask): NetDiskDownloadTask = withContext(Dispatchers.IO) {
        if (task.status !in setOf(NetDiskDownloadStatus.SAVING, NetDiskDownloadStatus.COMPLETED)) return@withContext task
        val original = task.outputUri?.takeIf(String::isNotBlank)
            ?: return@withContext if (task.status == NetDiskDownloadStatus.SAVING) interrupted(task) else missing(task)
        try {
            if (task.destinationTreeUri != null) {
                NetDiskDestinationStore.get(app).ensureAccess(task.destinationTreeUri)
                val uri = Uri.parse(original)
                if (!ownsTreeOutput(task, uri, task.publicationPending)) return@withContext missing(task)
                if (task.publicationPending) {
                    discardPending(task)
                    interrupted(task)
                } else {
                    val size = treeSize(uri, task.totalBytes.takeIf { it >= 0L } ?: task.downloadedBytes)
                    if (matchesExpected(task, size)) completed(task, original, size) else missing(task)
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                val uri = Uri.parse(original)
                val row = mediaRow(uri) ?: return@withContext missing(task)
                if (row.pending) {
                    if (ownsPending(task, row)) discardPending(task)
                    return@withContext interrupted(task)
                }
                if (matchesExpected(task, row.size)) completed(task, original, row.size) else missing(task)
            } else {
                val outputs = legacyFiles(task)
                val partUri = providerUri(outputs.part)
                val finalUri = providerUri(outputs.final)
                if (original !in setOf(partUri, finalUri)) return@withContext missing(task)
                // A crash after rename but before writing COMPLETED must not copy the same file twice.
                if (outputs.final.isFile && matchesExpected(task, outputs.final.length())) {
                    completed(task, finalUri, outputs.final.length())
                } else if (original == partUri) {
                    discardPending(task)
                    interrupted(task)
                } else missing(task)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { task.copy(status = NetDiskDownloadStatus.FAILED,
            errorMessage = "自定义下载目录的访问权限已失效，请重新选择目录；现有文件保留。") }
        catch (error: Exception) {
            if (task.destinationTreeUri != null && error is NetDiskException && error.reason == "directory_access")
                task.copy(status = NetDiskDownloadStatus.FAILED, errorMessage = error.message)
            else missing(task)
        }
    }

    /** Called on cancellation and startup recovery. Committed rows/final files are deliberately retained. */
    suspend fun discardPending(task: NetDiskDownloadTask) = withContext(Dispatchers.IO + NonCancellable) {
        val value = task.outputUri?.takeIf(String::isNotBlank) ?: return@withContext
        if (task.destinationTreeUri != null) {
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@withContext
            if (task.publicationPending && runCatching { ownsTreeOutput(task, uri) }.getOrDefault(false))
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        } else if (Build.VERSION.SDK_INT >= 29) {
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@withContext
            val row = runCatching { mediaRow(uri) }.getOrNull() ?: return@withContext
            if (row.pending && ownsPending(task, row)) runCatching { resolver.delete(uri, null, null) }
        } else {
            val outputs = runCatching { legacyFiles(task) }.getOrNull() ?: return@withContext
            if (value == providerUri(outputs.part)) runCatching { if (outputs.part.isFile) outputs.part.delete() }
        }
        Unit
    }

    private suspend fun publishTree(task: NetDiskDownloadTask, file: File, expected: Long,
        onAllocated: suspend (String, Boolean) -> Unit): String {
        NetDiskDestinationStore.get(app).ensureAccess(task.destinationTreeUri)
        val tree = Uri.parse(requireNotNull(task.destinationTreeUri))
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, task.mimeType,
            netDiskPublishedFileName(task.fileName, task.id))
            ?: throw IOException("无法在所选目录创建下载文件，请更换目录。")
        val allocated = task.copy(outputUri = uri.toString(), publicationPending = true)
        var committed = false
        try {
            onAllocated(uri.toString(), true)
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("所选目录无法写入文件。")
            output.use { target -> FileInputStream(file).use { source -> copyVerified(source, target, expected) } }
            currentCoroutineContext().ensureActive()
            if (treeSize(uri, expected) != expected) throw IOException("所选目录保存后的文件大小不一致，请重试。")
            // The verified document becomes committed before cancellation may clean up a pending copy.
            committed = true
            withContext(NonCancellable) { onAllocated(uri.toString(), false) }
            return uri.toString()
        } catch (error: Exception) {
            if (!committed) discardPending(allocated)
            throw error
        }
    }

    private fun ownsTreeOutput(task: NetDiskDownloadTask, uri: Uri, requirePrefix: Boolean = true): Boolean {
        val tree = task.destinationTreeUri?.let(Uri::parse) ?: return false
        if (uri.scheme != "content" || uri.authority != tree.authority || !DocumentsContract.isTreeUri(uri) ||
            DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(tree)) return false
        if (DocumentsContract.getDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree)) return false
        if (!requirePrefix) return true
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        return name.startsWith("${netDiskSafeTaskId(task.id)}_")
    }

    private suspend fun treeSize(uri: Uri, expected: Long): Long {
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
        if (descriptor != null && descriptor >= 0L) return descriptor
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
        if (metadata != null && metadata >= 0L) return metadata
        // Some document providers report unknown size; count the actual readable bytes with a bound.
        var bytes = 0L
        resolver.openInputStream(uri)?.use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) return bytes
                bytes += count
                if (bytes > expected) return bytes
            }
        }
        return -1L
    }

    private suspend fun publishMedia(task: NetDiskDownloadTask, file: File, expected: Long,
        onAllocated: suspend (String, Boolean) -> Unit): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, netDiskPublishedFileName(task.fileName, task.id))
            put(MediaStore.Downloads.MIME_TYPE, task.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("系统未能创建下载文件，请检查可用空间。")
        val allocated = task.copy(outputUri = uri.toString())
        try {
            onAllocated(uri.toString(), true)
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("无法写入系统下载目录。")
            output.use { target -> FileInputStream(file).use { source -> copyVerified(source, target, expected) } }
            currentCoroutineContext().ensureActive()
            val actual = actualSize(uri)
            if (actual != expected) throw IOException("保存后的文件大小不一致，请重试。")
            val committed = resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            if (committed != 1) throw IOException("系统未能确认保存下载文件，请重试。")
            return uri.toString()
        } catch (error: Exception) {
            discardPending(allocated)
            throw error
        }
    }

    private suspend fun publishLegacy(task: NetDiskDownloadTask, file: File, expected: Long,
        onAllocated: suspend (String, Boolean) -> Unit): String {
        val outputs = legacyFiles(task)
        if ((!outputs.directory.exists() && !outputs.directory.mkdirs()) || !outputs.directory.isDirectory)
            throw IOException("无法创建下载目录，请检查存储权限和可用空间。")
        // CREATE_NEW prevents an unrelated existing file, including a prior completed file, from being replaced.
        if (outputs.final.exists()) throw IOException("同名下载文件已经存在，已保留现有文件。")
        val partUri = providerUri(outputs.part)
        if (!outputs.part.createNewFile()) throw IOException("此任务的保存缓存仍存在，请稍后重试。")
        val allocated = task.copy(outputUri = partUri)
        try {
            onAllocated(partUri, true)
            FileOutputStream(outputs.part).use { output ->
                FileInputStream(file).use { source -> copyVerified(source, output, expected) }
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            if (outputs.part.length() != expected) throw IOException("保存后的文件大小不一致，请重试。")
            // No REPLACE_EXISTING: this move must fail rather than overwrite any existing destination.
            Files.move(outputs.part.toPath(), outputs.final.toPath())
            return providerUri(outputs.final)
        } catch (error: Exception) {
            discardPending(allocated)
            throw error
        }
    }

    private suspend fun copyVerified(source: InputStream, target: OutputStream, expected: Long) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count.toLong() > expected - copied) throw IOException("下载缓存大小已改变，已停止保存。")
            target.write(buffer, 0, count)
            copied += count
        }
        if (copied != expected) throw IOException("下载缓存尚不完整，请重试。")
        target.flush()
    }

    private data class MediaRow(val name: String, val path: String, val pending: Boolean, val size: Long)

    private fun mediaRow(uri: Uri): MediaRow? {
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return null
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
            MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)).orEmpty()
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)).orEmpty()
            val pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)) != 0
            MediaRow(name, path, pending, runCatching { actualSize(uri) }.getOrDefault(-1L).takeIf { it >= 0L }
                ?: cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
    }

    private fun actualSize(uri: Uri): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

    private fun ownsPending(task: NetDiskDownloadTask, row: MediaRow): Boolean =
        row.path.trimEnd('/') == relativePath.trimEnd('/') && row.name.startsWith("${netDiskSafeTaskId(task.id)}_")

    private data class LegacyFiles(val directory: File, val final: File, val part: File)
    private fun legacyFiles(task: NetDiskDownloadTask): LegacyFiles {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val directory = File(downloads, "Onvyra/网盘").canonicalFile
        if (!directory.path.startsWith(downloads.path + File.separator)) throw IOException("下载目录无效。")
        val name = netDiskPublishedFileName(task.fileName, task.id)
        val final = File(directory, name)
        val part = File(directory, "$name.part")
        if (final.canonicalFile.parentFile != directory || part.canonicalFile.parentFile != directory)
            throw IOException("下载文件路径无效。")
        return LegacyFiles(directory, final, part)
    }

    private fun providerUri(file: File): String = FileProvider.getUriForFile(app, authority, file).toString()
    private fun matchesExpected(task: NetDiskDownloadTask, size: Long): Boolean = size >= 0L &&
        size == (task.totalBytes.takeIf { it >= 0L } ?: task.downloadedBytes)
    private fun completed(task: NetDiskDownloadTask, uri: String, bytes: Long) = task.copy(
        status = NetDiskDownloadStatus.COMPLETED, downloadedBytes = bytes, totalBytes = bytes,
        speedBytesPerSecond = 0, activeConnections = 0, errorMessage = null, outputUri = uri, publicationPending = false)
    private fun interrupted(task: NetDiskDownloadTask) = task.copy(status = NetDiskDownloadStatus.PAUSED,
        speedBytesPerSecond = 0, activeConnections = 0, outputUri = null, publicationPending = false, errorMessage = "上次保存未完成，点击继续重新保存。")
    private fun missing(task: NetDiskDownloadTask) = task.copy(status = NetDiskDownloadStatus.FAILED,
        speedBytesPerSecond = 0, activeConnections = 0, errorMessage = "已保存文件不存在或大小不一致，请重新下载；现有文件未被删除。")
}

internal fun netDiskSafeTaskId(id: String): String = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
    ?: throw IOException("下载任务编号无效。")

/** Includes a task-unique prefix in the 200-byte filename budget and preserves a normal extension. */
internal fun netDiskPublishedFileName(original: String, taskId: String): String {
    val prefix = "${netDiskSafeTaskId(taskId)}_"
    val safe = original.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").trim().trim('.').ifBlank { "网盘文件" }
    val dot = safe.lastIndexOf('.')
    val extension = if (dot > 0) safe.substring(dot).takeIf { it.toByteArray(Charsets.UTF_8).size <= 32 }.orEmpty() else ""
    val stem = if (extension.isNotEmpty()) safe.dropLast(extension.length) else safe
    val available = 200 - prefix.toByteArray(Charsets.UTF_8).size - extension.toByteArray(Charsets.UTF_8).size
    return prefix + netDiskUtf8Prefix(stem, available).ifBlank { "file" } + extension
}

private fun netDiskUtf8Prefix(value: String, maxBytes: Int): String {
    var offset = 0
    var bytes = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        val count = when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
        if (count > maxBytes - bytes) break
        bytes += count
        offset += Character.charCount(codePoint)
    }
    return value.substring(0, offset)
}

internal fun netDiskPublishSize(total: Long, actual: Long): Long {
    if (actual < 0L || total >= 0L && total != actual) throw IOException("下载缓存尚不完整，请重试。")
    return actual
}
