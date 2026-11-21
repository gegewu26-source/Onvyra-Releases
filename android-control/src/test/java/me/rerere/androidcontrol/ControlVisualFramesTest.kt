package me.rerere.androidcontrol

import org.junit.Assert.*
import org.junit.Test

class ControlVisualFramesTest {
    private val bounds = listOf(40, 90, 840, 490)

    @Test fun repeatedReadReusesFrameButAnActionAlwaysRequiresNewObservation() {
        val state = ControlVisualFrames()
        val first = state.observe("pixels", 3, bounds, 1, 100)
        assertFalse(first.unchanged)
        assertEquals(first.frame, state.cached(3, bounds, 1, 101))
        state.markAction()
        assertNull(state.cached(3, bounds, 1, 102))
        val next = state.observe("pixels", 3, bounds, 1, 103)
        assertTrue(next.unchanged); assertTrue(next.actionUnchanged); assertFalse(next.blocked)
    }

    @Test fun onlyTwoUnchangedActionsBlockAndANewPictureRestoresProgress() {
        val state = ControlVisualFrames()
        state.observe("a", 3, bounds, 1, 1)
        repeat(5) { assertFalse(state.observe("a", 3, bounds, 1, 2).blocked) }
        state.markAction(); assertFalse(state.observe("a", 3, bounds, 1, 3).blocked)
        state.markAction(); assertTrue(state.observe("a", 3, bounds, 1, 4).blocked)
        assertFalse(state.observe("b", 3, bounds, 1, 5).blocked)
    }

    @Test fun imagePixelsUseBoundOriginAndRejectStaleRotationOrFrame() {
        val state = ControlVisualFrames()
        val first = state.observe("a", 3, bounds, 1, 1).frame
        assertEquals(140 to 290, state.imagePoint(first.id, 100, 200, 3, bounds, 1))
        for (wrong in listOf({ state.imagePoint("invented", 100, 200, 3, bounds, 1) },
            { state.imagePoint(first.id, 100, 200, 4, bounds, 1) },
            { state.imagePoint(first.id, 100, 200, 3, listOf(0,0,400,800), 1) },
            { state.imagePoint(first.id, 100, 200, 3, bounds, 2) })) {
            try { wrong(); fail("A stale image cannot authorize coordinates") }
            catch (e: IllegalStateException) { assertEquals("stale_screenshot_frame_refresh_required", e.message) }
        }
        try { state.imagePoint(first.id, 800, 200, 3, bounds, 1); fail("Outside crop") }
        catch (e: IllegalArgumentException) { assertEquals("image_coordinates_out_of_bounds", e.message) }
    }

    @Test fun revisionOrWindowChangeInvalidatesCaptureCache() {
        val state = ControlVisualFrames()
        state.observe("a", 3, bounds, 1, 100)
        assertNull(state.cached(3, bounds, 1, 1601))
        assertNull(state.cached(3, bounds, 2, 101))
        assertNull(state.cached(4, bounds, 1, 101))
    }

    @Test fun aPreviousTaskCannotReuseItsFirstFrameIdInANewTask() {
        val old = ControlVisualFrames().observe("a",3,bounds,1,100).frame
        val next = ControlVisualFrames()
        val current = next.observe("a",3,bounds,1,100).frame
        assertNotEquals(old.id,current.id)
        try { next.imagePoint(old.id,100,100,3,bounds,1); fail("Old task frame") }
        catch (e: IllegalStateException) { assertEquals("stale_screenshot_frame_refresh_required",e.message) }
    }

    @Test fun scaledLandscapeFrameMapsPixelsToItsActualWindowAndEdges() {
        val state=ControlVisualFrames()
        val window=listOf(30,80,2830,1352)
        val frame=state.observe("jpeg",3,window,4,100,1280,581).frame
        assertEquals(30 to 80,state.imagePoint(frame.id,0,0,3,window,4))
        assertEquals(1430 to 714,state.imagePoint(frame.id,640,290,3,window,4))
        assertEquals(2827 to 1349,state.imagePoint(frame.id,1279,580,3,window,4))
        try {state.imagePoint(frame.id,1280,0,3,window,4);fail("Uploaded image right edge is exclusive")}
        catch (e:IllegalArgumentException) {assertEquals("image_coordinates_out_of_bounds",e.message)}
    }

    @Test fun scaledPortraitFrameKeepsWindowOffsetAndRejectsRotatedFrame() {
        val state=ControlVisualFrames()
        val window=listOf(20,40,1292,2840)
        val frame=state.observe("portrait",7,window,8,100,581,1280).frame
        assertEquals(654 to 1440,state.imagePoint(frame.id,290,640,7,window,8))
        try {state.imagePoint(frame.id,290,640,7,listOf(20,40,2820,1312),8);fail("Rotation invalidates geometry")}
        catch (e:IllegalStateException) {assertEquals("stale_screenshot_frame_refresh_required",e.message)}
    }
}
