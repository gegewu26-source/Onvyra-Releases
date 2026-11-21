package me.rerere.androidcontrol.adb

/** Validate before InetAddress: arbitrary hostnames must never trigger DNS for an on-device bridge. */
internal fun literalAdbHost(host: String): String {
    val value = host.trim().removePrefix("[").removeSuffix("]")
    if (value.equals("localhost", ignoreCase = true)) return "127.0.0.1"
    val ipv4 = value.split('.')
    if (ipv4.size == 4 && ipv4.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }) return value
    if (value.count { it == ':' } >= 2 && value.all { it in "0123456789abcdefABCDEF:." }) return value
    throw IllegalArgumentException("Only a literal local IP address or localhost is accepted")
}
