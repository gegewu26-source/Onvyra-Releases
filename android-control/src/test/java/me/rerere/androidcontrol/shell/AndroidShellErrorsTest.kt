package me.rerere.androidcontrol.shell

import me.rerere.androidcontrol.adb.AdbPairingException
import me.rerere.androidcontrol.adb.AdbIoCancelledException
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AndroidShellErrorsTest {
    @Test fun tlsFailureShowsPortGuidanceAndSafeCauseTypesWithoutRawSecrets() {
        val secret = "secret-code-123456-private-key"
        val tls = SSLHandshakeException(secret).apply { initCause(EOFException(secret)) }
        val failure = AdbPairingException(AdbPairingException.Reason.TLS_FAILED, secret, tls, AdbPairingException.Stage.TLS)
        val text = androidShellFailureMessage(failure)
        assertTrue(text.contains("两者不能混用"))
        assertTrue(text.contains("SSLHandshakeException"))
        assertTrue(text.contains("EOFException"))
        assertTrue(text.contains("阶段=TLS"))
        assertFalse(text.contains(secret))
    }
    @Test fun timeoutAndClosureMessagesDoNotBlameTheCode() {
        val timeout = androidShellFailureMessage(SocketTimeoutException("123456"))
        assertTrue(timeout.contains("超时"))
        assertFalse(timeout.contains("123456"))
        val closed = androidShellFailureMessage(AdbPairingException(AdbPairingException.Reason.CLOSED,"closed",EOFException()))
        assertTrue(closed.contains("已断开"))
        assertFalse(closed.contains("认证未通过"))
    }
    @Test fun cancellationDoesNotTellTheUserToDeleteTheirPairingIdentity() {
        assertTrue(androidShellFailureMessage(AdbIoCancelledException()).contains("现有配对身份未撤销"))
    }
}
