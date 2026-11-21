package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import com.constx.onvyra.data.media.MovieWebCandidate
import org.junit.Assert.*
import org.junit.Test

class VideoSourceSearchToolTest {
    @Test fun aLimitedWebCandidateListNeverClaimsToBeTheConfiguredSourceInventory() = runBlocking {
        val tool = createVideoSourceSearchTool { (1..12).map { MovieWebCandidate("候选$it", "https://example.org/watch/$it") } }
        val text = (tool.execute(buildJsonObject { put("query", "鬼灭之刃 动漫 第1集") }).single() as UIMessagePart.Text).text
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals("webpage_candidates", result["scope"]!!.jsonPrimitive.content)
        assertEquals(8, result["returned_candidates"]!!.jsonPrimitive.int)
        assertEquals(8, result["items"]!!.jsonArray.size)
        assertFalse(result["is_catalog_inventory"]!!.jsonPrimitive.boolean)
        assertFalse(result["playback_verified"]!!.jsonPrimitive.boolean)
    }
}
