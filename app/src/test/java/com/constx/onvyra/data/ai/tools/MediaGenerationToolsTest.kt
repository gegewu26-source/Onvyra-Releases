package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.datastore.Settings
import com.constx.onvyra.data.model.MediaCreationOutput
import com.constx.onvyra.data.model.MediaCreationParams
import com.constx.onvyra.data.model.MediaCreationRecord
import com.constx.onvyra.data.model.MediaCreationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MediaGenerationToolsTest {
    @Test fun `providers alone do not enable tools and a stale selection never falls back`() {
        val provider = MediaGenerationProviderSetting.OpenAI()
        assertTrue(Harness(Settings(mediaGenerationProviders = listOf(provider))).tools.isEmpty())
        assertTrue(Harness(Settings(
            mediaGenerationProviders = listOf(provider), chatImageModelId = Uuid.random(),
        )).tools.isEmpty())
        val wrongKind = MediaGenerationModel("video-only", MediaKind.IMAGE)
        assertTrue(Harness(Settings(
            mediaGenerationProviders = listOf(MediaGenerationProviderSetting.MiniMax(models = listOf(wrongKind))),
            chatImageModelId = wrongKind.id,
        )).tools.isEmpty())
    }

    @Test fun `schema only exposes formats and video duration supported by selected endpoints`() {
        val openai = MediaGenerationProviderSetting.OpenAI()
        val image = Harness(Settings(
            mediaGenerationProviders = listOf(openai), chatImageModelId = openai.models.first().id,
        )).generate.parameters() as InputSchema.Obj
        assertEquals(setOf("kind", "prompt", "output_format", "frame_count", "frame_duration_ms", "animation_mode"), image.properties.keys)
        assertEquals(setOf("png", "jpeg", "webp", "gif"), image.properties.getValue("output_format")
            .jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())

        val aliyun = MediaGenerationProviderSetting.Aliyun()
        val otherImage = Harness(Settings(
            mediaGenerationProviders = listOf(aliyun),
            chatImageModelId = aliyun.models.first { it.kind == MediaKind.IMAGE }.id,
        )).generate.parameters() as InputSchema.Obj
        assertEquals(setOf("kind", "prompt", "output_format", "frame_count", "frame_duration_ms", "animation_mode"), otherImage.properties.keys)
        assertEquals(listOf("gif"), otherImage.properties.getValue("output_format").jsonObject.getValue("enum")
            .jsonArray.map { it.jsonPrimitive.content })

        val miniMax = MediaGenerationProviderSetting.MiniMax()
        val video = Harness(Settings(
            mediaGenerationProviders = listOf(miniMax), chatVideoModelId = miniMax.models.first().id,
        )).generate.parameters() as InputSchema.Obj
        assertEquals(setOf("kind", "prompt", "duration_seconds"), video.properties.keys)
        assertEquals(600, video.properties.getValue("duration_seconds").jsonObject.getValue("maximum").jsonPrimitive.int)
    }

    @Test fun `delegation uses the explicitly selected provider and model even with matching names elsewhere`() = runBlocking {
        val otherModel = MediaGenerationModel("same-model-name", MediaKind.IMAGE)
        val chosenModel = MediaGenerationModel("same-model-name", MediaKind.IMAGE)
        val other = MediaGenerationProviderSetting.OpenAI(models = listOf(otherModel))
        val chosen = MediaGenerationProviderSetting.OpenAI(models = listOf(chosenModel))
        val harness = Harness(Settings(
            mediaGenerationProviders = listOf(other, chosen), chatImageModelId = chosenModel.id,
        ))
        harness.generate.payload(request("image", "  A waving lantern  ", "gif"))
        val submitted = harness.submissions.single()
        assertEquals(chosen.id, submitted.provider.id)
        assertEquals(chosenModel.id, submitted.model.id)
        assertEquals("A waving lantern", submitted.prompt)
        assertEquals("gif", submitted.params.outputFormat)
        assertNull(submitted.params.durationSeconds)
    }

    @Test fun `MiniMax required defaults are supplied and an explicit duration is preserved`() = runBlocking {
        val provider = MediaGenerationProviderSetting.MiniMax()
        val harness = Harness(Settings(
            mediaGenerationProviders = listOf(provider), chatVideoModelId = provider.models.first().id,
        ))
        harness.generate.payload(request("video", "A quiet landscape"))
        assertEquals(MediaCreationParams(resolution = "768P", aspectRatio = "16:9", durationSeconds = 5),
            harness.submissions.single().params)
        harness.generate.payload(buildJsonObject {
            put("kind", "video"); put("prompt", "Another landscape"); put("duration_seconds", 10)
        })
        assertEquals(10, harness.submissions.last().params.durationSeconds)
        assertEquals("768P", harness.submissions.last().params.resolution)
        assertEquals("16:9", harness.submissions.last().params.aspectRatio)
        listOf("0", "601", "1.5", "\"5\"", "true").forEach { duration ->
            val args = Json.parseToJsonElement("""{"kind":"video","prompt":"A landscape","duration_seconds":$duration}""").jsonObject
            assertEquals("failed", harness.generate.payload(args).status)
        }
        assertEquals(2, harness.submissions.size)
    }

    @Test fun `static endpoints can request local gif composition but unselected media kinds stay disabled`() = runBlocking {
        val provider = MediaGenerationProviderSetting.Aliyun()
        val harness = Harness(Settings(
            mediaGenerationProviders = listOf(provider),
            chatImageModelId = provider.models.first { it.kind == MediaKind.IMAGE }.id,
        ))
        assertEquals("succeeded", harness.generate.payload(request("image", "Waving", "gif")).status)
        assertEquals("gif", harness.submissions.single().params.outputFormat)
        assertEquals("failed", harness.generate.payload(request("video", "Waving")).status)
        assertEquals(1, harness.submissions.size)
        harness.revision++
        assertEquals("succeeded", harness.generate.payload(request("image", "A still landscape")).status)
        assertNull(harness.submissions.last().params.outputFormat)
        assertNull(harness.record)
    }

    @Test fun `malformed optional parameters cannot silently turn GIF into a static request`() = runBlocking {
        val provider = MediaGenerationProviderSetting.OpenAI()
        val harness = Harness(Settings(
            mediaGenerationProviders = listOf(provider), chatImageModelId = provider.models.first().id,
        ))
        val invalid = listOf(
            """{"kind":"image","prompt":"wave","output_format":{}}""",
            """{"kind":"image","prompt":"wave","output_format":"bmp"}""",
            """{"kind":"image","prompt":"wave","duration_seconds":5}""",
            """{"kind":"image","prompt":"wave","duration_seconds":"5"}""",
            """{"kind":"image","prompt":" "}""",
            """{"kind":"unknown","prompt":"wave"}""",
        )
        invalid.forEach { assertEquals("failed", harness.generate.payload(Json.parseToJsonElement(it).jsonObject).status) }
        assertTrue(harness.submissions.isEmpty())
        assertEquals("succeeded", harness.generate.payload(buildJsonObject {
            put("kind", "image"); put("prompt", "A still landscape")
            put("output_format", JsonNull); put("duration_seconds", JsonNull)
        }).status)
    }

    @Test fun `submission reports preparing and status reads completed output instead of inventing success`() = runBlocking {
        val provider = MediaGenerationProviderSetting.MiniMax()
        val harness = Harness(Settings(
            mediaGenerationProviders = listOf(provider), chatVideoModelId = provider.models.first().id,
        ))
        val initial = harness.generate.payload(request("video", "A lantern"))
        assertEquals("generated_media", initial.getValue("type").jsonPrimitive.content)
        assertEquals("preparing", initial.status)
        assertEquals(0, initial.getValue("output_count").jsonPrimitive.int)
        assertFalse(initial.toString().contains("base64"))
        val record = harness.record!!
        harness.record = record.copy(status = MediaCreationStatus.SUCCEEDED,
            outputs = listOf(MediaCreationOutput("media_creation/fixture/result.mp4", "video/mp4")))
        val current = harness.status.payload(buildJsonObject { put("record_id", record.id.toString()) })
        assertEquals(initial.getValue("record_id"), current.getValue("record_id"))
        assertEquals("succeeded", current.status)
        assertEquals(1, current.getValue("output_count").jsonPrimitive.int)
        assertEquals(1, harness.submissions.size)
        assertEquals("not_found", harness.status.payload(buildJsonObject { put("record_id", "invalid") }).status)
    }

    @Test fun `submission failure does not retry another provider and cancellation propagates`() = runBlocking {
        val first = MediaGenerationProviderSetting.OpenAI()
        val second = MediaGenerationProviderSetting.OpenAI()
        val harness = Harness(Settings(
            mediaGenerationProviders = listOf(first, second), chatImageModelId = first.models.first().id,
        ))
        harness.error = IllegalStateException("Cannot submit task")
        assertEquals("failed", harness.generate.payload(request("image", "A lantern")).status)
        assertEquals(first.id, harness.submissions.single().provider.id)
        val cancellation = CancellationException("Cancelled")
        harness.error = cancellation
        harness.revision++
        val thrown = runCatching { harness.generate.payload(request("image", "A lantern")) }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertEquals(2, harness.submissions.size)
    }

    private data class Submission(
        val provider: MediaGenerationProviderSetting,
        val model: MediaGenerationModel,
        val prompt: String,
        val params: MediaCreationParams,
    )

    private class Harness(settings: Settings) {
        val submissions = mutableListOf<Submission>()
        var record: MediaCreationRecord? = null
        var error: Exception? = null
        var revision = 0L
        val tools = createMediaGenerationTools(settings, submit = { provider, model, prompt, params ->
            submissions += Submission(provider, model, prompt, params)
            error?.let { throw it }
            MediaCreationRecord(sessionId = Uuid.random(), providerId = provider.id, providerName = provider.name,
                modelId = model.modelId, kind = model.kind, prompt = prompt, params = params).also { record = it }
        }, getRecord = { id -> record?.takeIf { it.id == id } }, generateChatImage = { provider, model, prompt, params ->
            submissions += Submission(provider, model, prompt, params)
            error?.let { throw it }
            listOf(UIMessagePart.Text("""{"status":"succeeded","output_count":1}"""),
                UIMessagePart.Image("file:///fixture/generated.png"))
        }, requestRevision = { revision })
        val generate: Tool get() = tools.single { it.name == GENERATE_MEDIA_TOOL }
        val status: Tool get() = tools.single { it.name == MEDIA_STATUS_TOOL }
    }

    private suspend fun Tool.payload(arguments: JsonObject): JsonObject =
        Json.parseToJsonElement(execute(arguments).filterIsInstance<UIMessagePart.Text>().first().text).jsonObject

    private val JsonObject.status: String get() = getValue("status").jsonPrimitive.content

    private fun request(kind: String, prompt: String, format: String? = null): JsonObject = buildJsonObject {
        put("kind", kind); put("prompt", prompt)
        format?.let { put("output_format", it) }
    }
}
