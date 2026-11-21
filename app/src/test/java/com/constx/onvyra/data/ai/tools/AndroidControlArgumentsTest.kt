package com.constx.onvyra.data.ai.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AndroidControlArgumentsTest {
    @Test fun nativeNodeTargetsNeverBecomeCoordinateFallbackBecauseOldHistoryIncludesPixels() {
        val tap = buildJsonObject { put("node_id", "d2n4");put("x",20);put("y",40);put("duration_ms",600) }
        assertEquals(buildJsonObject { put("node_id","d2n4");put("duration_ms",600) }, normalizeAndroidControlArguments("tap",tap))
        val swipe = Json.parseToJsonElement("""{"node_id":"d2n5","direction":"down","x1":1,"y1":2,"x2":3,"y2":4}""").jsonObject
        assertEquals(Json.parseToJsonElement("""{"node_id":"d2n5","direction":"down"}"""), normalizeAndroidControlArguments("swipe",swipe))
        val raw=buildJsonObject { put("x", 20);put("y",40) }
        assertEquals(raw, normalizeAndroidControlArguments("tap",raw))
    }

    @Test fun ordinaryAppAndOtherToolArgumentsArePreserved() {
        val open = buildJsonObject { put("app_name", "微信"); put("expect_app", "com.tencent.mm") }
        assertEquals(open, normalizeAndroidControlArguments("open_app", open))
        val tap = buildJsonObject { put("x", 20); put("y", 40); put("duration_ms", 600) }
        assertEquals(tap, normalizeAndroidControlArguments("tap", tap))
    }

    @Test fun exactNativeAndOcrSelectorsDiscardOldImageCoordinatesAndFrameReferences() {
        listOf("node_id", "target_text", "resource_id").forEach { selector ->
            listOf("tap", "swipe").forEach { tool ->
                val raw = buildJsonObject {
                    put(selector, "退出游戏"); put("coordinate_space", "image"); put("frame_id", "old-frame")
                    put("x", 10); put("y", 20); put("x1", 10); put("y1", 20); put("x2", 30); put("y2", 40)
                    put("duration_ms", 450)
                }
                assertEquals(buildJsonObject { put(selector, "退出游戏"); put("duration_ms", 450) },
                    normalizeAndroidControlArguments(tool, raw))
            }
        }
    }

    @Test fun imageCoordinatesAndTheirFrameReachTheControllerWithoutModelSideOriginMath() {
        val tap = Json.parseToJsonElement("""{"coordinate_space":"image","frame_id":"frame-3","x":20,"y":40}""").jsonObject
        val swipe = Json.parseToJsonElement("""{"coordinate_space":"image","frame_id":"frame-3","x1":20,"y1":40,"x2":50,"y2":80,"duration_ms":300}""").jsonObject
        assertEquals(tap, normalizeAndroidControlArguments("tap", tap))
        assertEquals(swipe, normalizeAndroidControlArguments("swipe", swipe))
        val batch = buildJsonObject {
            put("steps", buildJsonArray { add(buildJsonObject { put("tool", "tap"); put("args", tap) }) })
        }
        assertEquals(batch, normalizeAndroidControlArguments("run_steps", batch))
    }

    @Test fun modelSuppliedPreviewDimensionsNeverChangeTheNativePhoneProfile() {
        val raw = buildJsonObject {
            put("app_name", "抖音"); put("width", 320); put("height", 640); put("dpi", 480)
        }
        assertEquals(buildJsonObject { put("app_name", "抖音") }, normalizeAndroidControlArguments("open_app", raw))
        assertEquals(320, raw["width"]?.jsonPrimitive?.int)
    }

    @Test fun localBatchesNormalizeAppLaunchesWithoutChangingActionsOrExpectations() {
        val raw = Json.parseToJsonElement("""{"steps":[
          {"tool":"open_app","args":{"app_name":"微信","width":320,"height":640,"dpi":480},"expect_app":"com.tencent.mm"},
          {"tool":"tap","args":{"node_id":"n4"},"expect":"聊天"}
        ],"timeout_ms":2000}""").jsonObject
        val result = normalizeAndroidControlArguments("run_steps", raw)
        val steps = result["steps"]!!.jsonArray
        assertEquals(buildJsonObject { put("app_name", "微信") }, steps[0].jsonObject["args"])
        assertEquals("com.tencent.mm", steps[0].jsonObject["expect_app"]?.jsonPrimitive?.content)
        assertEquals(raw["steps"]!!.jsonArray[1], steps[1])
        assertEquals(raw["timeout_ms"], result["timeout_ms"])
    }
}
