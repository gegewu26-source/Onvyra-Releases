package me.rerere.ui.sketch

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.withTranslation
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import kotlin.math.roundToInt

/**
 * 画纸：跟着手指或触控笔落笔，并把 [state] 里的笔画和文字画出来。画纸按自己的比例放进可用区域的正中。
 *
 * Ink 负责生成笔画轮廓，正在画的笔迹也在同一张画布上立即显示，不依赖另一个 Surface 的交接。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SketchCanvas(state: SketchState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val xdpi = LocalContext.current.resources.displayMetrics.xdpi
        .takeIf { it.isFinite() && it > 0f } ?: (density.density * 160f)
    Box(
        modifier = modifier.onSizeChanged {
            if (it.width > 0 && it.height > 0) state.fit(it.toSize())
        },
        contentAlignment = Alignment.Center,
    ) {
        val paper = state.paperSize
        if (paper.isSpecified) {
            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            val input = remember(state) { SketchStrokeInput() }
            DisposableEffect(input, paper, canvasSize) {
                onDispose { input.cancelAll() }
            }
            Box(
                modifier = Modifier
                    .aspectRatio(paper.width / paper.height)
                    .onSizeChanged { canvasSize = it }
                    .clip(MaterialTheme.shapes.medium)
                    // 贴着屏幕边缘起笔时不要被当成系统的返回手势
                    .systemGestureExclusion()
                    .pointerInput(state, paper) {
                        awaitEachGesture {
                            // 抢在落笔层前面看到按下
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // 落笔说明颜色调好了
                            state.pickingColor = false
                            // 画笔由下方 Canvas 的原生触摸采样接手，这里管橡皮、文字和选择。
                            if (state.tool == SketchTool.Brush) return@awaitEachGesture
                            down.consume()
                            val scale = size.width / paper.width
                            // 画纸坐标的原点在正中
                            val center = Offset(size.width / 2f, size.height / 2f)
                            if (state.tool == SketchTool.Text) {
                                // 手指抬起的地方就是文字的位置，划出画纸或者被打断就不算
                                val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                                up.consume()
                                state.beginText((up.position - center) / scale, state.width.toPx() / scale)
                                return@awaitEachGesture
                            }
                            if (state.tool == SketchTool.Select) {
                                selectGesture(state, down, scale) { (it - center) / scale }
                                return@awaitEachGesture
                            }
                            val line = state.erase((down.position - center) / scale, state.width.toPx() / scale)
                            try {
                                // 只跟随落下的那根手指，其余的忽略
                                while (true) {
                                    val change = awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    // 两次事件之间系统攒下的采样点也用上，快速划过时线条才不会有棱角
                                    change.historical.forEach { state.extend(line, (it.position - center) / scale) }
                                    state.extend(line, (change.position - center) / scale)
                                    change.consume()
                                }
                            } finally {
                                // 手势被系统打断时也要收尾
                                state.finish(line)
                            }
                        }
                    },
            ) {
                val renderer = remember { createSketchStrokeRenderer() }
                val strokeTransform = remember { android.graphics.Matrix() }
                val selectionColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier.fillMaxSize().pointerInteropFilter { event ->
                        val starting = event.actionMasked == MotionEvent.ACTION_DOWN
                        if (canvasSize == IntSize.Zero ||
                            (starting && state.tool != SketchTool.Brush) ||
                            (!starting && input.activeStrokes.isEmpty())
                        ) {
                            false
                        } else {
                            val scale = canvasSize.width / paper.width
                            val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                            val completed = input.onMotionEvent(
                                event = event,
                                brushFactory = {
                                    state.brush.create(state.color, with(density) { state.width.toPx() } / scale)
                                },
                                toPaper = { (it - center) / scale },
                                strokeUnitLengthCm = scale * 2.54f / xdpi,
                            )
                            if (completed.isNotEmpty()) state.draw(completed)
                            true
                        }
                    },
                ) {
                    state.revision
                    input.revision
                    val scale = size.width / paper.width
                    drawSketch(state, scale, renderer)
                    strokeTransform.setScale(scale, scale)
                    translate(size.width / 2f, size.height / 2f) {
                        scale(scale, pivot = Offset.Zero) {
                            drawIntoCanvas { canvas ->
                                input.activeStrokes.forEach { stroke ->
                                    renderer.draw(canvas.nativeCanvas, stroke, strokeTransform)
                                }
                            }
                        }
                    }
                    drawSelection(state, scale, selectionColor)
                }
            }
        }
    }
}

private val LayerPaint = Paint()

// 屏幕上显示和导出图片用的是同一套绘制，[scale] 是画纸坐标到目标像素的比例
internal fun DrawScope.drawSketch(state: SketchState, scale: Float, renderer: CanvasStrokeRenderer) {
    drawRect(SketchDefaults.PaperColor)
    state.background?.let {
        drawImage(image = it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
    val steps = state.steps
    // 清空之前的笔画已经看不到了
    val first = steps.indexOfLast { it is SketchStep.Clear } + 1
    val placements = state.placements()
    // Ink 按最终的缩放来决定笔画边缘画得多细，画布上已有的变换它不会自己去读
    val strokeTransform = android.graphics.Matrix()
    // 笔画单独画在一层上：橡皮擦掉的只是这一层，下面的画纸和底图不受影响
    drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), LayerPaint) }
    translate(size.width / 2, size.height / 2) {
        scale(scale, pivot = Offset.Zero) {
            for (index in first until steps.size) {
                when (val step = steps[index]) {
                    is SketchStep.Item -> {
                        val placement = placements[step] ?: continue
                        translate(placement.offset.x, placement.offset.y) {
                            scale(placement.scale, pivot = Offset.Zero) {
                                drawIntoCanvas {
                                    when (step) {
                                        is SketchStep.Ink -> {
                                            strokeTransform.setScale(scale * placement.scale, scale * placement.scale)
                                            renderer.draw(it.nativeCanvas, step.stroke, strokeTransform)
                                        }

                                        is SketchStep.Text -> it.nativeCanvas.withTranslation(
                                            step.position.x,
                                            step.position.y,
                                        ) { step.layout.draw(this) }
                                    }
                                }
                            }
                        }
                    }

                    is SketchStep.Erase -> if (step.moved) {
                        drawPath(
                            path = step.path,
                            color = Color.Black,
                            style = Stroke(width = step.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            blendMode = BlendMode.Clear,
                        )
                    } else {
                        drawCircle(
                            color = Color.Black,
                            radius = step.width / 2,
                            center = step.start,
                            blendMode = BlendMode.Clear,
                        )
                    }

                    is SketchStep.Transform, SketchStep.Clear -> {}
                }
            }
        }
    }
    drawIntoCanvas { it.restore() }
}
