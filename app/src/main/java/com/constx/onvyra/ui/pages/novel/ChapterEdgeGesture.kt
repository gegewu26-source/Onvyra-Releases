package com.constx.onvyra.ui.pages.novel

import kotlin.math.abs

internal enum class ChapterTurnDirection { PREVIOUS, NEXT }

/** Only an intentional drag beyond an edge can turn a chapter; layout/flings cannot. */
internal class ChapterEdgeGesture(private val thresholdPx: Float) {
    private var pull = 0f

    fun drag(unconsumedY: Float, atStart: Boolean, atEnd: Boolean,
        canPrevious: Boolean, canNext: Boolean) {
        val allowed = (unconsumedY > 0 && atStart && canPrevious) ||
            (unconsumedY < 0 && atEnd && canNext)
        if (!allowed) { pull = 0f; return }
        pull = if (pull * unconsumedY < 0) unconsumedY else pull + unconsumedY
    }

    fun release(): ChapterTurnDirection? {
        val direction = when {
            abs(pull) < thresholdPx -> null
            pull > 0 -> ChapterTurnDirection.PREVIOUS
            else -> ChapterTurnDirection.NEXT
        }
        pull = 0f
        return direction
    }

    fun reset() { pull = 0f }
}
