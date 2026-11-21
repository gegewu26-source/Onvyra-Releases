package com.constx.onvyra.data.ai.prompts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskIntentPromptTest {
    @Test
    fun additionsAndCorrectionsKeepUnwithdrawnRequirementsAndCompletedEffects() {
        val prompt = buildTaskIntentPrompt(emptySet())
        assertTrue(prompt.contains("all earlier requirements that the user has not withdrawn"))
        assertTrue(prompt.contains("supersedes only the conflicting requirement"))
        assertTrue(prompt.contains("never repeat an already completed side effect"))
        assertTrue(prompt.contains("instead of claiming it was undone"))
    }

    @Test
    fun `latest corrections and outcome checks apply without media tools`() {
        val prompt = buildTaskIntentPrompt(emptySet())

        assertTrue(prompt.contains("latest actual user request and explicit correction"))
        assertTrue(prompt.contains("whether the observed result satisfies that goal"))
        assertTrue(prompt.contains("brief and internal"))
        assertFalse(prompt.contains("video_search"))
        assertFalse(prompt.contains("content_type="))
    }

    @Test
    fun `title alone must not select novels even when a reading tool is available`() {
        val prompt = buildTaskIntentPrompt(setOf("mcp__reader__search"))

        assertTrue(prompt.contains("A shared title alone does not select a novel, comic or animation"))
        assertTrue(prompt.contains("Use the current context"))
        assertTrue(prompt.contains("one concise choice"))
        assertFalse(prompt.contains("video_search"))
    }

    @Test
    fun `anime correction selects catalog and actual episodes rather than commentary`() {
        val prompt = buildTaskIntentPrompt(setOf("video_search", "video_resolve", "mcp__reader__search"))

        assertTrue(prompt.contains("动漫、动画、番剧"))
        assertTrue(prompt.contains("correction from novels to anime, use video_search first"))
        assertTrue(prompt.contains("content_type=anime"))
        assertTrue(prompt.contains("Preserve the requested season/episode"))
        assertTrue(prompt.contains("actual film or episodes"))
        assertTrue(prompt.contains("commentary/解说, edits/混剪, trailers"))
        assertTrue(prompt.contains("Even a playable candidate must match"))
    }

    @Test
    fun `url extraction alone does not advertise a title search tool`() {
        val prompt = buildTaskIntentPrompt(setOf("video_resolve", "web_fetch"))

        assertFalse(prompt.contains("video_search"))
        assertFalse(prompt.contains("content_type="))
        assertTrue(prompt.contains("latest actual user request"))
    }

    @Test
    fun `share download requests use the local builtin without manual page navigation`() {
        val prompt = buildTaskIntentPrompt(setOf("netdisk_parse", "netdisk_download", "netdisk_download_status"))
        assertTrue(prompt.contains("call netdisk_download directly with the supplied share text"))
        assertTrue(prompt.contains("Do not ask the user to open the netdisk page"))
        assertTrue(prompt.contains("only to inspect or parse uses netdisk_parse without downloading"))
        assertTrue(prompt.contains("queued status is not completion"))
        assertTrue(prompt.contains("netdisk_download_status before claiming a completed download"))
    }

    @Test
    fun `models without the download tool are not told to call it`() {
        assertFalse(buildTaskIntentPrompt(emptySet()).contains("netdisk_download"))
        assertFalse(buildTaskIntentPrompt(setOf("netdisk_parse")).contains("netdisk_download"))
    }
}
