package com.constx.onvyra.data.ai.compression

/** Empty or zero uses the model's automatic budget. K means exactly 1,000 tokens. */
internal fun parseAutoCompressionThresholdInput(input: String): Int? {
    val text = input.trim()
    if (text.isEmpty()) return 0

    val thousands = text.last() == 'K' || text.last() == 'k'
    val number = if (thousands) text.dropLast(1) else text
    if (number.isEmpty()) return null
    if (!thousands) {
        if (number.any { it !in '0'..'9' }) return null
        return number.toIntOrNull()
    }

    val parts = number.split('.')
    if (parts.size > 2 || parts.any { it.isEmpty() || it.any { digit -> digit !in '0'..'9' } }) return null
    val whole = parts[0].toIntOrNull() ?: return null
    // Trim insignificant zeroes so 1.5000K remains exact, without floating point rounding.
    val fraction = parts.getOrNull(1)?.trimEnd('0').orEmpty()
    if (fraction.length > 3) return null
    val extraTokens = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').toInt()
    val tokens = whole.toLong() * 1_000 + extraTokens
    return tokens.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

internal fun formatAutoCompressionThresholdInput(tokens: Int): String = when {
    tokens <= 0 -> ""
    tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}
