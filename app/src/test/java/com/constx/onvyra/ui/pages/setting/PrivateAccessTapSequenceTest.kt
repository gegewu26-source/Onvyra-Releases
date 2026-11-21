package com.constx.onvyra.ui.pages.setting

import com.constx.onvyra.ui.pages.setting.PrivateAccessTapSequence.Target.LOGO
import com.constx.onvyra.ui.pages.setting.PrivateAccessTapSequence.Target.VERSION
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAccessTapSequenceTest {
    private val sequence = listOf(LOGO, LOGO, LOGO, VERSION, VERSION, VERSION, LOGO, LOGO, LOGO)

    @Test fun onlyTheNinthTapOfTheExactSequenceOpensTheEntry() {
        val gesture = PrivateAccessTapSequence()
        sequence.dropLast(1).forEachIndexed { index, target ->
            assertFalse(gesture.tap(target, index * 100L))
        }
        assertTrue(gesture.tap(LOGO, 800L))
    }

    @Test fun completionRequiresANewFullSequenceBeforeOpeningAgain() {
        val gesture = PrivateAccessTapSequence()
        repeat(2) { run ->
            sequence.forEachIndexed { index, target ->
                val opened = gesture.tap(target, run * 1_000L + index * 100L)
                if (index == sequence.lastIndex) assertTrue(opened) else assertFalse(opened)
            }
        }
    }

    @Test fun wrongTargetClearsPartialProgress() {
        val gesture = PrivateAccessTapSequence()
        assertFalse(gesture.tap(LOGO, 0L))
        assertFalse(gesture.tap(LOGO, 100L))
        assertFalse(gesture.tap(VERSION, 200L))
        sequence.forEachIndexed { index, target ->
            val opened = gesture.tap(target, 300L + index * 100L)
            if (index == sequence.lastIndex) assertTrue(opened) else assertFalse(opened)
        }
    }

    @Test fun ordinaryRepeatedLogoTapsNeverOpenTheEntry() {
        val gesture = PrivateAccessTapSequence()
        repeat(30) { assertFalse(gesture.tap(LOGO, it * 100L)) }
    }

    @Test fun aGapOverTenSecondsDiscardsEarlierTaps() {
        val gesture = PrivateAccessTapSequence()
        sequence.dropLast(1).forEachIndexed { index, target ->
            assertFalse(gesture.tap(target, index * 100L))
        }
        assertFalse(gesture.tap(LOGO, 10_701L))
        assertFalse(gesture.tap(LOGO, 10_801L))
    }

    @Test fun tenSecondsIsStillAnAllowedGap() {
        val gesture = PrivateAccessTapSequence()
        sequence.forEachIndexed { index, target ->
            val opened = gesture.tap(target, index * 10_000L)
            if (index == sequence.lastIndex) assertTrue(opened) else assertFalse(opened)
        }
    }

    @Test fun aClockGoingBackwardsDiscardsProgress() {
        val gesture = PrivateAccessTapSequence()
        sequence.dropLast(1).forEachIndexed { index, target ->
            assertFalse(gesture.tap(target, 1_000L + index * 100L))
        }
        assertFalse(gesture.tap(LOGO, 500L))
    }

    @Test fun explicitResetDiscardsProgress() {
        val gesture = PrivateAccessTapSequence()
        sequence.take(6).forEachIndexed { index, target ->
            assertFalse(gesture.tap(target, index * 100L))
        }
        gesture.reset()
        repeat(3) { assertFalse(gesture.tap(LOGO, 600L + it * 100L)) }
    }
}
