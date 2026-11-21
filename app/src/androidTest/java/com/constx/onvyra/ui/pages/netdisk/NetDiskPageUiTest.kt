package com.constx.onvyra.ui.pages.netdisk

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.constx.onvyra.Screen
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.netdisk.NetDiskPlatform
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalSettings
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.theme.ColorMode
import com.constx.onvyra.ui.theme.OnvyraTheme
import java.io.File
import java.util.Locale
import me.rerere.ui.toast.rememberToasterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Dedicated empty AVD only: no provider requests, account writes or SettingsStore updates. */
class NetDiskPageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threeTabsKeepLocalParseStateAndElevenAccountsAcrossBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "OnvyraNetDiskQA") {
            "This UI check is restricted to the isolated OnvyraNetDiskQA AVD."
        }
        val context = instrumentation.targetContext
        val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }).resources
        val stack = mutableStateListOf<NavKey>(Screen.Setting, Screen.NetDisk)
        val color = mutableStateOf(ColorMode.LIGHT)
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings.dummy(),
                LocalNavController provides Navigator(stack),
                LocalToaster provides rememberToasterState(),
                LocalResources provides resources,
            ) {
                OnvyraTheme(colorMode = color.value) { NetDiskPage() }
            }
        }
        compose.onNodeWithTag("netdisk-page").assertIsDisplayed()
        assertThreeTabs()
        compose.onNodeWithTag("netdisk-tab-parse").assertIsSelected()
        compose.onNodeWithTag("netdisk-brand").assertTextEquals("Onvyra")
        compose.onNodeWithTag("netdisk-parse").assertIsNotEnabled()
        capture(device, "light-initial")

        compose.onNodeWithTag("netdisk-history").performScrollTo().performClick()
        compose.onNode(hasText("暂无解析记录") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()

        val examples = linkedMapOf(
            NetDiskPlatform.LANZOU to "https://qa.lanzoux.com/iSample123",
            NetDiskPlatform.ILANZOU to "https://www.ilanzou.com/s/Sample123",
            NetDiskPlatform.PAN123 to "https://www.123pan.cn/s/Abcd-EfgHi",
            NetDiskPlatform.ALIYUN to "https://www.alipan.com/s/Sample123",
            NetDiskPlatform.QUARK to "https://pan.quark.cn/s/abcdef123456",
            NetDiskPlatform.UC to "https://drive.uc.cn/s/abcdef123456",
            NetDiskPlatform.BAIDU to "https://pan.baidu.com/s/1Sample123",
            NetDiskPlatform.XUNLEI to "https://pan.xunlei.com/s/VOsample123",
            NetDiskPlatform.PAN115 to "https://115.com/s/sws8lxs36jf",
            NetDiskPlatform.C139 to "https://yun.139.com/shareweb/#/w/i/Sample123",
            NetDiskPlatform.GUANGYA to "https://www.guangyapan.com/s/Sample123",
        )
        assertEquals(NetDiskPlatform.entries.toSet(), examples.keys)
        // Typing only exercises local link detection. None of these fake links is submitted.
        examples.forEach { (platform, link) ->
            compose.onNodeWithTag("netdisk-share-input").performScrollTo().performTextReplacement(link)
            compose.onNodeWithText(platform.label, substring = false).assertExists()
            compose.onNodeWithTag("netdisk-parse").assertIsEnabled()
        }
        compose.onNodeWithTag("netdisk-share-code").performScrollTo().performTextReplacement("ab12")
        compose.onNodeWithTag("netdisk-share-code").assertTextContains("ab12")
        compose.onNodeWithTag("netdisk-share-input").performScrollTo()
            .performTextReplacement("https://example.invalid/not-a-netdisk-share")
        compose.onNodeWithTag("netdisk-parse").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("没有识别到受支持的网盘分享链接", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("没有识别到受支持的网盘分享链接", substring = true).performScrollTo().assertIsDisplayed()
        capture(device, "light-error")

        // The local error must not trap the page or disable editing for another share.
        compose.onNodeWithTag("netdisk-share-input").performScrollTo().performTextReplacement(examples.getValue(NetDiskPlatform.QUARK))
        compose.onNodeWithText(NetDiskPlatform.QUARK.label, substring = false).assertExists()
        compose.onNodeWithTag("netdisk-parse").assertIsEnabled()
        // The existing account shortcut now switches to the cloud tab instead of opening a list dialog.
        compose.onNodeWithTag("netdisk-accounts").performScrollTo().performClick()
        assertThreeTabs()
        compose.onNodeWithTag("netdisk-tab-cloud").assertIsSelected()
        val accounts = compose.onNodeWithTag("netdisk-clouds")
        examples.keys.forEach { platform ->
            accounts.performScrollToNode(hasTestTag("netdisk-cloud-${platform.name}"))
            compose.onNodeWithTag("netdisk-cloud-${platform.name}").assertIsDisplayed()
        }
        capture(device, "light-accounts-bottom")

        compose.onNodeWithTag("netdisk-tab-transfer").performClick()
        assertThreeTabs()
        compose.onNodeWithTag("netdisk-tab-transfer").assertIsSelected()
        compose.onNodeWithTag("netdisk-transfer-empty").assertIsDisplayed()
        compose.onNodeWithText("暂无任务", substring = false).assertIsDisplayed()
        listOf("all", "active", "completed", "failed").forEach { filter ->
            compose.onNodeWithTag("netdisk-transfer-filter-$filter").assertExists()
        }
        compose.onNodeWithTag("netdisk-transfer-filter-failed").performClick()
        compose.onNodeWithText("暂无失败的任务", substring = false).assertIsDisplayed()
        capture(device, "light-transfer-empty")

        compose.onNodeWithTag("netdisk-tab-parse").performClick()
        compose.onNodeWithTag("netdisk-share-input").performScrollTo()
            .assertTextContains(examples.getValue(NetDiskPlatform.QUARK))
        compose.onNodeWithTag("netdisk-share-code").assertTextContains("ab12")
        compose.onNodeWithText("没有识别到受支持的网盘分享链接", substring = true)
            .performScrollTo().assertIsDisplayed()
        // An unsupported local input must not appear as a successfully recognized history entry.
        compose.onNodeWithTag("netdisk-history").performScrollTo().performClick()
        compose.onNode(hasText("暂无解析记录") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("关闭") and hasAnyAncestor(isDialog())).performClick()

        compose.runOnIdle { color.value = ColorMode.DARK }
        compose.waitForIdle()
        capture(device, "dark-error-editable")
        assertThreeTabs()
        compose.onNodeWithTag("netdisk-tab-cloud").performClick()
        compose.onNodeWithTag("netdisk-clouds").performScrollToNode(hasTestTag("netdisk-cloud-LANZOU"))
        compose.onNodeWithTag("netdisk-cloud-LANZOU").assertIsDisplayed()
        capture(device, "dark-accounts-top")
        compose.onNodeWithTag("netdisk-tab-transfer").performClick()
        compose.onNodeWithTag("netdisk-transfer-empty").assertIsDisplayed()
        capture(device, "dark-transfer-empty")
        compose.onNodeWithTag("netdisk-tab-parse").performClick()
        compose.onNodeWithTag("netdisk-brand").performScrollTo().assertTextEquals("Onvyra")
        compose.onNodeWithTag("netdisk-share-input").performScrollTo()
            .assertTextContains(examples.getValue(NetDiskPlatform.QUARK))
        compose.onNodeWithTag("netdisk-share-code").assertTextContains("ab12")
        compose.onNodeWithTag("netdisk-parse").assertIsEnabled()
    }

    private fun assertThreeTabs() {
        compose.onNodeWithTag("netdisk-bottom-nav").assertIsDisplayed()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("netdisk-bottom-nav")))
            .assertCountEquals(3)
        listOf("parse", "cloud", "transfer").forEach { tab ->
            compose.onNodeWithTag("netdisk-tab-$tab").assertIsDisplayed()
        }
        compose.onNodeWithText("我的", substring = false).assertDoesNotExist()
        compose.onNodeWithText("赞助", substring = true).assertDoesNotExist()
    }

    private fun capture(device: UiDevice, name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "netdisk-qa").apply { mkdirs() }
        assertTrue("Screenshot output must be available", device.takeScreenshot(File(directory, "reader51-$name.png")))
    }
}
