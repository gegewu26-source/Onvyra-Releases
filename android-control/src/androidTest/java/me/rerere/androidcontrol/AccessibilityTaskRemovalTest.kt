package me.rerere.androidcontrol

import android.Manifest
import android.app.ActivityManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import me.rerere.androidcontrol.ui.AndroidControlActivity
import me.rerere.androidcontrol.ui.AndroidControlNotificationService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Removes the UI task via Android's actual task API, never force-stops the package. */
@RunWith(AndroidJUnit4::class)
class AccessibilityTaskRemovalTest {
    @Test fun removingTheUiTaskKeepsAuthorizedStandbyAndNodeActionsAlive() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            shell("getprop ro.boot.qemu.avd_name") in setOf("CodexReaderQA", "CodexReaderBarsQA")) { "Dedicated lab only" }
        check(InstrumentationRegistry.getArguments().getString("allow_lab_a11y_setup") == "true")
        val context = instrumentation.targetContext
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val control = AndroidControl.get(context)
        check(control.accessibilityAuthorizationGranted()) { "Restore the lab's authorized service before running this check" }
        withTimeout(10_000) { while (!control.accessibilityConnected()) delay(100) }
        val wasEnabled = control.enabled
        try {
            control.stopAll()
            control.enabled = true
            withTimeout(5_000) { while (!AndroidControlNotificationService.running) delay(50) }
            repeat(3) { cycle ->
                val marker = "a11y-task-removal-$cycle"
                context.startActivity(Intent(context, AndroidControlActivity::class.java)
                    .putExtra("a11y_lifecycle_test", marker)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                automation.waitForIdle(100, 5_000)
                val manager = context.getSystemService(ActivityManager::class.java)
                val task = manager.appTasks.firstOrNull {
                    it.taskInfo?.baseIntent?.getStringExtra("a11y_lifecycle_test") == marker
                } ?: error("The explicitly created UI task was not found")
                // Same platform operation used by recent-task dismissal, scoped
                // to this test's own task; existing app data is untouched.
                instrumentation.runOnMainSync { task.finishAndRemoveTask() }
                delay(300)
                assertTrue("Removing UI must preserve the system grant", control.accessibilityAuthorizationGranted())
                withTimeout(5_000) { while (!control.accessibilityConnected()) delay(50) }
                assertTrue("Visible idle protection must survive UI task removal", AndroidControlNotificationService.running)
                shell("am start --display 0 -n me.rerere.androidcontrol.mainfixture/.MainFixtureActivity")
                var tree = JsonObject(emptyMap())
                val service = requireNotNull(AndroidControlAccessibilityService.instance)
                val captures = service.agentScreenshotRequestCount()
                withTimeout(8_000) {
                    do {
                        tree = withContext(Dispatchers.IO) { service.tree(0) }
                        if (nodes(tree).any { it["text"]?.jsonPrimitive?.contentOrNull?.matches(Regex("MAIN_COUNTER_\\d+")) == true }) break
                        delay(50)
                    } while (true)
                }
                val counter = nodes(tree).single { it["text"]?.jsonPrimitive?.contentOrNull?.matches(Regex("MAIN_COUNTER_\\d+")) == true }
                val countBefore = counter["text"]!!.jsonPrimitive.content.substringAfterLast('_').toInt()
                assertTrue(withContext(Dispatchers.IO) {
                    service.perform(0, counter["id"]!!.jsonPrimitive.content, "click")
                })
                withTimeout(5_000) {
                    while (withContext(Dispatchers.IO) { nodes(service.tree(0)) }.none {
                        it["text"]?.jsonPrimitive?.contentOrNull == "MAIN_COUNTER_${countBefore + 1}"
                    }) delay(50)
                }
                assertEquals("Node-only verification must take no Agent screenshots", captures, service.agentScreenshotRequestCount())
            }
            println("A11Y_TASK_REMOVAL=PASS; cycles=3; bound_rpc=true; node_click=true; agent_screenshots=0")
        } finally { control.enabled = wasEnabled }
    }

    private fun nodes(tree: JsonObject): List<JsonObject> = buildList {
        fun walk(value: kotlinx.serialization.json.JsonElement?) {
            when (value) {
                is JsonObject -> { if (value["id"]?.jsonPrimitive?.contentOrNull?.startsWith("d") == true) add(value); value.values.forEach(::walk) }
                is JsonArray -> value.forEach(::walk)
                else -> Unit
            }
        }
        walk(tree)
    }
}
