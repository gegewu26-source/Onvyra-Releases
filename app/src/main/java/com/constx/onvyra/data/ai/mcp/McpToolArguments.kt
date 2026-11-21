package com.constx.onvyra.data.ai.mcp

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema

internal fun McpTool.editableArgumentSchemas(): Map<String, JsonObject> =
    (inputSchema as? InputSchema.Obj)?.properties.orEmpty().mapNotNull { (name, value) ->
        val obj = value as? JsonObject ?: return@mapNotNull null
        val primitives = setOf("string", "integer", "number", "boolean")
        val directType = (obj["type"] as? JsonPrimitive)?.content
            ?: (obj["type"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.filterNot { it == "null" }?.singleOrNull()
        val nullable = (obj["anyOf"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.filter { (it["type"] as? JsonPrimitive)?.content in primitives }?.singleOrNull()
        val effective = when {
            directType in primitives -> JsonObject(obj + ("type" to JsonPrimitive(directType)))
            obj["type"] == null && nullable != null -> JsonObject(obj + nullable)
            else -> obj
        }
        if ((effective["type"] as? JsonPrimitive)?.content in primitives) name to effective else null
    }.toMap()

internal fun parseMcpArgument(text: String, schema: JsonObject): JsonElement {
    val result = when (schema["type"]?.jsonPrimitive?.content) {
        "string" -> JsonPrimitive(text)
        "integer" -> JsonPrimitive(text.trim().toLongOrNull() ?: error("请输入整数"))
        "number" -> JsonPrimitive(text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("请输入有效数字"))
        "boolean" -> JsonPrimitive(text.toBooleanStrictOrNull() ?: error("请选择开或关"))
        else -> error("该参数需要由本次工具调用填写")
    }
    (schema["enum"] as? JsonArray)?.let { require(result in it) { "请从允许的选项中选择" } }
    if (!result.isString && result.doubleOrNull != null) {
        val n = result.double
        schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { require(n >= it) { "数值不能小于 $it" } }
        schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { require(n <= it) { "数值不能大于 $it" } }
    }
    return result
}

internal fun McpTool.validSavedArguments(): JsonObject {
    if (defaultArguments.isEmpty()) return defaultArguments
    val schemas = editableArgumentSchemas()
    return JsonObject(defaultArguments.filter { (key, value) ->
        val schema = schemas[key] ?: return@filter false
        runCatching { parseMcpArgument((value as? JsonPrimitive)?.content ?: return@filter false, schema) == value }.getOrDefault(false)
    })
}

/** Explicit arguments (including false, 0 and null) always take precedence. */
internal fun McpTool.resolveArguments(args: JsonObject): JsonObject = JsonObject(validSavedArguments() + args)

internal fun McpTool.schemaWithSavedArguments(): InputSchema? {
    val schema = inputSchema as? InputSchema.Obj ?: return inputSchema
    val saved = validSavedArguments()
    return schema.copy(
        properties = JsonObject(schema.properties.mapValues { (key, value) ->
            val obj = value as? JsonObject
            if (obj != null && key in saved) JsonObject(obj + ("default" to saved.getValue(key))) else value
        }),
        required = schema.required?.filterNot { it in saved },
    )
}
