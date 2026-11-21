package com.constx.onvyra.data.ai

import com.constx.onvyra.data.model.AndroidControlPermissionMode.*
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class TaskToolPermissionPolicyTest {
    private val args = buildJsonObject { }
    private fun tool(name: String, declared: Boolean = false) = Tool(name, "fixture", needsApproval = { declared }, execute = { emptyList() })

    @Test fun readsNeverBecomeChangesMerelyBecauseTheGlobalModeIsStrict() {
        for (name in listOf("workspace_read_file", "memory_read", "memory_list", "search_web", "get_ui_tree", "list_chat_media", "eval_javascript", "search_mcp_tools")) {
            assertFalse(name, requiresTaskToolApproval(CONFIRM_CHANGES, tool(name), args))
        }
        assertFalse(requiresTaskToolApproval(CONFIRM_CHANGES, tool("clipboard_tool"), buildJsonObject { put("action", "read") }))
        assertTrue(requiresTaskToolApproval(CONFIRM_CHANGES, tool("clipboard_tool"), buildJsonObject { put("action", "write") }))
    }

    @Test fun confirmationCoversPhoneFilesShellImagesCalendarMemoryAndUnknownExternalActions() {
        for (name in listOf("tap", "workspace_write_file", "workspace_edit_file", "workspace_shell", "generate_native_image", "generate_media",
            "calendar_create", "memory_write", "chart_display", "mcp__server__unclassified")) {
            assertTrue(name, requiresTaskToolApproval(CONFIRM_CHANGES, tool(name), args))
            assertFalse(name, requiresTaskToolApproval(FULL_ACCESS, tool(name, declared = true), args))
        }
    }

    @Test fun autonomousKeepsDeclaredApprovalAndRecognizesExternalDestructiveOrPublishingActions() {
        assertFalse(requiresTaskToolApproval(AUTONOMOUS, tool("generate_native_image"), args))
        assertTrue(requiresTaskToolApproval(AUTONOMOUS, tool("workspace_shell", true), args))
        for (name in listOf("memory_delete", "mcp__mail__send_message", "mcp__files__delete_file", "mcp__social__publish_post",
            "mcp__mail__sendMessage", "mcp__files__deleteFile", "mcp__storage__uploadFile")) {
            assertTrue(name, requiresTaskToolApproval(AUTONOMOUS, tool(name), args))
        }
        assertTrue(requiresTaskToolApproval(AUTONOMOUS, tool("mcp__mail__perform"), buildJsonObject { put("action", "send") }))
    }

    @Test fun netDiskReadsStayReadOnlyWhileDownloadsRespectTheSelectedConfirmationMode() {
        for (name in listOf("netdisk_parse", "netdisk_download_status")) {
            assertFalse(requiresTaskToolApproval(CONFIRM_CHANGES, tool(name), args))
        }
        assertTrue(requiresTaskToolApproval(CONFIRM_CHANGES, tool("netdisk_download"), args))
        assertFalse(requiresTaskToolApproval(AUTONOMOUS, tool("netdisk_download"), args))
        assertTrue(requiresTaskToolApproval(AUTONOMOUS, tool("netdisk_download", declared = true), args))
        assertFalse(requiresTaskToolApproval(FULL_ACCESS, tool("netdisk_download", declared = true), args))
    }

    @Test fun realQuestionsNeedUserAnswersInEveryMode() {
        for (mode in AndroidControlPermissionMode.entries) assertTrue(requiresTaskToolApproval(mode, tool("ask_user"), args))
    }

    @Test fun modeChangesReleaseApplicationWaitsWithoutOverridingUserDecisions() {
        val definition = tool("workspace_write_file")
        val pending = UIMessagePart.Tool("id", definition.name, "{}", approvalState = ToolApprovalState.Pending)
        assertEquals(ToolApprovalState.Auto, resolveTaskToolApproval(pending, definition, FULL_ACCESS).approvalState)
        assertEquals(ToolApprovalState.Pending, resolveTaskToolApproval(pending, definition, CONFIRM_CHANGES).approvalState)
        val denied = pending.copy(approvalState = ToolApprovalState.Denied("no"))
        assertEquals(denied, resolveTaskToolApproval(denied, definition, FULL_ACCESS))
        val approved = pending.copy(approvalState = ToolApprovalState.Approved)
        assertEquals(approved, resolveTaskToolApproval(approved, definition, CONFIRM_CHANGES))
        assertEquals(ToolApprovalState.Pending, resolveTaskToolApproval(pending.copy(toolName = "ask_user"), tool("ask_user"), FULL_ACCESS).approvalState)
        assertTrue(canResumeTaskPermissionWait(pending, FULL_ACCESS))
        assertFalse(canResumeTaskPermissionWait(pending, AUTONOMOUS))
        assertFalse(canResumeTaskPermissionWait(pending.copy(toolName = "ask_user"), FULL_ACCESS))
    }
}
