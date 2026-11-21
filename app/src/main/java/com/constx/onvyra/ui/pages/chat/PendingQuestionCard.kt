package com.constx.onvyra.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.constx.onvyra.data.ai.tools.local.AskUserQuestion
import com.constx.onvyra.data.ai.tools.local.parseAskUserQuestions
import com.constx.onvyra.data.model.isTaskAnswerSubmitted
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion

internal typealias PendingQuestion = AskUserQuestion

/** Drafts belong to one tool call; changing question never clears the shared attachment tray. */
@Stable
internal class PendingQuestionState(val questions: List<PendingQuestion>) {
    var questionIndex by mutableIntStateOf(0)
        private set
    private var textAnswers by mutableStateOf(emptyMap<String, String>())
    private var optionAnswers by mutableStateOf(emptyMap<String, Set<String>>())
    var submitting by mutableStateOf(false)
        private set
    var receiptAccepted by mutableStateOf(false)
        private set

    // Only a service acknowledgement releases the composer. A click or an
    // in-flight submission alone must not hide a rejected answer.
    val capturesComposer: Boolean get() = !receiptAccepted

    val currentQuestion: PendingQuestion? get() = questions.getOrNull(questionIndex)
    val isLastQuestion: Boolean get() = questionIndex == questions.lastIndex

    fun textForCurrentQuestion(): String = currentQuestion?.let { textAnswers[it.id] }.orEmpty()

    fun rememberText(text: String) {
        val question = currentQuestion ?: return
        textAnswers = textAnswers + (question.id to text)
    }

    fun selectedOptions(): Set<String> = currentQuestion?.let { optionAnswers[it.id] }.orEmpty()

    fun selectOption(option: String) {
        val question = currentQuestion ?: return
        if (option !in question.options || submitting || receiptAccepted) return
        val selected = selectedOptions()
        optionAnswers = optionAnswers + (question.id to if (question.selectionType == "multi") {
            if (option in selected) selected - option else selected + option
        } else {
            setOf(option)
        })
    }

    fun canAnswerCurrent(draftText: String): Boolean =
        !receiptAccepted && (draftText.isNotBlank() || selectedOptions().isNotEmpty())

    fun moveTo(index: Int) {
        questionIndex = index.coerceIn(0, questions.lastIndex.coerceAtLeast(0))
    }

    fun firstUnansweredIndex(): Int? = questions.indices.firstOrNull { answerFor(questions[it]).isBlank() }

    fun answerPayload(): String = buildJsonObject {
        put("answers", buildJsonObject {
            questions.forEach { question -> put(question.id, answerFor(question)) }
        })
    }.toString()

    fun beginSubmission(): String? {
        if (submitting || receiptAccepted || questions.isEmpty() || firstUnansweredIndex() != null) return null
        submitting = true
        return answerPayload()
    }

    fun rejectSubmission() {
        if (!receiptAccepted) submitting = false
    }

    fun acceptSubmission() {
        if (!submitting) return
        receiptAccepted = true
        submitting = false
    }

    private fun answerFor(question: PendingQuestion): String {
        val text = textAnswers[question.id].orEmpty().trim()
        val selected = optionAnswers[question.id].orEmpty()
        return if (question.selectionType == "multi") {
            (question.options.filter { it in selected } + listOfNotNull(text.takeIf(String::isNotBlank)))
                .joinToString(", ")
        } else {
            text.ifBlank { question.options.firstOrNull { it in selected }.orEmpty() }
        }
    }

    private fun saveDraft(): String = buildJsonObject {
        put("index", questionIndex)
        put("texts", JsonObject(textAnswers.mapValues { JsonPrimitive(it.value) }))
        put("options", JsonObject(optionAnswers.mapValues { (_, values) ->
            JsonArray(values.map(::JsonPrimitive))
        }))
    }.toString()

    private fun restoreDraft(saved: String) {
        runCatching {
            val draft = Json.parseToJsonElement(saved).jsonObject
            moveTo(draft["index"]?.jsonPrimitive?.intOrNull ?: 0)
            textAnswers = draft["texts"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            optionAnswers = draft["options"]?.jsonObject?.mapValues { (_, value) ->
                value.jsonArray.map { it.jsonPrimitive.content }.toSet()
            }.orEmpty()
        }
    }

    companion object {
        fun saver(questions: List<PendingQuestion>) = Saver<PendingQuestionState, String>(
            save = { it.saveDraft() },
            restore = { saved -> PendingQuestionState(questions).apply { restoreDraft(saved) } },
        )
    }
}

@Composable
internal fun rememberPendingQuestionState(
    conversationKey: String,
    tool: UIMessagePart.Tool?,
): PendingQuestionState? {
    if (tool == null || tool.isTaskAnswerSubmitted) return null
    val questions = remember(tool.input) {
        parseAskUserQuestions(tool.inputAsJson())
    }
    return rememberSaveable(
        conversationKey, tool.toolCallId, tool.input,
        saver = remember(questions) { PendingQuestionState.saver(questions) },
    ) { PendingQuestionState(questions) }
}

@Composable
internal fun PendingQuestionCard(
    state: PendingQuestionState,
    draftText: String,
    onSelectOption: (String) -> Unit,
    onPrevious: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.receiptAccepted) return
    val question = state.currentQuestion
    Surface(
        modifier = modifier.fillMaxWidth().testTag("chat_pending_question_card"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(HugeIcons.BubbleChatQuestion, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = if (state.questions.size > 1) "等待你回答 · ${state.questionIndex + 1}/${state.questions.size}" else "等待你回答",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = question?.question ?: "问题信息不完整，可在下方补充说明。",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (question != null && question.options.isNotEmpty()) {
                    QuestionOptionFlow(
                        options = question.options,
                        selected = state.selectedOptions().takeIf {
                            question.selectionType == "multi" || draftText.isBlank()
                        }.orEmpty(),
                        enabled = !state.submitting,
                        onSelect = onSelectOption,
                    )
                }
                if (question != null) {
                    Text(
                        text = if (question.selectionType == "multi") "可多选，也可在下方输入框补充回答" else "也可以直接在下方输入框填写自定义回答",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (state.questionIndex > 0) {
                    TextButton(onClick = onPrevious, enabled = !state.submitting) { Text("上一题") }
                }
                FilledTonalButton(
                    onClick = onConfirm,
                    enabled = question != null && !state.submitting && state.canAnswerCurrent(draftText),
                    modifier = Modifier.testTag("chat_pending_question_confirm"),
                ) {
                    Text(if (state.submitting) "正在继续…" else if (state.isLastQuestion) "提交并继续" else "下一题")
                }
            }
        }
    }
}

/** Short choices share a row; a choice wider than half the card receives its own line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionOptionFlow(
    options: List<String>,
    selected: Set<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyMedium
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        val halfRow = ((maxWidth - gap) / 2).coerceAtLeast(0.dp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            maxItemsInEachRow = 3,
        ) {
            options.take(4).forEachIndexed { index, option ->
                // Include the chip's internal horizontal padding. Measure in the
                // current font/density so large accessibility text also gets room.
                val labelWidth = with(density) {
                    measurer.measure(option, labelStyle, softWrap = false, maxLines = 1).size.width.toDp()
                }
                val longChoice = labelWidth + 32.dp > halfRow
                FilterChip(
                    selected = option in selected,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = option,
                            style = labelStyle,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    },
                    modifier = (if (longChoice) Modifier.fillMaxWidth()
                        else Modifier.widthIn(min = 64.dp.coerceAtMost(halfRow), max = halfRow))
                        .testTag("chat_pending_question_option_$index"),
                )
            }
        }
    }
}
