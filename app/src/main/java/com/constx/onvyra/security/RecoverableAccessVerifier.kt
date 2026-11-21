package com.constx.onvyra.security

/**
 * Retry a failed authority lookup instead of caching its failure. The owner of [load] caches a
 * successful authority; each check still reads its current grant, including revocation/expiry.
 */
internal class RecoverableAccessVerifier<T>(
    private val load: () -> T,
    private val isAllowed: (T) -> Boolean,
) : () -> Boolean {
    override fun invoke(): Boolean = runCatching { isAllowed(load()) }.getOrDefault(false)
}
