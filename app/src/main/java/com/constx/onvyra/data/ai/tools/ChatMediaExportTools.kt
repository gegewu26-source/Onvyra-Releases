package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.model.AndroidControlPermissionMode
import com.constx.onvyra.data.model.MediaCreationRecord
import com.constx.onvyra.data.model.MediaCreationStatus
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

internal const val LIST_CHAT_MEDIA_TOOL = "list_chat_media"
internal const val SAVE_CHAT_MEDIA_TOOL = "save_media_to_gallery"
internal const val FIND_SAVED_MEDIA_TOOL = "find_saved_media_in_picker"
private val generationMediaTools = setOf(GENERATE_MEDIA_TOOL, GENERATE_NATIVE_IMAGE_TOOL, MEDIA_STATUS_TOOL)

internal data class ChatMediaAsset(
    val id: String,
    val file: File,
    val kind: String,
    val mimeType: String? = null,
    val origin: String,
) {
    fun reference(): JsonObject = buildJsonObject {
        put("media_id", id)
        put("kind", kind)
        put("origin", origin)
        put("file_name", file.name)
        mimeType?.let { put("mime_type", it) }
        put("size_bytes", file.length())
    }
}

internal data class SavedChatMedia(
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val relativePath: String,
    val savedAtMillis: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
)

