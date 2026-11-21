package com.constx.onvyra.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WebFetchToolTest {
    private val clients = mutableListOf<OkHttpClient>()

    @After
    fun closeClients() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
    }

    @Test
    fun exposesXuanXingCompatibleUrlAndOptionalMaxCharsSchema() {
        val tool = createWebFetchTool(WebPageFetcher(clientReturning("<p>Page</p>")))
        val schema = tool.parameters() as InputSchema.Obj

        assertEquals("web_fetch", tool.name)
        assertEquals(listOf("url"), schema.required)
        assertEquals(setOf("url", "maxChars", "offset", "pageId"), schema.properties.keys)
        assertEquals("string", schema.properties.getValue("url").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("integer", schema.properties.getValue("maxChars").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(tool.description.contains("independently of web search"))
        assertTrue(tool.description.contains("no extra API key"))
    }

    @Test
    fun addsUrlReadingInstructionsOnlyForModelsThatCanCallTools() {
        val tool = createWebFetchTool(WebPageFetcher(clientReturning("<p>Page</p>")))
        val prompt = tool.systemPrompt(Model(abilities = listOf(ModelAbility.TOOL)), emptyList())

        assertTrue(prompt.contains("web_fetch"))
        assertTrue(prompt.contains("read that URL"))
        assertTrue(prompt.contains("untrusted"))
        assertEquals("", tool.systemPrompt(Model(), emptyList()))
    }

    @Test
    fun executesWithoutSearchCredentialsAndReturnsActualPageContentAndResolvedLinks() = runBlocking {
        val tool = createWebFetchTool(
            WebPageFetcher(
                clientReturning(
                    """
                    <html lang="en"><head><title>CloudCanvas</title>
                    <meta name="description" content="A collaborative design workspace"></head>
                    <body><main><h1>Design together</h1><p>Create shared drawings online.</p>
                    <a href="../features">Explore features</a>
                    <script>Do not expose this script()</script></main></body></html>
                    """.trimIndent(),
                )
            )
        )
        val payload = tool.executePayload(buildJsonObject { put("url", "https://example.com/product/start") })

        assertEquals("https://example.com/product/start", payload.getValue("url").jsonPrimitive.content)
        assertEquals("https://example.com/product/start", payload.getValue("finalUrl").jsonPrimitive.content)
        assertEquals(200, payload.getValue("status").jsonPrimitive.int)
        assertEquals("CloudCanvas", payload.getValue("title").jsonPrimitive.content)
        assertEquals("A collaborative design workspace", payload.getValue("description").jsonPrimitive.content)
        val content = payload.getValue("content").jsonPrimitive.content
        assertTrue(content.contains("Design together"))
        assertTrue(content.contains("Create shared drawings online."))
        assertFalse(content.contains("Do not expose this script"))
        val link = payload.getValue("links").jsonArray.single().jsonObject
        assertEquals("Explore features", link.getValue("title").jsonPrimitive.content)
        assertEquals("https://example.com/features", link.getValue("url").jsonPrimitive.content)
        assertTrue(payload.getValue("retrievedAt").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun invalidArgumentsAndNetworkFailuresReturnStructuredErrorsInsteadOfCrashing() = runBlocking {
        val tool = createWebFetchTool(WebPageFetcher(clientReturning("<p>Unexpected response</p>")))
        val invalidArguments = listOf(
            JsonNull,
            JsonArray(emptyList()),
            JsonPrimitive("https://example.com/"),
            buildJsonObject {},
            buildJsonObject { put("url", " ") },
            buildJsonObject { put("url", JsonObject(emptyMap())) },
            buildJsonObject { put("url", "ftp://example.com/file") },
            buildJsonObject {
                put("url", "https://example.com/")
                put("maxChars", JsonArray(emptyList()))
            },
        )
        for (arguments in invalidArguments) {
            val payload = tool.executePayload(arguments)
            assertEquals("", payload.getValue("content").jsonPrimitive.content)
            assertTrue("Missing error for $arguments", payload.getValue("error").jsonPrimitive.content.isNotBlank())
        }

        val failingClient = OkHttpClient.Builder().addInterceptor {
            throw IOException("Synthetic connection failure")
        }.build().also(clients::add)
        val failure = createWebFetchTool(WebPageFetcher(failingClient))
            .executePayload(buildJsonObject { put("url", "https://example.com/") })
        assertEquals("", failure.getValue("content").jsonPrimitive.content)
        assertTrue(failure.getValue("error").jsonPrimitive.content.contains("Synthetic connection failure"))
    }

    @Test
    fun boundsEscapedJsonPayloadWithoutLosingTitleOrSplittingUnicode() {
        val title = "Cloud \"Canvas\" 🐾"
        val result = WebFetchResult(
            url = "https://example.com/",
            finalUrl = "https://example.com/",
            status = 200,
            contentType = "text/html; charset=utf-8",
            title = title,
            description = "A workspace with \"quoted\" text and \\ paths",
            content = ("\"quoted\" \\path\n😀").repeat(2_000),
            links = (1..120).map {
                WebPageLink("Link \"$it\" 🐾", "https://example.com/$it/" + "segment\\\"".repeat(200))
            },
        )
        val payload = boundedWebFetchPayload(result)
        val parsed = Json.parseToJsonElement(payload).jsonObject

        assertTrue("Tool results must fit the inline result budget", payload.length <= 30_000)
        assertEquals(title, parsed.getValue("title").jsonPrimitive.content)
        assertTrue(parsed.getValue("truncated").jsonPrimitive.boolean)
        val text = parsed.getValue("content").jsonPrimitive.content
        text.forEachIndexed { index, character ->
            if (character.isHighSurrogate()) {
                assertTrue(index + 1 < text.length && text[index + 1].isLowSurrogate())
            } else if (character.isLowSurrogate()) {
                assertTrue(index > 0 && text[index - 1].isHighSurrogate())
            }
        }
    }

    private fun clientReturning(html: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }
        .build()
        .also(clients::add)

    private suspend fun Tool.executePayload(arguments: JsonElement): JsonObject {
        val result = execute(arguments)
        assertEquals(1, result.size)
        return Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
    }
}
