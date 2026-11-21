package com.constx.onvyra.ui.pages.novel

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReaderReadingRouteCacheTest {
    private val book = NovelBook("real_1", "同名作品", "作者甲", detailUrl = "https://source.fixture.test/book/1",
        sourceId = "legado_rule_hash", sourceVariables = mapOf("book.kind" to "actual-id"))

    @Test fun newCacheInstanceReadsPersistedRouteAndRetainsPinnedSourceState() {
        val dir = Files.createTempDirectory("reader-routes-").toFile()
        try {
            val selected = book.copy(preferredSourceId = book.sourceId, lastReadChapterIndex = 15,
                lastReadChapterUrl = "https://source.fixture.test/chapter/16")
            ReaderReadingRouteCache(dir).put(selected)
            val restarted = ReaderReadingRouteCache(dir)
            assertEquals(selected, restarted.get(book.copy(sourceId = NovelSourceEngine.SOURCE_CATALOG), setOf(book.sourceId)))
            assertNull(restarted.get(book.copy(author = "作者乙"), setOf(book.sourceId)))
            assertNull(restarted.get(book.copy(isComic = true), setOf(book.sourceId)))
            assertNull(restarted.get(book, setOf("legado_changed_rule_hash")))
            assertNull(restarted.get(book, emptySet()))
        } finally { dir.deleteRecursively() }
    }

    @Test fun expiredFutureOrCorruptDiskDataCannotSupplyARoute() {
        val dir = Files.createTempDirectory("reader-routes-expiry-").toFile()
        try {
            var clock = 1_000_000L
            ReaderReadingRouteCache(dir) { clock }.put(book)
            clock += 7L * 24 * 60 * 60 * 1000
            assertNull(ReaderReadingRouteCache(dir) { clock }.get(book, setOf(book.sourceId)))
            clock = 999_999L
            assertNull(ReaderReadingRouteCache(dir) { clock }.get(book, setOf(book.sourceId)))
            dir.listFiles()!!.single().writeText("truncated JSON")
            assertNull(ReaderReadingRouteCache(dir).get(book, setOf(book.sourceId)))
        } finally { dir.deleteRecursively() }
    }

    @Test fun failuresAreBoundedAcrossRestartAndOnlySuccessfulContentResetsThem() {
        val dir = Files.createTempDirectory("reader-routes-failure-").toFile()
        try {
            ReaderReadingRouteCache(dir).apply { put(book); failed(book) }
            val restarted = ReaderReadingRouteCache(dir)
            assertNotNull(restarted.get(book, setOf(book.sourceId)))
            restarted.put(book.copy(description = "detail refresh"))
            restarted.failed(book)
            assertNull(ReaderReadingRouteCache(dir).get(book, setOf(book.sourceId)))
            restarted.put(book); restarted.failed(book); restarted.put(book, successfulRead = true); restarted.failed(book)
            assertNotNull(ReaderReadingRouteCache(dir).get(book, setOf(book.sourceId)))
            restarted.failed(book, identityMismatch = true)
            assertNull(ReaderReadingRouteCache(dir).get(book, setOf(book.sourceId)))
        } finally { dir.deleteRecursively() }
    }
}
