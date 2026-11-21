package com.constx.onvyra.data.ai.transformers

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.androidcontrol.AndroidControl

/** A request-only view: saved conversations, user uploads and unrelated tool output remain intact. */
object AndroidControlImageHistoryTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext,messages: List<UIMessage>): List<UIMessage> =
        trimAndroidControlImageHistory(messages)
}

internal fun trimAndroidControlImageHistory(messages: List<UIMessage>, keep: Int = 2): List<UIMessage> {
    require(keep >= 1)
    fun owned(tool: UIMessagePart.Tool): Boolean = tool.toolName in AndroidControl.TOOLS &&
        (tool.output.filterIsInstance<UIMessagePart.Image>().any {
            it.metadata?.get("android_control_frame")?.jsonPrimitive?.booleanOrNull == true
        } || tool.output.filterIsInstance<UIMessagePart.Text>().any { part ->
            // Existing reader.16 screenshots predate the image metadata marker.
            val value = runCatching { Json.parseToJsonElement(part.text) as? JsonObject }.getOrNull()
            value != null && value["task_id"] is JsonPrimitive && value["display_id"]?.jsonPrimitive?.intOrNull != null &&
                value["screen"] is JsonObject && value["frame_id"] is JsonPrimitive
        })
    val refs = messages.flatMapIndexed { mi,message -> message.parts.flatMapIndexed { pi,part ->
        if (part !is UIMessagePart.Tool || !owned(part)) emptyList() else part.output.mapIndexedNotNull { oi,output ->
            if (output is UIMessagePart.Image) Triple(mi,pi,oi) else null
        }
    } }
    if (refs.size <= keep) return messages
    val retained=refs.takeLast(keep).toSet()
    return messages.mapIndexed { mi,message -> message.copy(parts=message.parts.mapIndexed { pi,part ->
        if (part !is UIMessagePart.Tool || !owned(part)) part else part.copy(output=part.output.mapIndexed { oi,output ->
            if (output is UIMessagePart.Image && Triple(mi,pi,oi) !in retained)
                UIMessagePart.Text("[Earlier phone-control image omitted from this request; use the latest returned frames. Saved history is unchanged.]")
            else output
        })
    }) }
}
