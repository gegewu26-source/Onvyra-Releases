package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.model.AndroidControlPermissionMode
import com.constx.onvyra.data.model.MediaCreationOutput
import com.constx.onvyra.data.model.MediaCreationRecord
import com.constx.onvyra.data.model.MediaCreationStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.mediagen.model.MediaKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ChatMediaExportToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Harness(val owner: String = Uuid.random().toString()) {
        val root = temporary.newFolder()
        val upload = File(root, "upload").apply { mkdirs() }
        val registry = ChatMediaRegistry(owner, listOf(upload), File(root, "media_creation"))
        var history = emptyList<UIMessage>()
        var mode = AndroidControlPermissionMode.AUTONOMOUS
        var enabled = true
        var calls = 0
        var exportError: Exception? = null
        var lastFile: File? = null
        var savedReadable = true
        fun file(name: String = "generated.png") = File(upload, name).apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        fun tools() = createChatMediaExportTools(registry,
            refresh = { registry.includeHistory(history) { null } }, enabled = { enabled }, permissionMode = { mode },
            export = { asset, _ ->
                calls++; lastFile = asset.file
                exportError?.let { throw it }
                SavedChatMedia("content://media/external/images/media/42", "Onvyra.png", "image/png", asset.file.length(), "Pictures/Onvyra")
            }, storagePermission = { if (it is LegacyDenied) "android.permission.WRITE_EXTERNAL_STORAGE" else null },
            savedStillReadable = { savedReadable })
        fun generated(file: File = file(), metadata: JsonObject? = null): List<UIMessagePart> = registry.captureGenerated(listOf(
            UIMessagePart.Text("""{"status":"succeeded","output_count":1,"displayed_in_chat":true}"""),
            UIMessagePart.Image(file.toURI().toString(), metadata)))
    }

    @Test fun newlyGeneratedImageCanBeSavedImmediatelyBeforeHistoryOrSessionUpdates() = runBlocking {
        val h = Harness()
        val source = h.file()
        val token = buildJsonObject { put("request_id", "unique"); put("total_tokens", 363) }
        val sourceInfo = buildJsonObject { put("route", "codex_images_extension") }
        val parts = h.generated(source, buildJsonObject { put("onvyra_token_usage", token); put("onvyra_image_source", sourceInfo) })
        val image = parts.filterIsInstance<UIMessagePart.Image>().single()
        assertEquals(token, image.metadata?.get("onvyra_token_usage"))
        assertEquals(sourceInfo, image.metadata?.get("onvyra_image_source"))
        val id = payload(parts.filterIsInstance<UIMessagePart.Text>()).getValue("media").jsonArray.single()
            .jsonObject.getValue("media_id").jsonPrimitive.content
        assertEquals(id, image.metadata?.get("onvyra_media_id")?.jsonPrimitive?.content)
        val result = payload(h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args(id)))
        assertEquals("succeeded", result.getValue("status").jsonPrimitive.content)
        assertEquals("content://media/external/images/media/42", result.getValue("content_uri").jsonPrimitive.content)
        assertEquals(source.canonicalFile, h.lastFile)
        assertEquals(1, h.calls)
        assertFalse(result.getValue("published").jsonPrimitive.boolean)
        assertTrue(h.history.isEmpty())
    }

    @Test fun oldCurrentChatImagesAreDiscoverableButPhoneScreenshotOutputsAreNot() = runBlocking {
        val h = Harness()
        val generated = h.file("old.gif")
        val screenshot = h.file("screen.jpg")
        h.history = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("old-call", GENERATE_NATIVE_IMAGE_TOOL, "{}", listOf(UIMessagePart.Image(generated.toURI().toString()))),
            UIMessagePart.Image(screenshot.toURI().toString(), buildJsonObject { put("android_control_frame", true) }),
        )))
        val tools = h.tools()
        val listed = payload(tools.single { it.name == LIST_CHAT_MEDIA_TOOL }.execute(args()))
            .getValue("media").jsonArray
        assertEquals(1, listed.size)
        assertEquals("old.gif", listed.single().jsonObject.getValue("file_name").jsonPrimitive.content)
        assertFalse(listed.toString().contains(h.root.canonicalPath))
        assertEquals("succeeded", payload(tools.single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args()))["status"]?.jsonPrimitive?.content)
        assertEquals(generated.canonicalFile, h.lastFile)
    }

    @Test fun opaqueIdsCannotCrossConversationsOrTurnArbitraryPathsIntoMedia() = runBlocking {
        val first = Harness()
        val other = Harness()
        first.generated()
        other.generated()
        val foreignId = first.registry.list().single().id
        assertNotEquals(foreignId, other.registry.list().single().id)
        val save = other.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }
        assertEquals("media_not_available_in_this_chat", payload(save.execute(args(foreignId)))["error_code"]?.jsonPrimitive?.content)
        for (field in listOf("path", "url", "conversation_id")) {
            val result = payload(save.execute(buildJsonObject { put(field, "file:///data/private/account.json") }))
            assertEquals("invalid_media_arguments", result["error_code"]?.jsonPrimitive?.content)
        }
        assertEquals(0, other.calls)
    }

    @Test fun generatedPartsCannotRegisterPrivateFilesOrRemoteUrls() {
        val h = Harness()
        val secret = File(h.root, "account.png").apply { writeText("private account material") }
        h.generated(secret)
        h.registry.captureGenerated(listOf(UIMessagePart.Image("https://example.invalid/image.png")))
        assertTrue(h.registry.list().isEmpty())
    }

    @Test fun oldVideoTaskOnlyRegistersItsOwnCompletedOutputDirectory() = runBlocking {
        val h = Harness()
        val recordId = Uuid.random()
        val session = Uuid.random()
        val output = File(h.root, "media_creation/$session/$recordId/video.mp4").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(9, 8, 7)) }
        val record = MediaCreationRecord(recordId, sessionId = session, providerId = Uuid.random(), providerName = "test",
            modelId = "test-video", kind = MediaKind.VIDEO, prompt = "public fixture", status = MediaCreationStatus.SUCCEEDED,
            outputs = listOf(MediaCreationOutput(output.relativeTo(h.root).path, "video/mp4"),
                MediaCreationOutput("private/account.json", "image/png")))
        val text = buildJsonObject { put("type", "generated_media"); put("record_id", recordId.toString()); put("session_id", session.toString()) }
        h.registry.includeHistory(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("video", GENERATE_MEDIA_TOOL, "{}", listOf(UIMessagePart.Text(text.toString()))))))) { id -> if (id == recordId) record else null }
        assertTrue(h.registry.knowsRecord(recordId))
        assertFalse(h.registry.knowsRecord(Uuid.random()))
        assertEquals("video", h.registry.list().single().kind)
        assertEquals(output.canonicalFile, h.registry.list().single().file)
    }

    @Test fun confirmChangesRequiresActualApprovalAndSurvivesToolRecreation() = runBlocking {
        val h = Harness()
        h.mode = AndroidControlPermissionMode.CONFIRM_CHANGES
        h.generated()
        val request = args(h.registry.list().single().id)
        val save = h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }
        assertTrue(save.needsApproval(request))
        assertEquals("confirmation_required", payload(save.execute(request))["error_code"]?.jsonPrimitive?.content)
        assertEquals(0, h.calls)
        assertTrue(save.needsApproval(request))
        AndroidControlApprovals.approve(h.owner, SAVE_CHAT_MEDIA_TOOL, request.toString())
        val rebuilt = h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }
        assertEquals("succeeded", payload(rebuilt.execute(request))["status"]?.jsonPrimitive?.content)
        assertEquals(1, h.calls)
    }

    @Test fun changingToConfirmModeBeforeExecutionCannotReuseAnAutomaticDecision() = runBlocking {
        val h = Harness()
        h.generated()
        val save = h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }
        val request = args()
        assertFalse(save.needsApproval(request))
        h.mode = AndroidControlPermissionMode.CONFIRM_CHANGES
        assertEquals("confirmation_required", payload(save.execute(request))["error_code"]?.jsonPrimitive?.content)
        assertEquals(0, h.calls)
    }

    @Test fun repeatSaveReusesVerifiedGalleryCopyAndReexportsIfItWasRemoved() = runBlocking {
        val h = Harness()
        h.generated()
        val save = h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }
        assertFalse(payload(save.execute(args()))["already_saved"]!!.jsonPrimitive.boolean)
        assertTrue(payload(save.execute(args()))["already_saved"]!!.jsonPrimitive.boolean)
        assertEquals(1, h.calls)
        h.savedReadable = false
        assertFalse(payload(save.execute(args()))["already_saved"]!!.jsonPrimitive.boolean)
        assertEquals(2, h.calls)
    }

    @Test fun writeFailuresNeverReportSuccessOrLeakExceptionPaths() = runBlocking {
        val h = Harness()
        h.generated()
        h.exportError = IOException("private-file:///account/secret-token")
        val result = payload(h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args()))
        assertEquals("failed", result["status"]?.jsonPrimitive?.content)
        assertEquals("gallery_write_failed", result["error_code"]?.jsonPrimitive?.content)
        assertNull(result["content_uri"])
        assertFalse(result.toString().contains("secret-token"))
    }

    @Test fun legacyPermissionAndDisabledControlAreAccurateFailures() = runBlocking {
        val h = Harness()
        h.generated()
        h.exportError = LegacyDenied()
        val save = h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }
        val missing = payload(save.execute(args()))
        assertEquals("storage_permission_required", missing["error_code"]?.jsonPrimitive?.content)
        assertEquals("android.permission.WRITE_EXTERNAL_STORAGE", missing["required_permission"]?.jsonPrimitive?.content)
        h.enabled = false
        assertEquals("phone_control_disabled", payload(save.execute(args()))["error_code"]?.jsonPrimitive?.content)
        assertEquals(1, h.calls)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingASuccessOrRetryInstruction() = runBlocking {
        val h = Harness()
        h.generated()
        h.exportError = CancellationException("stopped")
        try {
            h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args())
            fail("cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(1, h.calls)
    }

    @Test fun savedGalleryIdentityAndPickerPlanAreReturnedWithoutGuessingItsPosition() = runBlocking {
        val h = Harness()
        h.generated()
        val result = payload(h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args()))
        assertEquals("Onvyra", result["album_name"]?.jsonPrimitive?.content)
        assertEquals("42", result["media_store_id"]?.jsonPrimitive?.content)
        assertNotNull(result["source_fingerprint"])
        assertTrue(result["next_step"]!!.jsonPrimitive.content.contains("native photo picker"))
        assertFalse(result.toString().contains(h.root.absolutePath))
    }

    @Test fun aNewToolFactoryRecoversItsOwnSavedMediaFromActualHistory() = runBlocking {
        val h = Harness()
        val generated = h.generated()
        val saved = h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args())
        val fresh = ChatMediaRegistry(h.owner, listOf(h.upload), File(h.root, "media_creation"))
        val history = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("generate", GENERATE_NATIVE_IMAGE_TOOL, "{}", generated),
            UIMessagePart.Tool("save", SAVE_CHAT_MEDIA_TOOL, "{}", saved))))
        var exports = 0
        val tools = createChatMediaExportTools(fresh,
            refresh = { fresh.includeHistory(history) { null } }, enabled = { true },
            permissionMode = { AndroidControlPermissionMode.AUTONOMOUS },
            export = { _, _ -> exports++; error("Must reuse the verified historical save") },
            savedStillReadable = { true })
        val result = payload(tools.single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args()))
        assertTrue(result["already_saved"]!!.jsonPrimitive.boolean)
        assertEquals(0, exports)
        assertEquals("content://media/external/images/media/42", result["content_uri"]!!.jsonPrimitive.content)
    }

    @Test fun legacySavedReferencesAreRecoveredButMustPassTheCurrentVerificationCallback() = runBlocking {
        val h = Harness()
        val generated = h.generated()
        val modern = payload(h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args()))
        val legacy = JsonObject(modern - "source_fingerprint")
        val fresh = ChatMediaRegistry(h.owner, listOf(h.upload), File(h.root, "media_creation"))
        val history = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("generate", GENERATE_NATIVE_IMAGE_TOOL, "{}", generated),
            UIMessagePart.Tool("save", SAVE_CHAT_MEDIA_TOOL, "{}", listOf(UIMessagePart.Text(legacy.toString()))))))
        var checked = false
        val tools = createChatMediaExportTools(fresh,
            refresh = { fresh.includeHistory(history) { null } }, enabled = { true },
            permissionMode = { AndroidControlPermissionMode.AUTONOMOUS }, export = { _, _ -> error("read-only test") },
            locateInPicker = { _, _ -> error("Unverified gallery reference cannot be located") },
            verifySaved = { asset, saved -> checked = true; assertEquals(asset.file.length(), saved.sizeBytes); false })
        val result = payload(tools.single { it.name == FIND_SAVED_MEDIA_TOOL }.execute(args()))
        assertTrue(checked)
        assertEquals("media_not_saved", result["error_code"]!!.jsonPrimitive.content)
    }

    @Test fun localPickerLookupUsesOnlyTheRegisteredSavedAssetAndDoesNotExportAgain() = runBlocking {
        val h = Harness()
        h.generated()
        h.tools().single { it.name == SAVE_CHAT_MEDIA_TOOL }.execute(args())
        var lookups = 0
        val tools = createChatMediaExportTools(h.registry, refresh = {}, enabled = { true },
            permissionMode = { AndroidControlPermissionMode.AUTONOMOUS }, export = { _, _ -> error("No repeat export") },
            savedStillReadable = { true }, locateInPicker = { asset, saved ->
                lookups++
                assertEquals(h.registry.list().single().id, asset.id)
                assertEquals("42", saved.contentUri.substringAfterLast('/'))
                buildJsonObject { put("status", "found"); put("automatically_selected", false) }
            })
        val result = payload(tools.single { it.name == FIND_SAVED_MEDIA_TOOL }.execute(args()))
        assertEquals("found", result["status"]!!.jsonPrimitive.content)
        assertFalse(result["automatically_selected"]!!.jsonPrimitive.boolean)
        assertEquals(1, lookups)
        assertEquals(1, h.calls)
    }

    @Test fun historicalGalleryReferencesCannotPointAtPrivateProvidersOrOtherAlbums() {
        for (uri in listOf("content://private.accounts/passwords/42", "file:///private.png", "content://media/internal/images/media/42")) {
            assertFalse(validSavedGalleryReference(SavedChatMedia(uri, "image.png", "image/png", 10, "Pictures/Onvyra")))
        }
        assertFalse(validSavedGalleryReference(SavedChatMedia("content://media/external/images/media/42", "image.png", "image/png", 10, "DCIM/Camera")))
    }

    private class LegacyDenied : SecurityException()
    private fun args(id: String? = null) = buildJsonObject { id?.let { put("media_id", it) } }
    private fun payload(parts: List<UIMessagePart>): JsonObject = Json.parseToJsonElement(
        parts.filterIsInstance<UIMessagePart.Text>().first().text).jsonObject
}
