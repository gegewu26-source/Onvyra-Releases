package com.constx.onvyra.ui.pages.setting

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.constx.onvyra.Screen
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.theme.ColorMode
import com.constx.onvyra.ui.theme.OnvyraTheme
import java.io.File
import me.rerere.androidcontrol.AndroidControl
import me.rerere.ui.toast.rememberToasterState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingAndroidControlOutlineUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactGroupsKeepBothModesAndSharedPermissionsOnTheLandingPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == "CodexReaderBarsQA")
        val control = AndroidControl.get(instrumentation.targetContext)
        val original = Triple(control.enabled, control.confirmSensitive, control.previewEnabled)
        val stack = mutableStateListOf<NavKey>(Screen.Setting, Screen.SettingAndroidControl)
        compose.setContent {
            CompositionLocalProvider(LocalNavController provides Navigator(stack), LocalToaster provides rememberToasterState()) {
                OnvyraTheme(colorMode = ColorMode.LIGHT) { SettingAndroidControlPage() }
            }
        }
        compose.onNodeWithText("手机操控").assertIsDisplayed()
        compose.onNodeWithTag("phone-control-mode-wireless").assertIsDisplayed()
        compose.onNodeWithTag("phone-control-mode-root").assertIsDisplayed()
        compose.onNodeWithTag("phone-control-permissions").assertExists()
        capture("phone-control-top")
        // Touch input must move the list too; semantic scroll actions bypass nested-scroll
        // consumption and would miss the fixed-app-bar regression on this landing page.
        repeat(3) { compose.onNodeWithTag("phone-control-list").performTouchInput { swipeUp() } }
        compose.onNodeWithTag("phone-control-switch-preview").assertIsDisplayed()
        compose.onNodeWithTag("phone-control-list").performScrollToNode(hasTestTag("phone-control-switch-preview"))
        compose.onNodeWithTag("phone-control-switch-preview").assertIsDisplayed()
        compose.onNodeWithTag("phone-control-switch-enabled").assertExists()
        compose.onNodeWithTag("phone-control-switch-confirm").assertExists()
        compose.onNodeWithTag("phone-control-release").assertExists()
        compose.onNodeWithText("运行记录").assertDoesNotExist()
        capture("phone-control-bottom")
        compose.onNodeWithTag("phone-control-list").performScrollToIndex(0)
        // These rows now start/stop a real helper instead of opening another page.
        // Rendering this layout check must never request su or start system pairing.
        compose.onNodeWithText("Root 模式").assertIsDisplayed()
        compose.onNodeWithText("无线调试模式").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择操控模式").assertDoesNotExist()
        compose.onNodeWithText("开始自动配对").assertDoesNotExist()
        compose.onNodeWithTag("phone-control-permissions").assertExists()
        assertEquals("Rendering mode controls must preserve user grants", original,
            Triple(control.enabled, control.confirmSensitive, control.previewEnabled))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reader-qa").apply { mkdirs() }
        File(root, "reader19-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
