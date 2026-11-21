package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

enum class HttpErrorCategory {
    OTHER,
    QUOTA_EXHAUSTED,
}

class HttpException(
    message: String,
    val category: HttpErrorCategory = HttpErrorCategory.OTHER,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private val errorFields = listOf("error", "detail", "message", "description", "data", "msg")
private val creditExhaustionCodes = setOf(
    "14018", // CodeBuddy's account credits are exhausted, independent of message locale.
    "insufficient_quota",
    "credits_exhausted",
    "credit_exhausted",
    "billing_hard_limit_reached",
    "insufficient_balance",
)
private const val MAX_ERROR_DEPTH = 16

fun JsonElement.parseErrorDetail(): HttpException {
    val creditError = findCreditExhaustion()
    if (creditError != null) {
        // A proxy may wrap an exhausted-credit response in a temporary rate-limit message.
        // Show the actionable inner error and keep the wrapper available for diagnosis.
        return HttpException(
            message = "服务商账户额度已耗尽，请检查账户用量，等待额度恢复或按服务商规则补充额度。\n$creditError",
            category = HttpErrorCategory.QUOTA_EXHAUSTED,
            cause = RuntimeException(Json.encodeToString(JsonElement.serializer(), this)),
        )
    }
    return HttpException(errorMessage())
}

private fun JsonElement.errorMessage(depth: Int = 0): String {
    if (depth >= MAX_ERROR_DEPTH) return toString()
    return when (this) {
        is JsonObject -> {
            val foundField = errorFields.firstOrNull { this[it] != null }
            foundField?.let { this[it]!!.errorMessage(depth + 1) } ?: toString()
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                "Unknown error: Empty JSON array"
            } else {
                this.first().errorMessage(depth + 1)
            }
        }

        is JsonPrimitive -> this.jsonPrimitive.content
        else -> toString()
    }
}

private fun JsonElement.findCreditExhaustion(depth: Int = 0): String? {
    if (depth >= MAX_ERROR_DEPTH) return null
    return when (this) {
        is JsonObject -> {
            // Only follow error-envelope fields, never unrelated request/usage metadata.
            errorFields.firstNotNullOfOrNull { this[it]?.findCreditExhaustion(depth + 1) }
                ?: if (listOf("code", "type").any {
                        (this[it] as? JsonPrimitive)?.content?.lowercase() in creditExhaustionCodes
                    }
                ) errorMessage() else null
        }

        is JsonArray -> firstOrNull()?.findCreditExhaustion(depth + 1)
        is JsonPrimitive -> {
            val message = content.trim()
            if (message.isCreditExhaustionMessage()) {
                message
            } else {
                message.decodeNestedError()?.findCreditExhaustion(depth + 1)
            }
        }

        else -> null
    }
}

private fun String.isCreditExhaustionMessage(): Boolean {
    val normalized = lowercase()
    return listOf("credits exhausted", "credit exhausted", "your credit balance is too low")
        .any { phrase ->
            normalized.startsWith(phrase) &&
                (normalized.length == phrase.length || !normalized[phrase.length].isLetterOrDigit())
        }
}

private fun String.decodeNestedError(): JsonElement? {
    if (startsWith('{') || startsWith('[') || startsWith('"')) {
        return runCatching { Json.parseToJsonElement(this) }.getOrNull()
    }
    // WorkBuddy's proxy places a JSON error after its own "retry in ..." prefix.
    val start = indexOf('{')
    val end = lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val nested = runCatching { Json.parseToJsonElement(substring(start, end + 1)) }.getOrNull()
    return (nested as? JsonObject)?.takeIf { "error" in it }
}
