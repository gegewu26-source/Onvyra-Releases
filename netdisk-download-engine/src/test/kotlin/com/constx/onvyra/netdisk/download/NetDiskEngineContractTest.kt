package com.constx.onvyra.netdisk.download

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** End-to-end contracts call the production adapter and real vendored TurboDL core. */
class NetDiskEngineContractTest {
    @Test fun actualConcurrentRangeTransfersProduceTheExactPayloadAndRealProgress() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture().use { fixture ->
                fixture.delayPerBlockMs = 3
                val updates = CopyOnWriteArrayList<NetDiskEngineProgress>()
                val output = withTimeout(30_000) {
                    TurboNetDiskEngine().download(request(fixture, work)) { updates += it }
                }
                assertPayload(fixture.bytes, output)
                assertTrue("At least two real HTTP response bodies must overlap", fixture.maxActiveTransfers.get() >= 2)
                assertTrue("Progress must come from the engine's transferred bytes", updates.any { it.downloadedBytes > 0 })
                assertTrue("Connection count must reflect an actual worker", updates.any { it.activeConnections > 0 })
                assertTrue(updates.all { it.activeConnections in 0..8 })
                assertTrue("Strong validators must protect subsequent slices", fixture.requests.any { it.ifRange == fixture.etag })
            }
        }
    }

    @Test fun aServerIgnoringRangeFallsBackToOneCompleteCorrectFile() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture(NetDiskRangeFixture.payload(1024 * 1024)).use { fixture ->
                fixture.mode = NetDiskRangeFixture.Mode.IGNORE_RANGE
                val updates = CopyOnWriteArrayList<NetDiskEngineProgress>()
                val output = withTimeout(20_000) { TurboNetDiskEngine().download(request(fixture, work)) { updates += it } }
                assertPayload(fixture.bytes, output)
                // The server may briefly still be writing an ignored probe when
                // its client cancels it. Count complete GETs, not that old socket.
                assertEquals("A whole-file response must not be fetched once per slice", 1,
                    fixture.requests.count { it.method == "GET" && it.range == null })
                assertTrue("The host must see truthful fallback mode", updates.any { it.mode == "SINGLE_STREAM" })
            }
        }
    }

    @Test fun invalidRangesAndTruncatedBodiesNeverReportACorruptSuccessfulFile() = runBlocking {
        for (mode in listOf(NetDiskRangeFixture.Mode.WRONG_RANGE, NetDiskRangeFixture.Mode.TRUNCATED)) {
            temporaryDirectory { work ->
                NetDiskRangeFixture(NetDiskRangeFixture.payload(1024 * 1024)).use { fixture ->
                    fixture.mode = mode
                    val result = withTimeout(25_000) { runCatching { TurboNetDiskEngine().download(request(fixture, work)) {} } }
                    // A conservative whole-file restart or a verified retry is safe;
                    // accepting a short/misaligned slice as a completed file is not.
                    result.getOrNull()?.let { assertPayload(fixture.bytes, it) }
                    assertTrue("The fixture must exercise the requested Range contract", fixture.requests.any { it.range != null })
                }
            }
        }
    }

    @Test fun pauseWaitsForWorkersAndANewClientResumesItsValidatedCheckpoint() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture().use { fixture ->
                fixture.delayPerBlockMs = 12
                val input = request(fixture, work)
                val first = async { TurboNetDiskEngine().download(input) {} }
                withTimeout(10_000) { while (fixture.transferredBodyBytes.get() < 512 * 1024) delay(10) }
                first.cancelAndJoin()
                withTimeout(5_000) { while (fixture.activeTransfers.get() != 0) delay(10) }
                val initialStarts = fixture.requests.mapNotNull { rangeStart(it.range) }.toSet()
                val beforeResume = fixture.requests.size
                val output = withTimeout(30_000) { TurboNetDiskEngine().download(input) {} }
                assertPayload(fixture.bytes, output)
                val later = fixture.requests.drop(beforeResume).filter { it.method == "GET" && it.range != "bytes=0-0" }
                assertTrue("A new engine instance must reuse a partial checkpoint, not restart every original slice",
                    later.any { seen -> rangeStart(seen.range)?.let { it > 0 && it !in initialStarts } == true })
            }
        }
    }

    @Test fun aSameSizedResourceChangeCannotMixOldAndNewCheckpointBytes() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture().use { fixture ->
                fixture.delayPerBlockMs = 10
                val input = request(fixture, work)
                val first = async { TurboNetDiskEngine().download(input) {} }
                withTimeout(10_000) { while (fixture.transferredBodyBytes.get() < 512 * 1024) delay(10) }
                first.cancelAndJoin()
                withTimeout(5_000) { while (fixture.activeTransfers.get() != 0) delay(10) }
                fixture.bytes = NetDiskRangeFixture.payload(fixture.bytes.size, version = 2)
                fixture.etag = "\"fixture-b\""
                fixture.delayPerBlockMs = 0
                val result = withTimeout(25_000) { runCatching { TurboNetDiskEngine().download(input) {} } }
                // Either safely restart the new resource or reject the stale
                // checkpoint explicitly. Never expose a successful mixed file.
                result.getOrNull()?.let { assertPayload(fixture.bytes, it) }
            }
        }
    }

    @Test fun zeroByteResourcesCompleteAndAuthorizationFailuresStayBounded() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture(ByteArray(0)).use { fixture ->
                val output = withTimeout(10_000) { TurboNetDiskEngine().download(request(fixture, work)) {} }
                assertTrue(output.isFile); assertEquals(0L, output.length())
            }
        }
        for (mode in listOf(NetDiskRangeFixture.Mode.UNAUTHORIZED, NetDiskRangeFixture.Mode.FORBIDDEN)) {
            temporaryDirectory { work ->
                NetDiskRangeFixture(ByteArray(1024)).use { fixture ->
                    fixture.mode = mode
                    val result = withTimeout(15_000) { runCatching { TurboNetDiskEngine().download(request(fixture, work)) {} } }
                    assertTrue("An HTTP account/access rejection cannot complete", result.isFailure)
                    assertTrue("An expired token must not create an unbounded retry loop", fixture.requests.size <= 8)
                }
            }
        }
    }

    @Test fun aRateLimitIsEitherSurfacedOrRetriedAfterTheServersDelay() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture(NetDiskRangeFixture.payload(1024 * 1024)).use { fixture ->
                fixture.mode = NetDiskRangeFixture.Mode.RATE_LIMITED
                val result = withTimeout(20_000) { runCatching { TurboNetDiskEngine().download(request(fixture, work)) {} } }
                assertTrue(fixture.rateLimitsServed.get() > 0)
                if (fixture.requests.size > 1) {
                    val waitMs = (fixture.requests[1].atNanos - fixture.requests[0].atNanos) / 1_000_000
                    assertTrue("Retry-After=1 must not trigger an immediate second request ($waitMs ms)", waitMs >= 850)
                }
                result.getOrNull()?.let { assertPayload(fixture.bytes, it) }
            }
        }
    }

    @Test fun redirectsDoNotForwardAccountCredentialsToAnotherOrigin() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture(NetDiskRangeFixture.payload(1024 * 1024)).use { destination ->
                NetDiskRangeFixture().use { origin ->
                    origin.redirectTo = destination.url
                    val input = request(origin, work).copy(url = origin.urlWithHost("localhost"),
                        headers = mapOf("Cookie" to "__puus=fixture-private", "Authorization" to "Bearer fixture-private"))
                    val output = withTimeout(20_000) { TurboNetDiskEngine().download(input) {} }
                    assertPayload(destination.bytes, output)
                    assertTrue("The redirected endpoint must actually receive the request", destination.requests.isNotEmpty())
                    assertTrue(destination.requests.all { it.cookie.isNullOrBlank() && it.authorization.isNullOrBlank() })
                }
            }
        }
    }

    @Test fun aProvidedChecksumMustMatchBeforeTheEngineReturnsACompletedFile() = runBlocking {
        temporaryDirectory { work ->
            NetDiskRangeFixture(NetDiskRangeFixture.payload(512 * 1024)).use { fixture ->
                val result = withTimeout(15_000) { runCatching {
                    TurboNetDiskEngine().download(request(fixture, work).copy(expectedSha256 = "0".repeat(64))) {}
                } }
                assertTrue("A digest mismatch cannot be published as completed", result.isFailure)
            }
        }
    }

    @Test fun controlledPerConnectionThrottlingMeasuresParallelThroughputWithoutChangingPayload() = runBlocking {
        val measurements = linkedMapOf<Int, Long>()
        val bytes = NetDiskRangeFixture.payload(2 * 1024 * 1024)
        for (connections in listOf(1, 8)) {
            temporaryDirectory { work ->
                NetDiskRangeFixture(bytes).use { fixture ->
                    // 16 KiB per 16 ms for each HTTP response. This is a local
                    // controlled limit, not a claim about any real cloud provider.
                    fixture.delayPerBlockMs = 16
                    val began = System.nanoTime()
                    val output = withTimeout(20_000) {
                        TurboNetDiskEngine().download(request(fixture, work).copy(connections = connections)) {}
                    }
                    measurements[connections] = (System.nanoTime() - began) / 1_000_000
                    assertPayload(bytes, output)
                    if (connections == 8) assertTrue(fixture.maxActiveTransfers.get() >= 2)
                }
            }
        }
        println("NETDISK_CONTROLLED_BENCH bytes=${bytes.size} single_ms=${measurements[1]} parallel8_ms=${measurements[8]} both_sha256_match=true")
    }

    private fun request(fixture: NetDiskRangeFixture, work: File) = NetDiskEngineRequest(
        id = "fixture-task", url = fixture.url, headers = emptyMap(), workDirectory = work,
        fileName = "fixture.bin", connections = 8,
    )

    private fun rangeStart(value: String?): Long? = value?.let { Regex("bytes=(\\d+)-.*").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun assertPayload(expected: ByteArray, output: File) {
        assertTrue("Only a real downloaded file can complete", output.isFile)
        assertEquals("Completed byte length must match", expected.size.toLong(), output.length())
        assertEquals("The completed payload must match every byte", NetDiskRangeFixture.sha256(expected),
            NetDiskRangeFixture.sha256(output.readBytes()))
    }

    private suspend fun temporaryDirectory(block: suspend (File) -> Unit) {
        val parent = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val folder = Files.createTempDirectory(parent.toPath(), "onvyra-netdisk-engine-").toFile().canonicalFile
        require(folder.toPath().startsWith(parent.toPath()))
        try { block(folder) } finally { require(folder.toPath().startsWith(parent.toPath())); folder.deleteRecursively() }
    }
}
