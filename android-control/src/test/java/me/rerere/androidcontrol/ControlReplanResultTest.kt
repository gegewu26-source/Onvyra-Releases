package me.rerere.androidcontrol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlReplanResultTest {
    private val screen = buildJsonObject { put("displayId", 17); put("revision", 42) }

    @Test
    fun `replanning before the first step retains the task and records that no action ran`() {
        val result = controlReplanResult(screen, emptyList(), 0)

        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals("task_update_pending", result.getValue("status").jsonPrimitive.content)
        assertEquals("new_user_instruction", result.getValue("reason").jsonPrimitive.content)
        assertTrue(result.getValue("task_retained").jsonPrimitive.boolean)
        assertTrue(result.getValue("display_retained").jsonPrimitive.boolean)
        assertFalse(result.getValue("stop_performed").jsonPrimitive.boolean)
        assertTrue(result.getValue("replan_required").jsonPrimitive.boolean)
        assertEquals(0, result.getValue("stopped_before_step").jsonPrimitive.int)
        assertTrue(result.getValue("steps").jsonArray.isEmpty())
        assertEquals(screen, result["screen"])
    }

    @Test
    fun `interruption preserves successful action results instead of claiming they were cancelled`() {
        val completed = buildJsonObject {
            put("index", 0); put("tool", "input_text")
            put("result", buildJsonObject { put("ok", true); put("input_accepted", true) })
        }
        val output = mutableListOf(completed)
        val result = controlReplanResult(screen, output, 1)
        output.clear()

        assertEquals(1, result.getValue("steps").jsonArray.size)
        assertEquals(completed, result.getValue("steps").jsonArray.single().jsonObject)
        assertEquals(1, result.getValue("stopped_before_step").jsonPrimitive.int)
        assertFalse(result.containsKey("stopped"))
    }

    @Test
    fun `a standalone wait interruption includes the observation without inventing batch progress`() {
        val result = controlReplanResult(screen)

        assertEquals(screen, result.getValue("screen"))
        assertTrue(result.getValue("task_retained").jsonPrimitive.boolean)
        assertFalse(result.containsKey("steps"))
        assertFalse(result.containsKey("stopped_before_step"))
    }
}
