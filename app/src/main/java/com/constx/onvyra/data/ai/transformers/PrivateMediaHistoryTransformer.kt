package com.constx.onvyra.data.ai.transformers

import com.constx.onvyra.security.PrivateMediaAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Filters the outgoing request only; the owner's saved conversations are never rewritten. */
internal object PrivateMediaHistoryTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        redactPrivateMediaToolHistory(messages)
}

internal fun redactPrivateMediaToolHistory(
    messages: List<UIMessage>,
    authorized: Boolean = PrivateMediaAccess.hasAccess(),
): List<UIMessage> {
    if (authorized) return messages
    return messages.map { message -> message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && (privateMediaText(part.input) || part.output.any(::privateMediaPart))) {
            part.copy(input = "{}", output = listOf(UIMessagePart.Text("{\"status\":\"unavailable\",\"error\":\"当前内容不可用。\"}")), metadata = null)
        } else part
    }) }
}

private fun privateMediaPart(part: UIMessagePart): Boolean = when (part) {
    is UIMessagePart.Text -> privateMediaText(part.text)
    is UIMessagePart.Image -> PrivateMediaAccess.isRestrictedUrl(part.url)
    is UIMessagePart.Video -> PrivateMediaAccess.isRestrictedUrl(part.url)
    is UIMessagePart.Audio -> PrivateMediaAccess.isRestrictedUrl(part.url)
    is UIMessagePart.Tool -> privateMediaText(part.input) || part.output.any(::privateMediaPart)
    else -> false
}

private fun privateMediaText(text: String): Boolean {
    if (privateMediaName.containsMatchIn(text)) return true
    val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return false
    fun restricted(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.values.any(::restricted)
        is JsonArray -> value.any(::restricted)
        is JsonPrimitive -> PrivateMediaAccess.isRestrictedSource(value.content) || PrivateMediaAccess.isRestrictedUrl(value.content)
    }
    return restricted(json)
}

private val privateMediaName = Regex("(?i)(?<![a-z0-9])(?:jable(?:tv)?|missav|supjav|hanime1)(?![a-z0-9])")
