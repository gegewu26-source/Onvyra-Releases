package com.constx.onvyra.data.ai

import com.constx.onvyra.data.model.AndroidControlPermissionMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

private val READ_ONLY_TASK_TOOLS = setOf(
    "get_ui_tree", "screenshot", "read_captcha", "wait_for", "stop_task", "find_saved_media_in_picker", "list_chat_media",
    "workspace_read_file", "read_context_history", "search_web", "scrape_web", "web_fetch",
    "video_resolve", "video_search", "video_find_sources", "media_generation_status",
    "recent_chats", "conversation_search", "conversation_read", "memory_read", "memory_list",
    "get_time_info", "calendar_query", "get_screen_time", "eval_javascript", "use_skill", "mcp_status",
    "netdisk_parse", "netdisk_download_status", "search_mcp_tools",
)

/** This is a question awaiting a real answer, never an application permission prompt. */
internal fun requiresTaskUserAnswer(name: String): Boolean = name == "ask_user"

internal fun canResumeTaskPermissionWait(call: UIMessagePart.Tool, mode: AndroidControlPermissionMode): Boolean =
    call.isPending && mode == AndroidControlPermissionMode.FULL_ACCESS && !requiresTaskUserAnswer(call.toolName)

/** Unknown external tools are not assumed read-only merely because their names sound harmless. */
internal fun taskToolChangesState(name: String, args: JsonElement): Boolean = when {
    requiresTaskUserAnswer(name) -> false
    name == "clipboard_tool" -> (args as? JsonObject)?.get("action")?.let { (it as? JsonPrimitive)?.contentOrNull } != "read"
    name in READ_ONLY_TASK_TOOLS -> false
    else -> true
}

private fun taskToolHasAdditionalRisk(name: String, args: JsonElement): Boolean {
    val operations = listOf(name.substringAfterLast("__")) + listOf("action", "operation", "action_kind").mapNotNull {
        ((args as? JsonObject)?.get(it) as? JsonPrimitive)?.contentOrNull
    }
    return operations.any { value ->
        val normalized = value.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
        Regex("(?:^|[_ -])(?:delete|remove|erase|destroy|send|publish|post|upload|share|payment|purchase|transfer)(?:[_ -]|$)")
            .containsMatchIn(normalized)
    }
}

internal fun requiresTaskToolApproval(mode: AndroidControlPermissionMode, tool: Tool, args: JsonElement): Boolean {
    // Preserve declarations' bookkeeping (the phone's observed-target approval ticket).
    val declared = tool.needsApproval(args)
    if (requiresTaskUserAnswer(tool.name)) return true
    return when (mode) {
        AndroidControlPermissionMode.CONFIRM_CHANGES -> declared || taskToolChangesState(tool.name, args)
        AndroidControlPermissionMode.AUTONOMOUS -> declared || taskToolHasAdditionalRisk(tool.name, args)
        AndroidControlPermissionMode.FULL_ACCESS -> false
    }
}

/** Approved/denied/answered decisions stay exact; only automatic permission waits follow a changed mode. */
internal fun resolveTaskToolApproval(call: UIMessagePart.Tool, definition: Tool?, mode: AndroidControlPermissionMode): UIMessagePart.Tool {
    if (call.isExecuted || call.approvalState !in listOf(ToolApprovalState.Auto, ToolApprovalState.Pending)) return call
    val approval = definition != null && requiresTaskToolApproval(mode, definition, call.inputAsJson())
    return call.copy(approvalState = if (approval) ToolApprovalState.Pending else ToolApprovalState.Auto)
}

internal fun taskPermissionPrompt(mode: AndroidControlPermissionMode): String =
    "Task permission for this conversation is ${mode.name}. It applies to every available tool, including phone control, " +
        "workspace files/commands, memory, media generation and external services. " + when (mode) {
        AndroidControlPermissionMode.CONFIRM_CHANGES -> "The app asks for approval before state-changing tools. Read-only tools can run directly. "
        AndroidControlPermissionMode.AUTONOMOUS -> "Routine tools can run directly; the app asks for approval for declared or high-risk actions. "
        AndroidControlPermissionMode.FULL_ACCESS -> "The app does not ask for repeated tool approval. Do not invent missing permission or repeatedly ask for an approval already granted. "
    } + "Real user questions still need an answer. This choice does not grant Android system permissions, " +
        "Root access, owner-only authorization, credentials, or access outside a tool's scope. Inspect actual tool results."
