package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.media.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaVideoSearchToolTest {
    private val model = Model(abilities = listOf(ModelAbility.TOOL))
    private fun privateSelection(sourceId: String = "missav") = MoviePlaybackSelection(
        MovieCatalogItem("fixture", "Fixture", detailUrl = "https://missav-primary.private-media.invalid/cn/fixture", sourceId = sourceId, sourceName = "MissAV"),
        VideoPlaybackSource("https://cdn.example.test/master.m3u8", VideoPlaybackKind.DIRECT, title = "Fixture"), 1, "main",
    )
    private suspend fun execute(tool: Tool): String =
        (tool.execute(buildJsonObject { put("title", "Fixture") }).single() as UIMessagePart.Text).text

    @Test fun lockedToolMetadataDoesNotAdvertisePrivateSourcesOrSearchFields() {
        val tool = createTypedVideoSearchTool(privateSourcesAvailable = { false }) { _, _, _, _ -> error("unused") }
        val metadata = tool.description + tool.systemPrompt(model, emptyList()) + (tool.parameters() as InputSchema.Obj).properties
        listOf("Jable", "MissAV", "SupJav", "Hanime1", "actress", "番号").forEach { assertFalse(metadata.contains(it, true)) }
        assertTrue(metadata.contains("movie"))
    }

    @Test fun privateResultWithForgedSourceIdIsRejectedWithoutTryingAdditionalFallbacks() = runBlocking {
        var fallback = false
        val tool = createTypedVideoSearchTool(
            privateSourcesAvailable = { false },
            discoverAndPlay = { _, _, _, _ -> fallback = true; privateSelection() },
        ) { _, _, _, _ -> privateSelection("forged") }
        val text = execute(tool)
        assertFalse(fallback)
        assertEquals("error", Json.parseToJsonElement(text).jsonObject["status"]!!.jsonPrimitive.content)
        assertFalse(text.contains("missav", true))
        assertFalse(text.contains("playback_id"))
    }

    @Test fun grantIsRecheckedAfterAResultEvenWhenToolWasCreatedWhileUnlocked() = runBlocking {
        var allowed = true
        val tool = createTypedVideoSearchTool(privateSourcesAvailable = { allowed }) { _, _, _, _ ->
            allowed = false
            privateSelection()
        }
        assertFalse(tool.description.contains("MissAV"))
        val text = execute(tool)
        assertFalse(text.contains("playback_id"))
        assertFalse(text.contains("missav", true))
        assertFalse(tool.systemPrompt(model, emptyList()).contains("MissAV"))
    }

    @Test fun ordinaryFilmsRemainAvailableAndPrivateWebCandidatesAreFiltered() = runBlocking {
        val ordinary = privateSelection("ordinary").let { it.copy(item = it.item.copy(
            detailUrl = "https://example.test/film", sourceName = "Ordinary")) }
        val ordinaryTool = createTypedVideoSearchTool(privateSourcesAvailable = { false }) { _, _, _, _ -> ordinary }
        assertEquals("ready", Json.parseToJsonElement(execute(ordinaryTool)).jsonObject["status"]!!.jsonPrimitive.content)
        val fallbackTool = createTypedVideoSearchTool(privateSourcesAvailable = { false }, webFallback = { _, _, _, _ ->
            MovieWebSearchOutcome(status = "unverified_sources", candidates = listOf(
                MovieWebCandidate("Private", "https://supjav-primary.private-media.invalid/zh/1.html"),
                MovieWebCandidate("Ordinary", "https://example.test/film"),
            ))
        }) { _, _, _, _ -> error("unavailable") }
        val text = execute(fallbackTool)
        assertFalse(text.contains("supjav", true))
        assertTrue(text.contains("https://example.test/film"))
    }
}
