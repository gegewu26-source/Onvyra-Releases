package com.constx.onvyra.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.utils.JsonInstant

/** Keep every completed MCP call nonempty, since the generation loop uses output to track execution. */
internal suspend fun CallToolResult.toMessageParts(
    convertImage: suspend (ImageContent) -> UIMessagePart.Image,
): List<UIMessagePart> {
    val parts = content.map { block ->
        when (block) {
            is TextContent -> UIMessagePart.Text(block.text)
            is ImageContent -> convertImage(block)
            else -> UIMessagePart.Text(JsonInstant.encodeToString(block))
        }
    }.toMutableList()

    // Structured-only responses are valid MCP results. Without this fallback the same call can
    // remain unexecuted in the conversation and be sent to the server again.
    val structured = structuredContent
    if (structured != null && parts.filterIsInstance<UIMessagePart.Text>().none { it.text.isNotBlank() }) {
        parts += UIMessagePart.Text(structured.toString())
    }

    if (isError == true) {
        parts.add(0, mcpToolError("MCP tool execution failed (isError=true). Read the returned details."))
    } else if (parts.none { it !is UIMessagePart.Text || it.text.isNotBlank() }) {
        parts.add(0, UIMessagePart.Text(buildJsonObject {
            put("tool_call_completed", true)
            put("empty_result", true)
            put("message", "The MCP tool returned no content. This does not confirm the requested task succeeded.")
        }.toString()))
    }
    return parts
}

internal fun mcpToolError(message: String) = UIMessagePart.Text(buildJsonObject {
    put("tool_error", true)
    put("error", message)
}.toString())
