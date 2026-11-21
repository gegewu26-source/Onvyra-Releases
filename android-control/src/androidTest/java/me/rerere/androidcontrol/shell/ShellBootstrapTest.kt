package me.rerere.androidcontrol.shell

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.androidcontrol.AndroidControl
import me.rerere.androidcontrol.adb.AdbConnection
import me.rerere.androidcontrol.adb.AndroidAdb
import me.rerere.androidcontrol.adb.AdbServiceTypes
import me.rerere.androidcontrol.adb.NsdAdbServiceFinder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit lab bootstrap; never reads or prints the one-time nonce or the pairing code. */
@RunWith(AndroidJUnit4::class)
class ShellBootstrapTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun writeUsbStartupScript() {
        assertEmulator()
        val command = AndroidControl.get(instrumentation.targetContext).bridge.startUsbScript()
        assertTrue(command.startsWith("adb shell sh "))
        // The host harness executes this command through the non-root adb shell afterwards.
        println("ANDROID_CONTROL_USB_COMMAND=$command")
    }

    @Test fun authenticateSurvivingHelperAfterProcessRestart() = runBlocking {
        assertEmulator()
        val bridge = AndroidControl.get(instrumentation.targetContext).bridge
        val helper = withTimeout(20_000) {
            while (bridge.shell.value == null) delay(100)
            requireNotNull(bridge.shell.value)
        }
        val ids = helper.identity()
        assertEquals("Helper must be shell, never root or the app UID", 2000, ids[0])
        assertTrue(ids[1] > 0)
        assertTrue(bridge.status().connected)
    }

    /** Run explicitly with the current Settings pairing dialog's ephemeral values. */
    @Test fun realWirelessPairingAuthenticatesTheAdbTransport() = runBlocking {
        assertEmulator()
        val args = InstrumentationRegistry.getArguments()
        val pairingPort = args.getString("wireless_pair_port")?.toIntOrNull()
        val connectionPort = args.getString("wireless_connect_port")?.toIntOrNull()
        val code = args.getString("wireless_pair_code")
        assumeTrue("Run the dedicated wireless lab harness to provide ephemeral pairing values",
            pairingPort != null && connectionPort != null && code != null)
        val context = instrumentation.targetContext
        val bridge = AndroidControl.get(context).bridge
        bridge.pair("127.0.0.1", pairingPort!!, code!!).getOrThrow()
        // Exercise a new TLS connection even if an authenticated USB helper is already alive.
        AdbConnection.open("127.0.0.1", connectionPort!!, AndroidAdb.tls(AndroidAdb.identity(context))).use {
            assertTrue("adbd must complete TLS pairing and return its device banner", it.banner.startsWith("device::"))
        }
        assertTrue(bridge.status().paired)
    }

    /** Validates the actual platform pairing service even on an ATD image without Settings UI. */
    @Test fun platformPairingServiceAcceptsTheAppSpake2Exchange() = runBlocking {
        assertEmulator()
        val code = InstrumentationRegistry.getArguments().getString("wireless_pair_code")
        assumeTrue("Start the dedicated emulator platform pairing-service harness first", code != null)
        val context = instrumentation.targetContext
        val ports = NsdAdbServiceFinder(context).findPorts(AdbServiceTypes.PAIRING, 10_000)
        assertTrue("Android's native pairing service must advertise a local endpoint", ports.isNotEmpty())
        val bridge = AndroidControl.get(context).bridge
        var success = false
        for (port in ports) {
            if (bridge.pair("127.0.0.1", port, code!!).isSuccess) { success = true; break }
        }
        assertTrue("The app must complete TLS exporter + SPAKE2 + authenticated PeerInfo against Android", success)
        assertTrue(bridge.status().paired)
    }

    private fun assertEmulator() {
        check(Build.VERSION.SDK_INT >= 34) { "Shell bootstrap requires Android 14+" }
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "This lab bootstrap must not run on the user's phone" }
    }
}
