package com.constx.onvyra.ui.components.ai

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.FileInputStream
import kotlin.math.abs
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneControlPermissionPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theAnchoredPickerShowsAllModesAndUpdatesTheSmallSelectedIcon() {
        val selected = mutableStateOf(AndroidControlPermissionMode.AUTONOMOUS)
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
                    PhoneControlPermissionButton(selected.value, { selected.value = it })
                }
            }
        }
        compose.onNodeWithTag("phone-control-permission-icon-AUTONOMOUS", useUnmergedTree = true).assertIsDisplayed()
        openMenuAndWait()
        compose.onNodeWithText("变更前确认").assertIsDisplayed()
        compose.onNodeWithText("自主模式").assertIsDisplayed()
        compose.onNodeWithText("完全访问").assertIsDisplayed()
        compose.onNodeWithTag("phone-control-permission-option-AUTONOMOUS").assertIsSelected()
        compose.onNodeWithText("变更前确认").performClick()
        compose.runOnIdle { assertEquals(AndroidControlPermissionMode.CONFIRM_CHANGES, selected.value) }
        compose.onNodeWithTag("phone-control-permission-menu").assertDoesNotExist()
        compose.onNodeWithTag("phone-control-permission-icon-CONFIRM_CHANGES", useUnmergedTree = true).assertIsDisplayed()
        openMenuAndWait()
        compose.onNodeWithText("完全访问").performClick()
        compose.runOnIdle { assertEquals(AndroidControlPermissionMode.FULL_ACCESS, selected.value) }
        compose.onNodeWithTag("phone-control-permission-icon-FULL_ACCESS", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun externalModeUpdatesKeepTheButtonPositionAndSize() {
        val selected = mutableStateOf(AndroidControlPermissionMode.AUTONOMOUS)
        compose.setContent {
            MaterialExpressiveTheme {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("思考", Modifier.testTag("reasoning-position-fixture"))
                    PhoneControlPermissionButton(selected.value, { selected.value = it })
                }
            }
        }
        val before = compose.onNodeWithTag("phone-control-permission-button").fetchSemanticsNode().boundsInRoot
        val reasoning = compose.onNodeWithTag("reasoning-position-fixture").fetchSemanticsNode().boundsInRoot
        assertTrue(before.left >= reasoning.right)
        compose.runOnIdle { selected.value = AndroidControlPermissionMode.FULL_ACCESS }
        compose.onNodeWithTag("phone-control-permission-icon-FULL_ACCESS", useUnmergedTree = true).assertIsDisplayed()
        val after = compose.onNodeWithTag("phone-control-permission-button").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        openMenuAndWait()
        compose.onNodeWithTag("phone-control-permission-option-FULL_ACCESS").assertIsSelected()
    }

    @Test fun openingFromAnActualKeyboardShowsAlignedRowsAndAllowsDismissAndSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("Refusing to change keyboard settings outside the dedicated lab AVD", "StatsUsageTest",
            shell("getprop ro.boot.qemu.avd_name"))
        val previousKeyboardSetting = shell("settings get secure show_ime_with_hard_keyboard")
        val selected = mutableStateOf(AndroidControlPermissionMode.AUTONOMOUS)
        val draft = mutableStateOf("未发送草稿")
        var view: View? = null
        var keyboard: SoftwareKeyboardController? = null
        fun imeVisible(): Boolean {
            var result = false
            instrumentation.runOnMainSync {
                result = view?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true
            }
            return result
        }
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            compose.setContent {
                val currentView = LocalView.current
                val currentKeyboard = LocalSoftwareKeyboardController.current
                SideEffect { view = currentView; keyboard = currentKeyboard }
                MaterialExpressiveTheme {
                    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                        TextField(draft.value, { draft.value = it }, modifier = Modifier.testTag("permission-input"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("思考")
                            PhoneControlPermissionButton(selected.value, { selected.value = it })
                        }
                    }
                }
            }
            fun showRealKeyboard() {
                compose.onNodeWithTag("permission-input").performClick()
                compose.runOnIdle { keyboard?.show() }
                compose.waitUntil(timeoutMillis = 5000) { imeVisible() }
            }
            showRealKeyboard()
            openMenuAndWait()
            compose.waitUntil(timeoutMillis = 3000) { !imeVisible() }
            compose.onNodeWithTag("permission-input").assertIsNotFocused().assertTextContains("未发送草稿")
            assertRowsAlign()

            // A non-focusable menu is dismissed through the activity's BackHandler.
            UiDevice.getInstance(instrumentation).pressBack()
            compose.waitUntil(timeoutMillis = 3000) { !menuExists() }
            showRealKeyboard()
            openMenuAndWait()
            assertRowsAlign()
            compose.onNodeWithTag("phone-control-permission-option-FULL_ACCESS").performClick()
            compose.waitUntil(timeoutMillis = 3000) { !menuExists() }
            compose.runOnIdle { assertEquals(AndroidControlPermissionMode.FULL_ACCESS, selected.value) }
            compose.onNodeWithTag("phone-control-permission-icon-FULL_ACCESS", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("permission-input").assertTextContains("未发送草稿")
            assertFalse("Selecting a permission must not reopen the keyboard", imeVisible())
            println("PHONE_PERMISSION_REAL_IME=PASS; rows_vertical_aligned=true; back_dismiss=true; selection_closes=true; draft_preserved=true")
        } finally {
            compose.runOnIdle { keyboard?.hide() }
            if (previousKeyboardSetting == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $previousKeyboardSetting")
        }
    }

    private fun menuExists() = compose.onAllNodesWithTag("phone-control-permission-menu")
        .fetchSemanticsNodes().isNotEmpty()

    private fun openMenuAndWait() {
        compose.onNodeWithTag("phone-control-permission-button").performClick()
        compose.waitUntil(timeoutMillis = 3000) { menuExists() }
    }

    private fun assertRowsAlign() {
        val options = AndroidControlPermissionMode.entries
        val rows = options.map { option ->
            compose.onNodeWithTag("phone-control-permission-option-${option.name}")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val labels = options.map { option ->
            compose.onNodeWithTag("phone-control-permission-label-${option.name}", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val icons = options.map { option ->
            compose.onNodeWithTag("phone-control-permission-option-icon-${option.name}", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        rows.forEachIndexed { index, row ->
            assertTrue("All options must have equal row widths", abs(row.left - rows.first().left) <= 1f && abs(row.right - rows.first().right) <= 1f)
            assertTrue("All options must have equal row heights", abs(row.height - rows.first().height) <= 1f)
            assertTrue("Option text must share a single vertical column", abs(labels[index].left - labels.first().left) <= 1f)
            assertTrue("Option icons must share a single vertical column", abs(icons[index].center.x - icons.first().center.x) <= 1f)
            if (index > 0) assertTrue("Option rows must not overlap", row.top >= rows[index - 1].bottom)
        }
    }
}
