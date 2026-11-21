package com.constx.onvyra.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.model.Assistant
import com.constx.onvyra.data.model.InjectionPosition
import com.constx.onvyra.data.model.Lorebook
import com.constx.onvyra.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemModeCompatibilityTest {
    private fun text(message: UIMessage) = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun `system position is preserved and compatibility instruction precedes latest real user`() {
        val mode = PromptInjection.ModeInjection(content = "每次回复都叫我大哥")
        val first = UIMessage.user("older question")
        val last = UIMessage.user("current question")
        val messages = listOf(UIMessage.system("base system"), first, UIMessage.assistant("older answer"), last)
        val result = transformMessages(messages, Assistant(modeInjectionIds = setOf(mode.id)), listOf(mode), emptyList(), true)

        assertEquals("base system\n${mode.content}", text(result.first()))
        val compatibility = result.single { it.role == MessageRole.USER && it.isSynthetic }
        assertEquals(mode.content, text(compatibility))
        assertEquals(result.indexOf(last) - 1, result.indexOf(compatibility))
        assertEquals(first, result[1])
        assertEquals(last, result.last())
        assertEquals(4, messages.size)
        assertFalse(messages.any { it.isSynthetic })
    }

    @Test
    fun `compatibility instruction does not grow or split the tool chain between steps`() {
        val mode = PromptInjection.ModeInjection(content = "mode instruction")
        val question = UIMessage.user("call tool")
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "tool-1", toolName = "fixture", input = "{}", output = listOf(UIMessagePart.Text("result")),
        )))
        val assistant = Assistant(modeInjectionIds = setOf(mode.id))
        repeat(3) {
            val result = transformMessages(listOf(question, tool), assistant, listOf(mode), emptyList(), true)
            assertEquals(1, result.count { it.role == MessageRole.USER && it.isSynthetic })
            assertEquals(result.indexOf(question) + 1, result.indexOf(tool))
        }
    }

    @Test
    fun `standalone positions are not delivered a second time`() {
        for (position in listOf(InjectionPosition.TOP_OF_CHAT, InjectionPosition.BOTTOM_OF_CHAT, InjectionPosition.AT_DEPTH)) {
            val mode = PromptInjection.ModeInjection(content = "standalone", position = position)
            val result = transformMessages(listOf(UIMessage.user("question")),
                Assistant(modeInjectionIds = setOf(mode.id)), listOf(mode), emptyList(), true)
            assertEquals(position.name, 1, result.count { text(it) == mode.content })
        }
    }

    @Test
    fun `disabled or unselected modes and lorebooks are never copied as compatibility requirements`() {
        val disabled = PromptInjection.ModeInjection(content = "disabled", enabled = false)
        val unselected = PromptInjection.ModeInjection(content = "unselected")
        val entry = PromptInjection.RegexInjection(content = "lorebook text", constantActive = true)
        val lorebook = Lorebook(entries = listOf(entry))
        val result = transformMessages(listOf(UIMessage.user("question")),
            Assistant(modeInjectionIds = setOf(disabled.id), lorebookIds = setOf(lorebook.id)),
            listOf(disabled, unselected), listOf(lorebook), true)
        assertFalse(result.any { it.role == MessageRole.USER && it.isSynthetic })
        assertTrue(text(result.first()).contains(entry.content))
        assertFalse(result.any { text(it).contains(disabled.content) || text(it).contains(unselected.content) })
    }

    @Test
    fun `before and after positions and priority are retained without extra per-mode messages`() {
        val before = PromptInjection.ModeInjection(content = "before", position = InjectionPosition.BEFORE_SYSTEM_PROMPT)
        val high = PromptInjection.ModeInjection(content = "high", priority = 10)
        val low = PromptInjection.ModeInjection(content = "low")
        val modes = listOf(before, low, high)
        val result = transformMessages(listOf(UIMessage.system("base"), UIMessage.user("question")),
            Assistant(modeInjectionIds = modes.map { it.id }.toSet()), modes, emptyList(), true)
        assertEquals("before\nbase\nhigh\nlow", text(result.first()))
        assertEquals("high\nbefore\nlow", text(result.single { it.role == MessageRole.USER && it.isSynthetic }))
    }

    @Test
    fun `normal system delivery remains unchanged when compatibility is not requested`() {
        val mode = PromptInjection.ModeInjection(content = "mode instruction")
        val result = transformMessages(listOf(UIMessage.user("question")),
            Assistant(modeInjectionIds = setOf(mode.id)), listOf(mode), emptyList())
        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result.first().role)
        assertFalse(result.any { it.role == MessageRole.USER && it.isSynthetic })
    }
}
