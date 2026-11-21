package me.rerere.androidcontrol

import android.Manifest
import android.app.UiAutomation
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.ComponentName
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.androidcontrol.ui.AndroidControlActivity
import me.rerere.androidcontrol.ui.AndroidControlNotificationService
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real notification actions and real display teardown; refuses physical devices. */
@RunWith(AndroidJUnit4::class)
class AndroidControlStandbyProtectionTest {
    @Test fun stopTaskReleasesItsDisplayButStandbyRemainsUntilCloseControl() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            shell("getprop ro.boot.qemu.avd_name") in setOf("StatsUsageTest", "CodexReaderQA", "CodexReaderBarsQA")) { "Dedicated lab only" }
        val context = instrumentation.targetContext
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val control = AndroidControl.get(context)
        if (!control.accessibilityConnected()) {
            check(InstrumentationRegistry.getArguments().getString("allow_lab_a11y_setup") == "true") {
                "Explicit allow_lab_a11y_setup is required to restore the lab's accessibility fixture"
            }
            val component = ComponentName(context, AndroidControlAccessibilityService::class.java)
            val remaining = shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" }
                .orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
            check(remaining.all { it.matches(Regex("[A-Za-z0-9_.$/]+")) })
            if (remaining.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services "+remaining.joinToString(":"))
            shell("settings put secure accessibility_enabled "+if (remaining.isEmpty()) "0" else "1")
            delay(300)
            shell("settings put secure enabled_accessibility_services "+(remaining+component.flattenToString()).joinToString(":"))
            shell("settings put secure accessibility_enabled 1")
        }
        val wasEnabled = control.enabled
        val wasPreview = control.previewEnabled
        control.stopAll()
        var phase = "validate_authorization"
        var observedDisplay = -1
        try {
            assertTrue("The lab must explicitly authorize accessibility", control.accessibilityAuthorizationGranted())
            phase = "await_accessibility_and_shell"
            withTimeout(10_000) { while (!control.accessibilityConnected() || control.bridge.shell.value == null) delay(100) }
            phase = "open_setup_activity_and_await_idle"
            context.startActivity(Intent(context, AndroidControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            automation.waitForIdle(100, 5_000)
            control.enabled = true
            control.previewEnabled = false
            val owner = "standby-notification-test"
            control.setApprovalMode(owner, AndroidControlApprovalMode.FULL_ACCESS)
            phase = "open_fixture_on_control_display"
            val opened = control.execute("open_app", buildJsonObject {
                put("package_name", "me.rerere.androidcontrol.fixture")
            }, owner)
            assertNull(opened.toString(), opened["error"])
            val display = requireNotNull(opened["display_id"]).jsonPrimitive.int
            observedDisplay = display
            phase = "await_active_notification_stop_action"
            val active = awaitNotification { it.actions?.any { action -> action.title.toString() == "停止任务" } == true }
            phase = "send_notification_stop_action"
            active.actions.single { it.title.toString() == "停止任务" }.actionIntent.send()
            phase = "await_session_and_display_release"
            withTimeout(8_000) { while (control.hasActiveSession() ||
                context.getSystemService(DisplayManager::class.java).getDisplay(display) != null) delay(50) }
            phase = "await_idle_standby_notification"
            val idle = awaitNotification { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "手机操控待机保护" }
            phase = "assert_standby_binding_and_actions"
            assertTrue(control.enabled)
            assertTrue("Stopping a task must retain the authorized accessibility binding", control.accessibilityConnected())
            assertTrue(AndroidControlNotificationService.running)
            assertFalse(idle.actions.any { it.title.toString() == "停止任务" })
            phase = "send_notification_disable_action"
            idle.actions.single { it.title.toString() == "关闭操控" }.actionIntent.send()
            phase = "await_control_disabled_and_notification_removed"
            withTimeout(5_000) { while (control.enabled || AndroidControlNotificationService.running ||
                notification() != null) delay(50) }
            phase = "assert_system_authorization_retained"
            assertTrue("Closing app control must not alter the system accessibility grant", control.accessibilityAuthorizationGranted())
        } catch (error: Throwable) {
            // Only this control service's lifecycle flags and fixed notification
            // labels are exported. No page tree, app identity, intent or text.
            runCatching {
                val state = control.state()
                val safeKeys = setOf("enabled", "control_ready", "accessibility_connected",
                    "accessibility_authorized", "shell_connected", "standby_protected",
                    "control_notifications_allowed", "standby_protection_error", "readiness_error",
                    "state", "paused", "error")
                val currentNotification = notification()
                println("STANDBY_TEST_DIAGNOSTIC=" + buildJsonObject {
                    put("phase", phase)
                    put("failure_type", error.javaClass.simpleName)
                    put("control", JsonObject(state.filterKeys { it in safeKeys }))
                    put("session_active", control.hasActiveSession())
                    put("observed_display_present", observedDisplay >= 0 &&
                        context.getSystemService(DisplayManager::class.java).getDisplay(observedDisplay) != null)
                    put("notification_present", currentNotification != null)
                    put("notification_title", currentNotification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(80))
                    put("notification_actions", JsonArray(currentNotification?.actions.orEmpty().map {
                        JsonPrimitive(it.title.toString().take(80))
                    }))
                })
            }.onFailure { println("STANDBY_TEST_DIAGNOSTIC_UNAVAILABLE phase=$phase type=${it.javaClass.simpleName}") }
            throw error
        } finally {
            control.stopAll()
            control.previewEnabled = wasPreview
            control.enabled = wasEnabled
            shell("am start --display 0 -n me.rerere.androidcontrol.mainfixture/.MainFixtureActivity")
        }
    }

    private fun notification(): Notification? = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.id == AndroidControlNotificationService.NOTIFICATION_ID }?.notification

    private suspend fun awaitNotification(predicate: (Notification) -> Boolean): Notification = withTimeout(8_000) {
        while (true) {
            notification()?.takeIf(predicate)?.let { return@withTimeout it }
            delay(50)
        }
        error("unreachable")
    }
}
