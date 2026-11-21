package com.constx.onvyra.ui.pages.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQuestionStateTest {
    @Test
    fun returningToAQuestionKeepsItsOwnTextAndSelections() {
        val state = PendingQuestionState(listOf(
            question("goal"),
            question("features", selectionType = "multi", options = listOf("搜索", "导出")),
        ))
        state.rememberText("先整理现有内容")
        state.moveTo(1)
        state.selectOption("导出")
        state.rememberText("也要保留附件")

        state.moveTo(0)
        assertEquals("先整理现有内容", state.textForCurrentQuestion())
        assertTrue(state.selectedOptions().isEmpty())

        state.moveTo(1)
        assertEquals("也要保留附件", state.textForCurrentQuestion())
        assertEquals(setOf("导出"), state.selectedOptions())
    }

    @Test
    fun customTextReplacesASingleChoiceAndCombinesWithMultipleChoices() {
        val state = PendingQuestionState(listOf(
            question("location", selectionType = "single", options = listOf("北京", "上海")),
            question("features", selectionType = "multi", options = listOf("搜索", "导出")),
        ))
        state.selectOption("北京")
        state.rememberText("杭州")
        state.moveTo(1)
        state.selectOption("导出")
        state.selectOption("搜索")
        state.rememberText("离线阅读")

        val payload = state.beginSubmission()
        assertNotNull(payload)
        val answers = Json.parseToJsonElement(payload!!).jsonObject.getValue("answers").jsonObject
        assertEquals("杭州", answers.getValue("location").jsonPrimitive.content)
        assertEquals("搜索, 导出, 离线阅读", answers.getValue("features").jsonPrimitive.content)
    }

    @Test
    fun suggestionsDoNotPreselectOrSubmitAnAnswer() {
        val state = PendingQuestionState(listOf(
            question("location", selectionType = "single", options = listOf("北京", "上海")),
        ))

        assertTrue(state.selectedOptions().isEmpty())
        assertFalse(state.canAnswerCurrent(""))
        assertFalse(state.canAnswerCurrent("  \n "))
        assertEquals(0, state.firstUnansweredIndex())
        assertNull(state.beginSubmission())
        assertFalse(state.submitting)
    }

    @Test
    fun aPartialBatchCannotSubmitAndACompleteBatchSubmitsOnlyOnce() {
        val state = PendingQuestionState(listOf(question("goal"), question("scope")))
        state.rememberText("整理文件")
        state.moveTo(1)

        assertEquals(1, state.firstUnansweredIndex())
        assertNull(state.beginSubmission())
        assertFalse(state.submitting)

        state.rememberText("仅整理下载目录")
        assertNull(state.firstUnansweredIndex())
        assertNotNull(state.beginSubmission())
        assertTrue(state.submitting)
        assertNull(state.beginSubmission())
    }

    @Test
    fun aRejectedSubmissionKeepsTheAnswerAndCanBeRetried() {
        val state = PendingQuestionState(listOf(
            question("features", selectionType = "multi", options = listOf("搜索", "导出")),
        ))
        state.selectOption("导出")
        state.rememberText("保留现有文件")
        val firstPayload = state.beginSubmission()
        assertNotNull(firstPayload)
        assertTrue(state.submitting)

        state.rejectSubmission()

        assertFalse(state.submitting)
        assertEquals("保留现有文件", state.textForCurrentQuestion())
        assertEquals(setOf("导出"), state.selectedOptions())
        assertEquals(firstPayload, state.beginSubmission())
        assertTrue(state.submitting)
    }

    @Test
    fun unknownOptionsAndLateSelectionChangesCannotAlterTheSubmittedAnswer() {
        val state = PendingQuestionState(listOf(
            question("choice", selectionType = "single", options = listOf("继续", "稍后")),
        ))
        state.selectOption("页面外的未知选项")
        assertTrue(state.selectedOptions().isEmpty())
        assertNull(state.beginSubmission())

        state.selectOption("继续")
        val submitted = state.beginSubmission()
        assertNotNull(submitted)
        state.selectOption("稍后")
        assertEquals(setOf("继续"), state.selectedOptions())
        assertEquals(submitted, state.answerPayload())
    }

    @Test fun aServiceReceiptReleasesInputImmediatelyWhileFailedSubmissionRemainsAnswerable() {
        val state = PendingQuestionState(listOf(question("choice", "single", listOf("继续", "稍后"))))
        state.selectOption("继续")
        assertNotNull(state.beginSubmission())
        assertTrue(state.capturesComposer)
        state.rejectSubmission()
        assertTrue(state.capturesComposer)
        assertNotNull(state.beginSubmission())
        state.acceptSubmission()
        assertFalse(state.capturesComposer)
        assertFalse(state.submitting)
        assertTrue(state.receiptAccepted)
        assertNull(state.beginSubmission())
        assertFalse(state.canAnswerCurrent("subsequent task update"))
        state.rejectSubmission()
        assertFalse(state.capturesComposer)
    }

    private fun question(
        id: String,
        selectionType: String = "text",
        options: List<String> = emptyList(),
    ) = PendingQuestion(id, "请回答 $id", options, selectionType)
}
