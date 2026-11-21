package com.constx.onvyra.data.netdisk.download

import org.junit.Assert.*
import org.junit.Test

class NetDiskDownloadErrorTest {
    @Test fun expiredAndUnverifiableResourcesExplainTheRequiredRecovery() {
        assertTrue(safeDownloadError(Exception("HTTP 403 https://cdn.test/f?token=private")).contains("重新解析"))
        assertTrue(safeDownloadError(Exception("ETag changed on resume")).contains("移除本任务"))
        assertTrue(safeDownloadError(Exception("HTTP 429")).contains("限流"))
    }
    @Test fun unknownFailuresNeverExposeSignedUrlsOrCredentials() {
        val message = safeDownloadError(Exception("Request failed https://cdn.test/file?token=secret Authorization: Bearer private-value"))
        assertFalse(message.contains("secret")); assertFalse(message.contains("private-value"))
        assertTrue(message.contains("[下载地址]"))
    }
}
