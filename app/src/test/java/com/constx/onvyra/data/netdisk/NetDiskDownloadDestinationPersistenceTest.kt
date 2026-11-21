package com.constx.onvyra.data.netdisk

import com.constx.onvyra.data.netdisk.download.NetDiskDownloadTask
import com.constx.onvyra.data.netdisk.download.StoredNetDiskDownload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NetDiskDownloadDestinationPersistenceTest {
    @Test fun olderDownloadLedgersRemainReadableWithTheDefaultDestination() {
        val record=Json.decodeFromString<StoredNetDiskDownload>("""{"task":{"id":"fixture-old","fileName":"file.bin"},"url":"https://example.invalid/file"}""")
        assertNull(record.task.destinationTreeUri)
        assertFalse(record.task.publicationPending)
    }
    @Test fun selectedTreeAndCopyCommitStateSurviveTheActualLedgerSerializer() {
        val original=StoredNetDiskDownload(NetDiskDownloadTask("fixture-new","file.bin",
            destinationTreeUri="content://fixture.documents/tree/primary%3ADownload%2FTest",
            destinationLabel="Download/Test",outputUri="content://fixture.documents/tree/primary%3ADownload%2FTest/document/file",
            publicationPending=true),"https://example.invalid/file")
        val restored=Json.decodeFromString<StoredNetDiskDownload>(Json.encodeToString(original))
        assertEquals(original.task.destinationTreeUri,restored.task.destinationTreeUri)
        assertEquals(original.task.outputUri,restored.task.outputUri)
        assertEquals("Download/Test",restored.task.destinationLabel)
        assertTrue(restored.task.publicationPending)
    }
}
