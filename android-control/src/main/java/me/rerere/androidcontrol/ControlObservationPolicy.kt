package me.rerere.androidcontrol

import kotlinx.serialization.json.*

/** Selection stays local and exact; labels observed on one page are not coordinates. */
internal fun nativeActionableNodeCount(tree: JsonObject): Int {
    // A native page underneath a canvas/dialog cannot supply actionable targets
    // for the top window. Do not mistake its buttons for a usable native route.
    val top = (tree["windows"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it["type"]?.jsonPrimitive?.intOrNull == 1 }
    return controlTreeNodes(top ?: tree).count { node ->
        // A game may expose one clickable rendering surface, but no button
        // semantics. That single full-screen click is not a native Start target.
        val type = node.stringValue("type").orEmpty()
        val renderSurface = type.endsWith("SurfaceView") || type.endsWith("GLTextureView") ||
            type.endsWith("TextureView") || type.contains("UnityPlayer")
        node.stringValue("source") != "local_ocr" && !renderSurface && node.booleanValue("visible") != false &&
            node.booleanValue("enabled") == true && listOf("clickable", "editable", "scrollable").any { node.booleanValue(it) == true }
    }
}

internal fun controlTreeNodes(value: JsonElement): Sequence<JsonObject> = sequence {
    when (value) {
        is JsonObject -> {
            if (value["id"] is JsonPrimitive && value["type"] is JsonPrimitive && value["bounds"] != null) yield(value)
            value.values.forEach { yieldAll(controlTreeNodes(it)) }
        }
        is JsonArray -> value.forEach { yieldAll(controlTreeNodes(it)) }
        else -> Unit
    }
}

internal fun matchingControlTargets(tree: JsonObject, args: JsonObject, tool: String? = null): List<JsonObject> {
    val id = args.stringValue("node_id")
    val text = args.stringValue("target_text")
    val resource = args.stringValue("resource_id")
    if (id == null && text == null && resource == null) return emptyList()
    val matches = controlTreeNodes(tree).filter { node ->
        (id == null || node.stringValue("id") == id) &&
            (resource == null || node.stringValue("resourceId") == resource) &&
            (text == null || listOf("text", "description", "hint").any { node.stringValue(it) == text }) &&
            (id != null || node.booleanValue("visible") != false && node.booleanValue("enabled") == true) &&
            (tool != "input_text" || node.booleanValue("editable") == true) &&
            (tool != "swipe" || node.booleanValue("scrollable") == true)
    }.toList()

    // QQ and similar apps often expose the group name as an enabled TextView
    // inside a clickable row. Tapping the text node itself is not an
    // accessibility action, even though the row is. For semantic selectors,
    // promote such a match to its nearest clickable ancestor while retaining
    // exact-label and unique-target checks. Explicit node_id selectors remain
    // strict and are never silently retargeted.
    if (tool == "tap" && id == null && matches.isNotEmpty()) {
        val promoted = matches.mapNotNull { match ->
            // A text node which is already clickable is an intentional target.
            // Only promote non-clickable semantic children such as QQ's group-name
            // TextView to their clickable row; do not redirect an actual button.
            if (match.booleanValue("clickable") == true) match
            else nearestClickableAncestor(tree, match) ?: match
        }.distinctBy { it.stringValue("id") }
        return promoted
    }
    return matches
}

private fun nearestClickableAncestor(tree: JsonObject, target: JsonObject): JsonObject? {
    fun visit(element: JsonElement, ancestors: List<JsonObject>): JsonObject? {
        when (element) {
            is JsonObject -> {
                val nextAncestors = if (element["id"] is JsonPrimitive && element["bounds"] != null)
                    ancestors + element else ancestors
                if (element === target) {
                    return ancestors.asReversed().firstOrNull {
                        it.booleanValue("visible") != false &&
                            it.booleanValue("enabled") == true &&
                            it.booleanValue("clickable") == true
                    }
                }
                element.values.forEach { child -> visit(child, nextAncestors)?.let { return it } }
            }
            is JsonArray -> element.forEach { child -> visit(child, ancestors)?.let { return it } }
            else -> Unit
        }
        return null
    }
    return visit(tree, emptyList())
}

internal fun resolveControlTarget(tree: JsonObject, args: JsonObject, tool: String): JsonObject {
    if (tool !in setOf("tap", "input_text", "swipe")) return args
    if (args.stringValue("node_id") == null && args.stringValue("target_text") == null && args.stringValue("resource_id") == null) return args
    val candidates = matchingControlTargets(tree, args, tool)
    check(candidates.isNotEmpty()) { "stale_or_unknown_node_id" }
    check(candidates.size == 1) { "ambiguous_target_refresh_required" }
    return JsonObject(args + ("node_id" to checkNotNull(candidates.single()["id"])))
}

internal fun controlObservation(tree: JsonObject, hasOcr: Boolean): JsonObject = buildJsonObject {
    tree.forEach { (key, value) -> put(key, value) }
    val actionable = nativeActionableNodeCount(tree)
    put("observation_method", if (hasOcr) "accessibility_then_local_ocr" else "accessibility")
    put("native_actionable_nodes", actionable)
    put("visual_fallback_required", actionable == 0)
}

internal fun screenshotNeedsNativeRedirect(tree: JsonObject, reason: String?): Boolean =
    nativeActionableNodeCount(tree) > 0 && reason !in setOf("visual_content", "user_requested")

/** An explicitly requested canvas read still needs local OCR if it exposes a generic clickable root. */
internal fun screenshotNeedsLocalOcr(tree: JsonObject, reason: String?): Boolean =
    reason == "visual_content" || nativeActionableNodeCount(tree) == 0

internal fun swipeWithinBounds(bounds: List<Int>, direction: String): Pair<IntArray, IntArray> {
    require(bounds.size == 4 && bounds[2] > bounds[0] && bounds[3] > bounds[1]) { "node_not_actionable" }
    val left = bounds[0]; val top = bounds[1]; val right = bounds[2]; val bottom = bounds[3]
    val midX = left + (right - left) / 2; val midY = top + (bottom - top) / 2
    val lowX = left + (right - left) / 5; val highX = right - (right - left) / 5
    val lowY = top + (bottom - top) / 5; val highY = bottom - (bottom - top) / 5
    val endpoints = when (direction) {
        "down" -> intArrayOf(midX, midX) to intArrayOf(highY, lowY)
        "up" -> intArrayOf(midX, midX) to intArrayOf(lowY, highY)
        "right" -> intArrayOf(highX, lowX) to intArrayOf(midY, midY)
        "left" -> intArrayOf(lowX, highX) to intArrayOf(midY, midY)
        else -> error("invalid_scroll_direction")
    }
    return endpoints
}

private fun JsonObject.stringValue(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.booleanValue(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
