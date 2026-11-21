package me.rerere.androidcontrol.shell

import org.junit.Assert.*
import org.junit.Test

class HelperHandoffTest {
    private class MemoryStore : NonceStore {
        var slots = NonceSlots()
        override fun load() = slots
        override fun save(slots: NonceSlots) { this.slots = slots }
    }
    private var uptime = 1_000L
    private var wall = 1_000_000L
    private var sequence = 0
    private val store = MemoryStore()
    private val nonces = HandoffNonces(store, { uptime }, { wall }, { "nonce-${++sequence}" })

    @Test fun rejectsNonShellCallerBeforeInspectingBinder() {
        assertEquals(HelperHandoff.Reject.NotShell, check(uid = 12345))
    }
    @Test fun rejectsSpoofedUidAndPid() {
        assertEquals(HelperHandoff.Reject.IdMismatch, check(reportedUid = 0))
        assertEquals(HelperHandoff.Reject.IdMismatch, check(reportedPid = 999))
    }
    @Test fun rejectsHelperRunningAnOldApkOrMissingBinder() {
        assertEquals(HelperHandoff.Reject.StaleApk, check(apk = "/old/base.apk"))
        assertEquals(HelperHandoff.Reject.NoBinder, check(binder = false))
        assertNull(check())
    }
    @Test fun launchNonceCanOnlyBeAcceptedOnce() {
        val launch = nonces.issueLaunch()
        val resume = nonces.accept(launch)
        assertNotNull(resume)
        assertNull(nonces.accept(launch))
        assertTrue(nonces.matches(resume))
    }
    @Test fun cancellingALaunchRejectsALateHelperAndPreservesResumeAndUsb() {
        val resume = nonces.accept(nonces.issueLaunch())
        val usb = nonces.usbNonce()
        val launch = nonces.issueLaunch()
        nonces.cancelLaunch(launch)
        assertFalse(nonces.matches(launch))
        assertNull(nonces.accept(launch))
        assertTrue(nonces.matches(resume))
        assertTrue(nonces.matches(usb))
    }
    @Test fun cancellingAnOldAttemptCannotInvalidateANewerLaunch() {
        val old = nonces.issueLaunch()
        val current = nonces.issueLaunch()
        nonces.cancelLaunch(old)
        assertFalse(nonces.matches(old))
        assertTrue(nonces.matches(current))
        assertNotNull(nonces.accept(current))
    }
    @Test fun cancellingAnAcceptedLaunchCannotRevokeItsRotatedResumeToken() {
        val launch = nonces.issueLaunch()
        val resume = nonces.accept(launch)
        nonces.cancelLaunch(launch)
        assertTrue(nonces.matches(resume))
        nonces.cancelLaunch(launch)
        assertTrue(nonces.matches(resume))
    }
    @Test fun acceptingAnySlotInvalidatesAllPreviousSlots() {
        val launch = nonces.issueLaunch()
        val usb = nonces.usbNonce()
        val resume = nonces.accept(usb)
        assertFalse(nonces.matches(launch))
        assertFalse(nonces.matches(usb))
        assertTrue(nonces.matches(resume))
    }
    @Test fun launchNonceExpiresAndRejectsClockRollback() {
        val launch = nonces.issueLaunch()
        uptime += HandoffNonces.LAUNCH_TTL_MS + 1
        assertFalse(nonces.matches(launch))
        uptime = 999
        assertFalse(nonces.matches(launch))
    }
    @Test fun usbNonceSurvivesRebootButExpiresAfterOneDay() {
        val usb = nonces.usbNonce()
        uptime = 10
        assertTrue(nonces.matches(usb))
        wall += HandoffNonces.USB_TTL_MS + 1
        assertFalse(nonces.matches(usb))
    }
    @Test fun freshUsbNonceIsReusedAndReplacedAfterHalfItsLifetime() {
        val usb = nonces.usbNonce()
        assertEquals(usb, nonces.usbNonce())
        wall += HandoffNonces.USB_TTL_MS / 2 + 1
        assertNotEquals(usb, nonces.usbNonce())
    }
    @Test fun revocationRejectsResumeLaunchAndUsbTokens() {
        val resume = nonces.accept(nonces.issueLaunch())
        val launch = nonces.issueLaunch()
        val usb = nonces.usbNonce()
        nonces.clear()
        listOf(resume, launch, usb, null, "unknown").forEach { assertFalse(nonces.matches(it)) }
    }
    @Test fun resumeNoncePersistsAcrossAppProcessRecreationAndRotatesAgain() {
        val resume = nonces.accept(nonces.issueLaunch())
        val recreated = HandoffNonces(store, { uptime }, { wall }, { "new-${++sequence}" })
        val next = recreated.accept(resume)
        assertNotNull(next)
        assertFalse(recreated.matches(resume))
        assertTrue(recreated.matches(next))
    }
    @Test fun shellCommandUsesStdinNonceAndQuotesPaths() {
        val command = HelperHandoff.command("/data/app/my'app/base.apk", "me.example.debug", 12345, "certificate")
        assertTrue(command.startsWith("read n;"))
        assertTrue(command.contains("setsid app_process"))
        assertTrue(command.contains("'\\''"))
        assertFalse(command.contains("secret-nonce"))
        assertEquals("secret-nonce\n", HelperHandoff.stdinLine("secret-nonce").toString(Charsets.UTF_8))
    }
    @Test fun certificatePinsDoNotDependOnSignerOrdering() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertEquals(HelperHandoff.certPin(listOf(a, b)), HelperHandoff.certPin(listOf(b, a)))
        assertNull(HelperHandoff.certPin(emptyList()))
        assertNotEquals(HelperHandoff.certPin(listOf(a)), HelperHandoff.certPin(listOf(b)))
    }
    private fun check(uid: Int = 2000, reportedUid: Int = uid, reportedPid: Int = 123,
                      apk: String = "/current/base.apk", binder: Boolean = true) =
        HelperHandoff.check(uid, 123, reportedUid, reportedPid, apk, "/current/base.apk", binder)
}
