package me.rerere.common.http

import kotlinx.serialization.json.JsonObject

private val reasoningControls = setOf(
    "reasoning", "reasoning_effort", "thinking", "enable_thinking", "thinking_mode",
    "thinking_budget", "reasoning_budget", "thinkingConfig", "thinking_config",
)
private val chatControls = reasoningControls + setOf(
    "messages", "input", "instructions", "tools", "tool_choice", "parallel_tool_calls",
    "functions", "function_call", "max_tokens", "max_completion_tokens", "max_output_tokens",
    "temperature", "top_p", "logprobs", "top_logprobs", "modalities", "store", "include",
    "previous_response_id", "session_id",
)

/** Closed set of mainline IDs that use an image tool rather than the Images model slot. */
fun isOpenAiImageToolModel(modelId: String): Boolean {
    val id = modelId.lowercase().removePrefix("openai/").replace(Regex("-[0-9]{4}-[0-9]{2}-[0-9]{2}$"), "")
        .replace("_", "").replace("-", "").replace(".", "")
    return id in setOf("gpt6luna", "6luna", "gpt6sol", "gpt6astra", "gpt6a", "gpt61sol", "61sol",
        "gpt61terra", "gpt55", "gpt56", "gpt56sol")
}

/** Only the API envelope is filtered; image-specific options and input data stay intact. */
fun JsonObject.withoutReasoningControls(): JsonObject = withoutControls(reasoningControls)

fun JsonObject.forImageEndpoint(): JsonObject = withoutControls(chatControls).withImageComplexity()

/** Image complexity is independent of chat effort and has only low/medium/high values. */
fun JsonObject.withImageComplexity(): JsonObject = JsonObject(mapValues { (key, value) ->
    when {
        key == "extra_body" && value is JsonObject -> value.withImageComplexity()
        key == "tools" && value is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map { tool ->
            if (tool is JsonObject && (tool["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "image_generation")
                tool.withImageComplexity() else tool
        })
        else -> value
    }
}.filter { (key, value) ->
    key != "complexity" || (value as? kotlinx.serialization.json.JsonPrimitive)?.content in setOf("low", "medium", "high")
})

private fun JsonObject.withoutControls(keys: Set<String>): JsonObject = JsonObject(
    entries.filterNot { it.key in keys }.associate { (key, value) ->
        key to if (key == "extra_body" && value is JsonObject) value.withoutControls(keys) else value
    },
)
