package com.constx.onvyra.data.ai

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import me.rerere.androidcontrol.AndroidControl
import me.rerere.androidcontrol.ui.AndroidControlActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidControlReadinessUiTest {
    @Test fun setupReportsHelperAccessibilityAndStandbySeparatelyAndOffersBatterySettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() in setOf("StatsUsageTest", "CodexReaderQA")) { "Dedicated lab only" }
        val context = instrumentation.targetContext
        val control = AndroidControl.get(context)
        val wasEnabled = control.enabled
        try {
            control.enabled = false
            context.startActivity(Intent(context, AndroidControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertNotNull(device.wait(Until.findObject(By.text("Root 模式").pkg(context.packageName)), 10_000))
            // Shared readiness and recovery settings belong to the mode-selection page.
            repeat(5) {
                if (!device.hasObject(By.text("手机操控：尚未就绪").pkg(context.packageName))) {
                    device.findObject(By.scrollable(true).pkg(context.packageName))?.scroll(Direction.DOWN, .35f)
                }
            }
            assertNotNull(device.wait(Until.findObject(By.text("手机操控：尚未就绪").pkg(context.packageName)), 5_000))
            assertFalse("A helper connection cannot advertise usable control while opt-in is disabled",
                device.hasObject(By.text("手机操控：可以操作").pkg(context.packageName)))
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("手机助手：").pkg(context.packageName)), 5_000))
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("无障碍：").pkg(context.packageName)), 5_000))
            var sawStandby = false
            var sawBattery = false
            repeat(8) {
                sawStandby = sawStandby || device.hasObject(By.text("待机保护：未运行").pkg(context.packageName))
                sawBattery = sawBattery || device.hasObject(By.text("打开后台与电池设置").pkg(context.packageName))
                if (!sawBattery) device.findObject(By.scrollable(true).pkg(context.packageName))?.scroll(Direction.DOWN, .45f)
            }
            assertTrue("Disabled control must show no standby protection", sawStandby)
            assertTrue("The recovery flow needs a visible background/battery settings entry", sawBattery)
        } finally {
            control.enabled = wasEnabled
            device.pressBack()
        }
    }
}
