package me.rerere.androidcontrol.shell

import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.androidcontrol.adb.AdbIoCancelledException
import me.rerere.androidcontrol.adb.AdbIoSession

/** A setup service may cancel its own work without cancelling a newer mode's launch. */
class ShellConnectionOwner {
    private val stopped = AtomicBoolean(false)
    internal fun cancel() { stopped.set(true) }
    internal fun checkActive() { if (stopped.get()) throw AdbIoCancelledException() }
}

/** Registration and cancellation share the bridge's launch lock; closing happens after detaching. */
internal class ShellConnectionIoSlot(private val launchLock: Any) {
    private data class Pending(val io: AdbIoSession, val owner: ShellConnectionOwner?)
    private var pending: Pending? = null

    fun register(io: AdbIoSession, owner: ShellConnectionOwner?, ticketIsCurrent: () -> Boolean) = synchronized(launchLock) {
        owner?.checkActive()
        if (!ticketIsCurrent()) throw AdbIoCancelledException()
        pending = Pending(io, owner)
    }

    fun detachAll(): AdbIoSession? = synchronized(launchLock) {
        pending?.io.also { pending = null }
    }

    fun detachOwned(owner: ShellConnectionOwner): AdbIoSession? = synchronized(launchLock) {
        owner.cancel()
        pending?.takeIf { it.owner === owner }?.io?.also { pending = null }
    }

    fun finished(io: AdbIoSession) = synchronized(launchLock) {
        if (pending?.io === io) pending = null
    }
}
