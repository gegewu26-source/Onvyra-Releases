package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.ai.transformers.DocumentAsPromptTransformer
import com.constx.onvyra.data.ai.transformers.OcrTransformer
import com.constx.onvyra.data.ai.transformers.TransformerContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Prepare only the uploaded answer attachments; conversation templates must not run again. */
internal suspend fun prepareQuestionAnswerAttachments(
    parts: List<UIMessagePart>,
    ctx: TransformerContext,
): List<UIMessagePart> {
    currentCoroutineContext().ensureActive()
    if (parts.isEmpty()) return emptyList()

    val uploaded = listOf(UIMessage(role = MessageRole.USER, parts = parts))
    val documents = DocumentAsPromptTransformer.transform(ctx, uploaded)
    currentCoroutineContext().ensureActive()
    val recognized = OcrTransformer.transform(ctx, documents)
    currentCoroutineContext().ensureActive()

    // OCR may replace a picture with text. Keep each original file reference in history as well.
    return (recognized.flatMap { it.parts } + parts).distinct()
}
