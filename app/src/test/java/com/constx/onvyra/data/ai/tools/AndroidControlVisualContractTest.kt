package com.constx.onvyra.data.ai.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AndroidControlVisualContractTest {
    @Test fun bothPixelGesturesDeclareFrameBoundImageCoordinatesAndLegacyDisplayDefault() {
        listOf("tap", "swipe").forEach { tool ->
            val schema = androidControlSchema(tool)
            val coordinate = schema.properties["coordinate_space"]!!.jsonObject
            assertEquals(listOf("display", "image"), coordinate["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("display", coordinate["default"]!!.jsonPrimitive.content)
            assertEquals("string", schema.properties["frame_id"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(schema.required.orEmpty().contains("frame_id"))
            val axes = if (tool == "tap") listOf("x", "y") else listOf("x1", "y1", "x2", "y2")
            axes.forEach { axis -> assertEquals("integer", schema.properties[axis]!!.jsonObject["type"]!!.jsonPrimitive.content) }
        }
        assertNull(androidControlSchema("input_text").properties["coordinate_space"])
    }

    @Test fun unchangedAndRejectedScreenshotsNeverSendAnImageEvenIfTheyCarryAnOldPath() {
        val image = buildJsonObject { put("ok", true); put("screenshot_path", "/cache/frame.png") }
        assertTrue(androidControlShouldAttachScreenshot(image))
        assertFalse(androidControlShouldAttachScreenshot(JsonObject(image + ("image_unchanged" to JsonPrimitive(true)))))
        assertFalse(androidControlShouldAttachScreenshot(JsonObject(image + ("ok" to JsonPrimitive(false)))))
        assertFalse(androidControlShouldAttachScreenshot(buildJsonObject { put("ok", true); put("image_unchanged", true) }))
        assertFalse(androidControlShouldAttachScreenshot(buildJsonObject { put("ok", true); put("screenshot_path", "") }))
    }

    @Test fun imageModelsSkipColdOcrButTextModelsKeepTheLocalFallback() {
        val empty=JsonObject(emptyMap())
        assertFalse(androidControlVisualArguments("screenshot",empty,true)["include_ocr"]!!.jsonPrimitive.boolean)
        assertTrue(androidControlVisualArguments("screenshot",empty,false)["include_ocr"]!!.jsonPrimitive.boolean)
        val opted=buildJsonObject {put("include_ocr",true)}
        assertTrue(androidControlVisualArguments("screenshot",opted,true)["include_ocr"]!!.jsonPrimitive.boolean)
        assertEquals(empty,androidControlVisualArguments("tap",empty,true))
        assertTrue(androidControlVisualArguments("screenshot",buildJsonObject {put("include_ocr",false)},false)["include_ocr"]!!.jsonPrimitive.boolean)
    }
}
