package me.rerere.androidcontrol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OcrCoordinatesTest {
    @org.junit.Test fun scaledRecognitionPreservesOriginalWindowPixelsAndRejectsOutsideEdges() {
        org.junit.Assert.assertEquals(listOf(97,288,982,1632),
            offsetOcrBounds(listOf(0,0,720,1093),885,1344,97,288,720,1093))
        org.junit.Assert.assertEquals(listOf(219,533,467,782),
            offsetOcrBounds(listOf(100,200,301,401),885,1344,97,288,720,1093))
        org.junit.Assert.assertNull(offsetOcrBounds(listOf(0,0,721,1093),885,1344,97,288,720,1093))
    }
    @Test fun floatingWindowCoordinatesIncludeItsActualOrigin() {
        assertEquals(listOf(130,200,230,250),offsetOcrBounds(listOf(10,20,110,70),600,500,120,180))
        assertEquals(listOf(10,20,110,70),offsetOcrBounds(listOf(10,20,110,70),600,500,0,0))
    }

    @Test fun invalidOrUninjectableCoordinatesAreDiscarded() {
        assertNull(offsetOcrBounds(listOf(-1,20,110,70),600,500,120,180))
        assertNull(offsetOcrBounds(listOf(10,20,601,70),600,500,120,180))
        assertNull(offsetOcrBounds(listOf(10,70,110,20),600,500,120,180))
        assertNull(offsetOcrBounds(listOf(10,20,110,70),600,500,-120,180))
        assertNull(offsetOcrBounds(listOf(10,20,110,70),600,500,Int.MAX_VALUE,180))
    }

    @Test fun unchangedCapturedWindowCanSupplyOcrTargets() {
        assertTrue(ocrWindowMatches(tree(),17,listOf(120,180,720,680),9))
    }

    @Test fun replacedMovedOrUnknownCapturedWindowCannotSupplyTargets() {
        assertFalse(ocrWindowMatches(tree(windowId=18),17,listOf(120,180,720,680),9))
        assertFalse(ocrWindowMatches(tree(left=121),17,listOf(120,180,720,680),9))
        assertFalse(ocrWindowMatches(tree(),null,listOf(120,180,720,680),9))
    }

    @Test fun ContentChangesDuringRecognitionInvalidateCoordinates() {
        assertFalse(ocrWindowMatches(tree(revision=10),17,listOf(120,180,720,680),9))
    }

    @Test fun newlyCoveringDialogCannotReuseUnderlyingWindowCoordinates() {
        val original=tree()
        val covered=buildJsonObject {
            put("revision",9)
            put("windows",JsonArray(listOf(window(20,120)) + original["windows"]!!.jsonArray))
        }
        assertFalse(ocrWindowMatches(covered,17,listOf(120,180,720,680),9))
    }

    private fun tree(windowId:Int=17,left:Int=120,revision:Long=9)=buildJsonObject {
        put("revision",revision)
        put("windows",JsonArray(listOf(window(windowId,left))))
    }

    private fun window(id:Int,left:Int)=buildJsonObject {
        put("id",id);put("type",1)
        put("bounds",JsonArray(listOf(left,180,720,680).map(::JsonPrimitive)))
    }
}
