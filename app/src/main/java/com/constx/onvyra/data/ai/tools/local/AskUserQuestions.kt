package com.constx.onvyra.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val MAX_ASK_USER_QUESTIONS = 3
internal const val MIN_ASK_USER_OPTIONS = 2
internal const val MAX_ASK_USER_OPTIONS = 4

internal data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "single",
)

/** New requests must provide meaningful choices; typing a custom answer is always available. */
internal fun validateAskUserQuestions(arguments: JsonElement): String? {
    val questions = (arguments as? JsonObject)?.get("questions") as? JsonArray
        ?: return "questions must be an array of 1 to 3 questions."
    if (questions.size !in 1..MAX_ASK_USER_QUESTIONS) return "Ask at most 3 questions at once; prefer one necessary question."
    val ids = mutableSetOf<String>()
    for (item in questions) {
        val question = item as? JsonObject ?: return "Every question must be an object."
        val id = question.string("id") ?: return "Every question needs a nonblank string id."
        if (!ids.add(id)) return "Question ids must be unique."
        if (question.string("question") == null) return "Every question needs nonblank question text."
        val options = question["options"] as? JsonArray ?: return "Every question needs 2 to 4 meaningful suggested options."
        if (options.size !in MIN_ASK_USER_OPTIONS..MAX_ASK_USER_OPTIONS) return "Every question needs 2 to 4 meaningful suggested options."
        val texts = options.map { it.nonblankString() ?: return "Options must be nonblank strings." }
        if (texts.distinct().size != texts.size) return "Suggested options must be distinct."
        val type = question["selection_type"]
        if (type != null && type.nonblankString() !in listOf("single", "multi", "text")) {
            return "selection_type must be single or multi. Custom text is available for both."
        }
    }
    return null
}

/** Tolerant bounded presentation of old messages; never invent choices or discard typed answers. */
internal fun parseAskUserQuestions(arguments: JsonElement): List<AskUserQuestion> =
    ((arguments as? JsonObject)?.get("questions") as? JsonArray).orEmpty().mapNotNull { item ->
        val question = item as? JsonObject ?: return@mapNotNull null
        val id = question.string("id") ?: return@mapNotNull null
        val text = question.string("question") ?: return@mapNotNull null
        AskUserQuestion(id, text,
            (question["options"] as? JsonArray).orEmpty().mapNotNull { it.nonblankString() }
                .distinct().take(MAX_ASK_USER_OPTIONS),
            if (question.string("selection_type") == "multi") "multi" else "single")
    }.distinctBy { it.id }.take(MAX_ASK_USER_QUESTIONS)

private fun JsonElement.nonblankString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }
    ?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.string(name: String): String? = get(name)?.nonblankString()
