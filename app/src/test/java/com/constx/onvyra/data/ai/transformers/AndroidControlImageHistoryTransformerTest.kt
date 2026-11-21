package com.constx.onvyra.data.ai.transformers

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AndroidControlImageHistoryTransformerTest {
    private fun image(n:Int,marked:Boolean=true)=UIMessagePart.Image("file:///private/uploads/$n.jpg",
        metadata=if(marked) buildJsonObject {put("android_control_frame",true)} else null)
    private fun tool(n:Int,marked:Boolean=true)=UIMessagePart.Tool("call$n","screenshot","{}",listOf(
        UIMessagePart.Text("""{"ok":true,"task_id":"owned","display_id":8,"frame_id":"frame$n","screen":{}}"""),image(n,marked)))
    private fun images(messages:List<UIMessage>)=messages.flatMap {it.parts}.filterIsInstance<UIMessagePart.Tool>()
        .flatMap {it.output}.filterIsInstance<UIMessagePart.Image>()

    @Test fun requestKeepsLatestTwoFramesWhileOriginalSavedObjectsAndToolInputsRemainIntact() {
        val original=(1..6).map {UIMessage(role=MessageRole.ASSISTANT,parts=listOf(tool(it)))}
        val transformed=trimAndroidControlImageHistory(original)
        assertEquals(listOf(image(5),image(6)),images(transformed))
        assertEquals(6,images(original).size)
        assertEquals(original.map {it.id},transformed.map {it.id})
        assertEquals(original.map {it.getTools().single().input},transformed.map {it.getTools().single().input})
        assertEquals(2,original.first().getTools().single().output.size)
        assertTrue(transformed.first().getTools().single().output.last() is UIMessagePart.Text)
    }

    @Test fun userImagesAndOtherToolImagesAreNeverDroppedEvenWithSimilarMetadata() {
        val user=UIMessage(role=MessageRole.USER,parts=listOf(image(100)))
        val other=UIMessage(role=MessageRole.ASSISTANT,parts=listOf(
            UIMessagePart.Tool("other","media_search","{}",listOf(image(200)))))
        val original=listOf(user,other)+(1..5).map {UIMessage(role=MessageRole.ASSISTANT,parts=listOf(tool(it)))}
        val transformed=trimAndroidControlImageHistory(original)
        assertEquals(user,transformed[0]);assertEquals(other,transformed[1])
        assertEquals(3,images(transformed).size)
    }

    @Test fun reader16LegacyFramesUseControlResultIdentityAndUnrelatedScreenshotsRemain() {
        val unrelated=UIMessage(role=MessageRole.ASSISTANT,parts=listOf(
            UIMessagePart.Tool("generic","screenshot","{}",listOf(UIMessagePart.Text("browser screenshot"),image(200,false)))))
        val original=listOf(unrelated)+(1..4).map {UIMessage(role=MessageRole.ASSISTANT,parts=listOf(tool(it,false)))}
        val transformed=trimAndroidControlImageHistory(original)
        assertEquals(unrelated,transformed.first())
        assertEquals(listOf(image(200,false),image(3,false),image(4,false)),images(transformed))
    }

    @Test fun atMostTwoFramesReuseTheOriginalRequestList() {
        val original=listOf(UIMessage(role=MessageRole.ASSISTANT,parts=listOf(tool(1),tool(2))))
        assertSame(original,trimAndroidControlImageHistory(original))
    }
}
