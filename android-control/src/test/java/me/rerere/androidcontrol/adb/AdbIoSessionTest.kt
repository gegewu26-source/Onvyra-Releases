package me.rerere.androidcontrol.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.net.SocketTimeoutException
import java.io.EOFException
import javax.net.ssl.SSLException

class AdbIoSessionTest {
    @Test fun cancellationClosesRegisteredResourcesAndRejectsLateSockets() {
        val session = AdbIoSession()
        var closed = 0
        session.register(Closeable { closed++ })
        session.close()
        session.close()
        assertEquals(1, closed)
        assertThrows(AdbIoCancelledException::class.java) { session.register(Closeable { closed++ }) }
        assertEquals(2, closed)
        assertThrows(AdbIoCancelledException::class.java) { session.ifActive { fail("A cancelled operation must not publish success") } }
    }

    @Test fun cancellationUnblocksARealSocketReadWithoutWaitingForItsTimeout() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use {
                    val session = AdbIoSession()
                    session.register(client)
                    client.soTimeout = 30_000
                    val entered = CountDownLatch(1)
                    val finished = CountDownLatch(1)
                    val failure = AtomicReference<Throwable>()
                    val reader = Thread {
                        entered.countDown()
                        try { client.getInputStream().read(); fail("Peer sent no bytes") }
                        catch (error: Throwable) { failure.set(error) }
                        finally { finished.countDown() }
                    }.apply { isDaemon = true; start() }
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    session.close()
                    assertTrue("Closing the registered socket must unblock I/O promptly", finished.await(2, TimeUnit.SECONDS))
                    assertNotNull(failure.get())
                    assertTrue(failure.get() is java.io.IOException)
                    reader.join(500)
                }
            }
        }
    }

    @Test fun timeoutsAndTransportClosuresAreNeverReportedAsWrongCode() {
        val stage = AdbPairingException.Stage.PEER_INFO
        assertEquals(AdbPairingException.Reason.TIMEOUT, pairingIoFailure(stage, SocketTimeoutException()).reason)
        assertEquals(AdbPairingException.Reason.CLOSED, pairingIoFailure(stage, EOFException()).reason)
        assertEquals(AdbPairingException.Reason.TLS_FAILED, pairingIoFailure(stage, SSLException("peer closed")).reason)
    }

    @Test fun protocolFailuresKeepTheirOriginalClassification() {
        val error = AdbPairingException(AdbPairingException.Reason.PROTOCOL, "invalid header")
        assertSame(error, pairingIoFailure(AdbPairingException.Stage.PEER_INFO, error))
    }

    @Test fun numericLocalEndpointsDoNotNeedDnsNames() {
        assertEquals("127.0.0.1", literalAdbHost("localhost"))
        assertEquals("127.0.0.1", literalAdbHost("127.0.0.1"))
        assertEquals("::1", literalAdbHost("[::1]"))
        listOf("example.com", "phone.local", "192.168.0.300", "http://127.0.0.1", "127.0.0.1:1234").forEach {
            assertThrows(IllegalArgumentException::class.java) { literalAdbHost(it) }
        }
    }
}
