package com.constx.onvyra.data.ai.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AndroidControlChallengeContractTest {
    @Test fun releasingAHeldFingerRequiresOnlyItsPhaseAndDoesNotRequireNewCoordinates() {
        val schema = androidControlSchema("drag")
        assertEquals(listOf("phase"), schema.required)
        assertEquals(listOf("begin", "move", "end", "cancel"),
            schema.properties["phase"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        for (phase in listOf("end", "cancel")) {
            val args = buildJsonObject { put("phase", phase) }
            assertEquals(args, normalizeAndroidControlArguments("drag", args))
        }
    }

    @Test fun sliderAndHeldDragExposeTheSameFrameBoundCoordinateContract() {
        for (tool in listOf("solve_slider", "drag")) {
            val schema = androidControlSchema(tool)
            val coordinate = schema.properties["coordinate_space"]!!.jsonObject
            assertEquals("display", coordinate["default"]!!.jsonPrimitive.content)
            assertEquals(listOf("display", "image"), coordinate["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("string", schema.properties["frame_id"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("boolean", schema.properties["observe_after"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        val slider = androidControlSchema("solve_slider")
        for (hint in listOf("handle_x", "handle_y", "distance_px", "piece_start_x", "piece_width_px")) {
            assertEquals("integer", slider.properties[hint]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        for (region in listOf("roi", "track")) {
            val field = slider.properties[region]!!.jsonObject
            assertEquals(4, field["minItems"]!!.jsonPrimitive.int)
            assertEquals(4, field["maxItems"]!!.jsonPrimitive.int)
        }
    }

    @Test fun challengeArgumentsRetainImageBindingAndSignedDistanceEvenWithAHandleNode() {
        val slider = Json.parseToJsonElement("""{
          "node_id":"n8", "coordinate_space":"image", "frame_id":"frame-current",
          "distance_px":-120, "roi":[0,0,640,320], "track":[0,320,640,400],
          "piece_start_x":500, "piece_width_px":32
        }""").jsonObject
        assertEquals(slider, normalizeAndroidControlArguments("solve_slider", slider))
        val drag = Json.parseToJsonElement("""{
          "phase":"move", "coordinate_space":"image", "frame_id":"frame-current",
          "x":210, "y":340, "duration_ms":180, "observe_after":true
        }""").jsonObject
        assertEquals(drag, normalizeAndroidControlArguments("drag", drag))
    }

    @Test fun compactionPreservesChallengeOutcomeAndActiveFingerStateWithoutExportingLocalPaths() {
        val result = Json.parseToJsonElement("""{
          "ok":true, "drag_active":true, "drag_timeout_ms":60000,
          "input_accepted":true, "effect_verified":false, "action_observation":true,
          "frame_id":"frame-next", "image_width":640, "image_height":400,
          "slider":{"distance_px":-120,"method":"explicit_distance","capture_ms":0},
          "screenshot_path":"/private/cache/frame.jpg"
        }""").jsonObject
        val compact = compactAndroidControlResult(result)
        assertNull(compact["screenshot_path"])
        for ((key, value) in result) {
            if (key != "screenshot_path") assertEquals(key, value, compact[key])
        }
        val unavailable = buildJsonObject {
            put("ok", false); put("error", "screenshot_protected_window")
            put("drag_active", false); put("input_accepted", true); put("effect_verified", false)
        }
        assertEquals(unavailable, compactAndroidControlResult(unavailable))
        assertFalse(androidControlShouldAttachScreenshot(unavailable))
    }

    @Test fun anExplicitScreenshotRequestCanResendAnUnchangedImageButAFailureCannot() {
        val repeated = Json.parseToJsonElement("""{"ok":true,"image_unchanged":true,"image_resent":true,"screenshot_path":"/private/cache/frame.jpg"}""").jsonObject
        assertTrue(androidControlShouldAttachScreenshot(repeated))
        assertFalse(androidControlShouldAttachScreenshot(JsonObject(repeated + ("ok" to JsonPrimitive(false)))))
        assertFalse(androidControlShouldAttachScreenshot(JsonObject(repeated - "image_resent")))
        assertTrue(compactAndroidControlResult(repeated)["image_resent"]!!.jsonPrimitive.boolean)
    }
}
