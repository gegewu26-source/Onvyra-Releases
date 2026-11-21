package com.constx.onvyra.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenerationCheckpointWriterTest {
    @Test fun continuousOutputIsSavedBeforeTheStreamFinishes() = runTest {
        var latest = 0
        val stored = mutableListOf<Int>()
        val writer = GenerationCheckpointWriter(this, save = { stored += latest })
        try {
            repeat(9) {
                latest++
                writer.changed()
                runCurrent()
                advanceTimeBy(500)
                runCurrent()
            }
            assertTrue("A continuously changing stream must not postpone every checkpoint", stored.size >= 2)
            assertTrue("The task is still producing output when these saves happen", stored.first() < latest)
        } finally { writer.close() }
    }

    @Test fun lastPartialUpdateIsSavedEvenWhenNoMoreChunksArrive() = runTest {
        var stored: String? = null
        val writer = GenerationCheckpointWriter(this, save = { stored = "partial reasoning" })
        try {
            writer.changed()
            runCurrent()
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(null, stored)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("partial reasoning", stored)
        } finally { writer.close() }
    }

    @Test fun completedToolBoundaryDoesNotWaitForThePeriodicDelay() = runTest {
        var saved = false
        val writer = GenerationCheckpointWriter(this, save = { saved = true })
        try {
            writer.changed(immediate = true)
            assertTrue(saved)
            assertEquals(0L, testScheduler.currentTime)
        } finally { writer.close() }
    }

    @Test fun updatesReceivedDuringASlowSaveReachASubsequentCheckpoint() = runTest {
        var latest = 1
        val stored = mutableListOf<Int>()
        val firstEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val writer = GenerationCheckpointWriter(this, save = {
            val snapshot = latest
            if (stored.isEmpty()) {
                firstEntered.complete(Unit)
                finishFirst.await()
            }
            stored += snapshot
        })
        try {
            val immediate = launch { writer.changed(immediate = true) }
            runCurrent()
            assertTrue(firstEntered.isCompleted)
            latest = 2
            writer.changed()
            runCurrent()
            assertFalse(immediate.isCompleted)
            finishFirst.complete(Unit)
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(listOf(1, 2), stored)
            immediate.join()
        } finally { writer.close() }
    }

    @Test fun transientStorageFailureRetainsDirtyProgressAndRetriesWithoutAnotherChunk() = runTest {
        var attempts = 0
        var saved = false
        val failures = mutableListOf<Throwable>()
        val writer = GenerationCheckpointWriter(this, save = {
            attempts++
            if (attempts == 1) error("synthetic storage failure")
            saved = true
        }, onFailure = { failures += it })
        try {
            writer.changed()
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertFalse(saved)
            assertEquals(1, failures.size)
            advanceTimeBy(2_001)
            runCurrent()
            assertTrue(saved)
            assertEquals(2, attempts)
        } finally { writer.close() }
    }

    @Test fun closedWriterCannotPersistAnOlderSnapshotOverTheFinalSave() = runTest {
        var stored = "initial"
        val writer = GenerationCheckpointWriter(this, save = { stored = "stale partial" })
        writer.changed()
        runCurrent()
        writer.close()
        stored = "final reply"
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("final reply", stored)
    }
}
