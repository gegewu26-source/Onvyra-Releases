package me.rerere.androidcontrol.captcha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaCharsetTest {

    /** Mirrors the real asset: index 0 is the empty CTC blank, not a character. */
    private val charset = listOf("", "a", "b", "c", "1", "2", "中")

    /** One score row per time step, each with a single hot class at [hotIndices]. */
    private fun scores(vararg hotIndices: Int, classes: Int = 7): Array<FloatArray> =
        Array(hotIndices.size) { step ->
            FloatArray(classes).also { row -> row[hotIndices[step]] = 1f }
        }

    @Test fun blankAtIndexZeroIsNeverEmittedAsText() {
        assertEquals("", CaptchaCharset.decode(intArrayOf(0, 0, 0), charset))
    }

    @Test fun repeatedIndicesCollapseButCharactersAfterBlankSurvive() {
        // a a BLANK a -> "aa" (the blank separates two identical characters)
        assertEquals("aa", CaptchaCharset.decode(intArrayOf(1, 1, 0, 1), charset))
    }

    @Test fun indicesOutsideTheCharsetAreDropped() {
        assertEquals("ab", CaptchaCharset.decode(intArrayOf(1, 99, 2), charset))
    }

    @Test fun decodingMatchesTheCharsetOrderExactly() {
        // Catches the off-by-one that a string-joined charset would introduce,
        // because entry 0 is "" and would shift "a" to index 0.
        assertEquals("abc12中", CaptchaCharset.decode(intArrayOf(1, 2, 3, 4, 5, 6), charset))
    }

    @Test fun unrestrictedDecodingTakesTheArgmaxPerStep() {
        val decoded = CaptchaCharset.decodeRestricted(scores(1, 2, 4), charset, null)
        assertEquals("ab1", decoded)
    }

    @Test fun whitelistRestrictsChoiceToAllowedCharacters() {
        // Step 1 favours "a" (index 1), but the whitelist only allows digits.
        val rows = arrayOf(
            floatArrayOf(0f, 0.9f, 0.1f, 0f, 0.2f, 0.1f, 0f),
            floatArrayOf(0f, 0.1f, 0f, 0f, 0.85f, 0.05f, 0f),
        )
        assertEquals("1", CaptchaCharset.decodeRestricted(rows, charset, "0123456789".toSet()))
        // Both steps resolve to "a" once restricted, and CTC merges the repeat.
        assertEquals("a", CaptchaCharset.decodeRestricted(rows, charset, "ab".toSet()))
        assertEquals("a1", CaptchaCharset.decodeRestricted(rows, charset, null))
    }

    @Test fun whitelistWithNothingInCommonWithTheCharsetReturnsEmpty() {
        val rows = arrayOf(floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f))
        assertEquals("", CaptchaCharset.decodeRestricted(rows, charset, "ZQ".toSet()))
    }

    @Test fun emptyWhitelistMeansNoRestrictionRatherThanNoCharacters() {
        assertNull(CaptchaCharset.whitelist(null))
        assertNull(CaptchaCharset.whitelist("   "))
        assertEquals(setOf('0', '1'), CaptchaCharset.whitelist("01"))
    }

    @Test fun recognitionWidthFollowsDdddocrHeight64WithAspectRatio() {
        assertEquals(64, CaptchaCharset.recognitionWidth(100, 100))
        assertEquals(192, CaptchaCharset.recognitionWidth(300, 100))
        assertEquals(1, CaptchaCharset.recognitionWidth(1, 1000))
        assertEquals(320, CaptchaCharset.recognitionWidth(1000, 200))
    }
}