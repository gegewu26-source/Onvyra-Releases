package com.constx.onvyra.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownUtilsTest {
    @Test
    fun `thinking title uses the last complete bold line`() {
        assertEquals("第二步", "**第一步**\n过程说明\n** 第二步 **\n继续思考".extractThinkingTitle())
    }

    @Test
    fun `thinking title accepts surrounding whitespace and all line endings`() {
        assertEquals("标题", "前文\r\n  ** 标题 **  \r后文".extractThinkingTitle())
    }

    @Test
    fun `inline bold and unfinished streaming titles are not standalone titles`() {
        assertNull("说明 **行内加粗**\n**未完成\n**另一段*".extractThinkingTitle())
    }

    @Test
    fun `a later blank bold line does not revive an earlier title`() {
        assertNull("**先前标题**\n**   **".extractThinkingTitle())
    }

    @Test
    fun `repeated calls and changed streaming content keep the current title`() {
        val content = "**阶段一**\n正在处理"
        assertEquals("阶段一", content.extractThinkingTitle())
        assertEquals("阶段一", content.extractThinkingTitle())
        assertEquals("阶段二", "$content\n**阶段二**".extractThinkingTitle())
        assertNull("".extractThinkingTitle())
    }
}
