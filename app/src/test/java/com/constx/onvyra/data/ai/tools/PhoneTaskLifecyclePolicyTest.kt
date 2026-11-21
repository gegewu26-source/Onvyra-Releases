package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PhoneTaskLifecyclePolicyTest {
    @Test fun `supplement correction and rejected action keep the same display without cleanup`() = runBlocking {
        for (text in listOf("顺便把图片保存好", "不要发小红书了，改成发给我", "拒绝这个操作，继续整理文案", "不要停止，继续操作")) {
            var displayId: Int? = 17
            val result = executePhoneToolWithStopPolicy("stop_task", UIMessage.user(text), false) {
                displayId = null
                buildJsonObject { put("stopped", true) }
            }
            assertEquals(17, displayId)
            assertTrue(result.getValue("task_retained").jsonPrimitive.boolean)
            assertFalse(result.getValue("stop_performed").jsonPrimitive.boolean)
        }
    }

    @Test fun `explicit whole stop reaches the owned display cleanup exactly once`() = runBlocking {
        var calls = 0
        val result = executePhoneToolWithStopPolicy("stop_task", UIMessage.user("停止手机操作"), false) {
            calls++
            buildJsonObject { put("ok", true); put("stopped", true) }
        }
        assertEquals(1, calls)
        assertTrue(result.getValue("stopped").jsonPrimitive.boolean)
    }

    @Test fun `an unread update invalidates a stop chosen from older instructions`() = runBlocking {
        var calls = 0
        val result = executePhoneToolWithStopPolicy("stop_task", UIMessage.user("停止任务"), true) {
            calls++
            JsonObject(emptyMap())
        }
        assertEquals(0, calls)
        assertEquals("new_instruction_pending", result.getValue("reason").jsonPrimitive.content)
        assertTrue(result.getValue("display_retained").jsonPrimitive.boolean)
    }

    @Test fun `a model cannot invent user authority to close the preview`() = runBlocking {
        val result = executePhoneToolWithStopPolicy("stop_task", null, false) {
            error("No actual user stop request")
        }
        assertFalse(result.getValue("stop_performed").jsonPrimitive.boolean)
    }

    @Test fun `an attached instruction requires model interpretation and keeps the phone open`() = runBlocking {
        val request = UIMessage.user("停止").copy(parts = listOf(
            UIMessagePart.Text("停止"), UIMessagePart.Image("file:///owned-fixture.png")))
        val result = executePhoneToolWithStopPolicy("stop_task", request, false) {
            error("Attachment meaning is unknown")
        }
        assertTrue(result.getValue("task_retained").jsonPrimitive.boolean)
    }

    @Test fun `ordinary tool execution remains available after a task change`() = runBlocking {
        var taps = 0
        val result = executePhoneToolWithStopPolicy("tap", UIMessage.user("改成选第二个"), false) {
            taps++
            buildJsonObject { put("ok", true); put("input_accepted", true) }
        }
        assertEquals(1, taps)
        assertTrue(result.getValue("input_accepted").jsonPrimitive.boolean)
    }
}
