package com.constx.onvyra.ui.pages.netdisk

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadStatus
import com.constx.onvyra.data.netdisk.download.NetDiskDownloadTask
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure download-row UI: no account store, network call, output file or real task is touched. */
class NetDiskDownloadTaskUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partialProgressStaysVisibleAcrossPauseResumeAndCancelUsesItsOwnCallback() {
        val task = mutableStateOf(NetDiskDownloadTask("qa-progress", "测试.zip", status = NetDiskDownloadStatus.DOWNLOADING,
            downloadedBytes = 1024, totalBytes = 2048, speedBytesPerSecond = 512, activeConnections = 3))
        var pauses = 0
        var resumes = 0
        var cancels = 0
        compose.setContent {
            MaterialExpressiveTheme {
                NetDiskDownloadRow(task.value, 0, 1,
                    onPause = { pauses++; task.value = task.value.copy(status = NetDiskDownloadStatus.PAUSED) },
                    onResume = { resumes++; task.value = task.value.copy(status = NetDiskDownloadStatus.DOWNLOADING) },
                    onCancel = { cancels++; task.value = task.value.copy(status = NetDiskDownloadStatus.CANCELLED) },
                    onOpen = {}, onRemove = {})
            }
        }
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithTag("netdisk-download-rate-qa-progress").assertTextContains("3 个连接", substring = true)
        compose.onNodeWithText("暂停").performClick()
        compose.onNodeWithText("已暂停").assertIsDisplayed()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithTag("netdisk-download-rate-qa-progress").assertDoesNotExist()
        compose.onNodeWithText("继续").performClick()
        compose.onNodeWithText("下载中").assertIsDisplayed()
        compose.onNodeWithText("取消下载").performClick()
        compose.onNodeWithText("已取消").assertIsDisplayed()
        compose.onNodeWithText("继续").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, pauses); assertEquals(1, resumes); assertEquals(1, cancels) }
    }

    @Test fun completedZeroByteFilesOfferOpenAndHistoryRemovalWithoutCancellation() {
        var opened = 0
        var removed = 0
        compose.setContent {
            MaterialExpressiveTheme {
                NetDiskDownloadRow(NetDiskDownloadTask("qa-completed", "空文件.txt", status = NetDiskDownloadStatus.COMPLETED,
                    downloadedBytes = 0, totalBytes = 0, outputUri = "content://qa/complete"), 0, 1,
                    onPause = {}, onResume = {}, onCancel = {}, onOpen = { opened++ }, onRemove = { removed++ })
            }
        }
        compose.onNodeWithText("0 B / 0 B").assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithText("取消下载").assertDoesNotExist()
        compose.onNodeWithText("暂停").assertDoesNotExist()
        compose.onNodeWithText("打开文件").performClick()
        compose.onNodeWithText("移除记录").performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, removed) }
    }

    @Test fun probingUnknownSizeNeverClaimsACompletedPercentage() {
        compose.setContent {
            MaterialExpressiveTheme {
                NetDiskDownloadRow(NetDiskDownloadTask("qa-probe", "未知大小.bin", status = NetDiskDownloadStatus.PROBING), 0, 1,
                    onPause = {}, onResume = {}, onCancel = {}, onOpen = {}, onRemove = {})
            }
        }
        compose.onNodeWithText("0 B / 未知大小").assertIsDisplayed()
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithText("0%").assertDoesNotExist()
        compose.onNodeWithText("正在检查文件大小和分片支持…").assertIsDisplayed()
    }
}
