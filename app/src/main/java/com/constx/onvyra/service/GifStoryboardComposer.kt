package com.constx.onvyra.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.constx.onvyra.data.files.AnimatedGifEncoder
import com.constx.onvyra.data.files.GifAnimationVerifier
import com.constx.onvyra.data.files.GifPixels
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One generation yields a contact sheet; this step performs no network requests. */
internal suspend fun composeStoryboardGif(sheetBytes: ByteArray, plan: GifAnimationPlan): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(sheetBytes, 0, sheetBytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "模型未返回可读取的分镜图片。" }
    var sample = 1
    while (bounds.outWidth / sample > plan.columns * 512 || bounds.outHeight / sample > plan.rows * 512) sample *= 2
    val sheet = BitmapFactory.decodeByteArray(sheetBytes, 0, sheetBytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        ?: error("模型返回的分镜图片无法解码。")
    try {
        val cellWidth = sheet.width / plan.columns
        val cellHeight = sheet.height / plan.rows
        require(cellWidth >= 32 && cellHeight >= 32) { "模型返回的分镜尺寸过小。" }
        // A single wide picture is not assumed to be a four-panel animation.
        require(kotlin.math.abs(sheet.width.toDouble() / sheet.height - plan.columns.toDouble() / plan.rows) <= 0.25) {
            "模型返回的图片未遵循${plan.columns}×${plan.rows}分镜布局；请明确动作并重试，没有把单张图片伪装成GIF。"
        }
        val frames = List(plan.frames) { index ->
            currentCoroutineContext().ensureActive()
            val pixels = IntArray(cellWidth * cellHeight)
            sheet.getPixels(pixels, 0, cellWidth, index % plan.columns * cellWidth,
                index / plan.columns * cellHeight, cellWidth, cellHeight)
            GifPixels(cellWidth, cellHeight, pixels)
        }
        val bytes = AnimatedGifEncoder.encode(frames, plan.delayMs)
        currentCoroutineContext().ensureActive()
        val verified = GifAnimationVerifier.verify(bytes)
        check(verified.frames == plan.frames)
        return bytes
    } finally { sheet.recycle() }
}
