package com.constx.onvyra.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** A bounded write interval, even while tokens keep arriving; never debounce an entire task. */
internal class GenerationCheckpointWriter(
    scope: CoroutineScope,
    private val intervalMillis: Long = 2_000L,
    private val save: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val revision = AtomicLong()
    private val mutex = Mutex()
    private var savedRevision = 0L
    private var failureReported = false
    private val worker = scope.launch {
        for (ignored in changes) {
            delay(intervalMillis)
            flush()
            if (revision.get() > savedRevision) changes.trySend(Unit)
        }
    }

    suspend fun changed(immediate: Boolean = false) {
        revision.incrementAndGet()
        if (immediate) flush()
        changes.trySend(Unit)
    }

    private suspend fun flush() = mutex.withLock {
        val target = revision.get()
        if (target <= savedRevision) return@withLock
        try {
            save()
            savedRevision = target
            failureReported = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Keep the dirty revision for retry, without killing the model request.
            if (!failureReported) {
                failureReported = true
                onFailure(failure)
            }
        }
    }

    /** The owning generation performs its existing full final save after this has joined. */
    suspend fun close() = withContext(NonCancellable) {
        worker.cancelAndJoin()
        changes.close()
    }
}
