package com.constx.onvyra.service

import com.constx.onvyra.data.model.MediaCreationParams

internal data class GifAnimationPlan(val mode: String, val frames: Int, val delayMs: Int) {
    val columns: Int get() = if (frames <= 4) 2 else 4
    val rows: Int get() = 2

    fun storyboardPrompt(userPrompt: String): String = """
        Create ONE raster animation storyboard contact sheet, not a GIF file.
        Layout is exactly $columns columns by $rows rows, $frames equally sized square edge-to-edge panels.
        Read panels from left to right, then top to bottom. No margins, grid lines, text, labels or captions.
        Each panel is the next frame of ONE continuous short looping animation. Keep the same subject,
        camera, scale, composition, lighting and background in every panel. Show a clearly different
        phase of the requested movement in consecutive panels. Keep the entire subject inside each
        panel. The final phase should lead naturally back to the first. Do not draw one large image
        across multiple panels or repeat an identical still image. The client will crop the panels
        and assemble a $frames-frame looping GIF with ${delayMs}ms between frames.

        Requested animation:
        $userPrompt
    """.trimIndent()
}

internal fun gifAnimationPlan(params: MediaCreationParams, modelId: String, nativeImageEndpoint: Boolean): GifAnimationPlan {
    val mode = params.animationMode ?: "auto"
    require(mode in setOf("auto", "native", "storyboard")) { "GIF模式只能为auto、native或storyboard。" }
    val frames = params.frameCount ?: 4
    val requestedDelay = params.frameDurationMs ?: 200
    require(requestedDelay in 20..2000) { "GIF帧间隔应为20至2000毫秒。" }
    val delay = ((requestedDelay + 5) / 10) * 10
    val documentedGifModel = Regex("^gpt-image-2\\.6-(?:sunburst|flare)(?:$|-)", RegexOption.IGNORE_CASE)
        .containsMatchIn(modelId.substringAfterLast('/'))
    val selected = if (mode == "auto") {
        if (nativeImageEndpoint && documentedGifModel) "native" else "storyboard"
    } else mode
    require(selected != "native" || nativeImageEndpoint) {
        "当前聊天生图接口未声明原生GIF输出；可用storyboard模式在同模型生成分镜后合成动图。"
    }
    if (selected == "native") require(frames in 2..16) { "原生GIF帧数应为2至16。" }
    else require(frames in setOf(4, 8)) { "本地分镜合成GIF请选择4帧（较快）或8帧。" }
    return GifAnimationPlan(selected, frames, delay)
}
