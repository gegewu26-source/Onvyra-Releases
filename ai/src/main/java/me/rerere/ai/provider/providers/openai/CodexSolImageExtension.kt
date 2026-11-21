package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json

internal fun Model.usesCodexSolImageExtension(): Boolean = modelId.lowercase().removePrefix("openai/")
    .replace(Regex("-[0-9]{4}-[0-9]{2}-[0-9]{2}$"), "")
    .replace(Regex("[-_.]"), "") in setOf("gpt61sol", "61sol")

/** Only an explicit, pre-generation hosted-tool rejection permits a new image request.
 * Generic failures, incomplete outputs, auth/quota errors and transport failures do not. */
internal fun isHostedImageCompatibilityRejection(error: Exception): Boolean {
    if (error is kotlinx.coroutines.CancellationException) return false
    val match = Regex("^Failed to get response: (400|422|502) (.+)$", RegexOption.DOT_MATCHES_ALL)
        .matchEntire(error.message.orEmpty()) ?: return false
    val root = runCatching { json.parseToJsonElement(match.groupValues[2]) as? JsonObject }.getOrNull()
        ?: return false
    val detail = root["error"] as? JsonObject ?: return false
    fun value(key: String) = (detail[key] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    val message = value("message")
    val type = value("type")
    val code = value("code")
    val unsupportedComplexity = type == "image_generation_user_error" && code == "invalid_value" &&
        value("param") in setOf("", "complexity") && "complexity" in message &&
        "not supported" in message && "image model" in message
    val unsupportedHostedRoute = type == "invalid_request_error" && (
        ("hosted image_generation cannot be forced" in message && "responses-lite" in message) ||
        ("only supports function tools" in message && "custom tools" in message))
    return unsupportedComplexity || unsupportedHostedRoute
}
