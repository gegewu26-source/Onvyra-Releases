package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.ai.taskToolChangesState
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract for the captcha tool: the AI must be able to call it with a region,
 * and it must stay a read-only observation rather than a state change.
 */
class AndroidControlCaptchaContractTest {

    @Test fun readCaptchaRequiresACaptchaRegionAndNeverAsksForAnImageUpload() {
        val schema = androidControlSchema("read_captcha")
        assertEquals(listOf("region"), schema.required)
        val region = schema.properties["region"]!!.jsonObject
        assertEquals("array", region["type"]!!.jsonPrimitive.content)
        assertEquals(4, region["minItems"]!!.jsonPrimitive.int)
        assertEquals(4, region["maxItems"]!!.jsonPrimitive.int)
        // Recognition happens on the phone; the model gets text, not pixels.
        assertNull(schema.properties["include_ocr"])
    }

    @Test fun readCaptchaSharesTheFrameBoundCoordinateContractAndAddsWindowSpace() {
        val schema = androidControlSchema("read_captcha")
        val coordinate = schema.properties["coordinate_space"]!!.jsonObject
        assertEquals("display", coordinate["default"]!!.jsonPrimitive.content)
        assertEquals(listOf("display", "image", "window"),
            coordinate["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", schema.properties["frame_id"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // Other tools must not silently gain window space.
        assertEquals(listOf("display", "image"),
            androidControlSchema("tap").properties["coordinate_space"]!!.jsonObject["enum"]!!
                .jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun captchaArgumentsSurviveNormalizationUnchanged() {
        val args = Json.parseToJsonElement("""{
          "coordinate_space":"image","frame_id":"frame-current",
          "region":[100,50,300,110],"charset":"0123456789"
        }""").jsonObject
        assertEquals(args, normalizeAndroidControlArguments("read_captcha", args))
    }

    @Test fun readingACaptchaIsAnObservationNotAStateChange() {
        val args = buildJsonObject {
            put("region", JsonArray(listOf(0, 0, 200, 60).map(::JsonPrimitive)))
        }
        assertFalse("reading pixels must not require a state-change approval",
            taskToolChangesState("read_captcha", args))
    }
}