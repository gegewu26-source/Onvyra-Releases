package me.rerere.androidcontrol
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalUiOcrTest {
    @Test fun bundledRecognizerReadsActualBitmapWithoutAVisionModel() = runBlocking {
        val bitmap=Bitmap.createBitmap(720,360,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=54f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
            drawText("LOCAL TEST 123",40f,100f,paint)
            drawText("中文测试",40f,210f,paint)
        }
        try {
            val lines=withTimeout(20_000) {localUiOcr(bitmap,7)}
            val text=lines.joinToString(" ") {it.jsonObject["text"]!!.jsonPrimitive.content}
            assertTrue("The bundled recognizer must read real rendered pixels: $text",text.contains("LOCAL TEST 123"))
            assertTrue("The Chinese model must be bundled and callable: $text",text.contains("中文"))
            lines.forEach {line->
                val node=line.jsonObject
                assertTrue(node["id"]!!.jsonPrimitive.content.startsWith("d7o"))
                val bounds=node["bounds"]!!.jsonArray.map {it.jsonPrimitive.int}
                assertTrue(bounds[0]>=0 && bounds[1]>=0 && bounds[2]<=720 && bounds[3]<=360)
                assertEquals("local_ocr",node["source"]!!.jsonPrimitive.content)
            }
            println("ANDROID_CONTROL_LOCAL_OCR=PASS; lines=${lines.size}; latin_and_chinese=true")
        } finally {bitmap.recycle()}
    }
}
