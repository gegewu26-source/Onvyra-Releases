package me.rerere.androidcontrol.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Framework rebinds can overlap old destruction; only the owning service may retire a connection. */
internal class AccessibilityConnection<T : Any> {
    private val state = MutableStateFlow<T?>(null)
    val current: T? get() = state.value

    fun connected(service: T) { state.value = service }
    fun disconnected(service: T) { state.compareAndSet(service, null) }

    /** Only a pending user operation waits; a failed probe must not discard its service reference. */
    suspend fun awaitConnected(timeoutMs: Long, isReady: (T) -> Boolean = { true }): T? {
        current?.takeIf(isReady)?.let { return it }
        if (timeoutMs <= 0) return null
        return withTimeoutOrNull(timeoutMs.coerceAtMost(3_000L)) {
            // Android can finish its server-side rebind without delivering another
            // onServiceConnected callback. Recheck the same retained instance, as
            // well as replacements, within this short cancellable wait only.
            while (true) {
                current?.takeIf(isReady)?.let { return@withTimeoutOrNull it }
                delay(50L)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }
}
