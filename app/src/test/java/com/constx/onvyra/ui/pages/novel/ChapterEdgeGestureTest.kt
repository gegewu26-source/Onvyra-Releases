package com.constx.onvyra.ui.pages.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterEdgeGestureTest {
    @Test fun initialLayoutAndSmallPullDoNotTurnShortChapters() {
        val gesture = ChapterEdgeGesture(56f)
        assertNull(gesture.release())
        gesture.drag(-30f, atStart = true, atEnd = true, canPrevious = true, canNext = true)
        assertNull(gesture.release())
    }

    @Test fun upwardPullAtEndTurnsNextOnceOnRelease() {
        val gesture = ChapterEdgeGesture(56f)
        repeat(2) { gesture.drag(-30f, atStart = false, atEnd = true, canPrevious = true, canNext = true) }
        assertEquals(ChapterTurnDirection.NEXT, gesture.release())
        assertNull(gesture.release())
    }

    @Test fun downwardPullAtStartTurnsPrevious() {
        val gesture = ChapterEdgeGesture(56f)
        gesture.drag(60f, atStart = true, atEnd = false, canPrevious = true, canNext = true)
        assertEquals(ChapterTurnDirection.PREVIOUS, gesture.release())
    }

    @Test fun interiorDragsAndUnavailableAdjacentChapterNeverTurn() {
        val gesture = ChapterEdgeGesture(56f)
        gesture.drag(-120f, atStart = false, atEnd = false, canPrevious = true, canNext = true)
        assertNull(gesture.release())
        gesture.drag(-120f, atStart = false, atEnd = true, canPrevious = true, canNext = false)
        assertNull(gesture.release())
        gesture.drag(120f, atStart = true, atEnd = false, canPrevious = false, canNext = true)
        assertNull(gesture.release())
    }

    @Test fun returningIntoContentCancelsAccumulatedEdgePull() {
        val gesture = ChapterEdgeGesture(56f)
        gesture.drag(-70f, atStart = false, atEnd = true, canPrevious = true, canNext = true)
        gesture.drag(0f, atStart = false, atEnd = false, canPrevious = true, canNext = true)
        assertNull(gesture.release())
    }
}
