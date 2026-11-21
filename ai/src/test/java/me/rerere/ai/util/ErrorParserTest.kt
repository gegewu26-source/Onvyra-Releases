package me.rerere.ai.util

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserTest {
    private val creditsMessage =
        "Credits exhausted. Please visit https://www.codebuddy.ai/profile/usage"

    private fun codeBuddyError() = buildJsonObject {
        put("error", buildJsonObject {
            put("data", buildJsonObject {
                put("code", 14018)
                put("msg", creditsMessage)
            })
            put("requestId", "test-request")
        })
    }

    @Test
    fun `nested WorkBuddy credits exhausted takes precedence over proxy retry advice`() {
        val proxyMessage = "upstream rate limit reached for this model: retry in 59s - ${codeBuddyError()}"
        val body = buildJsonObject {
            put("error", buildJsonObject {
                put("message", proxyMessage)
                put("code", "rate_limit_exceeded")
            })
        }

        val error = body.parseErrorDetail()

        assertEquals(HttpErrorCategory.QUOTA_EXHAUSTED, error.category)
        assertTrue(error.message!!.contains("额度已耗尽"))
        assertTrue(error.message!!.contains(creditsMessage))
        assertFalse(error.message!!.contains("retry in 59s"))
        assertTrue(error.cause!!.message!!.contains("14018"))
        assertTrue(error.cause!!.message!!.contains("retry in 59s"))
        // GenerationLoop retries IOException only; a classified account error must stay outside it.
        assertFalse((error as Throwable) is IOException)
    }

    @Test
    fun `nested data msg exposes account exhaustion and keeps request details`() {
        val error = codeBuddyError().parseErrorDetail()

        assertEquals(HttpErrorCategory.QUOTA_EXHAUSTED, error.category)
        assertTrue(error.message!!.contains(creditsMessage))
        assertTrue(error.cause!!.message!!.contains("test-request"))
    }

    @Test
    fun `JSON and JSON string wrapped provider errors retain credit classification`() {
        val errors = listOf(
            JsonPrimitive(codeBuddyError().toString()),
            JsonPrimitive(JsonPrimitive(codeBuddyError().toString()).toString()),
        )

        errors.forEach { body ->
            val error = body.parseErrorDetail()
            assertEquals(HttpErrorCategory.QUOTA_EXHAUSTED, error.category)
            assertTrue(error.message!!.contains(creditsMessage))
        }
    }

    @Test
    fun `explicit billing code classifies account errors with other wording`() {
        val error = Json.parseToJsonElement(
            """{"error":{"code":"insufficient_quota","message":"Check your plan and billing details."}}"""
        ).parseErrorDetail()

        assertEquals(HttpErrorCategory.QUOTA_EXHAUSTED, error.category)
        assertTrue(error.message!!.contains("Check your plan and billing details."))
    }

    @Test
    fun `CodeBuddy numeric billing code remains stable across message locales`() {
        val error = Json.parseToJsonElement(
            """{"error":{"data":{"code":14018,"msg":"账户积分已用完，请查看账户用量。"}}}"""
        ).parseErrorDetail()

        assertEquals(HttpErrorCategory.QUOTA_EXHAUSTED, error.category)
        assertTrue(error.message!!.contains("账户积分已用完"))
        assertTrue(error.cause!!.message!!.contains("14018"))
    }

    @Test
    fun `temporary rate limit keeps its original message and behavior`() {
        val message = "upstream rate limit reached for this model: retry in 59s"
        val error = buildJsonObject {
            put("error", buildJsonObject {
                put("message", message)
                put("code", "rate_limit_exceeded")
            })
        }.parseErrorDetail()

        assertEquals(HttpErrorCategory.OTHER, error.category)
        assertEquals(message, error.message)
        assertNull(error.cause)
    }

    @Test
    fun `daily quota limits and unknown numeric codes are not assumed to be billing errors`() {
        val message = "Daily quota exceeded. Retry in 59s."
        val error = Json.parseToJsonElement(
            """{"error":{"data":{"code":14019,"msg":"$message"}}}"""
        ).parseErrorDetail()

        assertEquals(HttpErrorCategory.OTHER, error.category)
        assertEquals(message, error.message)
    }

    @Test
    fun `credits mentioned in request metadata or quoted request data are not classified`() {
        val message = "Invalid input: {\"request\":{\"message\":\"Credits exhausted\"}}"
        val error = buildJsonObject {
            put("error", buildJsonObject { put("message", message) })
            put("request", codeBuddyError())
        }.parseErrorDetail()

        assertEquals(HttpErrorCategory.OTHER, error.category)
        assertEquals(message, error.message)
    }

    @Test
    fun `ordinary provider error messages remain unchanged`() {
        val error = Json.parseToJsonElement(
            """{"error":{"code":"server_error","message":"Upstream generation failed"}}"""
        ).parseErrorDetail()

        assertEquals(HttpErrorCategory.OTHER, error.category)
        assertEquals("Upstream generation failed", error.message)
        assertNull(error.cause)
    }
}
