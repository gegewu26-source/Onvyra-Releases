package com.constx.onvyra.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Keep generated files in saved chat/UI while avoiding unsupported tool-image inputs to text models. */
internal object ChatGeneratedImageHistoryTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        chatGeneratedImageRequestHistory(messages)
}

internal fun chatGeneratedImageRequestHistory(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
    message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolName in setOf("generate_media", "generate_native_image") &&
            part.output.any { it is UIMessagePart.Image }) {
            part.copy(output = part.output.filterNot { it is UIMessagePart.Image } +
                UIMessagePart.Text("Generated images are already displayed in this conversation. Do not invent or print image URLs, and do not submit the same generation again."))
        } else part
    })
}
