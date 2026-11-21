package com.constx.onvyra.data.ai.prompts

import com.constx.onvyra.data.datastore.Settings
import me.rerere.mediagen.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaGenerationPromptsTest {
    @Test fun guidanceUsesTheRequestedMediaKindAndPreservesTheWholeTask() {
        val settings = Settings(
            imageGenerationPrompt = "  Image constraints  ",
            videoGenerationPrompt = "\nVideo constraints\n",
        )
        val task = "  用户任务\n保留 {literal} 和最后一行  "
        assertEquals("Image constraints\n\n$task", settings.mediaGenerationPromptFor(MediaKind.IMAGE, task))
        assertEquals("Video constraints\n\n$task", settings.mediaGenerationPromptFor(MediaKind.VIDEO, task))
    }

    @Test fun clearingGuidanceSendsOnlyTheUnmodifiedTask() {
        val settings = Settings(imageGenerationPrompt = "", videoGenerationPrompt = " \n\t")
        val task = "Original task\n"
        assertEquals(task, settings.mediaGenerationPromptFor(MediaKind.IMAGE, task))
        assertEquals(task, settings.mediaGenerationPromptFor(MediaKind.VIDEO, task))
    }
}
