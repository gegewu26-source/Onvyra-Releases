// Authenticated Binder handoff adapted from Mika. Apache-2.0.
package me.rerere.androidcontrol.shell

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One app-process recipient, also used when a provider call precedes Application.onCreate. */
internal class AndroidShellHub private constructor(context: Context) {
    private val app = context.applicationContext
    private val mutableShell = MutableStateFlow<IAndroidShell?>(null)
    val shell = mutableShell.asStateFlow()
    private val token = Binder()
    @Volatile var generation: Long = 0
        private set
    private val prefs = app.getSharedPreferences("android_control_handoff", Context.MODE_PRIVATE)
    private val store = object : NonceStore {
        override fun load() = NonceSlots(prefs.getString("resume", null), prefs.getString("launch", null),
            prefs.getLong("launchAt", 0), prefs.getString("usb", null), prefs.getLong("usbAt", 0))
        override fun save(slots: NonceSlots) {
            check(prefs.edit().putString("resume", slots.resume).putString("launch", slots.launch)
                .putLong("launchAt", slots.launchAt).putString("usb", slots.usb).putLong("usbAt", slots.usbAt).commit()) {
                "Could not persist helper authentication state"
            }
        }
    }
    val nonces = HandoffNonces(store, SystemClock::elapsedRealtime, System::currentTimeMillis)

    @Synchronized fun issueLaunch(expectedGeneration: Long): String {
        check(generation == expectedGeneration) { "Android shell authorization was revoked" }
        return nonces.issueLaunch()
    }

    @Synchronized fun cancelLaunch(nonce: String) = nonces.cancelLaunch(nonce)

    @Synchronized fun issueUsb(expectedGeneration: Long): String {
        check(generation == expectedGeneration) { "Android shell authorization was revoked" }
        return nonces.usbNonce()
    }

    @Synchronized
    fun offer(extras: Bundle?, callerUid: Int, callerPid: Int): Bundle {
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
        fun reject(reason: HelperHandoff.Reject) = Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, false)
            putString(HelperHandoff.KEY_REASON, reason.name)
        }
        HelperHandoff.check(callerUid, callerPid, extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
            extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1, extras?.getString(HelperHandoff.KEY_APK),
            app.applicationInfo.sourceDir, binder != null)?.let { return reject(it) }
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        if (!nonces.matches(nonce)) return reject(HelperHandoff.Reject.BadNonce)
        val service = IAndroidShell.Stub.asInterface(binder!!)
        val identity = runCatching { service.identity() }.getOrNull()
        if (identity == null || identity.size < 2 || identity[0] != callerUid || identity[1] != callerPid) {
            return reject(HelperHandoff.Reject.BinderElsewhere)
        }
        if (!HelperHandoff.currentImplementation(identity)) {
            return reject(HelperHandoff.Reject.StaleImplementation)
        }
        try { binder.linkToDeath({ synchronized(this) { mutableShell.compareAndSet(service, null) } }, 0) }
        catch (_: Exception) { return reject(HelperHandoff.Reject.NoBinder) }
        val next = nonces.accept(nonce) ?: return reject(HelperHandoff.Reject.BadNonce)
        val previous = mutableShell.value
        mutableShell.value = service
        if (previous != null && previous.asBinder() != binder) runCatching { previous.destroy() }
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, true)
            putString(HelperHandoff.KEY_NEXT, next)
            putBinder(HelperHandoff.KEY_TOKEN, token)
        }
    }

    @Synchronized fun revoke(): IAndroidShell? {
        generation++
        nonces.clear()
        return mutableShell.value.also { mutableShell.value = null }
    }

    companion object {
        @Volatile private var instance: AndroidShellHub? = null
        fun of(context: Context): AndroidShellHub = instance ?: synchronized(this) {
            instance ?: AndroidShellHub(context).also { instance = it }
        }
    }
}
