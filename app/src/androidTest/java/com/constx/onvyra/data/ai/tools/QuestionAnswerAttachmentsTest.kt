package com.constx.onvyra.data.ai.tools

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.data.ai.transformers.TransformerContext
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.Assistant
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated document fixture, without reading chat history or contacting an OCR model. */
@RunWith(AndroidJUnit4::class)
class QuestionAnswerAttachmentsTest {
    @Test
    fun uploadedTextBecomesToolReadableTextAndRetainsOneOriginalDocumentReference() = runBlocking {
        val ctx = transformerContext()
        val file = File.createTempFile("question-answer-document-", ".txt", ctx.context.cacheDir)
        try {
            val contents = "QUESTION_ATTACHMENT_FIXTURE: use platform 3, departure at 09:15."
            file.writeText(contents)
            val document = UIMessagePart.Document(file.toUri().toString(), "destination-details.txt", "text/plain")

            val prepared = prepareQuestionAnswerAttachments(listOf(document, document), ctx)

            val extracted = prepared.filterIsInstance<UIMessagePart.Text>().single().text
            assertTrue("The text file content must reach the model", extracted.contains(contents))
            assertTrue(extracted.contains("<UploadFile name=\"destination-details.txt\""))
            assertFalse("Answer attachments must not inject the assistant prompt", extracted.contains(PERSONA_MARKER))
            assertEquals(1, prepared.filterIsInstance<UIMessagePart.Document>().size)
            assertSame("Keep the original document object for file tracking", document,
                prepared.filterIsInstance<UIMessagePart.Document>().single())
        } finally {
            file.delete()
        }
    }

    @Test
    fun emptyAttachmentsReturnWithoutChangingProcessingStatus() = runBlocking {
        val status = MutableStateFlow<String?>("waiting for question answer")
        val ctx = transformerContext(status)

        assertTrue(prepareQuestionAnswerAttachments(emptyList(), ctx).isEmpty())
        assertEquals("waiting for question answer", status.value)
    }

    private fun transformerContext(status: MutableStateFlow<String?> = MutableStateFlow(null)): TransformerContext {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = Model(modelId = "question-attachment-fixture", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        val assistant = Assistant(chatModelId = model.id, systemPrompt = PERSONA_MARKER)
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id, chatModelId = model.id)
        return TransformerContext(context, model, assistant, settings, processingStatus = status)
    }

    private companion object {
        const val PERSONA_MARKER = "ASSISTANT_PERSONA_MUST_NOT_BE_IN_ATTACHMENT_TEXT"
    }
}
