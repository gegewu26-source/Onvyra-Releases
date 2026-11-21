package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.ai.ModelStopTaskDecision
import com.constx.onvyra.data.ai.modelStopTaskDecision
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** The model's cleanup preference cannot close a phone session after a task update. */
internal suspend fun executePhoneToolWithStopPolicy(
    name: String,
    latestUserMessage: UIMessage?,
    hasPendingUpdate: Boolean,
    execute: suspend () -> JsonObject,
): JsonObject {
    if (name != "stop_task") return execute()
    val decision = modelStopTaskDecision(
        latestUserText = latestUserMessage?.toText().orEmpty(),
        hasPendingUpdate = hasPendingUpdate,
        hasAttachments = latestUserMessage?.parts?.any { it !is UIMessagePart.Text } == true,
    )
    if (decision == ModelStopTaskDecision.ALLOWED) return execute()
    return buildJsonObject {
        put("ok", true)
        put("status", "task_retained")
        put("stop_performed", false)
        put("task_retained", true)
        put("display_retained", true)
        put("reason", if (decision == ModelStopTaskDecision.DEFERRED) "new_instruction_pending" else "whole_task_stop_not_requested")
        put("message", "Keep the current phone display and app state while processing the latest instruction. A supplemental request, changed goal or rejected step does not authorize stop_task. When the whole task is complete, return the final result; the app will close and release the preview automatically. Use stop_task only for an explicit whole-task stop request.")
    }
}
