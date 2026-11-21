package me.rerere.androidcontrol

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.androidcontrol.accessibility.AndroidControlAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit manual takeover is tested only against the two disposable fixture applications. */
@RunWith(AndroidJUnit4::class)
class AndroidControlTakeoverTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun manualTakeoverInterruptsAnInFlightWaitWithoutDestroyingItsResumableSession() = runBlocking {
        assertDedicatedLab()
        val context = instrumentation.targetContext
        val service = awaitService()
        val control = AndroidControl.get(context)
        val previousEnabled = control.enabled
        val previousPreview = control.previewEnabled
        val owner = "takeover-${UUID.randomUUID()}"
        var waiting: Deferred<JsonObject>? = null
        val mainIntent = requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN_FIXTURE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            withTimeout(15_000) { while (control.bridge.shell.value == null) delay(100) }
            assertEquals("Exercise the actual authenticated helper", 2000,
                requireNotNull(control.bridge.shell.value).identity()[0])
            control.enabled = true
            // Preview permissions and window geometry are covered by a separate overlay test.
            control.previewEnabled = false
            context.startActivity(mainIntent)
            awaitMainPackage(service, MAIN_FIXTURE)
            assertMainFocusIsFixture()

            val opened = control.execute("open_app", json { put("package_name", FIXTURE) }, owner)
            assertOkay(opened)
            val taskId = requireNotNull(opened.string("task_id"))
            val displayId = requireNotNull(opened["display_id"]).jsonPrimitive.int
            assertTrue(displayId > 0)
            awaitTaskTree(control, owner)

            val operationOwner = control.javaClass.getDeclaredField("operationOwner").apply { isAccessible = true }
            val waitJob = async(Dispatchers.IO) {
                control.execute("wait_for", json {
                    put("expect", "takeover-state-that-never-exists-${UUID.randomUUID()}")
                    put("timeout_ms", 60_000)
                }, owner)
            }
            waiting = waitJob
            withTimeout(3_000) {
                while (operationOwner.get(control) != owner) {
                    assertFalse("The wait completed before takeover could interrupt it", waitJob.isCompleted)
                    delay(10)
                }
            }
            assertFalse(waitJob.isCompleted)
            val handoff = withTimeout(3_000) { control.takeover() }
            assertOkay(handoff)
            assertEquals("user_takeover_active", withTimeout(3_000) { waitJob.await() }.string("error"))
            assertTrue(control.state()["paused"]?.jsonPrimitive?.booleanOrNull == true)
            assertEquals("Takeover must retain the original session", taskId, control.state().string("task_id"))
            assertEquals(displayId, control.state()["display_id"]?.jsonPrimitive?.int)
            awaitMainPackage(service, FIXTURE)

            val blocked = control.execute("press_key", json { put("key", "SEARCH") }, owner)
            assertEquals("Agent actions must remain paused during manual control", "user_takeover_active", blocked.string("error"))
            assertEquals(taskId, control.state().string("task_id"))
            assertEquals("leave_target_app_on_main_then_retry", control.resumeAfterTakeover().string("error"))
            assertTrue(control.state()["paused"]?.jsonPrimitive?.booleanOrNull == true)

