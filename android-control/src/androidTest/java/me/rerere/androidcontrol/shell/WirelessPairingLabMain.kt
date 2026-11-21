package me.rerere.androidcontrol.shell

import android.os.Build
import android.os.IBinder
import android.os.Process

/** Test-APK-only entry point. Invoked by the emulator host as UID2000, never exported by production. */
object WirelessPairingLabMain {
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000) { "Lab pairing must run as shell UID2000" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Lab pairing is restricted to the emulator" }
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "adb") as IBinder
        val service = Class.forName("android.debug.IAdbManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        when (args.firstOrNull()) {
            "start" -> {
                // Read the ephemeral QR password from stdin; never place it in cmdline or logs.
                val password = System.`in`.bufferedReader().readLine()?.trim().orEmpty()
                check(password.matches(Regex("[0-9]{6}"))) { "A six-digit ephemeral lab password is required on stdin" }
                service.javaClass.getMethod("enablePairingByQrCode", String::class.java, String::class.java)
                    .invoke(service, "onvyra-lab-${Process.myPid()}", password)
                println("ANDROID_CONTROL_PAIRING_SERVICE_STARTED")
            }
            "stop" -> {
                service.javaClass.getMethod("disablePairing").invoke(service)
                println("ANDROID_CONTROL_PAIRING_SERVICE_STOPPED")
            }
            else -> error("Use start or stop")
        }
    }
}
