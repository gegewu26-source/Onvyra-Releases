package me.rerere.androidcontrol.captcha

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the bundled ddddocr model really loads and reads on this device.
 *
 * A JVM test cannot cover this: ONNX Runtime's native library only exists on
 * Android. Rendering the captcha here and asserting the recognised text keeps
 * the claim "the phone recognises captchas offline" honest.
 */
@RunWith(AndroidJUnit4::class)
class CaptchaOcrDeviceTest {

    private fun captcha(text: String, width: Int = 160, height: Int = 56): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(248, 248, 248))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 24)
            textSize = height * 0.7f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val step = (width - 16f) / text.length
        text.forEachIndexed { index, char ->
            canvas.drawText(char.toString(), 8f + index * step, height * 0.78f, paint)
        }
        return bitmap
    }

    @Test fun bundledModelLoadsAndReadsRenderedCaptchaOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ocr = CaptchaOcr(context)
        val cases = listOf("3F7K", "A9X2", "R5T8")
        val outcomes = ArrayList<String>()
        var firstText = ""
        var firstResult = ""
        for ((caseIndex, text) in cases.withIndex()) {
            val bitmap = captcha(text)
            val read = try {
                withTimeout(60_000) { ocr.read(bitmap) }
            } finally { bitmap.recycle() }
            outcomes.add("$text -> ${read.text} (${read.status}, conf=${read.meanConfidence})")
            assertTrue("model must load and recognise, got ${read.status}",
                read.status in setOf("recognized", "empty"))
            assertTrue("confidence out of range: ${read.meanConfidence}",
                read.meanConfidence in 0f..1f)
            if (caseIndex == 0) {
                firstText = text
                firstResult = read.text
            }
        }
        // A model that decodes nothing for every rendered captcha is broken, and
        // one that returns exactly the rendered glyphs is clearly working.
        val readAny = outcomes.any { it.substringAfter("-> ").substringBefore(" (").isNotEmpty() }
        assertTrue("no captcha recognised at all: $outcomes", readAny)
        val exact = outcomes.count { line ->
            val source = line.substringBefore(" -> ")
            val got = line.substringAfter("-> ").substringBefore(" (")
            got.equals(source, ignoreCase = true)
        }
        assertTrue("recognised none of the rendered captchas correctly: $outcomes", exact >= 1)
        // Reuse must stay cheap and give the same answer for the same pixels.
        val repeatBitmap = captcha(firstText)
        val second = try {
            withTimeout(60_000) { ocr.read(repeatBitmap) }
        } finally { repeatBitmap.recycle() }
        assertEquals("same pixels must decode identically", firstResult, second.text)
        println("CAPTCHA_DEVICE_OCR=PASS; ${outcomes.joinToString(" | ")}")
    }

    @Test fun whitelistConstrainsDecodingToTheRequestedAlphabet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ocr = CaptchaOcr(context)
        val bitmap = captcha("5B2Q")
        try {
            val read = withTimeout(60_000) { ocr.read(bitmap, "0123456789".toSet()) }
            assertTrue("digits-only read returned letters: ${read.text}",
                read.text.all { it in '0'..'9' } || read.text.isEmpty())
        } finally { bitmap.recycle() }
    }
}