package com.constx.onvyra.data.ai.tools

import com.constx.onvyra.data.media.*
import com.constx.onvyra.security.PrivateMediaAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** No real catalogue, model API, playback download or account is used by these regressions. */
class UnlockedVideoCatalogRegressionTest {
    private var allowed = false
    private val model = Model(abilities = listOf(ModelAbility.TOOL))
    @Before fun setup() { PrivateMediaAccess.installVerifier { allowed } }
    @After fun restore() { PrivateMediaAccess.installVerifier { false } }

    private fun fixture(private: Boolean): MoviePlaybackSelection {
        val item = MovieCatalogItem("fixture", "Fixture", year = null, category = null,
            detailUrl = if (private) "https://missav-primary.private-media.invalid/cn/fixture" else "https://example.invalid/fixture",
            sourceId = if (private) "missav" else "ordinary", sourceName = if (private) "MissAV" else "Ordinary")
        return MoviePlaybackSelection(item, VideoPlaybackSource("https://cdn.example.invalid/fixture.m3u8",
            VideoPlaybackKind.DIRECT, title = "Fixture"), 1, "fixture")
    }

    private suspend fun query(tool: Tool): JsonObject = Json.parseToJsonElement(
        (tool.execute(buildJsonObject { put("title", "Fixture"); put("content_type", "general_video") })
            .single() as UIMessagePart.Text).text,
    ).jsonObject

    @Test fun sameToolRefreshesPromptAndQuerySchemaImmediatelyAcrossUnlockAndRelock() {
        val tool = createTypedVideoSearchTool(privateSourcesAvailable = { allowed }) { _, _, _, _ -> error("unused") }
        fun metadata() = tool.systemPrompt(model, emptyList()) + (tool.parameters() as InputSchema.Obj).properties
        listOf("MissAV", "JableTV", "SupJav", "Hanime1").forEach { assertFalse(metadata().contains(it)) }
        allowed = true
        val configuredNames = EXTERNAL_VIDEO_CATALOG_SOURCES.map { it.name }.toSet()
        val unlockedMetadata = metadata()
        listOf("MissAV", "JableTV", "SupJav", "Hanime1").forEach {
            assertEquals(it in configuredNames, unlockedMetadata.contains(it))
        }
        assertTrue(unlockedMetadata.contains("catalog identifier/code"))
        assertEquals(configuredNames.isNotEmpty(), unlockedMetadata.contains("Omit year"))
        assertEquals(configuredNames.isNotEmpty(), unlockedMetadata.contains("older assistant"))
        allowed = false
        listOf("MissAV", "JableTV", "SupJav", "Hanime1").forEach { assertFalse(metadata().contains(it)) }
        assertFalse(metadata().contains("person name or tag"))
    }

    @Test fun sameCatalogInstanceRestoresEveryPrivateSourceAndHidesThemAgainWithoutRestart() {
        val client = MovieCatalogClient(
            OkHttpClient.Builder().addInterceptor { error("No network") }.build(),
            DEFAULT_MOVIE_CATALOG_SOURCES + EXTERNAL_VIDEO_SITE_IDS.map {
                MovieCatalogSource(it, "Fixture catalog", "https://catalog.private-media.invalid/")
            },
        )
        val public = client.builtInSourceIds()
        assertTrue(public.intersect(EXTERNAL_VIDEO_SITE_IDS).isEmpty())
        allowed = true
        assertEquals(public + EXTERNAL_VIDEO_SITE_IDS, client.builtInSourceIds())
        allowed = false
        assertEquals(public, client.builtInSourceIds())
    }

    @Test fun priorLockedAttemptDoesNotSuppressAnUnlockedResultWithUnknownYearOrCategory() = runBlocking {
        var requests = 0
        val tool = createTypedVideoSearchTool(privateSourcesAvailable = { allowed }) { _, year, _, type ->
            requests++
            assertNull(year)
            assertEquals(MovieContentType.GENERAL_VIDEO, type)
            fixture(private = allowed)
        }
        assertEquals("ready", query(tool)["status"]!!.jsonPrimitive.content)
        allowed = true
        val result = query(tool)
        assertEquals("ready", result["status"]!!.jsonPrimitive.content)
        assertEquals("missav", result["catalog_source_id"]!!.jsonPrimitive.content)
        assertTrue(result.containsKey("playback_id"))
        assertEquals(2, requests)
    }

    @Test fun aCompletedUnlockedQueryCannotLaunchTheSameCatalogAndFallbackLoopAgain() = runBlocking {
        allowed = true
        var catalogRequests = 0
        var webpageRequests = 0
        val tool = createTypedVideoSearchTool(privateSourcesAvailable = { allowed }, webFallback = { _, _, _, _ ->
            webpageRequests++
            MovieWebSearchOutcome(status = "no_results")
        }) { _, _, _, _ -> catalogRequests++; throw java.io.IOException("fixture unavailable") }
        query(tool)
        assertEquals("query_already_attempted", query(tool)["error_code"]!!.jsonPrimitive.content)
        assertEquals(1, catalogRequests)
        assertEquals(1, webpageRequests)
    }

    @Test fun closingAfterToolCreationStillBlocksAPrivateResult() = runBlocking {
        allowed = true
        val tool = createTypedVideoSearchTool(privateSourcesAvailable = { allowed }) { _, _, _, _ ->
            allowed = false
            fixture(private = true)
        }
        val result = query(tool).toString()
        assertFalse(result.contains("playback_id"))
        assertFalse(result.contains("missav", ignoreCase = true))
    }
}
