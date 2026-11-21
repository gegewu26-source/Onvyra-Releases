package me.rerere.common.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.reflect.KClass

/** Offline Call fixtures verify cancellation before headers and during a blocking body read. */
class RequestCancellationTest {
    @Test fun `await cancellation cancels the underlying call before response headers`() = runBlocking {
        val call = FakeCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.await() }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        val lateBody = TrackingBody("late response")
        call.respond(lateBody)
        assertTrue("A response delivered after cancellation must close", lateBody.closed.get())
    }

    @Test fun `awaitResult closes a successful response after reading its body`() = runBlocking {
        val call = FakeCall()
        val body = TrackingBody("complete")
        val result = CompletableDeferred<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            result.complete(call.awaitResult { it.body.string() })
        }
        call.respond(body)
        job.join()
        assertEquals("complete", result.await())
        assertTrue(body.closed.get())
        assertFalse(call.isCanceled())
    }

    @Test fun `awaitResult cancellation before headers does not enter the reader`() = runBlocking {
        val call = FakeCall()
        var enteredReader = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.awaitResult { enteredReader = true; it.body.string() }
        }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        val body = TrackingBody("late")
        call.respond(body)
        assertFalse(enteredReader)
        assertTrue(body.closed.get())
    }

    @Test fun `awaitResult cancellation during body read closes the source and never resumes old work`() = runBlocking {
        val call = FakeCall()
        val startedReading = CompletableDeferred<Unit>()
        val body = BlockingBody(startedReading)
        var completed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.awaitResult { it.body.string() }
            completed = true
        }
        val callbackThread = thread(name = "request-cancellation-fixture") { call.respond(body) }
        try {
            startedReading.await()
            job.cancelAndJoin()
            callbackThread.join(5_000)
            assertFalse("Closing the source must unblock the old reader", callbackThread.isAlive)
            assertTrue(call.isCanceled())
            assertTrue(body.closed.get())
            assertFalse(completed)
        } finally {
            body.close()
            callbackThread.join(5_000)
        }
    }

    @Test fun `awaitResult propagates body or parser failures while closing the response`() = runBlocking {
        val call = FakeCall()
        val body = TrackingBody("malformed")
        val failure = CompletableDeferred<Throwable?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure.complete(runCatching {
                call.awaitResult<String> { throw IllegalArgumentException("parser fixture failed") }
            }.exceptionOrNull())
        }
        call.respond(body)
        job.join()
        assertTrue(failure.await() is IllegalArgumentException)
        assertEquals("parser fixture failed", failure.await()?.message)
        assertTrue(body.closed.get())
    }

    @Test fun `awaitResult propagates transport failures`() = runBlocking {
        val call = FakeCall()
        val failure = CompletableDeferred<Throwable?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            failure.complete(runCatching { call.awaitResult { it.body.string() } }.exceptionOrNull())
        }
        call.fail(IOException("transport fixture failed"))
        job.join()
        assertTrue(failure.await() is IOException)
        assertEquals("transport fixture failed", failure.await()?.message)
    }

    @Test fun `awaitResult keeps ownership until a reader finishes and does not close early`() = runBlocking {
        val call = FakeCall()
        val body = TrackingBody("owned response")
        val result = CompletableDeferred<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            result.complete(call.awaitResult {
                assertFalse(body.closed.get())
                val value = it.body.string()
                value
            })
        }
        call.respond(body)
        job.join()
        assertEquals("owned response", result.await())
        assertTrue(body.closed.get())
    }

    private class FakeCall : Call {
        private val request = Request.Builder().url("https://offline-fixture.example.test/").build()
        private var callback: Callback? = null
        private val cancelled = AtomicBoolean(false)
        private val tags = mutableMapOf<Class<*>, Any>()
        override fun request(): Request = request
        override fun execute(): Response = error("Fixture only supports enqueue")
        override fun enqueue(responseCallback: Callback) {
            check(callback == null)
            callback = responseCallback
        }
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout()
        override fun addEventListener(eventListener: EventListener) = Unit
        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.java)
        override fun <T> tag(type: Class<out T>): T? = synchronized(tags) { type.cast(tags[type]) }
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            tag(type.java, computeIfAbsent)
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = synchronized(tags) {
            type.cast(tags[type]) ?: computeIfAbsent().also { tags[type] = it }
        }
        override fun clone(): Call = FakeCall()
        fun respond(body: ResponseBody) {
            requireNotNull(callback).onResponse(this, Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build())
        }
        fun fail(error: IOException) { requireNotNull(callback).onFailure(this, error) }
    }

    private class TrackingBody(content: String) : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val source = object : ForwardingSource(Buffer().writeUtf8(content)) {
            override fun close() { closed.set(true); super.close() }
        }.buffer()
        override fun contentType(): MediaType = "text/plain".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }

    private class BlockingBody(started: CompletableDeferred<Unit>) : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val closeSignal = CountDownLatch(1)
        private val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                started.complete(Unit)
                check(closeSignal.await(5, TimeUnit.SECONDS)) { "Reader did not receive cancellation" }
                throw IOException("source closed by cancellation")
            }
            override fun timeout(): Timeout = Timeout()
            override fun close() { closed.set(true); closeSignal.countDown() }
        }.buffer()
        override fun contentType(): MediaType = "text/plain".toMediaType()
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = source
    }
}
