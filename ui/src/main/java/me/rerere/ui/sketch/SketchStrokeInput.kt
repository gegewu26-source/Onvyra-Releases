package me.rerere.ui.sketch

import android.view.MotionEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * 画布直接收集 Ink 输入，并在同一个 Canvas 中画正在进行的笔画，不创建独立的 Surface。
 *
 * 历史点和当前点依次 [append]，每个输入事件结束调用一次 [update]，避免每个采样点都重算轮廓。
 * 所有方法都由画布的 UI 线程调用；[activeStrokes] 的几何改动通过 [revision] 通知重绘。
 */
@Stable
internal class SketchStrokeInput {
    private class ActiveStroke(
        val stroke: InProgressStroke,
        val startUptimeMillis: Long,
        val toolType: InputToolType,
        val strokeUnitLengthCm: Float,
    ) {
        // 每一笔复用自己的输入批次，历史采样和多个手指之间不会混在一起。
        val pending = MutableStrokeInputBatch()
        var lastElapsedTimeMillis = -1L
        var lastPosition = Offset.Unspecified
        var lastPressure = StrokeInput.NO_PRESSURE
    }

    private val active = LinkedHashMap<Long, ActiveStroke>()
    private val visibleStrokes = mutableStateListOf<InProgressStroke>()
    private val noPredictions = MutableStrokeInputBatch()

    val activeStrokes: List<InProgressStroke> get() = visibleStrokes

    var revision by mutableIntStateOf(0)
        private set

    fun contains(pointerId: Long): Boolean = active.containsKey(pointerId)

    /** MotionEvent 的坐标已经是画布坐标；这里只做同一套画纸变换并保留原始历史采样。 */
    fun onMotionEvent(
        event: MotionEvent,
        brushFactory: () -> Brush,
        toPaper: (Offset) -> Offset,
        strokeUnitLengthCm: Float,
    ): List<Stroke> {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelAll()
                val index = event.actionIndex
                start(
                    pointerId = event.getPointerId(index).toLong(),
                    brush = brushFactory(),
                    position = toPaper(Offset(event.getX(index), event.getY(index))),
                    uptimeMillis = event.eventTime,
                    toolType = when (event.getToolType(index)) {
                        MotionEvent.TOOL_TYPE_FINGER -> InputToolType.TOUCH
                        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> InputToolType.STYLUS
                        MotionEvent.TOOL_TYPE_MOUSE -> InputToolType.MOUSE
                        else -> InputToolType.UNKNOWN
                    },
                    pressure = event.getPressure(index),
                    strokeUnitLengthCm = strokeUnitLengthCm,
                )
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    appendSamples(event, index, toPaper)
                    update(event.getPointerId(index).toLong())
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index).toLong()
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    cancel(pointerId)
                } else {
                    appendSamples(event, index, toPaper)
                    finish(pointerId)?.let { return listOf(it) }
                }
            }

            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return emptyList()
    }

    private fun appendSamples(event: MotionEvent, index: Int, toPaper: (Offset) -> Offset) {
        val pointerId = event.getPointerId(index).toLong()
        if (!contains(pointerId)) return
        for (historyIndex in 0 until event.historySize) {
            append(
                pointerId = pointerId,
                position = toPaper(
                    Offset(event.getHistoricalX(index, historyIndex), event.getHistoricalY(index, historyIndex))
                ),
                uptimeMillis = event.getHistoricalEventTime(historyIndex),
                pressure = event.getHistoricalPressure(index, historyIndex),
            )
        }
        append(
            pointerId = pointerId,
            position = toPaper(Offset(event.getX(index), event.getY(index))),
            uptimeMillis = event.eventTime,
            pressure = event.getPressure(index),
        )
    }

    fun start(
        pointerId: Long,
        brush: Brush,
        position: Offset,
        uptimeMillis: Long,
        toolType: InputToolType,
        pressure: Float?,
        strokeUnitLengthCm: Float,
    ) {
        cancel(pointerId)
        if (!position.x.isFinite() || !position.y.isFinite()) return

        // Brush 在起笔时固定下来，之后切换颜色或粗细不会改掉已经开始的一笔。
        val stroke = InProgressStroke().apply { start(brush) }
        active[pointerId] = ActiveStroke(
            stroke = stroke,
            startUptimeMillis = uptimeMillis,
            toolType = toolType,
            strokeUnitLengthCm = strokeUnitLengthCm.takeIf { it.isFinite() && it > 0f }
                ?: StrokeInput.NO_STROKE_UNIT_LENGTH,
        )
        visibleStrokes += stroke
        append(pointerId, position, uptimeMillis, pressure)
        update(pointerId)
    }

    fun append(pointerId: Long, position: Offset, uptimeMillis: Long, pressure: Float?) {
        val current = active[pointerId] ?: return
        if (!position.x.isFinite() || !position.y.isFinite()) return

        val elapsedTimeMillis = (uptimeMillis - current.startUptimeMillis).coerceAtLeast(0L)
        // 部分设备会在下一次事件里重复提供历史点；不要让笔画的时间倒退。
        if (elapsedTimeMillis < current.lastElapsedTimeMillis) return
        val inputPressure = if (current.toolType == InputToolType.STYLUS) {
            pressure?.takeIf { it.isFinite() && it >= 0f }?.coerceIn(0f, 1f)
                ?: current.lastPressure.takeIf { it >= 0f }
                ?: 1f
        } else {
            // 手指的 MotionEvent pressure 不是触控笔压感，保留 Ink 原本的无压感行为。
            StrokeInput.NO_PRESSURE
        }
        if (elapsedTimeMillis == current.lastElapsedTimeMillis &&
            position == current.lastPosition && inputPressure == current.lastPressure
        ) return

        current.pending.add(
            current.toolType,
            position.x,
            position.y,
            elapsedTimeMillis,
            current.strokeUnitLengthCm,
            inputPressure,
        )
        current.lastElapsedTimeMillis = elapsedTimeMillis
        current.lastPosition = position
        current.lastPressure = inputPressure
    }

    fun update(pointerId: Long) {
        val current = active[pointerId] ?: return
        if (current.pending.isEmpty()) return
        current.stroke.enqueueInputs(current.pending, noPredictions)
        current.pending.clear()
        current.stroke.updateShape(current.lastElapsedTimeMillis)
        revision++
    }

    /** 抬笔后交出完整的 Ink 笔画；被取消或已经结束的手指不会产生笔画。 */
    fun finish(pointerId: Long): Stroke? {
        val current = active[pointerId] ?: return null
        update(pointerId)
        current.stroke.finishInput()
        // 让收笔和按时间平滑的画笔行为完成，保存的形状与最终显示的形状相同。
        current.stroke.updateShape()
        val completed = current.stroke.toImmutable()
        active.remove(pointerId)
        visibleStrokes.remove(current.stroke)
        revision++
        return completed
    }

    fun cancel(pointerId: Long) {
        val current = active.remove(pointerId) ?: return
        visibleStrokes.remove(current.stroke)
        current.pending.clear()
        current.stroke.clear()
        revision++
    }

    fun cancelAll() {
        if (active.isEmpty()) return
        active.values.forEach {
            it.pending.clear()
            it.stroke.clear()
        }
        active.clear()
        visibleStrokes.clear()
        revision++
    }
}
