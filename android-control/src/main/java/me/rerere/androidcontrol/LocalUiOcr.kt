package me.rerere.androidcontrol

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private object LocalOcrModel {
    val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    val completionExecutor = Executor { it.run() }
    val permit = Semaphore(1)
    val initialized = AtomicBoolean(false)
}

internal data class LocalUiOcrResult(val labels: JsonArray, val status: String, val elapsedMs: Long)

/** Bundled model, offline; only used for a screen whose native hierarchy has no useful labels. */
internal suspend fun localUiOcr(bitmap: Bitmap, displayId: Int, originX: Int = 0, originY: Int = 0,
                                shouldStop: () -> Boolean = {false}): JsonArray =
    localUiOcrResult(bitmap, displayId, originX, originY, shouldStop).labels

internal suspend fun localUiOcrResult(bitmap: Bitmap, displayId: Int, originX: Int = 0, originY: Int = 0,
    shouldStop: () -> Boolean = {false}): LocalUiOcrResult {
    val began = android.os.SystemClock.elapsedRealtime()
    fun outcome(status: String, labels: JsonArray = JsonArray(emptyList())) =
        LocalUiOcrResult(labels, status, android.os.SystemClock.elapsedRealtime() - began)
    // A STOP may leave native inference finishing its owned frame. Do not enqueue
    // another model task until that completion has released the frame and permit.
    while (!LocalOcrModel.permit.tryAcquire()) {
        if (shouldStop()) return outcome("stopped")
        if (android.os.SystemClock.elapsedRealtime() - began >= 15_000) return outcome("busy")
        delay(50)
    }
    if (shouldStop()) { LocalOcrModel.permit.release(); return outcome("stopped") }
    val imageWidth = bitmap.width
    val imageHeight = bitmap.height
    val scale = minOf(1.0, 720.0 / imageWidth, 1280.0 / imageHeight)
    val recognitionWidth = (imageWidth * scale).roundToInt().coerceAtLeast(1)
    val recognitionHeight = (imageHeight * scale).roundToInt().coerceAtLeast(1)
    // Coordinates always map back to the original protected-window capture.
    // ML Kit owns this independent bitmap until completion, even after STOP.
    val owned = try {
        if (recognitionWidth == imageWidth && recognitionHeight == imageHeight)
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(bitmap, recognitionWidth, recognitionHeight, true)
    } catch (_: Exception) { null }
    if (owned == null) { LocalOcrModel.permit.release(); return outcome("invalid_frame") }
    val cold = !LocalOcrModel.initialized.getAndSet(true)
    val result = CompletableDeferred<LocalUiOcrResult>()
    try { LocalOcrModel.recognizer.process(InputImage.fromBitmap(owned,0))
        .addOnCompleteListener(LocalOcrModel.completionExecutor) { task ->
            try {
            if (!task.isSuccessful) {
                result.complete(outcome("failed"))
                return@addOnCompleteListener
            }
            val lines = task.result.textBlocks.flatMap { it.lines }.take(100).mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                val displayBounds = offsetOcrBounds(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom),
                    imageWidth,imageHeight,originX,originY,recognitionWidth,recognitionHeight) ?: return@mapNotNull null
                buildJsonObject {
                    put("id", "d${displayId}o${(line.text+"|"+displayBounds).hashCode().toUInt().toString(36)}")
                    put("type","ocr_text");put("source","local_ocr");put("text",line.text.take(1024))
                    put("bounds",JsonArray(displayBounds.map(::JsonPrimitive)))
                    put("clickable",false);put("editable",false);put("scrollable",false);put("enabled",true);put("visible",true)
                }
            }
            result.complete(outcome(if (lines.isEmpty()) "empty" else "recognized", JsonArray(lines)))
            } catch (_: Exception) { result.complete(outcome("failed")) }
            finally {
                android.util.Log.i("LocalUiOcr", "finished elapsed_ms=${android.os.SystemClock.elapsedRealtime()-began} cold=$cold input=${imageWidth}x$imageHeight inference=${recognitionWidth}x$recognitionHeight status=${result.getCompleted().status}")
                owned.recycle()
                LocalOcrModel.permit.release()
            }
        }
    } catch (_:Exception) {owned.recycle();LocalOcrModel.permit.release();return outcome("failed")}
    // The measured emulator cold inference needs ~9.8s. Wait for that one task;
    // keep STOP responsive rather than discarding it at 8s and spawning retries.
    val deadline=android.os.SystemClock.elapsedRealtime()+15_000
    while(android.os.SystemClock.elapsedRealtime()<deadline && !shouldStop()) {
        withTimeoutOrNull(100) {result.await()}?.let {return it}
    }
    return outcome(if (shouldStop()) "stopped" else "timed_out").also {
        android.util.Log.w("LocalUiOcr", "wait_finished elapsed_ms=${it.elapsedMs} cold=$cold status=${it.status}")
    }
}

/** Converts a window-local OCR rectangle to display pixels without accepting an invalid crop. */
internal fun offsetOcrBounds(bounds: List<Int>, imageWidth: Int, imageHeight: Int, originX: Int, originY: Int,
    recognitionWidth: Int = imageWidth, recognitionHeight: Int = imageHeight): List<Int>? {
    if (bounds.size != 4 || imageWidth <= 0 || imageHeight <= 0 || recognitionWidth <= 0 || recognitionHeight <= 0 ||
        bounds[0] < 0 || bounds[1] < 0 || bounds[0] >= bounds[2] || bounds[1] >= bounds[3] ||
        bounds[2] > recognitionWidth || bounds[3] > recognitionHeight) return null
    val translated = bounds.mapIndexed { index, value ->
        val scale = if (index % 2 == 0) imageWidth.toDouble() / recognitionWidth else imageHeight.toDouble() / recognitionHeight
        val original = if (index < 2) floor(value * scale).toLong() else ceil(value * scale).toLong()
        original + if (index % 2 == 0) originX else originY
    }
    if (translated.any { it !in 0L..Int.MAX_VALUE.toLong() }) return null
    return translated.map(Long::toInt)
}

/** OCR coordinates are actionable only for the exact, unchanged window that supplied the image. */
internal fun ocrWindowMatches(tree: JsonObject, windowId: Int?, bounds: List<Int>, revision: Long): Boolean {
    if (windowId == null || bounds.size != 4 || tree["revision"]?.jsonPrimitive?.longOrNull != revision) return false
    val top = (tree["windows"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it["type"]?.jsonPrimitive?.intOrNull == 1 } ?: return false
    return top["id"]?.jsonPrimitive?.intOrNull == windowId &&
        (top["bounds"] as? JsonArray)?.map { it.jsonPrimitive.intOrNull } == bounds
}
