package com.constx.onvyra.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskUpdatePolicyTest {
    @Test
    fun `complete short Chinese stop commands authorize stopping the whole task`() {
        listOf(
            "停止", "请停止", "请你停止任务", "停止当前任务", "停止当前的所有任务",
            "停止手机操作", "停止手机操控任务", "停止操控手机", "停止控制手机",
            "停下来", "停下", "停", "先停一下", "先停下来吧", "请你立即停止手机操作，谢谢！",
            "麻烦你先停止当前任务，谢谢你。", "终止当前任务", "中止任务", "取消整个任务",
            "结束本次任务", "都停下", "全部停止", "停止全部", "别继续了", "不用继续执行了",
            "不要再做了", "暂停一下", "请停止好吗？", "停止所有操作", "先停一停",
        ).forEach { assertTrue("Expected an explicit whole-task stop: $it", explicitWholeTaskStop(it)) }
    }

    @Test
    fun `complete short English and mixed language stop commands are recognized`() {
        listOf(
            "stop", "STOP!", "Please stop.", "stop now", "cancel current task", "abort",
            "abort the current task", "stop all tasks", "stop everything", "halt the entire task",
            "terminate this job", "stop phone control", "pause the current task please",
            "Could you stop the current task, please?", "can you stop?", "do not continue", "don't continue",
            "请 stop", "stop 当前任务", "取消 current task", "麻烦你 please abort 当前任务，谢谢", "stop all operations",
            "ＳＴＯＰ！", "please STOP phone control now",
        ).forEach { assertTrue("Expected an explicit whole-task stop: $it", explicitWholeTaskStop(it)) }
    }

    @Test
    fun `additions changed goals and refusal of an individual action never stop the phone session`() {
        listOf(
            "再加一个任务", "顺便把图片保存到相册", "把刚才的文案改成英文", "换一个任务，去查天气",
            "停止搜索改做X", "先停止搜索，然后打开小红书", "停止搜索", "取消发布改为保存",
            "取消发布", "取消这个操作", "停止这个操作", "拒绝这个操作但继续任务",
            "别点发布，先保存图片", "刚才那个要求不要了，改成生成小猫图", "不要执行删除，继续保存",
            "please stop searching and save the image", "cancel publishing, save instead",
            "cancel this operation", "stop searching", "stop the current task and then open the album",
            "停止 search，改成 save", "不要 stop，继续任务", "cancel current task 改做另一个任务",
        ).forEach { assertFalse("A task update cannot authorize a whole-task stop: $it", explicitWholeTaskStop(it)) }
    }

    @Test
    fun `negation explanatory and conditional language cannot become a stop command`() {
        listOf(
            "不要停止", "请不要停止任务", "不用停止", "别停", "不要停下来", "不要取消当前任务",
            "继续", "继续操作手机", "停止/继续", "停止条件是什么", "为什么会停止任务？",
            "如果出现错误再停止任务", "停止之前先保存图片", "请解释停止当前任务是什么意思",
            "我刚才说的不是停止", "stop?", "停止？", "why did you stop", "do not stop",
            "please don't stop", "never cancel the current task", "explain stop current task",
            "if it fails, stop the task", "should we stop the current task?", "continue",
            "\"stop\"", "“停止”", "`stop`", "停止；继续", "停止\n再生成一张图",
        ).forEach { assertFalse("Not an explicit whole-task stop: $it", explicitWholeTaskStop(it)) }
    }

    @Test
    fun `attachments or lengthy messages require replanning instead of lexical whole task cancellation`() {
        assertFalse(explicitWholeTaskStop("停止", hasAttachments = true))
        assertFalse(explicitWholeTaskStop("please stop current task", hasAttachments = true))
        assertFalse(explicitWholeTaskStop(" "))
        assertFalse(explicitWholeTaskStop("请".repeat(100) + "停止"))
        assertFalse(explicitWholeTaskStop("停止任务" + "!".repeat(100)))
        assertTrue(explicitWholeTaskStop("  请停止任务！  "))
    }

    @Test
    fun `pending user updates always defer even a plausible model stop request`() {
        assertEquals(ModelStopTaskDecision.DEFERRED, modelStopTaskDecision("停止任务", hasPendingUpdate = true))
        assertEquals(ModelStopTaskDecision.DEFERRED, modelStopTaskDecision("顺便保存图片", hasPendingUpdate = true))
        assertEquals(ModelStopTaskDecision.DEFERRED, modelStopTaskDecision("", hasPendingUpdate = true))
    }

    @Test
    fun `only the latest complete user stop command can authorize the model stop tool`() {
        assertEquals(ModelStopTaskDecision.ALLOWED, modelStopTaskDecision("请停止手机操作", hasPendingUpdate = false))
        assertEquals(ModelStopTaskDecision.ALLOWED, modelStopTaskDecision("Abort the current task", hasPendingUpdate = false))
        assertEquals(ModelStopTaskDecision.DENIED, modelStopTaskDecision("改为保存图片", hasPendingUpdate = false))
        assertEquals(ModelStopTaskDecision.DENIED, modelStopTaskDecision("继续操作", hasPendingUpdate = false))
        assertEquals(ModelStopTaskDecision.DENIED, modelStopTaskDecision("不要停止", hasPendingUpdate = false))
        assertEquals(ModelStopTaskDecision.DENIED, modelStopTaskDecision("停止搜索", hasPendingUpdate = false))
        assertEquals(ModelStopTaskDecision.DENIED, modelStopTaskDecision("停止", hasPendingUpdate = false, hasAttachments = true))
        assertEquals(ModelStopTaskDecision.DENIED, modelStopTaskDecision("", hasPendingUpdate = false))
    }
}
