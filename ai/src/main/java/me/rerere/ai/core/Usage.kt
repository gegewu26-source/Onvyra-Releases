package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    // Anthropic can report input and output separately, each with its own subtotal.
    // Keep larger provider totals, but never let a partial subtotal hide merged tokens.
    val totalTokens = maxOf(
        if (other.totalTokens > 0) other.totalTokens else this?.totalTokens ?: 0,
        addTokenCounts(promptTokens, completionTokens),
    )
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens
    )
}

/** Adds usage from separate provider requests; [merge] handles snapshots within one request. */
fun TokenUsage?.accumulate(other: TokenUsage?): TokenUsage? {
    if (this == null) return other?.copy(totalTokens = other.effectiveTotalTokens())
    if (other == null) return this
    return TokenUsage(
        promptTokens = addTokenCounts(promptTokens, other.promptTokens),
        completionTokens = addTokenCounts(completionTokens, other.completionTokens),
        cachedTokens = addTokenCounts(cachedTokens, other.cachedTokens),
        totalTokens = addTokenCounts(effectiveTotalTokens(), other.effectiveTotalTokens()),
    )
}

private fun TokenUsage.effectiveTotalTokens(): Int =
    maxOf(totalTokens, addTokenCounts(promptTokens, completionTokens))

private fun addTokenCounts(first: Int, second: Int): Int =
    (first.toLong() + second).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
