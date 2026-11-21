package com.constx.onvyra.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelAbility

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask 1 to 3 necessary questions, preferably one, when clarification or additional information is needed.
        Every question must have 2 to 4 distinct, meaningful suggested options. Keep labels concise; the app lays them out to fit their text.
        The user may provide a free-text answer for every question, including single and multi selection questions.
        For multi selection questions, custom text can be combined with selected options.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
        The app keeps the current task open and shows this question above the chat input.
        After the user answers, continue the same task using that answer; preserve completed work and phone state.
        Questions are asynchronous: while an answer is pending, continue independent work that needs no answer.
        Never assume a choice or execute answer-dependent actions before the real answer arrives.
        If nothing independent remains, wait for the answer rather than polling, repeating questions or declaring completion.
    """.trimIndent().replace("\n", " "),
    systemPrompt = { model, _ -> if (ModelAbility.TOOL in model.abilities)
        "For a necessary choice, call ask_user with 1 to 3 concise questions (prefer one), each with 2 to 4 meaningful options. " +
            "The user can type a custom answer. A pending response is not an answer: continue only work independent of the pending choice. " +
            "Never guess the answer. If blocked with no independent work, finish this model reply and the app will wait in the same task. " +
            "Do not repeat the pending question or poll for its answer. Use the actual answer when it arrives; preserve completed effects and phone state."
        else "" },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions to ask the user")
                    put("minItems", 1)
                    put("maxItems", MAX_ASK_USER_QUESTIONS)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text to display to the user")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Required 2 to 4 distinct meaningful suggestions. Custom text is always available."
                                )
                                put("minItems", MIN_ASK_USER_OPTIONS)
                                put("maxItems", MAX_ASK_USER_OPTIONS)
                                put("uniqueItems", true)
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("single")
                                        add("multi")
                                    }
                                )
                                put(
                                    "description",
                                    "single (default): select one or type custom text; multi: select options and/or type custom text"
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                            add("options")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)
