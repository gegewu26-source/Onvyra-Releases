package com.constx.onvyra.data.ai.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.androidcontrol.AndroidControl

/** No gallery enumeration, cloud vision request, automatic tap or publishing action. */
internal class ChatMediaPickerLocator(context: Context, private val control: AndroidControl,
    private val owner: String, private val shouldReplan: () -> Boolean,
    private val onUserStop: (() -> Unit)?) {
    private val screenshotRoot = File(context.applicationContext.cacheDir, "android-control").canonicalFile.toPath()

    suspend fun locate(asset: ChatMediaAsset, saved: SavedChatMedia): JsonObject {
        val capture = control.captureMediaPickerFrame(owner, shouldReplan, onUserStop)
        if (capture["ok"]?.jsonPrimitive?.booleanOrNull != true) return JsonObject(
            compactAndroidControlResult(capture) + mapOf("status" to JsonPrimitive("unavailable")))
        val frameId = capture["frame_id"]?.jsonPrimitive?.contentOrNull ?: error("missing_owned_frame")
        val path = capture["screenshot_path"]?.jsonPrimitive?.contentOrNull ?: error("missing_owned_pixels")
        val screenshot = File(path).canonicalFile
        require(screenshot.toPath().startsWith(screenshotRoot) && screenshot.isFile)
        val imageWidth = capture.getValue("image_width").jsonPrimitive.int
        val imageHeight = capture.getValue("image_height").jsonPrimitive.int
        require(imageWidth in 1..1600 && imageHeight in 1..1600)
        val tree = capture.getValue("screen").jsonObject
        val nodes = AndroidControl.nodes(tree).toList()
        val labels = nodes.flatMap { node -> listOf("text", "description", "hint").mapNotNull {
            node[it]?.jsonPrimitive?.contentOrNull
        } }.joinToString(" ").take(20_000)
        if (!isVisibleMediaPicker(labels)) return buildJsonObject {
            put("status", "not_in_media_picker"); put("media_id", asset.id)
            put("message", "请先在目标应用打开其原生选图页，再调用本工具。不要打开系统相册的分享流程。");
            compactAndroidControlResult(capture)["screen"]?.let { put("screen", it) }
        }
        val origin = capture.getValue("image_origin").jsonArray.map { it.jsonPrimitive.int }
        val displayWidth = capture.getValue("display_image_width").jsonPrimitive.int
        val displayHeight = capture.getValue("display_image_height").jsonPrimitive.int
        val rectangles = nodes.asSequence().filter { node ->
            val type = node["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val resource = node["resourceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            type.contains("Image", true) || Regex("thumb|photo|media_item|image_item", RegexOption.IGNORE_CASE).containsMatchIn(resource)
        }.mapNotNull { node ->
            runCatching { pickerImageRect(node.getValue("bounds").jsonArray.map { it.jsonPrimitive.int },
                origin, displayWidth, displayHeight, imageWidth, imageHeight) }.getOrNull()
        }.toList()
        val result = withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val reference = decodePickerRaster(asset.file, 512)
            val visible = decodePickerRaster(screenshot, 1600)
            require(visible.width == imageWidth && visible.height == imageHeight)
            matchSavedMediaThumbnail(reference, visible, rectangles).also { currentCoroutineContext().ensureActive() }
        }
        if (shouldReplan()) return buildJsonObject {
            put("ok", true); put("status", "task_update_pending"); put("reason", "new_user_instruction")
            put("task_retained", true); put("display_retained", true); put("stop_performed", false)
        }
        val selectors = nodes.mapNotNull { node ->
            val type = node["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val resource = node["resourceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (node["checkable"]?.jsonPrimitive?.booleanOrNull != true && !type.contains("CheckBox", true) &&
                !Regex("check|select", RegexOption.IGNORE_CASE).containsMatchIn(resource)) return@mapNotNull null
            val rect = runCatching { pickerImageRect(node.getValue("bounds").jsonArray.map { it.jsonPrimitive.int },
                origin, displayWidth, displayHeight, imageWidth, imageHeight) }.getOrNull() ?: return@mapNotNull null
            PickerSelector(rect, node["checked"]?.jsonPrimitive?.booleanOrNull ?: node["selected"]?.jsonPrimitive?.booleanOrNull)
        }
        val selector = if (result.status == "found") result.candidates.firstOrNull()?.let {
            selectorForMatchedThumbnail(it.rect, selectors)
        } else null
        val status = if (result.status == "found" && selector?.checked == true) "already_selected" else result.status
        return buildJsonObject {
            put("status", status); put("media_id", asset.id); put("matching_method", "local_visible_thumbnail_pixels")
            put("selected_confirmed", status == "already_selected")
            put("other_gallery_images_uploaded", false); put("automatically_selected", false); put("published", false)
            put("evaluated_regions", result.evaluated)
            put("candidates", JsonArray(result.candidates.take(3).map { candidate -> buildJsonObject {
                put("visual_similarity", (candidate.score * 10000).toInt() / 10000.0)
                put("source", candidate.source)
                if (status == "found" && candidate == result.candidates.first()) {
                    val tapRect = selector?.rect ?: candidate.rect
                    put("tap", buildJsonObject {
                        put("x", tapRect.centerX); put("y", tapRect.centerY)
                        put("coordinate_space", "image"); put("frame_id", frameId)
                    })
                    put("tap_target", if (selector == null) "matching_thumbnail" else "selection_checkbox")
                }
            } }))
            put("message", when (status) {
                "found" -> "已在当前选图页找到明确匹配。使用第一个候选的 frame_id 和 image 坐标调用 tap（有勾选框时已优先定位勾选框），再核对选中状态。若打开图片预览，使用预览内的选择/下一步按钮。不要再搜索文件名、打开其他相册或重复保存。"
                "already_selected" -> "这项素材已经选中，不要再次点击而取消选择。直接核对下一步/完成按钮并继续当前任务。"
                "ambiguous" -> "当前有多个相近缩略图，未提供盲点坐标。请在本选图页切到 Onvyra 相册或让用户选择，不要反复全局搜索。"
                "not_distinctive" -> "图片缺少可区分的视觉特征，不能安全猜测。请按 Onvyra 相册及保存信息核对，必要时让用户选择。"
                else -> "当前可见缩略图中未找到可靠匹配。先在当前选图页选择 Onvyra 相册；仍没有结果时核对目标应用的照片访问授权，不要重新生成或重复保存。"
            })
            put("saved_gallery", savedGalleryReference(saved))
            compactAndroidControlResult(capture)["screen"]?.let { put("screen", it) }
        }
    }
}

private fun decodePickerRaster(file: File, longestSide: Int): PickerRaster {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (bounds.outWidth / options.inSampleSize > longestSide || bounds.outHeight / options.inSampleSize > longestSide) {
        options.inSampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: error("media_pixels_unavailable")
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        PickerRaster(bitmap.width, bitmap.height, pixels)
    } finally { bitmap.recycle() }
}

/** Only confirm a known app-owned MediaStore row; never enumerate the user's gallery. */
internal fun savedChatMediaStillReadable(context: Context, saved: SavedChatMedia, source: File? = null): Boolean {
    if (!validSavedGalleryReference(saved)) return false
    val resolver = context.contentResolver
    val uri = Uri.parse(saved.contentUri)
    val fields = buildList {
        add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.MIME_TYPE)
        if (Build.VERSION.SDK_INT >= 29) { add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME); add(MediaStore.MediaColumns.IS_PENDING) }
    }.toTypedArray()
    return runCatching {
        val exact = resolver.query(uri, fields, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == saved.displayName && cursor.getLong(1) == saved.sizeBytes &&
                cursor.getString(2) == saved.mimeType && (Build.VERSION.SDK_INT < 29 ||
                cursor.getString(3) == context.packageName && cursor.getInt(4) == 0)
        } ?: false
        if (!exact) false
        else if (source != null && saved.mimeType.startsWith("image/")) {
            val original = source.inputStream().use(::mediaContentDigest)
            val stored = resolver.openInputStream(uri)?.use(::mediaContentDigest)
            stored != null && MessageDigest.isEqual(original, stored)
        } else resolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
    }.getOrDefault(false)
}

private fun mediaContentDigest(input: InputStream): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Media verification interrupted")
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    return digest.digest()
}
