package me.rerere.androidcontrol.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RootShellBootstrapTest {
    private fun plan(apk: String = "/data/app/example/base.apk") =
        RootShellBootstrap.arguments(apk, "com.constx.onvyra", 10123, "certificate-pin")

    @Test fun magiskCommandTargetsShellUidAndGroupRatherThanRoot() {
        val args = plan()
        assertEquals(listOf("su", "-g", "2000", "--", "2000", "-c"), args.take(6))
        assertEquals("2000", args[4])
        assertEquals(7, args.size)
    }
    @Test fun checksActualUidBeforeLaunchingTheHelper() {
        val command = plan().last()
        assertTrue(command.startsWith("if [ \"\$(/system/bin/id -u)\" != \"2000\" ]; then exit 93; fi;"))
        assertTrue(command.indexOf(RootShellBootstrap.READY) < command.indexOf("read n;"))
        assertTrue(command.contains("setsid app_process"))
        assertTrue(command.contains(HelperHandoff.HELPER_CLASS))
    }
    @Test fun existingHandoffShellEscapingProtectsApkPaths() {
        assertTrue(plan("/data/app/a'b/base.apk").last().contains("'\\''"))
    }
    @Test fun commandNeverEmbedsALaunchNonceOrChangesMagiskConfiguration() {
        val command = plan().joinToString(" ")
        assertFalse(command.contains("secret-launch-nonce"))
        assertFalse(command.contains("magiskpolicy"))
        assertFalse(command.contains("resetprop"))
        assertFalse(command.contains("--sqlite"))
        assertTrue(command.contains("read n;"))
    }
    @Test fun appMustHaveAnApplicationUid() {
        assertThrows(IllegalArgumentException::class.java) {
            RootShellBootstrap.arguments("base.apk", "package", 0, "pin")
        }
    }
    @Test fun readinessAcceptsSuBannerAndCrlfButRequiresExactMarker() {
        assertTrue(RootShellBootstrap.awaitReady(ByteArrayInputStream(
            "su banner\n${RootShellBootstrap.READY}\r\n".toByteArray())))
        assertFalse(RootShellBootstrap.awaitReady(ByteArrayInputStream(
            "prefix ${RootShellBootstrap.READY}\n".toByteArray())))
        assertFalse(RootShellBootstrap.awaitReady(ByteArrayInputStream(
            ("x".repeat(4_096) + "\n${RootShellBootstrap.READY}\n").toByteArray())))
    }
    @Test fun cancellationDestroysProcessBeforeClosingPipesAndClosesAllPipes() {
        val closed = mutableListOf<String>()
        var destroyed = false
        fun mark(name: String) { assertTrue("Destroy before closing $name", destroyed); closed += name }
        val process = object : java.lang.Process() {
            val input = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { mark("input") }
            }
            val output = object : ByteArrayOutputStream() {
                override fun close() { mark("output"); throw IOException("pipe already closed") }
            }
            val error = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { mark("error") }
            }
            override fun getInputStream() = input
            override fun getOutputStream() = output
            override fun getErrorStream() = error
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() { destroyed = true }
            override fun destroyForcibly(): java.lang.Process { destroyed = true; return this }
        }
        RootShellBootstrap.closeProcess(process)
        assertTrue(destroyed)
        assertEquals(listOf("output", "input", "error"), closed)
    }
    @Test fun rootDenialAndTimeoutHaveSpecificSafeGuidance() {
        assertTrue(androidShellFailureMessage(RootShellStartException(RootShellStartException.Reason.DENIED)).contains("Magisk/Alpha"))
        assertTrue(androidShellFailureMessage(RootShellStartException(RootShellStartException.Reason.AUTHORIZATION_TIMEOUT)).contains("Root 授权超时"))
        assertTrue(androidShellFailureMessage(RootShellStartException(RootShellStartException.Reason.WRONG_UID)).contains("UID2000"))
    }
}
