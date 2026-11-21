package com.constx.onvyra.data.ai.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AndroidControlCompactResultTest {
    @Test fun selectionCheckboxStateSurvivesCompactionEvenWithoutATextLabel() {
        val result = Json.parseToJsonElement("""{"ok":true,"screen":{"windows":[{"root":{"id":"photo-selector","type":"CheckBox","bounds":[100,200,130,230],"checkable":true,"checked":false,"clickable":false}}]}}""").jsonObject
        val row = compactAndroidControlResult(result)["screen"]!!.jsonObject["nodes"]!!.jsonArray.single().jsonObject
        assertTrue(row["checkable"]!!.jsonPrimitive.boolean)
        assertFalse(row["checked"]!!.jsonPrimitive.boolean)
    }

    @Test fun observationsKeepNativeActionEvidenceAndMatchesDoNotRepeatWholeSubtrees() {
        val result=Json.parseToJsonElement("""{"ok":true,"execution_method":"accessibility_click","screenshot_path":"/private/cache/a.png","screen":{"displayId":2,"revision":8,"packageName":"fixture","observation_method":"accessibility","visual_fallback_required":false,"native_actionable_nodes":1,"windows":[{"root":{"id":"d2n1","type":"Button","text":"应用","bounds":[0,0,10,10],"clickable":true}}]},"matches":[{"id":"d2n1","type":"Button","text":"应用","children":[{"id":"child","text":"large duplicate subtree"}]}]}""").jsonObject
        val compact=compactAndroidControlResult(result)
        assertNull(compact["screenshot_path"])
        assertEquals("accessibility_click",compact["execution_method"]!!.jsonPrimitive.content)
        val screen=compact["screen"]!!.jsonObject
        assertEquals("accessibility",screen["observation_method"]!!.jsonPrimitive.content)
        assertFalse(screen["visual_fallback_required"]!!.jsonPrimitive.boolean)
        assertEquals("d2n1",screen["nodes"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertNull(compact["matches"]!!.jsonArray.single().jsonObject["children"])
    }

    @Test fun sameFrameOfflineOcrAndCoordinateMetadataSurviveWithoutDuplicateNodesOrPrivatePaths() {
        val result = Json.parseToJsonElement("""{"ok":true,"frame_id":"frame-4","image_width":600,"image_height":320,"image_origin":[12,24],"image_unchanged":true,"screenshot_path":"/private/cache/frame.png","screen":{"displayId":3,"revision":9,"windows":[{"root":{"id":"d3n1","type":"Button","text":"返回","bounds":[0,0,10,10],"clickable":true}}],"ocr":[{"id":"d3ocr1","type":"Text","text":"退出游戏","bounds":[120,140,180,170],"clickable":true,"source":"local_ocr"}]}}""").jsonObject
        val compact = compactAndroidControlResult(result)
        assertNull(compact["screenshot_path"])
        listOf("frame_id", "image_width", "image_height", "image_origin", "image_unchanged").forEach {
            assertEquals(result[it], compact[it])
        }
        val screen = compact["screen"]!!.jsonObject
        assertEquals("d3n1", screen["nodes"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val ocr = screen["ocr"]!!.jsonArray.single().jsonObject
        assertEquals("退出游戏", ocr["text"]!!.jsonPrimitive.content)
        assertEquals("local_ocr", ocr["source"]!!.jsonPrimitive.content)
        assertEquals(result["screen"]!!.jsonObject["ocr"]!!.jsonArray.single().jsonObject["bounds"], ocr["bounds"])
    }

    @Test fun dispatchEvidenceAndManualTakeoverErrorsRemainExplicit() {
        val pending = Json.parseToJsonElement("""{"ok":true,"input_accepted":true,"effect_verified":false,"action_effect":"pending_visual_check"}""").jsonObject
        assertEquals(pending, compactAndroidControlResult(pending))
        val stalled = Json.parseToJsonElement("""{"ok":false,"error":"visual_no_progress_use_user_takeover","manual_takeover_required":true,"image_unchanged":true,"frame_id":"frame-4"}""").jsonObject
        assertEquals(stalled, compactAndroidControlResult(stalled))
        assertFalse(androidControlShouldAttachScreenshot(stalled))
    }
}
