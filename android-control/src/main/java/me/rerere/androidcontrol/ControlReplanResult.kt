package me.rerere.androidcontrol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun JsonObject.isTaskUpdatePending(): Boolean =
    (this["status"] as? JsonPrimitive)?.content in setOf("task_update_pending", "interrupted")

/** Replanning stops stale actions without cancelling the owned display or hiding completed work. */
internal fun controlReplanResult(
    screen: JsonObject,
    steps: List<JsonObject>? = null,
    nextStepIndex: Int? = null,
): JsonObject = buildJsonObject {
    put("ok", true)
    put("status", "task_update_pending")
    put("reason", "new_user_instruction")
    put("replan_required", true)
    put("task_retained", true)
    put("display_retained", true)
    put("stop_performed", false)
    put("screen", screen)
    put("message", "A task update is waiting. The phone display and current app remain open. Keep completed results, read the new instruction, and continue the updated task from this screen. Remaining old batch steps are skipped while deciding the next actions.")
    if (steps != null) {
        // Snapshot the mutable progress list: later progress must not rewrite a returned result.
        put("steps", JsonArray(steps.toList()))
        put("execution_method", "native_batch")
    }
    nextStepIndex?.let { put("stopped_before_step", it) }
}
