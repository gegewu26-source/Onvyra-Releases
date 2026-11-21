package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/** Compatible gateways can return the ordinary text as typed content blocks. */
internal fun JsonObject.chatCompletionText(): String {
    val content = this["content"] ?: return ""
    content.jsonPrimitiveOrNull?.contentOrNull?.let { return it }
    return (content as? JsonArray)?.joinToString("") { element ->
        val block = element.jsonObjectOrNull
        when (block?.get("type")?.jsonPrimitiveOrNull?.contentOrNull) {
            "text", "output_text" -> block["text"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
            else -> ""
        }
    }.orEmpty()
}

internal fun JsonObject.chatCompletionReasoning(): String? {
    this["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull?.let { return it }
    this["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull?.let { return it }
    // Mistral may put several thinking blocks beside the ordinary text blocks.
    return (this["content"] as? JsonArray)?.mapNotNull { element ->
        val block = element.jsonObjectOrNull ?: return@mapNotNull null
        if (block["type"]?.jsonPrimitiveOrNull?.contentOrNull != "thinking") return@mapNotNull null
        val thinking = block["thinking"]
        thinking?.jsonPrimitiveOrNull?.contentOrNull ?: (thinking as? JsonArray)?.joinToString("") {
            it.jsonObjectOrNull?.get("text")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
        }
    }?.joinToString("")?.takeIf(String::isNotEmpty)
}
