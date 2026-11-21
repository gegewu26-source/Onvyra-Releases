package com.constx.onvyra.data.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constx.onvyra.data.ai.tools.local.buildAskUserTool
import com.constx.onvyra.data.datastore.NetworkSetting
import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.AndroidControlPermissionMode
import com.constx.onvyra.data.model.Assistant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real provider serialization with fixed local responses, never a model or phone. */
@RunWith(AndroidJUnit4::class)
class GenerationLoopQuestionWaitTest {
    @Test
    fun textAnswerResumesTheSameCollectionWithTheOriginalTaskAndToolJson() = runBlocking {
        verifyAnsweredRound(emptyList())
    }

    @Test
    fun attachmentAnswerResumesTheSameCollectionAndReachesTheProviderAsToolContent() = runBlocking {
        verifyAnsweredRound(listOf(UIMessagePart.Image(IMAGE_DATA)))
    }

    private suspend fun verifyAnsweredRound(attachments: List<UIMessagePart>) = coroutineScope {
        val fixture = Fixture()
        val enteredWait = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<List<UIMessagePart>>()
        val completedRevision = AtomicReference<Long?>(null)
        val collection = async {
            fixture.generate(
                onTaskCompleted = completedRevision::set,
                awaitUserAnswer = { messages, question ->
                    assertEquals(QUESTION_CALL, question.toolCallId)
                    assertTrue(question.isPending)
                    assertEquals(fixture.original, messages.take(fixture.original.size))
                    enteredWait.complete(Unit)
                    question.copy(
                        approvalState = ToolApprovalState.Answered(ANSWER_JSON),
                        output = answer.await(),
                    )
                },
            ).toList()
        }
        try {
            withTimeout(5_000) { enteredWait.await() }
            assertTrue("The original collector must remain active while the question waits", collection.isActive)
            assertEquals("Waiting must not reissue a provider request", 1, fixture.requests.size)
            assertNull("A question must not complete the original task", completedRevision.get())
            assertEquals(0, fixture.effectExecutions.get())

            assertTrue(answer.complete(listOf(UIMessagePart.Text(ANSWER_JSON)) + attachments))
            val chunks = withTimeout(5_000) { collection.await() }
            assertTrue("The same collection must finish after receiving its answer", collection.isCompleted)
            assertEquals(2, fixture.requests.size)
            val sent = fixture.requests.last().getValue("messages").jsonArray.map { it.jsonObject }
            val toolResult = sent.single {
                it["role"]?.jsonPrimitive?.content == "tool" &&
                    it["tool_call_id"]?.jsonPrimitive?.content == QUESTION_CALL
            }.getValue("content")
            if (attachments.isEmpty()) {
                assertEquals(ANSWER_JSON, toolResult.jsonPrimitive.content)
            } else {
                val parts = toolResult.jsonArray.map { it.jsonObject }
                assertEquals(ANSWER_JSON, parts.single { it["type"]?.jsonPrimitive?.content == "text" }
                    .getValue("text").jsonPrimitive.content)
                assertEquals(IMAGE_DATA, parts.single { it["type"]?.jsonPrimitive?.content == "image_url" }
                    .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
            }
            assertEquals("The answer must remain a tool result rather than a new user request", 1,
                sent.count { it["role"]?.jsonPrimitive?.content == "user" })
            val final = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages
            fixture.assertOriginalEffectsRetained(final)
            assertEquals(listOf(fixture.original.first().id), final.filter { it.role == MessageRole.USER }.map { it.id })
            val answered = final.flatMap { it.getTools() }.single { it.toolCallId == QUESTION_CALL }
            assertEquals(ToolApprovalState.Answered(ANSWER_JSON), answered.approvalState)
            assertEquals(listOf(UIMessagePart.Text(ANSWER_JSON)) + attachments, answered.output)
            assertTrue(final.last().toText().contains(FINAL_REPLY))
            assertEquals(0L, requireNotNull(completedRevision.get()))
        } finally {
            collection.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun steeringInterruptsTheQuestionWaitAndContinuesTheRetainedTaskInTheSameCollection() = runBlocking {
        val fixture = Fixture()
        val steering = GenerationSteering()
        val queued = mutableListOf<UIMessage>()
        val correction = UIMessage.user("Keep the original task, use the station instead, and continue.")
        val enteredWait = CompletableDeferred<Unit>()
        val exitedWait = CompletableDeferred<Unit>()
        val unanswered = CompletableDeferred<UIMessagePart.Tool>()
        val completedRevision = AtomicReference<Long?>(null)
        val collection = async {
            fixture.generate(
                steering = steering,
                consumeSteering = { synchronized(queued) { queued.toList().also { queued.clear() } } },
                onTaskCompleted = completedRevision::set,
                awaitUserAnswer = { _, _ ->
                    enteredWait.complete(Unit)
                    try { unanswered.await() } finally { exitedWait.complete(Unit) }
                },
            ).toList()
        }
        try {
            withTimeout(5_000) { enteredWait.await() }
            assertTrue(collection.isActive)
            assertEquals(1, fixture.requests.size)
            assertNull(completedRevision.get())
            synchronized(queued) { queued += correction }
            val revision = steering.signal()
            withTimeout(5_000) { exitedWait.await() }
            val chunks = withTimeout(5_000) { collection.await() }

            assertEquals(2, fixture.requests.size)
            assertEquals(revision, requireNotNull(completedRevision.get()))
            assertFalse(steering.hasPending)
            val sent = fixture.requests.last().getValue("messages").jsonArray.map { it.jsonObject }
            assertTrue(sent.any { it["role"]?.jsonPrimitive?.content == "user" &&
                it["content"].toString().contains("Keep the original task, use the station instead") })
            assertTrue(sent.toString().contains(ORIGINAL_TASK))
            val final = chunks.filterIsInstance<GenerationChunk.Messages>().last().messages
            fixture.assertOriginalEffectsRetained(final)
            assertEquals(1, final.count { it.id == correction.id })
            val superseded = final.flatMap { it.getTools() }.single { it.toolCallId == QUESTION_CALL }
            assertFalse(superseded.isPending)
            assertTrue(superseded.approvalState is ToolApprovalState.Denied)
            assertTrue(superseded.output.filterIsInstance<UIMessagePart.Text>().any {
                it.text.contains("new_user_instruction") && it.text.contains("\"task_retained\":true")
            })
            assertTrue(final.last().toText().contains(FINAL_REPLY))
        } finally {
            collection.cancelAndJoin()
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val model = Model(modelId = "question-wait-fixture",
            abilities = listOf(ModelAbility.TOOL), inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        private val assistant = Assistant(chatModelId = model.id, streamOutput = false, contextMessageLimit = 0,
            androidControlPermissionMode = AndroidControlPermissionMode.FULL_ACCESS)
        val requests = CopyOnWriteArrayList<JsonObject>()
        val effectExecutions = AtomicInteger()
        private val completedEffect = UIMessagePart.Tool(
            "already-completed-effect", "fixture_effect", "{}",
            output = listOf(UIMessagePart.Text("COMPLETED_EFFECT_RESULT")),
        )
        val original = listOf(UIMessage.user(ORIGINAL_TASK),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(completedEffect),
                finishedAt = LocalDateTime(2026, 1, 1, 0, 0)))
        private val effect = Tool(name = "fixture_effect", description = "Previously completed fixture effect",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
            execute = { effectExecutions.incrementAndGet(); listOf(UIMessagePart.Text("unexpected repeat")) })
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "question-wait-fixture.example.test")
            check(request.url.encodedPath == "/v1/chat/completions")
            requests += Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val index = requests.size
            check(index <= 2) { "The fixture permits only the question request and its continuation" }
            val response = buildJsonObject {
                put("id", "question-wait-response-$index")
                put("object", "chat.completion")
                put("model", model.modelId)
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("index", 0)
                    put("finish_reason", if (index == 1) "tool_calls" else "stop")
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", if (index == 1) JsonNull else JsonPrimitive(FINAL_REPLY))
                        if (index == 1) put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("id", QUESTION_CALL)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "ask_user")
                                put("arguments", """{"questions":[{"id":"destination","question":"Which destination?","options":["station","airport"],"selection_type":"single"}]}""")
                            })
                        }) })
                    })
                }) })
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val provider = ProviderSetting.OpenAI(baseUrl = "https://question-wait-fixture.example.test/v1",
            apiKey = "", models = listOf(model))
        private val settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
            assistantId = assistant.id, chatModelId = model.id, networkSetting = NetworkSetting(enableAutoRetry = false))

        fun generate(
            steering: GenerationSteering? = null,
            consumeSteering: (suspend () -> List<UIMessage>)? = null,
            onTaskCompleted: ((Long) -> Unit)? = null,
            awaitUserAnswer: suspend (List<UIMessage>, UIMessagePart.Tool) -> UIMessagePart.Tool,
        ) = GenerationLoop(context, ProviderManager(client, context), Json).generateText(
            settings = settings, model = model, messages = original, assistant = assistant,
            tools = listOf(buildAskUserTool(), effect), maxSteps = 3, startNewMessage = true,
            steering = steering, consumeSteering = consumeSteering, onTaskCompleted = onTaskCompleted,
            awaitUserAnswer = awaitUserAnswer,
        )

        fun assertOriginalEffectsRetained(final: List<UIMessage>) {
            assertEquals(original, final.take(original.size))
            assertEquals(1, final.flatMap { it.getTools() }.count { it.toolCallId == completedEffect.toolCallId })
            assertEquals(completedEffect, final.flatMap { it.getTools() }.single { it.toolCallId == completedEffect.toolCallId })
            assertEquals("Completed effects must not execute again after a question or correction", 0, effectExecutions.get())
        }

        override fun close() {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private companion object {
        const val QUESTION_CALL = "fixture-question-call"
        const val ORIGINAL_TASK = "ORIGINAL_TASK: finish the trip plan after asking for the destination."
        const val ANSWER_JSON = "{\"destination\":\"station\"}"
        const val FINAL_REPLY = "ORIGINAL_TASK_FINISHED"
        const val IMAGE_DATA = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlUAAAAAASUVORK5CYII="
    }
}
