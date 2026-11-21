package com.constx.onvyra.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatComposerActionTest {
    @Test fun activeTaskWithNoDraftShowsAndDispatchesStop() {
        assertEquals(ChatComposerAction.STOP, chatComposerAction(loading = true, empty = true))
    }

    @Test fun activeTaskWithTextOrAttachmentsSendsSupplementWithoutStopping() {
        // ChatInputState.isEmpty is false for either typed text or an attachment.
        assertEquals(ChatComposerAction.SEND, chatComposerAction(loading = true, empty = false))
    }

    @Test fun idleComposerNeverDispatchesStop() {
        assertEquals(ChatComposerAction.SEND, chatComposerAction(loading = false, empty = true))
        assertEquals(ChatComposerAction.SEND, chatComposerAction(loading = false, empty = false))
    }
}
