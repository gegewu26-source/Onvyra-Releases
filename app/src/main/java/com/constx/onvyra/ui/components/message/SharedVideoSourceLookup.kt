package com.constx.onvyra.ui.components.message

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One finite producer per playback/episode/retry; menus and recovery replay its snapshots. */
internal class SharedVideoSourceLookup(
    scope: CoroutineScope,
    upstream: Flow<List<VideoSourceOption>>,
    budgetMs: Long = 26_000,
) {
    private data class Snapshot(
        val options: List<VideoSourceOption>? = null,
        val completed: Boolean = false,
        val failure: Exception? = null,
    )
    private val snapshot = MutableStateFlow(Snapshot())
    private val producer = scope.launch(start = CoroutineStart.LAZY) {
        try {
            val completed = withTimeoutOrNull(budgetMs) {
                upstream.collect { options -> snapshot.value = Snapshot(options.distinctBy { it.id }) }
                true
            } == true
            snapshot.value = snapshot.value.copy(completed = true, failure =
                if (completed) null else IOException("片源查找尚未完成，可重新加载"))
        } catch (cancelled: CancellationException) {
            snapshot.value = snapshot.value.copy(completed = true, failure = cancelled)
            throw cancelled
        } catch (error: Exception) {
            snapshot.value = snapshot.value.copy(completed = true, failure = error)
        }
    }

    val updates: Flow<List<VideoSourceOption>> = flow {
        producer.start()
        snapshot.first { current ->
            current.options?.let { emit(it) }
            current.failure?.let { throw it }
            current.completed
        }
    }

    fun cancel() {
        producer.cancel()
        snapshot.value = snapshot.value.copy(completed = true,
            failure = CancellationException("Playback or lookup attempt changed"))
    }
}
