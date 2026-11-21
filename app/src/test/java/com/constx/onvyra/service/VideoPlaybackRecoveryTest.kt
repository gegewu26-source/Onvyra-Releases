package com.constx.onvyra.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.media.MovieContentType
import com.constx.onvyra.data.media.VideoPreviewPayload
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class VideoPlaybackRecoveryTest {
    @Test fun currentTurnExecutedVideoToolCanRecoverWithoutChangingHistory() {
        val user = UIMessage.user("Find this film")
        val reply = resultMessage("current-preview")
        val messages = listOf(user, reply)
        val target = videoRecoveryTarget(messages, "current-preview")!!
        assertEquals(user.id, target.userMessageId)
        assertEquals("current-preview", target.preview.playbackId)
        assertEquals(listOf(user, reply), messages)
        assertEquals(1, messages.count { it.role == MessageRole.USER })
    }

    @Test fun stalePlayerFromAnEarlierUserTurnCannotRestartTheModel() {
        val old = listOf(UIMessage.user("Old film"), resultMessage("old-preview"))
        assertNull(videoRecoveryTarget(old + UIMessage.user("New question"), "old-preview"))
        assertNull(videoRecoveryTarget(old + UIMessage.user("New film") + resultMessage("new-preview"), "old-preview"))
    }

    @Test fun removedBranchAndNonVideoOrUnexecutedToolsCannotRecover() {
        val user = UIMessage.user("Film")
        assertNull(videoRecoveryTarget(listOf(user, resultMessage("branch-b")), "branch-a"))
        assertNull(videoRecoveryTarget(listOf(user, resultMessage("preview", name = "web_fetch")), "preview"))
        assertNull(videoRecoveryTarget(listOf(user, UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = "pending", toolName = "video_search", input = "{}")))), "preview"))
        assertNull(videoRecoveryTarget(listOf(resultMessage("preview")), "preview"))
    }

    @Test fun malformedAndFailedVideoResultsAreNotPlaybackRecoveryTargets() {
        val user = UIMessage.user("Film")
        val bad = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "bad", toolName = "video_search", input = "{}",
            output = listOf(UIMessagePart.Text("""{"type":"video_preview","status":"error","playable":false,"playback_id":"preview"}""")),
        )))
        assertNull(videoRecoveryTarget(listOf(user, bad), "preview"))
        assertNull(videoRecoveryTarget(listOf(user, resultMessage("preview")), ""))
    }

    @Test fun duplicateCallbacksAndReplacementPlayersCannotCauseRepeatedChargesInOneTurn() {
        val gate = VideoRecoveryRequestGate()
        val conversation = Uuid.random(); val turn = Uuid.random()
        assertTrue(gate.schedule(conversation, turn))
        assertFalse(gate.schedule(conversation, turn))
        assertTrue(gate.start(conversation, turn))
        assertFalse(gate.start(conversation, turn))
        gate.finish(conversation, turn)
        assertFalse(gate.schedule(conversation, turn))
    }

    @Test fun cancelledWaitingRequestDoesNotConsumeTheTurnAndOtherTurnsRemainIndependent() {
        val gate = VideoRecoveryRequestGate()
        val conversation = Uuid.random(); val turn = Uuid.random()
        assertTrue(gate.schedule(conversation, turn))
        gate.finish(conversation, turn)
        assertTrue(gate.schedule(conversation, turn))
        assertTrue(gate.start(conversation, turn))
        assertTrue(gate.schedule(conversation, Uuid.random()))
        assertTrue(gate.schedule(Uuid.random(), turn))
    }

    @Test fun recoveryPromptDoesNotExposeStreamLocatorAndExplicitlyAsksForNewSources() {
        val preview = VideoPreviewPayload("https://source.example/watch?token=private", "Film", "local-secret-id")
        val prompt = videoRecoveryRuntimePrompt(preview)
        assertTrue(prompt.contains("actually failed"))
        assertTrue(prompt.contains("video_find_sources"))
        assertTrue(prompt.contains("web_fetch"))
        assertTrue(prompt.contains("video_resolve"))
        assertTrue(prompt.contains("not a new user message"))
        assertFalse(prompt.contains("private"))
        assertFalse(prompt.contains("local-secret-id"))
        assertFalse(prompt.contains("source.example"))
    }

    @Test fun animeRecoveryKeepsItsTypeAndEpisodeInsteadOfRestartingAsAGenericFilmSearch() {
        val preview = VideoPreviewPayload("https://source.example/watch", "动画作品", "typed-preview",
            catalogEpisode = 3, catalogYear = 2024, requestedContentType = MovieContentType.ANIME)
        val prompt = videoRecoveryRuntimePrompt(preview)
        assertTrue(prompt.contains("content_type=anime in video_search and video_resolve"))
        assertTrue(prompt.contains("catalog episode was 3"))
        assertTrue(prompt.contains("release year was 2024"))
        assertTrue(prompt.contains("unless the latest actual user correction changes it"))
        assertTrue(prompt.contains("requested full works or episodes"))
        assertFalse(prompt.contains("requested film"))
    }

    @Test fun legacyPreviewDoesNotOverrideTheLatestUserMediumWithTheGeneralVideoDefault() {
        val prompt = videoRecoveryRuntimePrompt(VideoPreviewPayload("https://source.example/watch", "作品", "legacy-preview"))
        assertTrue(prompt.contains("latest actual user request to choose content_type"))
        assertFalse(prompt.contains("Preserve content_type=general_video"))
        assertFalse(prompt.contains("requested film"))
    }

    @Test fun parsedCurrentAnimeResultKeepsTheTypeInTheRecoveryHandoff() {
        val result = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "typed-call", toolName = "video_search", input = "{}",
            output = listOf(UIMessagePart.Text("""{"type":"video_preview","status":"ready","playable":true,"original_url":"https://source.example/watch","title":"动画作品","playback_id":"typed-preview","requested_content_type":"anime","catalog_episode":3}""")),
        )))
        val target = videoRecoveryTarget(listOf(UIMessage.user("找这部动漫第三集"), result), "typed-preview")!!
        assertEquals(MovieContentType.ANIME, target.preview.requestedContentType)
        assertEquals(3, target.preview.catalogEpisode)
        assertTrue(videoRecoveryRuntimePrompt(target.preview).contains("content_type=anime"))
    }

    private fun resultMessage(id: String, name: String = "video_search") = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool(toolCallId = "call-$id", toolName = name, input = "{}",
            output = listOf(UIMessagePart.Text("""{"type":"video_preview","status":"ready","playable":true,"original_url":"https://source.example/watch","title":"Film","playback_id":"$id"}""")))),
    )
}
