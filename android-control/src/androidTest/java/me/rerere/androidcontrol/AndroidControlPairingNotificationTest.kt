package me.rerere.androidcontrol

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.androidcontrol.ui.AndroidControlPairingService
import me.rerere.androidcontrol.ui.AndroidControlNotificationService
import me.rerere.androidcontrol.ui.PairingSetupPhase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Host starts Android's native pairing service; this case exercises the real notification PendingIntent. */
@RunWith(AndroidJUnit4::class)
class AndroidControlPairingNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun queuedForegroundSessionStartCanBeImmediatelyDisabledOrStoppedWithoutCrashing() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            shell("getprop ro.boot.qemu.avd_name") == "StatsUsageTest") { "Dedicated lab only" }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val control = AndroidControl.get(context)
        val wasEnabled = control.enabled
        control.stopAll()
        try {
            // Queue starts and STOP before the main looper can deliver service callbacks.
            withContext(Dispatchers.Main) {
                repeat(3) {
                    control.enabled = true
                    context.startForegroundService(Intent(context,AndroidControlNotificationService::class.java))
                    control.enabled = false
                    context.startService(Intent(context,AndroidControlNotificationService::class.java)
                        .setAction(AndroidControlNotificationService.ACTION_STOP))
                }
            }
            // The old implementation crashed on Android's foreground-service five-second deadline.
            delay(6_500)
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id == 6801 })
            assertFalse(control.hasActiveSession())
        } finally {
            context.stopService(Intent(context,AndroidControlNotificationService::class.java))
            control.enabled = wasEnabled
        }
    }

    @Test fun notificationActionsFromDifferentSetupRoundsHaveDistinctPendingIntentIdentities() {
        val firstToken = UUID.randomUUID().toString()
        val secondToken = UUID.randomUUID().toString()
        val firstReply = AndroidControlPairingService.actionPendingIntent(context,AndroidControlPairingService.ACTION_SUBMIT,firstToken)
        val secondReply = AndroidControlPairingService.actionPendingIntent(context,AndroidControlPairingService.ACTION_SUBMIT,secondToken)
        val firstCancel = AndroidControlPairingService.actionPendingIntent(context,AndroidControlPairingService.ACTION_CANCEL,firstToken)
        val secondCancel = AndroidControlPairingService.actionPendingIntent(context,AndroidControlPairingService.ACTION_CANCEL,secondToken)
        try {
            assertNotEquals("An old RemoteInput must not be updated to a new round's token",firstReply,secondReply)
            assertNotEquals("An old cancel button must not cancel a new setup round",firstCancel,secondCancel)
            assertEquals(firstReply,AndroidControlPairingService.actionPendingIntent(context,AndroidControlPairingService.ACTION_SUBMIT,firstToken))
            assertFalse(firstReply.isImmutable)
            assertTrue(firstCancel.isImmutable)
        } finally {
            firstReply.cancel();secondReply.cancel();firstCancel.cancel();secondCancel.cancel()
        }
    }

    @Test fun notificationRemoteInputPairsWithTheNativeServiceWithoutOpeningTheController() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Dedicated emulator only" }
        check(shell("getprop ro.kernel.qemu") == "1" && shell("getprop ro.boot.qemu.avd_name") == "StatsUsageTest") {
            "Notification pairing lab must not run on the user's phone"
        }
        val code = InstrumentationRegistry.getArguments().getString("wireless_pair_code")
        assumeTrue("The host must start the native pairing lab and supply its ephemeral code",code?.matches(Regex("[0-9]{6}")) == true)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val control = AndroidControl.get(context)
        val wasEnabled = control.enabled
        val mainIntent = requireNotNull(context.packageManager.getLaunchIntentForPackage(MAIN_FIXTURE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(mainIntent)
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("The independent main fixture must be foreground before setup",device.wait(Until.hasObject(By.text("MAIN_SCREEN_UNCHANGED")),5_000))
        val focus = mainFocus()
        try {
            control.enabled = true
            AndroidControlPairingService.start(context)
            val reply = awaitReplyAction()
            assertFalse("RemoteInput requires a mutable PendingIntent with an explicit service target",reply.actionIntent.isImmutable)
            assertEquals(context.packageName,reply.actionIntent.creatorPackage)
            assertEquals(focus,mainFocus())

            sendReply(reply,"123")
            delay(200)
            assertEquals("Invalid codes must remain editable without contacting the pairing server",PairingSetupPhase.AWAITING_CODE,AndroidControlPairingService.status.value.phase)
            sendReply(awaitReplyAction(),requireNotNull(code))
            withTimeout(40_000) {
                while (!AndroidControlPairingService.status.value.paired) {
                    assertNotEquals("Notification setup failed before a real pair completed",PairingSetupPhase.FAILED,AndroidControlPairingService.status.value.phase)
                    delay(100)
                }
            }
            assertTrue("Only the native server completing SPAKE2 may mark the bridge paired",control.bridge.status().paired)
            assertEquals("Submitting notification input must not open the controller or replace the user's activity",focus,mainFocus())
            assertTrue(device.hasObject(By.text("MAIN_SCREEN_UNCHANGED")))

            context.startService(Intent(context,AndroidControlPairingService::class.java).setAction(AndroidControlPairingService.ACTION_CANCEL))
            withTimeout(2_000) { while (AndroidControlPairingService.status.value.phase != PairingSetupPhase.CANCELLED) delay(25) }
            assertTrue("Cancelling discovery/connection must retain completed pairing credentials",control.bridge.status().paired)
            assertEquals(focus,mainFocus())
        } finally {
            context.startService(Intent(context,AndroidControlPairingService::class.java).setAction(AndroidControlPairingService.ACTION_CANCEL))
            control.enabled = wasEnabled
        }
    }

    private suspend fun awaitReplyAction(): Notification.Action = withTimeout(15_000) {
        while (true) {
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
                .firstOrNull { it.id == AndroidControlPairingService.NOTIFICATION_ID }?.notification
            val action = notification?.actions?.firstOrNull { candidate -> candidate.remoteInputs?.any {
                it.resultKey == AndroidControlPairingService.KEY_CODE
            } == true }
            if (action != null && AndroidControlPairingService.status.value.phase == PairingSetupPhase.AWAITING_CODE) return@withTimeout action
            delay(100)
        }
        error("unreachable")
    }

    private fun sendReply(action: Notification.Action, code: String) {
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs,fillIn,Bundle().apply {
            putCharSequence(AndroidControlPairingService.KEY_CODE,code)
        })
        action.actionIntent.send(context,0,fillIn)
    }

    private fun mainFocus(): String {
        val dump = shell("dumpsys window displays")
        val heading = Regex("(?m)^([ \\t]*)Display: mDisplayId=0\\b[^\\n]*$").find(dump)
            ?: error("Main display missing")
        val after = dump.substring(heading.range.last+1)
        val end = Regex("(?m)^[ \\t]*Display: mDisplayId=\\d+").find(after)?.range?.first ?: after.length
        val display = after.substring(0,end)
        val focus = Regex("(?m)^[ \\t]*mCurrentFocus=.*$").find(display)?.value?.trim()
            ?: error("Main focus missing")
        check(focus.contains(MAIN_FIXTURE)) { "Main fixture lost focus" }
        return focus
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    companion object { private const val MAIN_FIXTURE = "me.rerere.androidcontrol.mainfixture" }
}
