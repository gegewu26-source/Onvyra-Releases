package com.constx.onvyra.data.ai.mcp

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/** Read all pages before replacing the stored catalog. A bad page must not erase old tools. */
internal suspend fun <T> collectMcpPages(load: suspend (String?) -> Pair<List<T>, String?>): List<T> {
    val result = mutableListOf<T>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val (items, next) = load(cursor)
        result += items
        cursor = next?.takeIf { it.isNotBlank() }
        require(cursor == null || seen.add(cursor)) { "MCP server repeated a tool-list cursor" }
        require(seen.size <= 1000) { "MCP tool list has too many pages" }
    } while (cursor != null)
    return result
}

internal fun safeMcpError(error: Throwable): String {
    val message = generateSequence(error) { it.cause }.take(6)
        .mapNotNull { it.message }.firstOrNull { it.isNotBlank() }
        ?: error.javaClass.simpleName
    return message
        .replace(Regex("https?://[^\\s\\]\\)]+"), "[server address]")
        .replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"), "$1[redacted]")
        .replace(Regex("(?i)((?:password|token|api[_-]?key|secret)\\s*[=:]\\s*)[^\\s,;]+"), "$1[redacted]")
        .take(700)
}

internal fun unknownToolResult(name: String, available: List<String>): UIMessagePart.Text {
    val prefix = name.substringBeforeLast("__", "")
    val candidates = available.filter { prefix.isNotEmpty() && it.startsWith("${prefix}__") }.take(40)
    return UIMessagePart.Text(buildJsonObject {
        put("tool_error", true)
        put("error_code", "unknown_tool")
        put("requested_tool", name)
        put("executed", false)
        put("message", "This exact tool is not in the current advertised catalog. Do not invent a terminal or substitute a different server. Use mcp_status to inspect the selected servers and their exact tool names. This is not evidence that a package or file is missing.")
        put("same_server_tools", buildJsonArray { candidates.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }.toString())
}
