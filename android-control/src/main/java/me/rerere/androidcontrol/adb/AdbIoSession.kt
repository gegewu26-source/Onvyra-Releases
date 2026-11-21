package me.rerere.androidcontrol.adb

import java.io.Closeable
import java.io.InterruptedIOException

/** Closing registered sockets unblocks TCP connect, TLS handshake and protocol reads. */
internal class AdbIoSession : Closeable {
    private val lock = Any()
    private val resources = LinkedHashSet<Closeable>()
    @Volatile var cancelled: Boolean = false
        private set

    fun register(resource: Closeable) {
        synchronized(lock) {
            if (!cancelled) { resources.add(resource); return }
        }
        runCatching { resource.close() }
        throw AdbIoCancelledException()
    }

    fun checkActive() { if (cancelled) throw AdbIoCancelledException() }

    /** Serialize state commits with cancellation so a cancelled operation cannot publish success. */
    fun <T> ifActive(block: () -> T): T = synchronized(lock) { checkActive(); block() }

    override fun close() {
        val closing = synchronized(lock) {
            cancelled = true
            resources.toList().also { resources.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }
}

internal class AdbIoCancelledException : InterruptedIOException("Android debugging operation cancelled")
