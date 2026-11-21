package me.rerere.androidcontrol.ui

/** One short-lived Settings-window observation. Never put its fields into logs or agent messages. */
internal class WirelessPairingReading(val code: String, val port: Int) {
    override fun toString(): String = "WirelessPairingReading(redacted)"
}

internal class WirelessPairingText(val text: String, val resourceId: String? = null) {
    override fun toString(): String = "WirelessPairingText(redacted)"
}

/**
 * Parsing is permitted only after the caller has verified a system Settings window.
 * A screen port must also be a currently discovered, local adbd pairing port.
 */
internal object WirelessPairingDialog {
    private val ipv4Port = Regex("(?<![\\w.:])([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})(?![\\w.:])")
    private val ipv6Port = Regex("\\[[0-9A-Fa-f:.]+(?:%[\\w.]+)?]:([0-9]{1,5})(?![\\w.:])")

    private val codeResourceIds = setOf("pairing_code", "adb_pairing_code")

    private fun digits(text: String): String = buildString {
        text.forEach { character ->
            val digit = Character.digit(character, 10)
            if (digit >= 0) append(('0'.code + digit).toChar())
        }
    }

    private fun codeLikeText(text: String): Boolean = text.all { character ->
        Character.digit(character, 10) >= 0 || character.isWhitespace() || character == '-'
    }

    private fun pairingLabel(text: String): Boolean {
        val lowered = text.lowercase()
        return lowered.contains("pairing code") || text.contains("配对码")
    }

    /** Settings versions expose the six-digit code as one node, a grouped string, or adjacent groups. */
    private fun codeCandidates(nodes: List<WirelessPairingText>): Set<String> {
        val result = linkedSetOf<String>()
        val fragments = ArrayList<Pair<Int, String>>()
        nodes.forEachIndexed { index, node ->
            val text = node.text
            val compact = digits(text)
            val resource = node.resourceId?.substringAfterLast('/')
            if (compact.length == 6 && (codeLikeText(text) || pairingLabel(text) || resource in codeResourceIds)) result += compact
            if (codeLikeText(text) && compact.length in 1..3) fragments += index to compact
        }
        var run = ArrayList<Pair<Int, String>>()
        fun flush() {
            if (run.size >= 2) {
                for (start in run.indices) {
                    var total = 0
                    val joined = StringBuilder()
                    for (end in start until run.size) {
                        total += run[end].second.length
                        if (total > 6) break
                        joined.append(run[end].second)
                        if (total == 6 && end - start + 1 <= 6) result += joined.toString()
                    }
                }
            }
            run = ArrayList()
        }
        fragments.forEach { fragment ->
            if (run.isEmpty() || fragment.first == run.last().first + 1) run += fragment else {
                flush()
                run += fragment
            }
        }
        flush()
        return result
    }

    fun read(nodes: List<WirelessPairingText>, localPairingPorts: Set<Int>): WirelessPairingReading? {
        val known = localPairingPorts.filter { it in 1..65535 }.toSet()
        if (known.isEmpty()) return null
        val hasPairingLabel = nodes.any { node ->
            pairingLabel(node.text) || node.resourceId?.substringAfterLast('/') in codeResourceIds
        }
        if (!hasPairingLabel) return null
        val codes = codeCandidates(nodes)
        val code = codes.singleOrNull() ?: return null
        val displayedPorts = nodes.flatMap { node ->
            val v4 = ipv4Port.findAll(node.text).filter { match ->
                match.groupValues[1].split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
            }.map { it.groupValues[2] }
            val v6 = ipv6Port.findAll(node.text).map { it.groupValues[1] }
            (v4 + v6).mapNotNull { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } }.toList()
        }.toSet()
        // Some Settings builds expose the dialog and its background page in one
        // window tree. The background address is the connection endpoint, not
        // the one-time pairing endpoint. Match against current local pairing
        // discovery before deciding whether the observation is ambiguous.
        val port = if (displayedPorts.isEmpty()) known.singleOrNull()
            else displayedPorts.intersect(known).singleOrNull()
        return port?.let { WirelessPairingReading(code, it) }
    }
}

/** No retry of the same observed code within one user-started session, including failed attempts. */
internal class WirelessPairingAutoAttempts {
    private var generation: Long? = null
    private val submitted = HashSet<String>()

    fun start(ticket: Long) { generation = ticket; submitted.clear() }
    fun canSubmit(ticket: Long, code: String): Boolean = generation == ticket && code !in submitted && submitted.size < 32
    fun submitted(ticket: Long, code: String) { if (generation == ticket) submitted += code }
    fun close() { generation = null; submitted.clear() }
}

/** Two nearby reads let Settings finish populating its dialog without a 400 ms polling penalty. */
internal class WirelessPairingCodeStability(private val stableForMs: Long = 80L) {
    private var reading: WirelessPairingReading? = null
    private var sinceMs = 0L

    fun observe(current: WirelessPairingReading?, nowMs: Long): Boolean {
        val previous = reading
        if (current == null) { reading = null; return false }
        if (previous == null || previous.code != current.code || previous.port != current.port || nowMs < sinceMs) {
            reading = current
            sinceMs = nowMs
            return false
        }
        return nowMs - sinceMs >= stableForMs
    }
}

/** A just-observed system dialog may reuse the matching local discovery, never an arbitrary cached port. */
internal fun verifiedAutomaticPairingPort(
    code: String,
    observedPort: Int?,
    current: WirelessPairingReading?,
    localPorts: Set<Int>,
    discoveryAgeMs: Long,
): Int? = observedPort?.takeIf {
    discoveryAgeMs in 0..5_000 && it in localPorts && current != null && current.port == it && current.code == code
}
