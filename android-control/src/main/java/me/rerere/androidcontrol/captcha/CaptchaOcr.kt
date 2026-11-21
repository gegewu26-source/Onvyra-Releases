package me.rerere.androidcontrol.captcha

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline character-captcha OCR, ported from ddddocr's bundled model.
 *
 * Why on-device: the caller is the on-phone agent, so recognition has to work
 * when the phone has no network or when the captcha image contains the user's
 * account page. Nothing here uploads the capture.
 *
 * The model (`common_old.onnx`, MIT, ddddocr) expects a single-channel 64px-high
 * greyscale strip and emits one 8210-way distribution per time step; the CTC
 * collapse lives in [CaptchaCharset] so it is testable without ONNX.
 */
internal class CaptchaOcr(context: Context) {

    private val appContext = context.applicationContext
    private val loadLock = Mutex()
    private val inferenceLock = Mutex()
    private val initialized = AtomicBoolean(false)

    @Volatile private var session: OrtSession? = null
    @Volatile private var inputName: String? = null
    @Volatile private var charset: List<String>? = null
    @Volatile private var loadFailure: String? = null

    data class Read(
        val text: String,
        val status: String,
        val elapsedMs: Long,
        val steps: Int = 0,
        val meanConfidence: Float = 0f,
    )

    /**
     * Reads characters from an already-cropped capture. [whitelist] restricts the
     * decision to those characters only when the caller asked for it.
     */
    suspend fun read(bitmap: Bitmap, whitelist: Set<Char>? = null): Read =
        withContext(Dispatchers.Default) {
            val began = android.os.SystemClock.elapsedRealtime()
            fun outcome(status: String, text: String = "", steps: Int = 0, confidence: Float = 0f) =
                Read(text, status, android.os.SystemClock.elapsedRealtime() - began, steps, confidence)
            if (bitmap.width < 8 || bitmap.height < 8) return@withContext outcome("invalid_image")
            val ready = ensureLoaded() ?: return@withContext outcome(loadFailure ?: "model_unavailable")
            val activeCharset = charset ?: return@withContext outcome("charset_unavailable")

            // ddddocr resizes to height 64 keeping the aspect ratio, then greyscales.
            val targetHeight = 64
            val targetWidth = CaptchaCharset.recognitionWidth(bitmap.width, bitmap.height, targetHeight)
            val scaled = try {
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            } catch (_: Exception) { return@withContext outcome("invalid_image") }
            val pixels = IntArray(targetWidth * targetHeight)
            try {
                scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            } catch (_: Exception) {
                return@withContext outcome("invalid_image")
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            val input = FloatArray(targetWidth * targetHeight)
            for (index in pixels.indices) {
                val argb = pixels[index]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                // PIL's "L" conversion weights, matching ddddocr's preprocessing.
                input[index] = (r * 299 + g * 587 + b * 114) / 1000f / 255f
            }

            val scores = infer(ready, input, targetWidth, targetHeight)
                ?: return@withContext outcome("inference_failed")
            val text = CaptchaCharset.decodeRestricted(scores, activeCharset, whitelist)
            val confidence = meanPeakConfidence(scores)
            outcome(if (text.isEmpty()) "empty" else "recognized", text, scores.size, confidence)
        }

    /** ASCII digits/letters only, which is what the bundled model was trained for. */
    private suspend fun ensureLoaded(): OrtSession? = loadLock.withLock {
        if (initialized.get()) return@withLock session
        try {
            val environment = OrtEnvironment.getEnvironment()
            val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = environment.createSession(bytes, options)
            session = created
            inputName = created.inputNames.firstOrNull()
            charset = readCharset()
            if (charset == null || inputName == null) {
                loadFailure = "model_signature_unexpected"
                created.close()
                session = null
                initialized.set(true)
                return@withLock null
            }
            loadFailure = null
            finishedLoad()
            created
        } catch (error: Throwable) {
            loadFailure = "model_load_failed"
            android.util.Log.w("CaptchaOcr", "load failed: ${error.javaClass.simpleName}")
            initialized.set(true)
            null
        }
    }

    private fun finishedLoad() { initialized.set(true) }

    private fun readCharset(): List<String>? = try {
        val raw = appContext.assets.open(CHARSET_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val array = JSONArray(raw)
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) values.add(array.getString(index))
        values.takeIf { it.size > 1 && it[0].isEmpty() }
    } catch (_: Exception) { null }

    /**
     * Runs the model and returns per-step class scores as `[steps][classes]`.
     * The bundled graph is quantised and single-threaded per call; one permit
     * keeps peak memory bounded if several screens queue up.
     */
    private suspend fun infer(session: OrtSession, input: FloatArray, width: Int, height: Int): Array<FloatArray>? =
        inferenceLock.withLock {
            val name = inputName ?: return@withLock null
            val classes = charset?.size ?: return@withLock null
            val environment = OrtEnvironment.getEnvironment()
            var tensor: OnnxTensor? = null
            var result: OrtSession.Result? = null
            try {
                tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                    longArrayOf(1L, 1L, height.toLong(), width.toLong()))
                result = session.run(mapOf(name to tensor))
                val output = result.get(0) as? OnnxTensor ?: return@withLock null
                // The class axis is always the last one; every preceding axis is
                // either a batch of 1 or the time steps, so the flattened buffer
                // is exactly steps * classes long.
                val shape = output.info.shape
                if (shape.isEmpty() || shape.last().toInt() != classes) return@withLock null
                val total = shape.fold(1L) { acc, dim -> acc * dim }
                val steps = (total / classes).toInt()
                if (steps <= 0 || total != steps.toLong() * classes) return@withLock null
                val flat = FloatArray(total.toInt())
                output.floatBuffer.get(flat)
                Array(steps) { step -> flat.copyOfRange(step * classes, step * classes + classes) }
            } catch (error: Throwable) {
                android.util.Log.w("CaptchaOcr", "inference failed: ${error.javaClass.simpleName}")
                null
            } finally {
                try { result?.close() } catch (_: Exception) {}
                try { tensor?.close() } catch (_: Exception) {}
            }
        }

    /**
     * ddddocr reports confidence as the mean over time steps of the peak
     * softmax probability, so apply softmax rather than assuming the graph
     * emits probabilities. The graph emits raw scores.
     */
    private fun meanPeakConfidence(scores: Array<FloatArray>): Float {
        if (scores.isEmpty()) return 0f
        var total = 0.0
        var counted = 0
        for (row in scores) {
            var max = row[0]
            for (value in row) if (value > max) max = value
            var sum = 0.0
            for (value in row) sum += kotlin.math.exp((value - max).toDouble())
            if (sum > 0.0) { total += 1.0 / sum; counted++ }
        }
        return if (counted == 0) 0f else (total / counted).toFloat()
    }

    private companion object {
        const val MODEL_ASSET = "captcha/common_old.onnx"
        const val CHARSET_ASSET = "captcha/charset_old.json"
    }
}