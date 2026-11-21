package com.constx.onvyra.data.ai.tools
import kotlinx.serialization.json.*

/** Keep the model's default observation small; the engine retains full nodes for validation. */
internal fun compactAndroidControlResult(result:JsonObject):JsonObject {
    fun compactScreen(tree:JsonObject):JsonObject {
        val rows=ArrayList<JsonObject>()
        var omitted=false
        fun visit(node:JsonObject,parent:String?) {
            val id=(node["id"] as? JsonPrimitive)?.contentOrNull ?: return
            val visible=node["visible"]?.jsonPrimitive?.booleanOrNull != false
            val meaningful=listOf("text","description","hint").any { (node[it] as? JsonPrimitive)?.contentOrNull?.isNotBlank()==true } ||
                listOf("clickable","editable","scrollable","password","checkable").any {node[it]?.jsonPrimitive?.booleanOrNull==true}
            val keep=visible && meaningful
            if(keep && rows.size<240)rows.add(buildJsonObject {
                put("id",id);parent?.let {put("parent",it)}
                (node["type"] as? JsonPrimitive)?.contentOrNull?.let {put("type",it.substringAfterLast('.'))}
                node["bounds"]?.let {put("bounds",it)}
                node["resourceId"]?.let {put("resourceId",it)}
                listOf("text","description","hint").forEach {key->(node[key] as? JsonPrimitive)?.contentOrNull?.take(600)?.let {put(key,it)}}
                listOf("clickable","editable","scrollable","password","focused","selected").forEach {key->if(node[key]?.jsonPrimitive?.booleanOrNull==true)put(key,true)}
                if (node["checkable"]?.jsonPrimitive?.booleanOrNull == true) {
                    put("checkable", true); put("checked", node["checked"]?.jsonPrimitive?.booleanOrNull == true)
                }
                if(node["enabled"]?.jsonPrimitive?.booleanOrNull==false)put("enabled",false)
                node["source"]?.let {put("source",it)}
            }) else if(keep) omitted=true
            (node["children"] as? JsonArray)?.forEach { (it as? JsonObject)?.let {child->visit(child,if(keep)id else parent)} }
        }
        (tree["windows"] as? JsonArray)?.forEach { (it as? JsonObject)?.get("root")?.let {root->(root as? JsonObject)?.let {visit(it,null)}} }
        (tree["ocr"] as? JsonArray)?.forEach {(it as? JsonObject)?.let {ocr->visit(ocr,null)}}
        return buildJsonObject {
            listOf("displayId","revision","packageName","activity","read_method","observation_method",
                "visual_fallback_required","native_actionable_nodes").forEach {key->tree[key]?.let {put(key,it)}}
            put("nodes",JsonArray(rows.filterNot { it["source"]?.jsonPrimitive?.contentOrNull == "local_ocr" }))
            val ocr = rows.filter { it["source"]?.jsonPrimitive?.contentOrNull == "local_ocr" }
            if (tree["ocr"] is JsonArray) put("ocr",JsonArray(ocr))
            put("truncated",omitted || tree["truncated"]?.jsonPrimitive?.booleanOrNull==true)
        }
    }
    return buildJsonObject {result.forEach { (key,value)->
        when {
            key=="screen" && value is JsonObject ->put(key,compactScreen(value))
            key=="matches" && value is JsonArray -> {
                // A matching parent may contain its whole original subtree. Do
                // not send it a second time after the compact observation.
                put(key,JsonArray(value.take(80).mapNotNull { it as? JsonObject }.map { node ->
                    JsonObject(node.filterKeys { it in setOf("id","type","text","description","hint","resourceId",
                        "bounds","clickable","editable","scrollable","enabled","visible","source") })
                }))
            }
            key=="steps" && value is JsonArray ->put(key,JsonArray(value.map {step->
                if(step !is JsonObject)step else buildJsonObject {step.forEach { (k,v)->put(k,if(k=="result" && v is JsonObject)compactAndroidControlResult(v) else v)}}
            }))
            key!="screenshot_path" ->put(key,value)
        }
    }}
}
