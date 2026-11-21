package com.constx.onvyra.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContextCapacityIndicatorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun physicalAnchorClickClosesWithoutReopeningAndCanOpenAgain() {
        var anchor = Offset.Zero
        compose.setContent {
            MaterialExpressiveTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ContextCapacityIndicator(
                        ContextCapacityState(50_000, 512_000),
                        Modifier.padding(bottom = 32.dp).onGloballyPositioned { coordinates ->
                            anchor = coordinates.positionOnScreen() + Offset(
                                coordinates.size.width / 2f, coordinates.size.height / 2f,
                            )
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        fun tapAnchor() {
            val now = SystemClock.uptimeMillis()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            for ((action, at) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 60)) {
                val event = MotionEvent.obtain(now, at, action, anchor.x, anchor.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            compose.waitForIdle()
        }
        // Semantic clicks bypass the native outside-dismiss/anchor-click race.
        tapAnchor()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("context_capacity_popup")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("context_capacity_popup").assertIsDisplayed()
        tapAnchor()
        compose.onNodeWithTag("context_capacity_popup").assertDoesNotExist()
        tapAnchor()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("context_capacity_popup")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("context_capacity_popup").assertIsDisplayed()
    }

    @Test fun halfFullPopupUpdatesAtThresholdAndSurvivesUnknownData() {
        val state = mutableStateOf(ContextCapacityState(
            usedTokens = 256_000, capacityTokens = 512_000,
            breakdown = listOf(
                ContextCapacityCategory("system_tools", "系统工具", 40_000),
                ContextCapacityCategory("messages", "消息", 80_000),
                ContextCapacityCategory("mcp_tools", "MCP 工具", 100_000),
                ContextCapacityCategory("skills", "技能", 10_000),
                ContextCapacityCategory("other", "其他", 6_000),
                ContextCapacityCategory("system_prompt", "系统提示词", 20_000),
            ), cacheHitRate = 0.944,
        ))
        compose.setContent {
            MaterialExpressiveTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text("Onvyra · 上下文容量", style = MaterialTheme.typography.titleLarge)
                        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 2.dp) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("输入消息与AI聊天", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    PhoneControlPermissionButton(AndroidControlPermissionMode.AUTONOMOUS, {})
                                    ContextCapacityIndicator(state.value)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("context_capacity_indicator").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("context_capacity_popup")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("context_capacity_summary").assertTextContains("50.00%", substring = true)
        compose.onNodeWithText("MCP 工具").assertIsDisplayed()
        compose.onNodeWithText("系统提示词").assertIsDisplayed()
        compose.onNodeWithTag("context_capacity_cache_rate").assertTextContains("94.40%")
        screenshot("half-full-popup")
        compose.runOnIdle { state.value = state.value.copy(usedTokens = 512_000) }
        compose.onNodeWithTag("context_capacity_summary").assertTextContains("100.00%", substring = true)
        compose.runOnIdle { state.value = state.value.copy(usedTokens = 600_000) }
        compose.onNodeWithTag("context_capacity_summary").assertTextContains("117.19%", substring = true)
        compose.runOnIdle { state.value = ContextCapacityState(capacityTokens = 512_000) }
        compose.onNodeWithTag("context_capacity_summary").assertTextContains("暂无数据", substring = true)
        compose.onNodeWithText("下次发送后可查看占用分类").assertIsDisplayed()
        compose.onNodeWithTag("context_capacity_cache_rate").assertTextContains("暂无数据")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "reader62-context")
        folder.mkdirs()
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
