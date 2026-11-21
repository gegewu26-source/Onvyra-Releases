package com.constx.onvyra.ui.components.support

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.ui.theme.ColorMode
import com.constx.onvyra.ui.theme.OnvyraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

/** Uses local assets and a test-only Compose tree; no payment or community application is opened. */
@RunWith(AndroidJUnit4::class)
class AuthorSupportDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun launchReminderSurvivesStoreRecreationAndRebasesAReversedClock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "author-support-qa-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val now = 1_730_000_000_000L
        val tenDays = AuthorSupportSchedule.INTERVAL_MILLIS
        try {
            val first = AuthorSupportLaunchStore(context, preferenceName)
            assertTrue(first.isDue(now))
            first.recordShown(now)

            val recreated = AuthorSupportLaunchStore(context, preferenceName)
            assertEquals(false, recreated.isDue(now + tenDays - 1))
            assertTrue(recreated.isDue(now + tenDays))

            recreated.recordShown(now + 30 * 86_400_000L)
            val reversedClock = AuthorSupportLaunchStore(context, preferenceName)
            assertEquals(false, reversedClock.isDue(now))

            val recreatedAfterClockCorrection = AuthorSupportLaunchStore(context, preferenceName)
            assertEquals(false, recreatedAfterClockCorrection.isDue(now + tenDays - 1))
            assertTrue(recreatedAfterClockCorrection.isDue(now + tenDays))
        } finally {
            // This unique test-only store is the only preference file modified here.
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test fun bundledQrImagesExactlyMatchTheProvidedPaymentImages() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val expected = listOf(
            Triple(SupportPayment.WECHAT, "d09b194f3f4cd3e04b051609e945e2c7680bff7c9a3d69420e754fd5f2cb7255", 1221 to 1221),
            Triple(SupportPayment.ALIPAY, "0ff3c0b0420a42f51f7b560f90b66c7208a473c763d10ec7546eabf93df2e770", 1260 to 1890),
        )
        for ((payment, hash, size) in expected) {
            val bytes = assets.open(payment.assetPath).use { it.readBytes() }
            val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(payment.name + " must retain the provided image bytes", hash, actualHash)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            requireNotNull(bitmap) { payment.name + " image must decode" }
            try {
                assertEquals(size.first, bitmap.width)
                assertEquals(size.second, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun dismissibleStartupDialogShowsBothQrImagesAndTheSameAboutSupportContent() {
        val presented = AtomicInteger()
        val opened = CopyOnWriteArrayList<String>()
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            OnvyraTheme(colorMode = ColorMode.LIGHT) {
                if (visible) {
                    AuthorSupportDialog(onDismiss = { visible = false }, onPresented = { presented.incrementAndGet() },
                        onOpenCommunity = { opened += it })
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                            .testTag("support-about-content")
                    ) {
                        AuthorSupportContent(showIntroduction = true, onOpenCommunity = { opened += it })
                    }
                }
            }
        }

        compose.onNodeWithTag("author-support-dialog").assertIsDisplayed()
        compose.onNodeWithText("欢迎使用 Onvyra", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("support-dismiss").assertIsDisplayed()
        compose.onNodeWithTag("support-qr-WECHAT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("support-qr-ALIPAY").assertIsDisplayed()
        awaitLoaded("support-qr-loaded-WECHAT")
        awaitLoaded("support-qr-loaded-ALIPAY")
        capture("author-support-dialog", "reader15-support-dialog")

        compose.onNodeWithTag("support-qr-WECHAT").performClick()
        compose.onNodeWithTag("support-image-dialog").assertIsDisplayed()
        awaitLoaded("support-image-loaded-WECHAT")
        capture("support-image-dialog", "reader15-support-wechat")
        compose.onNodeWithTag("support-image-close").performClick()
        compose.onNodeWithTag("support-image-dialog").assertDoesNotExist()
        compose.onNodeWithTag("support-qr-ALIPAY").performScrollTo().performClick()
        compose.onNodeWithTag("support-image-dialog").assertIsDisplayed()
        awaitLoaded("support-image-loaded-ALIPAY")
        capture("support-image-dialog", "reader15-support-alipay")
        compose.onNodeWithTag("support-image-close").performClick()

        compose.onNodeWithTag("author-support-body").performScrollToNode(hasTestTag("support-qq-join"))
        compose.onNodeWithTag("support-qq-join").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(AuthorSupportCommunity.QQ_INVITE_URL), opened.toList()) }
        compose.onNodeWithText("群号：996134022").assertIsDisplayed()
        compose.onNodeWithText(AuthorSupportCommunity.QQ_INVITE_URL, substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(AuthorSupportCommunity.QQ_INVITE_URL), opened.toList()) }
        compose.onNodeWithTag("support-qq-copy").assertIsDisplayed().performClick()
        compose.onNodeWithTag("support-telegram").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("https://t.me/gegewu678").assertIsDisplayed()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("996134022", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(listOf(AuthorSupportCommunity.QQ_INVITE_URL, AuthorSupportCommunity.TELEGRAM_URL), opened.toList())
            assertEquals("Opening enlarged images must not count as another startup presentation", 1, presented.get())
        }
        compose.onNodeWithTag("support-dismiss").assertIsDisplayed().performClick()
        compose.onNodeWithTag("author-support-dialog").assertDoesNotExist()
        compose.onNodeWithText("感谢你使用 Onvyra", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("support-qr-WECHAT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("support-qr-ALIPAY").assertIsDisplayed()
        awaitLoaded("support-qr-loaded-WECHAT")
        awaitLoaded("support-qr-loaded-ALIPAY")
        capture("support-about-content", "reader15-support-about")
        compose.onNodeWithTag("support-qq-join").performScrollTo().performClick()
        compose.onNodeWithTag("support-telegram").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(AuthorSupportCommunity.QQ_INVITE_URL, AuthorSupportCommunity.TELEGRAM_URL,
                AuthorSupportCommunity.QQ_INVITE_URL, AuthorSupportCommunity.TELEGRAM_URL), opened.toList())
        }
    }

    private fun awaitLoaded(tag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(tag: String, name: String) {
        compose.waitForIdle()
        // Compose owns the captured backing image; recycle only our independent file-writing copy.
        val bitmap = requireNotNull(
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        )
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reader-qa")
        check(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
