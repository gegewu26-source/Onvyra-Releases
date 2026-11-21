package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}

/**
 * Keep cancellation attached to the Call until its response has actually been consumed.
 * await() alone only waits for headers, after which a blocking body read could otherwise keep an
 * obsolete non-streaming model request alive. The reader runs on OkHttp's callback dispatcher.
 */
suspend fun <T> Call.awaitResult(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val activeResponse = AtomicReference<Response?>(null)
    continuation.invokeOnCancellation {
        // cancel closes the socket, and closing the held response also releases a body whose
        // source has already been delivered (including custom/offline response bodies).
        cancel()
        activeResponse.getAndSet(null)?.closeQuietly()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    activeResponse.set(response)
                    // Cancellation may have happened before the response was registered above.
                    if (!continuation.isActive) return
                    read(response)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                activeResponse.compareAndSet(response, null)
            }
        }
    })
}
