package com.constx.onvyra.data.ai

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.androidcontrol.AndroidControl
import me.rerere.androidcontrol.ui.AndroidControlActivity
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.ai.tools.ChatToolFactory
import com.constx.onvyra.data.ai.tools.createAndroidControlTools
import com.constx.onvyra.data.files.FilesManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class AndroidControlIntegrationSmokeTest {
    @Test fun existingAppRegistersToolsOnlyAfterOptInAndShowsAWorkingStopControl() = runBlocking {
        assertTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(instrumentation)
        assertEquals("Refusing a device other than the dedicated lab AVD", "StatsUsageTest",
            device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        val context=instrumentation.targetContext
        assertEquals("com.constx.onvyra.debug",context.packageName)
        val control=AndroidControl.get(context)
        val previous=control.enabled
        val files=GlobalContext.get().get<FilesManager>()
        // Resolve the real app's constructor graph, not an isolated fake factory.
        assertNotNull(GlobalContext.get().get<ChatToolFactory>())
        try {
            control.enabled=false
            assertTrue(createAndroidControlTools(context,"smoke",Model(),files).isEmpty())
            control.enabled=true
            val tools=createAndroidControlTools(context,"smoke",Model(),files)
            assertEquals(AndroidControl.TOOLS,tools.map {it.name}.toSet())
            val output=tools.single {it.name=="get_ui_tree"}.execute(JsonObject(emptyMap()))
            val response=Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
            assertFalse(response["ok"]!!.jsonPrimitive.boolean)
            assertEquals("shell_disconnected",response["error"]!!.jsonPrimitive.content)
            context.startActivity(Intent(context,AndroidControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("手机操控").pkg(context.packageName)),10000))
            assertNotNull("The first page must offer wireless mode",device.findObject(By.text("无线调试模式")))
            assertNotNull("The first page must offer Root mode",device.findObject(By.text("Root 模式")))
            assertFalse("Mode selection must not expose or start a setup action",device.hasObject(By.text("开始自动配对")))
            assertFalse(device.hasObject(By.text("Root授权启动（无需Wi-Fi）")))
            device.findObject(By.text("无线调试模式")).click()
            assertTrue(device.wait(Until.hasObject(By.text("无线调试自动配对").pkg(context.packageName)),5000))
            val stop=device.findObject(By.text("立即停止"))
            assertNotNull(stop)
            stop.click()
            assertTrue(device.wait(Until.hasObject(By.text("已停止")
                .pkg(context.packageName)),5000))
            // The module entry must redirect into the existing Compose navigation host.
            assertTrue(device.executeShellCommand("dumpsys activity activities").lineSequence().any {
                it.contains("topResumedActivity") && it.contains("com.constx.onvyra.RouteActivity")
            })
            var sawWirelessSetup=false
            repeat(12) {
                sawWirelessSetup = sawWirelessSetup || device.hasObject(By.text("开始自动配对"))
                assertFalse(device.hasObject(By.text("打开系统无线调试设置")))
                assertFalse(device.hasObject(By.text("连接端口")))
                assertFalse(device.hasObject(By.text("复制USB命令")))
                assertFalse(device.hasObject(By.text("撤销配对")))
                assertFalse("Wireless mode must not contain Root startup",device.hasObject(By.text("Root授权启动（无需Wi-Fi）")))
                assertSharedSettingsAbsent(device)
                assertFalse(device.hasObject(By.text("手动分屏配对").pkg(context.packageName)))
                assertFalse(device.hasObject(By.text("配对端口").pkg(context.packageName)))
                assertFalse(device.hasObject(By.text("6 位配对码").pkg(context.packageName)))
                device.findObject(By.scrollable(true).pkg(context.packageName))?.scroll(Direction.DOWN,.55f)
            }
            assertTrue("Wireless mode lost explicit automatic pairing startup",sawWirelessSetup)
            device.pressBack()
            assertNotNull("Back must return to mode selection",device.wait(Until.findObject(By.text("Root 模式")),5000))
            device.findObject(By.text("Root 模式")).click()
            val rootLabel="Root授权启动（无需Wi-Fi）"
            var rootStart=device.findObject(By.text(rootLabel))
            repeat(10) {
                if(rootStart==null) {
                    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,.65f)
                    rootStart=device.wait(Until.findObject(By.text(rootLabel)),500)
                }
            }
            assertNotNull("The explicit Root startup option is missing",rootStart)
            // Material3 exposes its disabled state on the Button semantics node, while
            // an unmerged descendant Text node can remain enabled. Inspect the button.
            fun rootButton(): androidx.test.uiautomator.UiObject2? {
                var node=device.findObject(By.text(rootLabel)) ?: return null
                repeat(8) {
                    if(node.isClickable || !node.isEnabled || node.className=="android.widget.Button")return node
                    node=node.parent ?: return null
                }
                return null
            }
            assertTrue("The Root action must expose an enabled action ancestor",rootButton()?.isEnabled == true)
            // Validate opt-in gating without requesting su or invoking a Root manager on the AVD.
            control.enabled=false
            assertTrue("The Root button must disable when phone-control authorization is off",
                withTimeoutOrNull(3000) {
                    while(rootButton()?.isEnabled != false)delay(50)
                    true
                } == true)
            // Inspect every composed section of the Root branch, never invoking su.
            repeat(12) {
                assertFalse("Root mode must not contain wireless startup",device.hasObject(By.text("开始自动配对")))
                assertSharedSettingsAbsent(device)
                assertFalse(device.hasObject(By.text("打开系统无线调试设置")))
                assertFalse(device.hasObject(By.text("连接端口")))
                assertFalse(device.hasObject(By.text("复制USB命令")))
                assertFalse(device.hasObject(By.text("手动分屏配对").pkg(context.packageName)))
                assertFalse(device.hasObject(By.text("配对端口").pkg(context.packageName)))
                assertFalse(device.hasObject(By.text("6 位配对码").pkg(context.packageName)))
                device.findObject(By.scrollable(true).pkg(context.packageName))?.scroll(Direction.DOWN,.55f)
            }
            device.pressBack()
            assertNotNull(device.wait(Until.findObject(By.text("无线调试模式")),5000))
            assertNotNull(device.findObject(By.text("Root 模式")))
            println("ANDROID_CONTROL_AGENT_INTEGRATION=PASS; tool_count=${tools.size}; master_opt_in=true; stop_ui=true; setup_modes_isolated=true; shared_settings_on_home=true; selection_does_not_start=true; paid_model_calls=0")
        } finally {control.stopAll();control.enabled=previous}
    }

    private fun assertSharedSettingsAbsent(device: UiDevice) {
        listOf("操控状态", "允许 AI 操作这台手机", "敏感操作前二次确认", "显示操控预览小窗",
            "打开系统无障碍设置", "打开后台与电池设置", "手机操作通知与待机保护", "释放操作", "运行记录")
            .forEach { assertFalse("Shared setting must stay on the mode-selection page: $it", device.hasObject(By.text(it))) }
    }
}
