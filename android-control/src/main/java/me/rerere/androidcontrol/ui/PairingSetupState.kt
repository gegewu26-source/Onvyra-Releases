package me.rerere.androidcontrol.ui

enum class PairingSetupPhase { IDLE, DISCOVERING, AWAITING_CODE, PAIRING, CONNECTING, CONNECTED, PAIRED_CONNECTION_FAILED, FAILED, CANCELLED }

data class PairingSetupStatus(
    val phase: PairingSetupPhase = PairingSetupPhase.IDLE,
    val message: String = "",
    val paired: Boolean = false,
)

internal fun pairingCode(raw: CharSequence?): String? {
    val value = raw?.toString()?.trim() ?: return null
    if (value.isEmpty() || value.any { !it.isWhitespace() && it != '-' && Character.digit(it, 10) < 0 }) return null
    val normalized = buildString {
        value.forEach { character ->
            val digit = Character.digit(character, 10)
            if (digit >= 0) append(('0'.code + digit).toChar())
        }
    }
    return normalized.takeIf { it.length == 6 }
}