            // Only this explicit test UI action leaves the taken-over app on the main screen.
            context.startActivity(mainIntent)
            awaitMainPackage(service, MAIN_FIXTURE)
            assertOkay(withTimeout(5_000) { control.resumeAfterTakeover() })
            assertEquals(taskId, control.state().string("task_id"))
            assertEquals(displayId, control.state()["display_id"]?.jsonPrimitive?.int)
            assertFalse(control.state()["paused"]?.jsonPrimitive?.booleanOrNull == true)
            awaitTaskTree(control, owner)
            // A real injected key proves startApp cleared the helper's cancelled-input marker.
            assertOkay(control.execute("press_key", json { put("key", "SEARCH") }, owner))
            awaitMainPackage(service, MAIN_FIXTURE)
            assertMainFocusIsFixture()
        } finally {
            control.stopAll()
            waiting?.cancelAndJoin()
            control.previewEnabled = previousPreview
            control.enabled = previousEnabled
            context.startActivity(mainIntent)
        }
    }

    private fun assertDedicatedLab() {
        assertTrue("Never run takeover tests on the user's phone", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        assertEquals("StatsUsageTest", shell("getprop ro.boot.qemu.avd_name"))
    }

    private suspend fun awaitService(): AndroidControlAccessibilityService {
        assertDedicatedLab()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        if (AndroidControlAccessibilityService.instance == null) {
            assertEquals("Lab authorization recovery must be explicitly enabled", "true",
                InstrumentationRegistry.getArguments().getString("allow_lab_a11y_setup"))
            val component = ComponentName(instrumentation.targetContext, AndroidControlAccessibilityService::class.java)
            fun configured() = shell("settings get secure enabled_accessibility_services")
                .takeUnless { it == "null" }.orEmpty().split(':').filter(String::isNotBlank)
            fun ours(value: String) = ComponentName.unflattenFromString(value) == component
            val remaining = configured().filterNot(::ours)
            writeServices(remaining)
            shell("settings put secure accessibility_enabled ${if (remaining.isEmpty()) 0 else 1}")
            withTimeoutOrNull(2_000) { while (AndroidControlAccessibilityService.instance != null) delay(50) }
            delay(250)
            writeServices(configured().filterNot(::ours) + component.flattenToString())
            shell("settings put secure accessibility_enabled 1")
        }
        return withTimeout(15_000) {
            while (AndroidControlAccessibilityService.instance == null) delay(100)
            requireNotNull(AndroidControlAccessibilityService.instance)
        }
    }

    private fun writeServices(components: List<String>) {
        assertTrue(components.all { it.matches(Regex("[A-Za-z0-9_.$/]+")) && ComponentName.unflattenFromString(it) != null })
        if (components.isEmpty()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services ${components.joinToString(":")}")
    }

    private suspend fun awaitMainPackage(service: AndroidControlAccessibilityService, expected: String) = withTimeout(10_000) {
        // A window can report its package before its first content/layout event.
        // Wait for package and fixture marker in the same actual observation.
        while (service.tree(0).let { tree ->
            tree.string("packageName") != expected ||
                (expected == MAIN_FIXTURE && (AndroidControl.nodes(tree).none { it.string("text") == "MAIN_SCREEN_UNCHANGED" } ||
                    mainFocusLines().any { !it.contains("$MAIN_FIXTURE/") }))
        }) delay(100)
    }

    private fun mainFocusLines(): List<String> {
        val dump = shell("dumpsys window displays")
        val section = Regex("(?m)^[ \\t]*Display: mDisplayId=0\\b[^\\n]*\\n([\\s\\S]*?)(?=^[ \\t]*Display: mDisplayId=\\d+|\\z)")
            .find(dump)?.value ?: error("The lab main display was absent")
        return listOf("mCurrentFocus=", "mFocusedApp=").map { prefix ->
            section.lineSequence().firstOrNull { it.trimStart().startsWith(prefix) }.orEmpty()
        }
    }

    private fun assertMainFocusIsFixture() {
        for (line in mainFocusLines()) {
            assertTrue("The restored main fixture lost its own display focus: $line", line.contains("$MAIN_FIXTURE/"))
        }
    }

    private suspend fun awaitTaskTree(control: AndroidControl, owner: String): JsonObject = withTimeout(10_000) {
        while (true) {
            val response = control.execute("get_ui_tree", json {}, owner)
            assertOkay(response)
            val tree = response["screen"] as? JsonObject
            if (tree?.string("packageName") == FIXTURE && AndroidControl.nodes(tree).any {
                    it.string("resourceId")?.endsWith(":id/input_editor") == true
                }) return@withTimeout tree
            delay(100)
        }
        error("unreachable")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun assertOkay(value: JsonObject) = assertTrue("Control operation failed: $value", value["ok"]?.jsonPrimitive?.booleanOrNull == true)
    private fun json(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content

    private companion object {
        const val FIXTURE = "me.rerere.androidcontrol.fixture"
        const val MAIN_FIXTURE = "me.rerere.androidcontrol.mainfixture"
    }
}
