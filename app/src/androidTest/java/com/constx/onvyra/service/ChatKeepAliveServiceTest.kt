package com.constx.onvyra.service

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.constx.onvyra.RouteActivity
import com.constx.onvyra.data.datastore.SettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Uses a real system overlay and restores both the app preference and AppOps after each test. */
@RunWith(AndroidJUnit4::class)
class ChatKeepAliveServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val settingsStore get() = GlobalContext.get().get<SettingsStore>()

    @Before
    fun allowNotificationPrompt() {
        // Equivalent to accepting the prompt in this isolated test app. Revoking from
        // inside instrumentation can kill its process; the test device owns this grant.
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Test
    fun enabledOverlayHidesWithoutBeingMovedExpandsOnTapAndDisappearsWhenDisabled() = withOverlay { scenario ->
        enable(scenario)
        val initial = awaitOverlay("initial complete ball") { hasWidth(it, 52) }
        // With no touch at all, the window itself must narrow to the exposed edge.
        val initiallyHidden = awaitOverlay("initial idle hiding at the original right edge") {
            hasWidth(it, 12) && it.right == initial.right
        }
        assertEquals("Idle hiding must keep the same right screen edge", initial.right, initiallyHidden.right)
        tap(initiallyHidden)
        val expanded = awaitOverlay("first tap expands the full ball at the right edge") {
            hasWidth(it, 52) && it.right == initiallyHidden.right
        }
        val screenWidth = context.resources.displayMetrics.widthPixels
        val destinationX = screenWidth * .4f
        drag(expanded.exactCenterX(), expanded.exactCenterY(), destinationX, expanded.exactCenterY())
        awaitOverlay("service ball follows drag") {
            abs(it.exactCenterX() - destinationX) < 20
        }
        awaitOverlay("dragged service ball hides on the left edge") {
            hasWidth(it, 12) && it.left == 0
        }
        runBlocking { settingsStore.update { it.copy(enableChatKeepAlive = false) } }
        await("disabled service removes its overlay") { !ChatKeepAliveService.running.value && overlayBounds() == null }
    }

    @Test
    fun hiddenBallTapExpandsBeforeOpeningOnBothScreenEdges() = withOverlay { _ ->
        val openCount = AtomicInteger()
        val windowFailures = AtomicInteger()
        lateinit var ball: ChatFloatingBall
        instrumentation.runOnMainSync {
            ball = ChatFloatingBall(
                context = context,
                onClick = { openCount.incrementAndGet() },
                onWindowFailure = { windowFailures.incrementAndGet() },
            )
            ball.show()
        }
        try {
            awaitOverlay("direct ball initially complete") { hasWidth(it, 52) }
            val screenWidth = context.resources.displayMetrics.widthPixels
            for (rightEdge in listOf(true, false)) {
                val beforeDrag = requireNotNull(overlayBounds())
                val destinationX = screenWidth * if (rightEdge) .75f else .25f
                drag(beforeDrag.exactCenterX(), beforeDrag.exactCenterY(), destinationX, beforeDrag.exactCenterY())
                val edgeName = if (rightEdge) "right" else "left"
                awaitOverlay("direct ball follows drag toward $edgeName") {
                    abs(it.exactCenterX() - destinationX) < 20
                }
                val hidden = awaitOverlay("direct ball hides at $edgeName edge") {
                    hasWidth(it, 12) && if (rightEdge) it.right == screenWidth else it.left == 0
                }
                val previousOpenCount = openCount.get()
                tap(hidden)
                val expanded = awaitOverlay("first $edgeName-edge tap only expands the ball") {
                    hasWidth(it, 52) && if (rightEdge) it.right == hidden.right else it.left == hidden.left
                }
                assertEquals("A hidden-ball tap must only expand it", previousOpenCount, openCount.get())
                if (rightEdge) assertEquals(hidden.right, expanded.right)
                else assertEquals(hidden.left, expanded.left)

                tap(expanded)
                await("second $edgeName-edge tap opens the application") { openCount.get() == previousOpenCount + 1 }
                assertEquals("One expanded-ball tap must open only once", previousOpenCount + 1, openCount.get())
            }
            assertEquals("Real overlay updates must succeed on both edges", 0, windowFailures.get())
        } finally {
            instrumentation.runOnMainSync { ball.dismiss() }
            await("direct ball dismissal removes its window") { overlayBounds() == null }
        }
    }

    @Test
    fun notificationStopRemovesOverlayAndPersistsDisabledState() = withOverlay { scenario ->
        enable(scenario)
        await("notification test overlay appears") { overlayBounds() != null }
        // Notification publication is asynchronous even after the overlay is attached.
        await("foreground notification is published") {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.notification.channelId == "chat_keep_alive" }
        }
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.notification.channelId == "chat_keep_alive" }
        assertNotNull("Keep-alive must have a foreground notification", notification)
        val stopAction = requireNotNull(notification).notification.actions.last()
        stopAction.actionIntent.send()
        await("notification stop disables service and removes overlay") {
            !settingsStore.settingsFlow.value.enableChatKeepAlive &&
                !ChatKeepAliveService.running.value && overlayBounds() == null
        }
        // Returning to the activity must not restart a feature stopped from its notification.
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        instrumentation.waitForIdleSync()
        assertFalse(settingsStore.settingsFlow.value.enableChatKeepAlive)
        assertFalse(ChatKeepAliveService.running.value)
    }

    private fun enable(scenario: ActivityScenario<RouteActivity>) {
        runBlocking { settingsStore.update { it.copy(enableChatKeepAlive = true) } }
        scenario.onActivity { assertTrue(ChatKeepAliveService.start(it)) }
        await("keep-alive service starts") { ChatKeepAliveService.running.value }
    }

    private fun withOverlay(block: (ActivityScenario<RouteActivity>) -> Unit) {
        val previousEnabled = runBlocking { settingsStore.awaitLoaded().enableChatKeepAlive }
        val appOps = shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
        val previousMode = Regex("SYSTEM_ALERT_WINDOW:\\s*(allow|ignore|deny|default|foreground)")
            .find(appOps)?.groupValues?.get(1) ?: "default"
        val previousFlags = automation.serviceInfo.flags
        var scenario: ActivityScenario<RouteActivity>? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            runBlocking { settingsStore.update { it.copy(enableChatKeepAlive = false) } }
            ChatKeepAliveService.stop(context)
            await("previous keep-alive service stops before the test") { !ChatKeepAliveService.running.value }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            await("overlay permission becomes available") { Settings.canDrawOverlays(context) }
            assertTrue("Test device must permit application overlays", Settings.canDrawOverlays(context))
            scenario = ActivityScenario.launch(RouteActivity::class.java)
            block(scenario)
        } finally {
            runBlocking { settingsStore.update { it.copy(enableChatKeepAlive = previousEnabled) } }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $previousMode")
            scenario?.onActivity {
                if (previousEnabled && Settings.canDrawOverlays(it)) ChatKeepAliveService.start(it)
                else ChatKeepAliveService.stop(it)
            }
            scenario?.close()
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    private fun overlayBounds(): Rect? = automation.windows.firstNotNullOfOrNull { window ->
        val root = window.root ?: return@firstNotNullOfOrNull null
        if (root.refresh() && root.contentDescription?.toString()?.startsWith("RikkaHub 悬浮球") == true) {
            // Node bounds can include a separate accessibility window transformation during movement.
            // Read the actual window frame for both its position and size in the same snapshot.
            Rect().also(window::getBoundsInScreen)
        } else null
    }

    private fun hasWidth(bounds: Rect, dp: Int): Boolean {
        val expectedWidth = (dp * context.resources.displayMetrics.density).roundToInt()
        return bounds.width() == expectedWidth
    }

    private fun awaitOverlay(label: String, predicate: (Rect) -> Boolean): Rect {
        var matchingBounds: Rect? = null
        await(label) {
            val current = overlayBounds()
            if (current != null && predicate(current)) {
                matchingBounds = current
                true
            } else false
        }
        return requireNotNull(matchingBounds)
    }

    private fun tap(bounds: Rect) {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            SystemClock.sleep(32)
        }
    }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, fromX, fromY)
        for (step in 1..12) {
            SystemClock.sleep(16)
            val fraction = step / 12f
            send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction)
        }
        send(MotionEvent.ACTION_UP, toX, toY)
    }

    private fun await(label: String, predicate: () -> Boolean) = runBlocking {
        try {
            withTimeout(15_000) {
                while (!predicate()) delay(50)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $label; last overlay bounds=${overlayBounds()}", timeout)
        }
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
