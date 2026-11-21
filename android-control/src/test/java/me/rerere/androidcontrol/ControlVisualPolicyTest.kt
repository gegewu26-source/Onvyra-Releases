package me.rerere.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class ControlVisualPolicyTest {
    @Test fun uploadsFitLandscapePortraitWithoutUpscalingOrDistorting() {
        assertEquals(1280 to 581,controlUploadSize(2800,1272))
        assertEquals(581 to 1280,controlUploadSize(1272,2800))
        assertEquals(800 to 400,controlUploadSize(800,400))
    }

    @Test fun nativeAndBatchActionsNeverCaptureBetweenSteps() {
        assertFalse(shouldObserveVisualAction(false,false,"tap",4,null))
        assertFalse(shouldObserveVisualAction(true,false,"tap",4,null))
        assertFalse(shouldObserveVisualAction(true,true,"tap",0,null))
        assertFalse(shouldObserveVisualAction(true,false,"input_text",0,null))
        assertFalse(shouldObserveVisualAction(true,false,"tap",0,false))
        assertTrue(shouldObserveVisualAction(true,false,"tap",0,null))
        assertTrue(shouldObserveVisualAction(true,false,"press_key",0,null))
        assertTrue(shouldObserveVisualAction(false,false,"swipe",0,true))
    }
}
