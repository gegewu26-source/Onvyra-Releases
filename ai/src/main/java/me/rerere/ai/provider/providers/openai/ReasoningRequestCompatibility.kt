package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.canonicalOpenAiReasoningModelId

/** Applied after custom-body merging so fixed sampling parameters cannot invalidate a depth selection. */
internal fun JsonObject.withCompatibleReasoningSampling(model: Model, responseApi: Boolean): JsonObject {
    val id = canonicalOpenAiReasoningModelId(model.modelId)
    val knownGpt = Regex("^(?:gpt-6-(?:luna|sol|astra)|gpt-6\\.1-sol|gpt-5\\.6(?:-sol)?|gpt-5\\.(?:2|5)|gpt-5\\.4(?:-mini)?)$").matches(id)
    if (!knownGpt) return this
    val reasoning = this["reasoning"] as? JsonObject
    val explicit = if (responseApi) (reasoning?.get("effort") as? JsonPrimitive)?.contentOrNull
        else (this["reasoning_effort"] as? JsonPrimitive)?.contentOrNull
    // GPT-5.2/5.4 default to none; GPT-5.5, GPT-5.6 and GPT-6 default to medium.
    val effort = explicit ?: if (id == "gpt-5.2" || id.startsWith("gpt-5.4")) "none" else "medium"
    if (effort == "none") return this
    val result = toMutableMap()
    result.remove("temperature")
    result.remove("top_p")
    result.remove("top_logprobs")
    if (responseApi) {
        (result["include"] as? JsonArray)?.let { includes ->
            result["include"] = JsonArray(includes.filterNot { (it as? JsonPrimitive)?.contentOrNull == "message.output_text.logprobs" })
        }
    } else {
        result.remove("logprobs")
    }
    return JsonObject(result)
}
