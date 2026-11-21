package com.constx.onvyra.ui.pages.novel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.PriorityQueue

/** Metadata only. Scans mapped UTF-8 keys without allocating a JSON tree or a book per row. */
internal class SkinSearchIndex private constructor(private val data: ByteBuffer, val generation: String,
    val createdAt: Long, private val rowCount: Int, private val start: Int) {
    data class Row(val id: Long, val title: String, val author: String, val cover: String,
        val category: String, val chapters: Int, val sourceBookId: Long, val description: String = "")
    private data class Hit(val position: Int, val rank: Int, val chapters: Int, val id: Long)

    /** A deterministic cross-library sample, not a popularity or sales ranking. */
    fun browse(limit: Int, checkCancelled: () -> Unit): List<Row> {
        if (limit <= 0) return emptyList()
        val buffer = data.asReadOnlyBuffer().apply { position(start) }
        val candidates = ArrayList<Row>()
        val step = (rowCount / (limit * 8).coerceAtLeast(1)).coerceAtLeast(1)
        try {
            repeat(rowCount) { index ->
                if (index % 128 == 0) checkCancelled()
                val length = buffer.int
                val position = buffer.position()
                val end = position.toLong() + length
                if (length < 28 || end > buffer.limit()) throw IOException("Skin 本地书库记录损坏")
                if (index % step == 0) {
                    val row = readRow(buffer, position)
                    if (row.title.isNotBlank() && row.chapters > 0) candidates += row
                }
                buffer.position(end.toInt())
            }
        } catch (error: java.nio.BufferUnderflowException) {
            throw IOException("Skin 本地书库不完整", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Skin 本地书库格式异常", error)
        }
        val selected = LinkedHashMap<String, Row>()
        val perCategory = mutableMapOf<String, Int>()
        val categoryLimit = (limit / 6).coerceAtLeast(2)
        for (row in candidates) {
            if ((perCategory[row.category] ?: 0) >= categoryLimit) continue
            val key = row.title.trim() + "|" + row.author.trim()
            if (selected.putIfAbsent(key, row) == null) perCategory[row.category] = (perCategory[row.category] ?: 0) + 1
            if (selected.size == limit) break
        }
        if (selected.size < limit) for (row in candidates) {
            selected.putIfAbsent(row.title.trim() + "|" + row.author.trim(), row)
            if (selected.size == limit) break
        }
        return selected.values.toList()
    }

    private fun readRow(buffer: ByteBuffer, position: Int): Row {
        buffer.position(position)
        skipString(buffer); skipString(buffer)
        val id = buffer.long
        val chapters = buffer.int
        val source = buffer.long
        return Row(id, readString(buffer), readString(buffer), readString(buffer), readString(buffer),
            chapters, source, readString(buffer))
    }

    fun search(keyword: String, limit: Int, checkCancelled: () -> Unit): List<Row> {
        val query = keyword.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)
        if (query.isEmpty()) return emptyList()
        val order = compareBy<Hit> { it.rank }.thenByDescending { it.chapters }.thenBy { it.id }
        val best = PriorityQueue(limit, order.reversed())
        val buffer = data.asReadOnlyBuffer()
        buffer.position(start)
        try {
            repeat(rowCount) { row ->
                if (row % 128 == 0) checkCancelled()
                val length = buffer.int
                val position = buffer.position()
                val end = position.toLong() + length
                if (length < 28 || end > buffer.limit()) throw IOException("Skin 本地搜索索引记录损坏")
                val titleLength = buffer.short.toInt() and 0xffff
                val titleAt = buffer.position()
                buffer.position(titleAt + titleLength)
                val authorLength = buffer.short.toInt() and 0xffff
                val authorAt = buffer.position()
                buffer.position(authorAt + authorLength)
                val id = buffer.long
                val chapters = buffer.int
                val rank = when {
                    equalsBytes(buffer, titleAt, titleLength, query) -> 0
                    startsWith(buffer, titleAt, titleLength, query) -> 1
                    contains(buffer, titleAt, titleLength, query) -> 2
                    equalsBytes(buffer, authorAt, authorLength, query) -> 3
                    startsWith(buffer, authorAt, authorLength, query) -> 4
                    contains(buffer, authorAt, authorLength, query) -> 5
                    else -> -1
                }
                if (rank >= 0) {
                    val worst = best.peek()
                    val better = worst == null || rank < worst.rank || (rank == worst.rank &&
                        (chapters > worst.chapters || (chapters == worst.chapters && id < worst.id)))
                    if (best.size < limit) best.add(Hit(position, rank, chapters, id))
                    else if (better) { best.poll(); best.add(Hit(position, rank, chapters, id)) }
                }
                buffer.position(end.toInt())
            }
            return best.sortedWith(order).map { hit ->
                checkCancelled()
                readRow(buffer, hit.position)
            }
        } catch (error: java.nio.BufferUnderflowException) {
            throw IOException("Skin 本地搜索索引不完整", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Skin 本地搜索索引格式异常", error)
        }
    }

    private fun equalsBytes(buffer: ByteBuffer, offset: Int, length: Int, query: ByteArray): Boolean =
        length == query.size && startsWith(buffer, offset, length, query)
    private fun startsWith(buffer: ByteBuffer, offset: Int, length: Int, query: ByteArray): Boolean {
        if (length < query.size) return false
        for (index in query.indices) if (buffer[offset + index] != query[index]) return false
        return true
    }
    private fun contains(buffer: ByteBuffer, offset: Int, length: Int, query: ByteArray): Boolean {
        if (length < query.size) return false
        for (start in 0..length - query.size) {
            if (buffer[offset + start] != query[0]) continue
            var same = true
            for (index in 1 until query.size) if (buffer[offset + start + index] != query[index]) { same = false; break }
            if (same) return true
        }
        return false
    }

    class Writer(output: OutputStream, generation: String, createdAt: Long) : AutoCloseable {
        private val stream = DataOutputStream(output)
        private var count = 0
        private var bytes = 0L
        init {
            stream.writeInt(MAGIC); stream.writeInt(VERSION); stream.writeLong(createdAt)
            stream.writeInt(0); stream.writeUTF(generation)
        }
        fun add(row: Row) {
            if (count >= MAX_ROWS) throw IOException("Skin 搜索索引超出处理大小，未返回不完整结果")
            val payload = ByteArrayOutputStream()
            DataOutputStream(payload).use { part ->
                part.writeSized(row.title.lowercase(Locale.ROOT)); part.writeSized(row.author.lowercase(Locale.ROOT))
                part.writeLong(row.id); part.writeInt(row.chapters); part.writeLong(row.sourceBookId)
                for (field in listOf(row.title, row.author, row.cover, row.category, row.description)) part.writeSized(field)
            }
            bytes += payload.size() + 4
            if (bytes > MAX_BYTES - 1024) throw IOException("Skin 搜索索引过大，未返回不完整结果")
            stream.writeInt(payload.size()); payload.writeTo(stream); count++
        }
        fun finish(): Int { stream.flush(); return count }
        override fun close() { stream.close() }
    }

    companion object {
        const val ASSET_PATH = "reader/skin-search-v1.bin"
        const val FILE_NAME = "skin-search-v1.bin"
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val MAGIC = 0x534b4931
        private const val VERSION = 1
        private const val MAX_ROWS = 500_000
        fun open(file: File): SkinSearchIndex? = try {
            if (!file.isFile || file.length() !in 24..MAX_BYTES) null else RandomAccessFile(file, "r").use {
                decode(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
        fun decode(bytes: ByteBuffer): SkinSearchIndex? { return try {
            val buffer = bytes.asReadOnlyBuffer()
            if (buffer.int != MAGIC || buffer.int != VERSION) return null
            val createdAt = buffer.long
            val count = buffer.int
            if (count !in 1..MAX_ROWS) return null
            val generation = readString(buffer)
            if (!generation.matches(Regex("[a-f0-9]{24}"))) return null
            SkinSearchIndex(buffer, generation, createdAt, count, buffer.position())
        } catch (_: Exception) { null } }
        fun setCount(file: File, count: Int) { RandomAccessFile(file, "rw").use { it.seek(16); it.writeInt(count) } }
        fun setCount(bytes: ByteArray, count: Int) { ByteBuffer.wrap(bytes).putInt(16, count) }
        private fun DataOutputStream.writeSized(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > 65535) throw IOException("Skin 搜索元数据字段过长")
            writeShort(bytes.size); write(bytes)
        }
        private fun readString(buffer: ByteBuffer): String {
            val size = buffer.short.toInt() and 0xffff
            val bytes = ByteArray(size); buffer.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }
        private fun skipString(buffer: ByteBuffer) {
            val size = buffer.short.toInt() and 0xffff
            buffer.position(buffer.position() + size)
        }
    }
}
