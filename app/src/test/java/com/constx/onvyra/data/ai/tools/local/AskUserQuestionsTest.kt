package com.constx.onvyra.data.ai.tools.local

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AskUserQuestionsTest {
    private fun question(id: String = "goal", options: List<String> = listOf("先整理", "先检查")) = buildJsonObject {
        put("id", id); put("question", "先做哪一步？")
        put("options", JsonArray(options.map(::JsonPrimitive)))
        put("selection_type", "single")
    }
    private fun request(vararg questions: JsonElement) = buildJsonObject { put("questions", JsonArray(questions.toList())) }

    @Test fun validChoicesHaveNoDefaultAnswerAndAllowSingleOrMultipleSelection() {
        val value = request(question(), question("format", listOf("图片", "文档")).let {
            JsonObject(it + ("selection_type" to JsonPrimitive("multi")))
        })
        assertNull(validateAskUserQuestions(value))
        val parsed = parseAskUserQuestions(value)
        assertEquals(2, parsed.size)
        assertEquals(listOf("先整理", "先检查"), parsed.first().options)
        assertEquals("multi", parsed.last().selectionType)
    }

    @Test fun tooManyQuestionsAndChoicesOrMissingRealChoicesAreRejected() {
        assertNotNull(validateAskUserQuestions(request()))
        assertNotNull(validateAskUserQuestions(request(*Array(4) { question("q$it") })))
        for (options in listOf(emptyList(), listOf("一个"), List(5) { "选项$it" }, listOf("重复", "重复"), listOf("", "另一个"))) {
            assertNotNull(validateAskUserQuestions(request(question(options = options))))
        }
        assertNotNull(validateAskUserQuestions(request(question(), question())))
        assertNotNull(validateAskUserQuestions(JsonPrimitive("invalid")))
    }

    @Test fun oldMessagesAreBoundedWithoutInventingOptionsOrCrashingOnMalformedEntries() {
        val legacy = request(JsonNull, JsonPrimitive("bad"),
            question("first", listOf("一", "二", "三", "四", "五")),
            question("second", listOf("旧单选")), question("third"), question("fourth"))
        val values = parseAskUserQuestions(legacy)
        assertEquals(3, values.size)
        assertEquals(listOf("一", "二", "三", "四"), values.first().options)
        assertEquals(listOf("旧单选"), values[1].options)
    }

    @Test fun schemaAdvertisesAndRequiresTheSameBoundsAsRuntimeValidation() {
        val schema = buildAskUserTool().parameters() as me.rerere.ai.core.InputSchema.Obj
        val questions = schema.properties.getValue("questions").jsonObject
        assertEquals(1, questions.getValue("minItems").jsonPrimitive.int)
        assertEquals(3, questions.getValue("maxItems").jsonPrimitive.int)
        val item = questions.getValue("items").jsonObject
        assertTrue(item.getValue("required").jsonArray.contains(JsonPrimitive("options")))
        val options = item.getValue("properties").jsonObject.getValue("options").jsonObject
        assertEquals(2, options.getValue("minItems").jsonPrimitive.int)
        assertEquals(4, options.getValue("maxItems").jsonPrimitive.int)
    }
}
