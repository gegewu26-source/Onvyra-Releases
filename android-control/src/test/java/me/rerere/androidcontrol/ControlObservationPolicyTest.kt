package me.rerere.androidcontrol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ControlObservationPolicyTest {
    private fun node(id: String, text: String, resource: String = "fixture:id/$id", editable: Boolean = false,
        scrollable: Boolean = false, visible: Boolean = true, source: String? = null) = buildJsonObject {
        put("id", id); put("type", "android.widget.Button"); put("text", text); put("resourceId", resource)
        put("bounds", JsonArray(listOf(10,20,310,620).map(::JsonPrimitive)))
        put("visible", visible); put("enabled", true); put("clickable", !scrollable)
        put("editable", editable); put("scrollable", scrollable); source?.let { put("source", it) }
    }
    private fun tree(vararg nodes: JsonObject) = buildJsonObject { put("children", JsonArray(nodes.toList())) }

    @Test fun nativeControlsMakeScreenshotAnExplicitFallbackInsteadOfTheDefault() {
        val observed = tree(node("send", "Send"))
        assertEquals(1, nativeActionableNodeCount(observed))
        assertTrue(screenshotNeedsNativeRedirect(observed, null))
        assertTrue(screenshotNeedsNativeRedirect(observed, "native_controls_unavailable"))
        assertFalse(screenshotNeedsNativeRedirect(observed, "visual_content"))
        assertFalse(screenshotNeedsNativeRedirect(observed, "user_requested"))
        assertFalse(screenshotNeedsNativeRedirect(tree(), null))
        val result = controlObservation(observed, false)
        assertEquals("accessibility", result["observation_method"]!!.jsonPrimitive.content)
        assertFalse(result["visual_fallback_required"]!!.jsonPrimitive.boolean)
    }

    @Test fun localOcrAndHiddenElementsNeverPretendToBeNativeActionableControls() {
        val observed = tree(node("ocr", "画布按钮", source="local_ocr"), node("hidden", "Hidden", visible=false))
        assertEquals(0, nativeActionableNodeCount(observed))
        assertTrue(controlObservation(observed, true)["visual_fallback_required"]!!.jsonPrimitive.boolean)
    }

    @Test fun aCanvasDialogDoesNotInheritTheCoveredPagesNativeButtons() {
        val observed = buildJsonObject { put("windows",JsonArray(listOf(
            buildJsonObject {put("id",2);put("type",1);put("root",tree())},
            buildJsonObject {put("id",1);put("type",1);put("root",tree(node("covered","Send")))}
        ))) }
        assertEquals(0,nativeActionableNodeCount(observed))
        assertTrue(controlObservation(observed,false)["visual_fallback_required"]!!.jsonPrimitive.boolean)
    }

    @Test fun aClickableGameSurfaceDoesNotPretendToExposeItsPaintedButtons() {
        val surface = JsonObject(node("game","Game surface") + mapOf("type" to JsonPrimitive("android.opengl.GLSurfaceView")))
        assertEquals(0,nativeActionableNodeCount(tree(surface)))
        assertTrue(screenshotNeedsLocalOcr(tree(surface),null))
        // Explicit canvas content may need OCR even if another native Back button exists.
        assertTrue(screenshotNeedsLocalOcr(tree(node("back","Back")),"visual_content"))
        assertFalse(screenshotNeedsLocalOcr(tree(node("back","Back")),"user_requested"))
    }

    @Test fun selectorsResolveOnlyExactFreshUniqueTargetsIncludingBatchFriendlyResourceIds() {
        val observed = tree(node("send", "Send"), node("editor", "", editable=true), node("scroll", "List", scrollable=true))
        val selected = resolveControlTarget(observed, buildJsonObject { put("target_text", "Send") }, "tap")
        assertEquals("send", selected["node_id"]!!.jsonPrimitive.content)
        assertEquals("editor", resolveControlTarget(observed, buildJsonObject { put("resource_id", "fixture:id/editor") }, "input_text")["node_id"]!!.jsonPrimitive.content)
        assertEquals("scroll", resolveControlTarget(observed, buildJsonObject { put("resource_id", "fixture:id/scroll") }, "swipe")["node_id"]!!.jsonPrimitive.content)
        for (args in listOf(buildJsonObject { put("target_text", "Sen") }, buildJsonObject { put("resource_id", "editor") },
            buildJsonObject { put("node_id", "send"); put("resource_id", "fixture:id/editor") })) {
            try { resolveControlTarget(observed,args,"tap"); fail("A mismatched selector cannot choose a different target") }
            catch (expected: IllegalStateException) { assertEquals("stale_or_unknown_node_id",expected.message) }
        }
        try { resolveControlTarget(tree(node("one","Send"),node("two","Send")),buildJsonObject { put("target_text","Send") },"tap"); fail("Duplicate labels must require review") }
        catch(expected:IllegalStateException){ assertEquals("ambiguous_target_refresh_required",expected.message) }
    }

    @Test fun semanticTapPromotesAGroupLabelToItsClickableRow() {
        val label = node("group-label", "测试群").let { JsonObject(it + ("clickable" to JsonPrimitive(false))) }
        val row = buildJsonObject {
            put("id", "group-row")
            put("type", "android.view.ViewGroup")
            put("bounds", JsonArray(listOf(0, 0, 720, 120).map(::JsonPrimitive)))
            put("visible", true)
            put("enabled", true)
            put("clickable", true)
            put("children", JsonArray(listOf(label)))
        }
        val selected = resolveControlTarget(
            tree(row),
            buildJsonObject { put("target_text", "测试群") },
            "tap",
        )
        assertEquals("group-row", selected["node_id"]!!.jsonPrimitive.content)
    }

    @Test fun coordinateFallbackForAValidScrollNodeDerivesPointsInsideItsBounds() {
        val (xs,ys)=swipeWithinBounds(listOf(10,20,310,620),"down")
        assertEquals(listOf(160,160),xs.toList());assertEquals(listOf(500,140),ys.toList())
        assertTrue(ys.all { it in 20..620 })
        val (leftXs,leftYs)=swipeWithinBounds(listOf(10,20,310,620),"left")
        assertEquals(listOf(70,250),leftXs.toList());assertEquals(listOf(320,320),leftYs.toList())
    }
}