/** IDs are scoped to the calling conversation, never interpreted as file names or URLs. */
internal class ChatMediaRegistry(
    val owner: String,
    managedRoots: List<File>,
    private val recordRoot: File,
) {
    private val roots = managedRoots.map { it.canonicalFile.toPath() }
    private val assets = linkedMapOf<String, ChatMediaAsset>()
    private val records = linkedMapOf<Uuid, Uuid>()
    private data class SavedRecord(val sourceFingerprint: String, val requestedName: String?, val saved: SavedChatMedia)
    private val savedRecords = linkedMapOf<String, SavedRecord>()

    @Synchronized
    fun rememberSaved(asset: ChatMediaAsset, requestedName: String?, saved: SavedChatMedia) {
        require(validSavedGalleryReference(saved) && saved.sizeBytes == asset.file.length())
        savedRecords[asset.id] = SavedRecord(asset.sourceFingerprint(), requestedName, saved)
        while (savedRecords.size > 64) savedRecords.remove(savedRecords.keys.first())
    }

    @Synchronized
    fun savedFor(asset: ChatMediaAsset, requestedName: String? = null): SavedChatMedia? = savedRecords[asset.id]
        ?.takeIf { it.sourceFingerprint == asset.sourceFingerprint() &&
            (requestedName == null || requestedName == it.requestedName) }?.saved

    @Synchronized
    fun forgetSaved(id: String) { savedRecords.remove(id) }

    private fun restoreSavedFromHistory(messages: List<UIMessage>) {
        messages.takeLast(128).filter { it.role != MessageRole.USER && !it.isContextCheckpoint }.forEach { message ->
            message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.toolName == SAVE_CHAT_MEDIA_TOOL }.forEach { tool ->
                tool.output.filterIsInstance<UIMessagePart.Text>().forEach savedPart@{ part ->
                    val payload = runCatching { Json.parseToJsonElement(part.text) as? JsonObject }.getOrNull() ?: return@savedPart
                    if (payload["status"]?.jsonPrimitive?.contentOrNull != "succeeded" ||
                        payload["saved_to_gallery"]?.jsonPrimitive?.booleanOrNull != true) return@savedPart
                    val id = payload["media_id"]?.jsonPrimitive?.contentOrNull ?: return@savedPart
                    val asset = find(id) ?: return@savedPart
                    val fingerprint = payload["source_fingerprint"]?.jsonPrimitive?.contentOrNull
                    if (fingerprint != null && fingerprint != asset.sourceFingerprint()) return@savedPart
                    val saved = parseSavedGalleryReference(payload) ?: return@savedPart
                    if (saved.sizeBytes != asset.file.length()) return@savedPart
                    val current = savedFor(asset)
                    if (current != null && current.savedAtMillis > saved.savedAtMillis) return@savedPart
                    rememberSaved(asset, payload["requested_display_name"]?.jsonPrimitive?.contentOrNull, saved)
                }
            }
        }
    }

    @Synchronized
    fun knowsRecord(id: Uuid): Boolean = id in records

    @Synchronized
    fun rememberSubmittedRecord(record: MediaCreationRecord) {
        records[record.id] = record.sessionId
        trimRecords()
    }

    /** Seed task ownership without touching image files when phone control is disabled. */
    fun rememberHistoryRecords(messages: List<UIMessage>) {
        messages.takeLast(128).filter { it.role != MessageRole.USER && !it.isContextCheckpoint }.forEach { message ->
            message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.toolName in generationMediaTools }.forEach { tool ->
                tool.output.filterIsInstance<UIMessagePart.Text>().forEach { rememberTaskPayload(it.text) }
            }
        }
    }

    @Synchronized
    fun registerRecord(record: MediaCreationRecord) {
        if (records[record.id] != record.sessionId || record.status != MediaCreationStatus.SUCCEEDED) return
        val permitted = File(File(recordRoot, record.sessionId.toString()), record.id.toString()).canonicalFile.toPath()
        record.outputs.take(32).forEach { output ->
            val file = File(recordRoot.parentFile, output.path).canonicalFile
            if (file.toPath().startsWith(permitted) && output.mimeType.startsWithAny("image/", "video/")) {
                add(file, if (output.isVideo) "video" else "image", output.mimeType, "generated")
            }
        }
    }

    /** A local record reference is accepted only from this chat's real generation-tool output. */
    suspend fun includeHistory(messages: List<UIMessage>, getRecord: suspend (Uuid) -> MediaCreationRecord?) {
        messages.takeLast(128).forEach { message ->
            if (message.isContextCheckpoint) return@forEach
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Image, is UIMessagePart.Video -> if (message.role in setOf(MessageRole.USER, MessageRole.ASSISTANT))
                        registerPart(part, if (message.role == MessageRole.USER) "chat_attachment" else "generated")
                    is UIMessagePart.Tool -> if (message.role != MessageRole.USER && part.toolName in generationMediaTools) {
                        captureGenerated(part.output)
                        part.output.filterIsInstance<UIMessagePart.Text>().forEach { text -> rememberTaskPayload(text.text) }
                    }
                    else -> Unit
                }
            }
        }
        val pending = synchronized(this) { records.keys.toList() }
        pending.forEach { id -> getRecord(id)?.let(::registerRecord) }
        restoreSavedFromHistory(messages)
    }

    fun captureGenerated(parts: List<UIMessagePart>): List<UIMessagePart> {
        val found = parts.mapNotNull { registerPart(it, "generated") }
        if (found.isEmpty()) return parts
        val refs = JsonArray(found.map(ChatMediaAsset::reference))
        var updatedText = false
        val result = parts.map { part ->
            when (part) {
                is UIMessagePart.Image -> registerPart(part, "generated")?.let { asset ->
                    part.copy(metadata = JsonObject((part.metadata ?: JsonObject(emptyMap())) +
                        mapOf("onvyra_media_id" to JsonPrimitive(asset.id), "onvyra_generated_media" to JsonPrimitive(true))))
                } ?: part
                is UIMessagePart.Video -> registerPart(part, "generated")?.let { asset ->
                    part.copy(metadata = JsonObject((part.metadata ?: JsonObject(emptyMap())) +
                        mapOf("onvyra_media_id" to JsonPrimitive(asset.id), "onvyra_generated_media" to JsonPrimitive(true))))
                } ?: part
                is UIMessagePart.Text -> {
                    val payload = runCatching { Json.parseToJsonElement(part.text) as? JsonObject }.getOrNull()
                    if (!updatedText && (payload?.get("status") as? JsonPrimitive)?.contentOrNull == "succeeded") {
                        updatedText = true
                        part.copy(text = JsonObject(payload + ("media" to refs)).toString())
                    } else part
                }
                else -> part
            }
        }
        return if (updatedText) result else result + UIMessagePart.Text(buildJsonObject {
            put("status", "succeeded"); put("media", refs)
        }.toString())
    }

    @Synchronized
    private fun rememberTaskPayload(text: String) {
        val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        if ((value["type"] as? JsonPrimitive)?.contentOrNull != "generated_media") return
        val id = (value["record_id"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        val session = (value["session_id"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: return
        records[id] = session
        trimRecords()
    }

    @Synchronized
    private fun trimRecords() { while (records.size > 64) records.remove(records.keys.first()) }

    @Synchronized
    private fun registerPart(part: UIMessagePart, origin: String): ChatMediaAsset? {
        if (part.metadata?.get("android_control_frame")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return null
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Video -> part.url
            else -> return null
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) return null
        val file = runCatching { File(uri).canonicalFile }.getOrNull() ?: return null
        if (roots.none { file.toPath().startsWith(it) }) return null
        return add(file, if (part is UIMessagePart.Video) "video" else "image", null, origin)
    }

    @Synchronized
    private fun add(file: File, kind: String, mimeType: String?, origin: String): ChatMediaAsset? {
        if (!file.isFile || !file.canRead() || file.length() == 0L) return null
        val id = "media_" + digest("$owner\n${file.toURI()}").take(32)
        val previous = assets[id]
        val asset = ChatMediaAsset(id, file, kind, mimeType ?: previous?.mimeType,
            if (previous?.origin == "generated") "generated" else origin)
        assets[id] = asset
        while (assets.size > 128) assets.remove(assets.keys.first())
        return asset
    }

    @Synchronized
    fun list(): List<ChatMediaAsset> = assets.values.filter {
        runCatching { it.file.canonicalFile == it.file && it.file.isFile && it.file.length() > 0 }.getOrDefault(false)
    }.takeLast(64)

    @Synchronized
    fun find(id: String?): ChatMediaAsset? {
        val available = list()
        return if (id == null) available.lastOrNull { it.origin == "generated" } ?: available.lastOrNull()
        else available.firstOrNull { it.id == id }
    }
}

/** Pure Tool plumbing; the factory supplies the Android writer and current permission state. */
internal fun createChatMediaExportTools(
    registry: ChatMediaRegistry,
    refresh: suspend () -> Unit,
    enabled: () -> Boolean,
    permissionMode: () -> AndroidControlPermissionMode,
    export: suspend (ChatMediaAsset, String?) -> SavedChatMedia,
    storagePermission: (Exception) -> String? = { null },
    savedStillReadable: suspend (SavedChatMedia) -> Boolean = { false },
    locateInPicker: (suspend (ChatMediaAsset, SavedChatMedia) -> JsonObject)? = null,
    verifySaved: suspend (ChatMediaAsset, SavedChatMedia) -> Boolean = { _, saved -> savedStillReadable(saved) },
): List<Tool> {
    val exportLock = Mutex()
    fun failure(code: String, message: String, permission: String? = null) = listOf(UIMessagePart.Text(buildJsonObject {
        put("status", "failed"); put("error_code", code); put("message", message)
        permission?.let { put("required_permission", it) }
    }.toString()))
    fun arguments(input: JsonElement): JsonObject = (input as? JsonObject)?.also {
        require(it.keys.all { key -> key in setOf("media_id", "display_name") })
        require(it["media_id"] == null || it["media_id"] is JsonPrimitive && it["media_id"]!!.jsonPrimitive.isString)
        require(it["display_name"] == null || it["display_name"] is JsonPrimitive && it["display_name"]!!.jsonPrimitive.isString)
    } ?: throw IllegalArgumentException("invalid_media_arguments")
    fun selected(input: JsonElement): ChatMediaAsset? {
        val args = arguments(input)
        val id = args["media_id"]?.jsonPrimitive?.contentOrNull
        return registry.find(id)
    }
    fun fingerprint(input: JsonElement): String? {
        if (permissionMode() != AndroidControlPermissionMode.CONFIRM_CHANGES) return null
        val asset = runCatching { selected(input) }.getOrNull() ?: return null
        return digest("${registry.owner}|$SAVE_CHAT_MEDIA_TOOL|${asset.id}|${asset.file.length()}|${asset.file.lastModified()}|$input")
    }
    val tools = listOf(
        Tool(
            name = LIST_CHAT_MEDIA_TOOL,
            description = "List real images and videos attached to or generated in this conversation, with opaque media_id references. Does not browse device files, other chats, the gallery or phone screenshots.",
            parameters = { InputSchema.Obj(buildJsonObject {}) },
            systemPrompt = { _, _ -> "For a request to save or publish generated media, use save_media_to_gallery on its media_id once, then continue from that successful saved reference. Prefer the destination app's own create/post action and native photo picker. Do not detour to the system Gallery/share sheet or search filenames globally while the correct photo is already visible. In the destination picker use find_saved_media_in_picker to locally identify the actual saved image; a confident candidate returns frame-bound image coordinates for tap. Never assume the first thumbnail is correct merely because it is newest. If ambiguous, use the Onvyra album or inspect one fresh screen, then ask the user rather than repeatedly searching or saving duplicates. A listed/saved image is not a publishing result or a grant of the destination app's photo permission. Keep publishing approvals and honor subsequent corrections." },
            execute = { input ->
                if (!enabled()) failure("phone_control_disabled", "请先在设置中启用手机操控。")
                else if (input !is JsonObject || input.isNotEmpty()) failure("invalid_media_arguments", "此工具不接受路径、网址或其他会话参数。")
                else try {
                    refresh()
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("status", "succeeded"); put("media", JsonArray(registry.list().map { asset ->
                            JsonObject(asset.reference() + (registry.savedFor(asset)?.let {
                                mapOf("saved_gallery" to savedGalleryReference(it))
                            } ?: emptyMap()))
                        }))
                    }.toString()))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    failure("media_inventory_unavailable", "当前会话的媒体信息暂时无法读取，请稍后重试。")
                }
            },
        ),
        Tool(
            name = SAVE_CHAT_MEDIA_TOOL,
            description = "Copy one real image/video from this conversation into Pictures/Onvyra or Movies/Onvyra so other apps can select it. Omit media_id for the latest generated item; use list_chat_media for older items. Never accepts a path or URL. Success returns the actual content URI, filename, album and byte count, not a publishing result.",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("media_id", buildJsonObject { put("type", "string"); put("description", "An exact opaque media_id from this conversation; omit for latest generated media.") })
                put("display_name", buildJsonObject { put("type", "string"); put("description", "Optional filename only, without directory separators; the actual extension follows the encoded media.") })
            }) },
            needsApproval = { input ->
                val required = fingerprint(input)
                AndroidControlApprovals.remember(registry.owner, SAVE_CHAT_MEDIA_TOOL, input.toString(), required)
                required != null
            },
            execute = { input ->
                if (!enabled()) failure("phone_control_disabled", "请先在设置中启用手机操控。")
                else try {
                    val args = arguments(input)
                    refresh()
                    val asset = selected(input)
                    if (asset == null) failure("media_not_available_in_this_chat", "此会话没有对应的可保存图片或视频，请先查看 list_chat_media。")
                    else {
                        val expected = fingerprint(input)
                        val approved = AndroidControlApprovals.consume(registry.owner, SAVE_CHAT_MEDIA_TOOL, input.toString())
                        if (expected != null && approved != expected) failure("confirmation_required", "当前手机操控模式要求先确认保存操作。")
                        else {
                            val name = args["display_name"]?.jsonPrimitive?.contentOrNull
                            var reused = false
                            val result = exportLock.withLock {
                                val existing = registry.savedFor(asset, name)
                                if (existing != null && verifySaved(asset, existing)) {
                                    reused = true
                                    existing
                                } else export(asset, name).also {
                                    registry.rememberSaved(asset, name, it)
                                }
                            }
                            check(validSavedGalleryReference(result)) { "invalid_export_result" }
                            listOf(UIMessagePart.Text(buildJsonObject {
                                savedGalleryReference(result).forEach { (key, value) -> put(key, value) }
                                put("status", "succeeded"); put("media_id", asset.id)
                                put("source_fingerprint", asset.sourceFingerprint())
                                name?.let { put("requested_display_name", it) }
                                put("saved_to_gallery", true); put("published", false)
                                put("already_saved", reused)
                                put("next_step", "Open the destination app's native photo picker; use find_saved_media_in_picker for this media_id. Do not open a separate gallery/share route or keep searching when a confident visible match is available.")
                            }.toString()))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val permission = storagePermission(error)
                    when {
                        permission != null -> failure("storage_permission_required", "旧版系统需要你允许存储权限后再保存。", permission)
                        error is IllegalArgumentException -> failure("invalid_media_arguments", "图片、视频或文件名无效，请使用当前会话中的媒体引用。")
                        error is SecurityException -> failure("gallery_write_denied", "系统拒绝写入相册，图片未确认保存。")
                        else -> failure("gallery_write_failed", "相册写入未成功，请检查可用空间或稍后重试。")
                    }
                }
            },
        ),
    )
    if (locateInPicker == null) return tools
    return tools + Tool(
        name = FIND_SAVED_MEDIA_TOOL,
        description = "Locally locate this chat's saved image among currently visible thumbnails in the destination app's native media picker. Returns a confident frame-bound tap candidate, ambiguity or not-found; does not tap, publish, browse the gallery, or upload other photos to the model. Use after save_media_to_gallery succeeds and the destination app has opened its picker.",
        parameters = { InputSchema.Obj(buildJsonObject {
            put("media_id", buildJsonObject { put("type", "string"); put("description", "Exact media_id from the saved result; omit only for latest generated image.") })
        }) },
        execute = { input ->
            if (!enabled()) failure("phone_control_disabled", "请先启用手机操控。")
            else try {
                require(input is JsonObject && input.keys.all { it == "media_id" })
                refresh()
                val asset = selected(input)
                when {
                    asset == null -> failure("media_not_available_in_this_chat", "当前会话中没有这项素材。")
                    asset.kind != "image" -> failure("image_matching_required", "当前本地缩略图定位仅支持图片。")
                    else -> {
                        val saved = registry.savedFor(asset)
                        if (saved == null || !verifySaved(asset, saved)) {
                            registry.forgetSaved(asset.id)
                            failure("media_not_saved", "请先成功保存这项素材，再在目标应用的选图页定位。")
                        } else listOf(UIMessagePart.Text(JsonObject(locateInPicker(asset, saved) + mapOf(
                            "media_id" to JsonPrimitive(asset.id), "saved_gallery" to savedGalleryReference(saved),
                        )).toString()))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure("media_picker_unavailable", "当前选图画面无法安全定位，请刷新选图页或选择 Onvyra 相册；不要反复全局搜索或重新保存。") }
        },
    )
}

internal fun ChatMediaAsset.sourceFingerprint(): String = digest("$id|${file.length()}|${file.lastModified()}")

internal fun validSavedGalleryReference(saved: SavedChatMedia): Boolean {
    val uri = runCatching { URI(saved.contentUri) }.getOrNull() ?: return false
    return uri.scheme == "content" && uri.authority == "media" && uri.query == null && uri.fragment == null &&
        Regex("/(?:external|external_primary)/(?:images|video)/media/[0-9]+").matches(uri.path.orEmpty()) &&
        ((saved.mimeType.startsWith("image/") && saved.relativePath == "Pictures/Onvyra" && uri.path.contains("/images/")) ||
            (saved.mimeType.startsWith("video/") && saved.relativePath == "Movies/Onvyra" && uri.path.contains("/video/"))) && saved.sizeBytes > 0 &&
        saved.displayName.isNotBlank() && saved.displayName.none { it == '/' || it == '\\' || it.isISOControl() } &&
        saved.mimeType.startsWithAny("image/", "video/") && saved.savedAtMillis >= 0
}

internal fun savedGalleryReference(saved: SavedChatMedia): JsonObject = buildJsonObject {
    put("content_uri", saved.contentUri); put("display_name", saved.displayName)
    put("mime_type", saved.mimeType); put("size_bytes", saved.sizeBytes); put("album", saved.relativePath)
    put("album_name", "Onvyra"); put("media_store_id", saved.contentUri.substringAfterLast('/'))
    if (saved.savedAtMillis > 0) put("saved_at_epoch_ms", saved.savedAtMillis)
    saved.width?.takeIf { it > 0 }?.let { put("width", it) }
    saved.height?.takeIf { it > 0 }?.let { put("height", it) }
    put("picker_hint", "In the destination app choose recent photos or the Onvyra album; verify the thumbnail instead of assuming its grid position.")
}

private fun parseSavedGalleryReference(value: JsonObject): SavedChatMedia? = runCatching {
    SavedChatMedia(value.getValue("content_uri").jsonPrimitive.content,
        value.getValue("display_name").jsonPrimitive.content, value.getValue("mime_type").jsonPrimitive.content,
        value.getValue("size_bytes").jsonPrimitive.long, value.getValue("album").jsonPrimitive.content,
        value["saved_at_epoch_ms"]?.jsonPrimitive?.longOrNull ?: 0,
        value["width"]?.jsonPrimitive?.intOrNull, value["height"]?.jsonPrimitive?.intOrNull)
}.getOrNull()?.takeIf(::validSavedGalleryReference)

private fun String.startsWithAny(vararg values: String): Boolean = values.any { startsWith(it) }
private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
