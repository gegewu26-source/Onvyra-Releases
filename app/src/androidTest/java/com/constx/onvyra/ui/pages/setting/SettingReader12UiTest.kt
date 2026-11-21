package com.constx.onvyra.ui.pages.setting

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import kotlinx.coroutines.runBlocking
import com.constx.onvyra.R
import me.rerere.androidcontrol.AndroidControl
import com.constx.onvyra.Screen
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.ui.context.LocalNavController
import com.constx.onvyra.ui.context.LocalToaster
import com.constx.onvyra.ui.context.Navigator
import com.constx.onvyra.ui.pages.backup.BackupPage
import com.constx.onvyra.ui.pages.extensions.ExtensionsPage
import com.constx.onvyra.ui.theme.ColorMode
import com.constx.onvyra.ui.theme.OnvyraTheme
import me.rerere.ui.toast.rememberToasterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/** Real settings pages and navigation; only the dedicated QA AVD may run this test. */
@RunWith(AndroidJUnit4::class)
class SettingReader12UiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun floatingWindowLivesInPreferencesAndModelRowsStayCompact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val avdName = UiDevice.getInstance(instrumentation).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()
        assertTrue(
            "Settings UI verification must use a dedicated CodexReaderQA or CodexReaderBarsQA emulator",
            avdName in setOf("CodexReaderQA", "CodexReaderBarsQA"),
        )
        val context = instrumentation.targetContext
        val store = getKoin().get<SettingsStore>()
        val original = runBlocking { store.awaitLoaded() }
        val control = AndroidControl.get(context)
        val originalControl = Triple(control.enabled, control.confirmSensitive, control.previewEnabled)
        val backStack = mutableStateListOf<NavKey>(Screen.Setting)
        val navigator = Navigator(backStack)
        fun text(resource: Int) = context.getString(resource)

        compose.setContent {
            CompositionLocalProvider(
                LocalNavController provides navigator,
                LocalToaster provides rememberToasterState(),
            ) {
                OnvyraTheme(colorMode = ColorMode.LIGHT) {
                    when (backStack.last()) {
                        Screen.Setting -> SettingPage()
                        Screen.SettingPreferences -> SettingPreferencesPage()
                        Screen.SettingAdvanced -> SettingAdvancedPage()
                        Screen.SettingModels -> SettingModelPage()
                        Screen.SettingAndroidControl -> SettingAndroidControlPage()
                        Screen.Backup -> BackupPage()
                        Screen.Extensions -> ExtensionsPage()
                        else -> error("Unexpected settings destination")
                    }
                }
            }
        }

        compose.onNodeWithText("高级功能").assertDoesNotExist()
        compose.onNodeWithText("悬浮窗功能").assertDoesNotExist()
        val device = UiDevice.getInstance(instrumentation)
        val anchor = requireNotNull(device.findObject(By.text(text(R.string.setting_page_color_mode)))).visibleBounds
        compose.onNodeWithText(text(R.string.setting_page_color_mode)).performClick()
        compose.onNodeWithTag("settings-color-menu").assertIsDisplayed()
        // A DropdownMenu owns a separate Android popup window. Compare screen bounds;
        // boundsInWindow would compare two unrelated window origins.
        val menu = requireNotNull(device.findObject(By.text(text(R.string.setting_page_color_mode_light)))).visibleBounds
        assertTrue("Color menu should open below its row", menu.top >= anchor.bottom)
        assertTrue("Color menu should stay beside the value, not the far left edge", menu.right > device.displayWidth * .6)
        device.pressBack()
        compose.waitForIdle()
        capture("home")
        compose.onNodeWithText(text(R.string.setting_page_preferences)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Screen.SettingPreferences, backStack.last()) }
        compose.onNodeWithText("悬浮窗功能").performScrollTo().assertIsDisplayed()
        capture("preferences")

        compose.onNodeWithText("悬浮窗功能").performClick()
        compose.runOnIdle { assertEquals(Screen.SettingAdvanced, backStack.last()) }
        compose.onNodeWithText("悬浮窗功能").assertIsDisplayed()
        compose.onNodeWithText("后台运行说明").assertDoesNotExist()
        // A merged switch row must expose only its headline, with no secondary paragraph.
        compose.onNodeWithText("悬浮球保活").assertIsDisplayed().assertTextEquals("悬浮球保活")
        capture("floating")

        compose.runOnIdle { navigator.clearAndNavigate(Screen.Backup) }
        capture("backup")
        compose.runOnIdle { navigator.clearAndNavigate(Screen.Extensions) }
        capture("extensions")

        compose.runOnIdle { navigator.clearAndNavigate(Screen.SettingModels) }
        compose.onNodeWithText(text(R.string.setting_model_page_default_chat_model)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_model_page_chat_model)).assertDoesNotExist()
        capture("model")
        compose.onNodeWithText(text(R.string.setting_model_page_default_chat_model)).performClick()
        compose.onNodeWithText(text(R.string.model_list_search_placeholder)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(Screen.SettingModels, backStack.last()) }
        UiDevice.getInstance(instrumentation).pressBack()
        compose.waitForIdle()

        val descriptions = listOf(
            R.string.setting_model_page_chat_model_desc,
            R.string.setting_model_page_fast_model_desc,
            R.string.setting_model_page_translate_model_desc,
            R.string.setting_model_page_ocr_model_desc,
            R.string.setting_model_page_compress_model_desc,
        )
        // Visit each real row so lazy composition cannot hide a leftover description from this check.
        for (title in listOf(
            R.string.setting_model_page_default_chat_model,
            R.string.setting_model_page_fast_model,
            R.string.setting_model_page_translate_model,
            R.string.setting_model_page_ocr_model,
            R.string.setting_model_page_compress_model,
        )) {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(text(title)))
            compose.onNodeWithText(text(title)).assertIsDisplayed()
            descriptions.forEach { compose.onNodeWithText(text(it)).assertDoesNotExist() }
        }

        compose.runOnIdle { navigator.clearAndNavigate(Screen.SettingAndroidControl) }
        compose.onNodeWithText("无线调试模式").assertIsDisplayed()
        compose.onNodeWithText("Root 模式").assertIsDisplayed()
        compose.onNodeWithText("开始自动配对").assertDoesNotExist()
        compose.onNodeWithText("Root授权启动（无需Wi-Fi）").assertDoesNotExist()
        val density = context.resources.displayMetrics.density
        val wirelessBounds = compose.onNodeWithTag("phone-control-mode-wireless").fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onNodeWithTag("phone-control-mode-root").fetchSemanticsNode().boundsInRoot
        for (bounds in listOf(wirelessBounds, rootBounds)) {
            assertTrue("Mode rows retain at least a 48dp touch target", bounds.height >= 48f * density)
            assertTrue("Mode rows must not retain the previous excessive vertical padding", bounds.height <= 96f * density)
        }
        assertTrue("Mode choices must use the compact grouped spacing", rootBounds.top - wirelessBounds.bottom in 0f..(4f * density))
        capture("control-home")
        compose.onNodeWithTag("phone-control-list").performScrollToNode(hasText("系统权限设置"))
        val permissionsTop = compose.onNodeWithText("系统权限设置").fetchSemanticsNode().boundsInRoot.top
        val statusTop = compose.onNodeWithText("操控状态").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The entire system permissions group must precede control status", permissionsTop < statusTop)
        assertNoRuntimeLogUi()
        capture("control-permissions")
        val sharedControlRows = listOf(
            "操控状态", "释放操作", "允许 AI 操作这台手机", "敏感操作前二次确认",
            "显示操控预览小窗", "打开系统无障碍设置", "打开后台与电池设置",
            "手机操作通知与待机保护", "系统权限设置",
        )
        for (title in sharedControlRows) {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
            assertNoRuntimeLogUi()
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Root 模式"))
        compose.onNodeWithText("Root 模式").performClick()
        compose.onNodeWithText("Root授权启动（无需Wi-Fi）").assertIsDisplayed()
        sharedControlRows.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("打开系统无线调试设置").assertDoesNotExist()
        assertNoRuntimeLogUi()
        capture("control-root")
        compose.onNodeWithContentDescription("选择操控模式").performClick()
        compose.onNodeWithText("无线调试模式").performClick()
        compose.onNodeWithText("打开系统无线调试设置").assertDoesNotExist()
        compose.onNodeWithText("启动手机助手").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("开始自动配对"))
        compose.onNodeWithText("开始自动配对").assertIsDisplayed()
        sharedControlRows.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("连接端口").assertDoesNotExist()
        compose.onNodeWithText("复制USB命令").assertDoesNotExist()
        compose.onNodeWithText("撤销配对").assertDoesNotExist()
        compose.onNodeWithText("1. 无线调试设置").assertDoesNotExist()
        compose.onNodeWithText("3. 无线调试连接备用").assertDoesNotExist()
        compose.onNodeWithText("无障碍已连接时自动读取系统配对码，无法读取时可在通知中输入。").assertDoesNotExist()
        sharedControlRows.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Root授权启动（无需Wi-Fi）").assertDoesNotExist()
        assertNoRuntimeLogUi()
        capture("control-wireless")
        compose.onNodeWithContentDescription("选择操控模式").performClick()
        compose.onNodeWithText("Root 模式").assertIsDisplayed()
        compose.runOnIdle {
            // Report only equality, never serialize credentials in a failing assertion.
            assertTrue("Opening and inspecting settings must not modify saved settings", original == store.settingsFlow.value)
            assertEquals("Mode navigation must preserve the shared phone-control settings", originalControl,
                Triple(control.enabled, control.confirmSensitive, control.previewEnabled))
        }
    }

    private fun assertNoRuntimeLogUi() {
        listOf("运行记录", "最近运行日志（自动刷新）", "暂无运行记录").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("复制日志").assertDoesNotExist()
    }

    private fun capture(page: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "reader-qa")
        check(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, "reader16-settings-$page.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
