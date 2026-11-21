package com.constx.onvyra.service

import org.junit.Assert.*
import org.junit.Test

class ChatNotificationPrivacyTest {
    @Test fun lockedProgressNeverReadsReasoningTextOrToolArgumentsOrExposesTheSender() {
        val presentation = chatNotificationPresentation(ChatNotificationVisibility.LOCKED, "Onvyra", "private persona", false) {
            error("Private parts must never be inspected")
        }!!
        assertEquals("Onvyra", presentation.first)
        assertEquals("锁定聊天正在进行", presentation.second.third)
        assertEquals("内容已隐藏", presentation.second.second)
        assertFalse(presentation.toString().contains("private persona"))
    }

    @Test fun lockedCompletionReplacesThePreviewRatherThanPuttingItInBigText() {
        val presentation = chatNotificationPresentation(ChatNotificationVisibility.LOCKED, "Onvyra", "private model", true) {
            error("Completion preview must stay unused")
        }!!
        assertEquals("锁定聊天的回复已完成", presentation.second.third)
        assertEquals("Onvyra", presentation.first)
    }

    @Test fun temporaryNotificationsRemainGenericAndClosedSessionsProduceNoNotification() {
        val temporary = chatNotificationPresentation(ChatNotificationVisibility.TEMPORARY, "Onvyra", "private sender", false) {
            error("Temporary transcript must not enter notification history")
        }!!
        assertEquals("临时聊天正在进行", temporary.second.third)
        assertEquals("Onvyra", temporary.first)
        assertNull(chatNotificationPresentation(ChatNotificationVisibility.HIDDEN, "Onvyra", "sender", true) {
            error("A disposed temporary chat cannot post a completion")
        })
    }

    @Test fun normalChatsKeepTheirExistingSenderStatusAndText() {
        val original = Triple("tool", "reading", "public progress")
        val presentation = chatNotificationPresentation(ChatNotificationVisibility.NORMAL, "Onvyra", "selected model", false) { original }!!
        assertEquals("selected model", presentation.first)
        assertSame(original, presentation.second)
    }
}
