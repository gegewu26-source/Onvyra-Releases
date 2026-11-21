package me.rerere.androidcontrol

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import me.rerere.androidcontrol.adb.AdbServiceTypes
import me.rerere.androidcontrol.adb.NsdAdbServiceFinder
import me.rerere.androidcontrol.ui.AndroidControlPairingService
import me.rerere.androidcontrol.ui.PairingSetupPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, test-APK-only native Settings check. The pairing secret never leaves memory.
 * Use allow_lab_automatic_pairing=true, allow_lab_a11y_setup=true and
 * lab_avd_name=CodexReaderBarsQA. Add full_automatic_pairing=true to require the real
 * SPAKE2 exchange and a connected shell, rather than only the production dialog reader.
 * No RemoteInput, caller-supplied code, screenshot, XML dump or agent tree is used.
 */
@RunWith(AndroidJUnit4::class)
class AndroidControlAutomaticPairingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    )

    @Test
    fun nativeSettingsDialogIsReadAndOptionallyPairsWithoutManualInput() = runBlocking {
        assumeTrue("Explicit isolated-lab opt-in required",
            args.getString("allow_lab_automatic_pairing") == "true")
        assertLab()
        val control = AndroidControl.get(context)
        val wasEnabled = control.enabled
        val priorServices = shell("settings get secure enabled_accessibility_services")
        val priorAccessibility = shell("settings get secure accessibility_enabled")
        val priorWireless = shell("settings get global adb_wifi_enabled")
        val fullPairing = args.getString("full_automatic_pairing") == "true"
        var openedSettings = false
        try {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            bindLabAccessibility(priorServices)
            control.enabled = true
            val service = withTimeout(15_000) {
                while (AndroidControlAccessibilityService.instance == null) delay(100)
                requireNotNull(AndroidControlAccessibilityService.instance)
            }
            val screenshotsBefore = service.agentScreenshotRequestCount()
            // Library instrumentation has no resumed Activity. Launch the lab's
            // Settings as shell; a background Context launch may silently be denied.
            shell("am start -W -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
            openedSettings = true
            clickSettingsLabel(setOf("Wireless debugging", "无线调试"), scroll = true)
            if (shell("settings get global adb_wifi_enabled") != "1") {
                clickSettingsLabel(setOf("Use wireless debugging", "Wireless debugging", "使用无线调试", "无线调试"))
                // AOSP may ask for permission to debug on the emulator's current Wi-Fi.
                clickSettingsLabel(setOf("Allow", "允许"), required = false,
                    allowedPackages = setOf("com.android.settings", "com.android.systemui"))
            }
            withTimeout(10_000) {
                while (shell("settings get global adb_wifi_enabled") != "1") delay(200)
            }
            clickSettingsLabel(setOf("Pair device with pairing code", "使用配对码配对设备", "通过配对码配对设备"))
            val finder = NsdAdbServiceFinder(context)
            var foundLocalPorts = false
            val dialogRead = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                var readable = false
                while (!readable) {
                    val ports = finder.findPorts(AdbServiceTypes.PAIRING, 2_000).toSet()
                    foundLocalPorts = foundLocalPorts || ports.isNotEmpty()
                    // Only the production reader handles native pairing-code text. Never
                    // include its result in assertions, diagnostics or test-runner output.
                    readable = ports.isNotEmpty() && service.readWirelessPairingDialog(ports) != null
                    if (!readable) delay(200)
                }
                true
            } == true
            assertTrue("Native dialog read timed out (live=${service.hasLiveConnection()}, localDiscovery=$foundLocalPorts, ${finder.lastDiagnostics})", dialogRead)
            assertEquals("Native dialog observation must not request an agent screenshot",
                screenshotsBefore, service.agentScreenshotRequestCount())
            if (fullPairing) {
                val previousSession = AndroidControlPairingService.status.value
                AndroidControlPairingService.start(context)
                // onStartCommand is asynchronous; ignore the previous session's terminal state.
                withTimeout(5_000) {
                    while (AndroidControlPairingService.status.value === previousSession) delay(25)
                }
                withTimeout(60_000) {
                    while (AndroidControlPairingService.status.value.phase != PairingSetupPhase.CONNECTED) {
                        val phase = AndroidControlPairingService.status.value.phase
                        check(phase !in setOf(PairingSetupPhase.FAILED, PairingSetupPhase.CANCELLED,
                            PairingSetupPhase.PAIRED_CONNECTION_FAILED)) {
                            "Automatic native pairing ended in phase $phase"
                        }
                        delay(200)
                    }
                }
                assertTrue("This setup session must perform a real SPAKE2 pairing",
                    AndroidControlPairingService.status.value.paired)
                assertTrue("The local bridge must retain the paired identity", control.bridge.status().paired)
                assertEquals("Automatic pairing must not request agent screenshots",
                    screenshotsBefore, service.agentScreenshotRequestCount())
            }
        } finally {
            context.startService(Intent(context, AndroidControlPairingService::class.java)
                .setAction(AndroidControlPairingService.ACTION_CANCEL))
            if (openedSettings) shell("input keyevent KEYCODE_BACK")
            control.enabled = wasEnabled
            restoreSetting("secure", "enabled_accessibility_services", priorServices)
            restoreSetting("secure", "accessibility_enabled", priorAccessibility)
            restoreSetting("global", "adb_wifi_enabled", priorWireless)
        }
    }

    private fun assertLab() {
        check(Build.VERSION.SDK_INT >= 34) { "Android 14+ lab required" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Never run on a physical phone" }
        check(args.getString("lab_avd_name") == LAB_AVD) { "Dedicated AVD argument required" }
        check(shell("getprop ro.boot.qemu.avd_name") == LAB_AVD && shell("getprop ro.kernel.qemu") == "1")
        check(shell("id -u") == "2000") { "Lab setup requires the Android shell identity" }
        check(context.packageName == "me.rerere.androidcontrol.test") { "Only the library test APK is supported" }
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY != 0) { "Test-only APK required" }
        check(args.getString("allow_lab_a11y_setup") == "true") { "Explicit lab accessibility setup required" }
    }

    private fun bindLabAccessibility(saved: String) {
        val component = ComponentName(context, AndroidControlAccessibilityService::class.java)
        val others = saved.takeUnless { it == "null" }.orEmpty().split(':')
            .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
        check(others.all { it.matches(Regex("[A-Za-z0-9_.$/]+")) })
        if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
        shell("settings put secure enabled_accessibility_services ${(others + component.flattenToString()).joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
    }

    /** Traverses native navigation labels only; never records or serializes the dialog. */
    private suspend fun clickSettingsLabel(labels: Set<String>, scroll: Boolean = false, required: Boolean = true,
        allowedPackages: Set<String> = setOf("com.android.settings")) {
        var observedPackage = "none"
        val clicked = kotlinx.coroutines.withTimeoutOrNull(if (required) 12_000 else 1_000) {
            while (true) {
                val root = automation.rootInActiveWindow
                if (root != null) {
                    try {
                        observedPackage = root.packageName?.toString().orEmpty()
                        if (root.packageName?.toString() in allowedPackages) {
                            if (clickNode(root, labels)) return@withTimeoutOrNull true
                            if (scroll) scrollNode(root)
                        }
                    } finally { @Suppress("DEPRECATION") root.recycle() }
                }
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") false
        } == true
        check(clicked || !required) { "Required native Settings navigation control was unavailable: ${labels.first()} (package=$observedPackage)" }
    }

    private fun clickNode(node: AccessibilityNodeInfo, labels: Set<String>, depth: Int = 0): Boolean {
        if (depth > 20) return false
        if (node.isVisibleToUser && node.text?.toString() in labels) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var ancestor = node.parent
            repeat(6) {
                val current = ancestor ?: return@repeat
                ancestor = null
                try {
                    if (current.packageName != node.packageName) return@repeat
                    if (current.isVisibleToUser && current.isClickable &&
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    ancestor = current.parent
                } finally { @Suppress("DEPRECATION") current.recycle() }
            }
            ancestor?.let { @Suppress("DEPRECATION") it.recycle() }
        }
        for (i in 0 until node.childCount.coerceAtMost(100)) {
            val child = node.getChild(i) ?: continue
            try { if (clickNode(child, labels, depth + 1)) return true }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return false
    }

    private fun scrollNode(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 20) return false
        // Settings wraps its RecyclerView in a collapsing ScrollView. Scrolling
        // the outer container can report success forever without advancing rows.
        for (i in 0 until node.childCount.coerceAtMost(100)) {
            val child = node.getChild(i) ?: continue
            try { if (scrollNode(child, depth + 1)) return true }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return node.isVisibleToUser && node.isScrollable &&
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun restoreSetting(namespace: String, key: String, value: String) {
        check(value == "null" || value.matches(Regex("[A-Za-z0-9_.$/:]+")))
        if (value == "null") shell("settings delete $namespace $key")
        else shell("settings put $namespace $key $value")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private companion object { const val LAB_AVD = "CodexReaderBarsQA" }
}
