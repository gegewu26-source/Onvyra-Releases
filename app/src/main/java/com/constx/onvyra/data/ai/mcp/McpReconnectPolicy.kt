package com.constx.onvyra.data.ai.mcp

import io.ktor.client.plugins.sse.SSEConfig
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions

internal const val MCP_MAX_RECONNECT_RETRIES = 2

/** Only the registry retries connections; nested transport loops would multiply this limit. */
internal fun mcpTransportReconnectionOptions() = ReconnectionOptions(maxRetries = 0)

internal fun SSEConfig.useRegistryReconnectBudget() {
    maxReconnectionAttempts = 0
}

internal data class McpReconnectAttempt(val number: Int, val delayMs: Long)

/** Reserved under the session lifecycle mutex, separately from the initial connection attempt. */
internal class McpReconnectBudget {
    @Volatile
    var retryCount: Int = 0
        private set

    val exhausted: Boolean get() = retryCount >= MCP_MAX_RECONNECT_RETRIES

    fun allowsAutomaticCheck(reconnectScheduled: Boolean, failed: Boolean): Boolean =
        retryCount == 0 && !reconnectScheduled && !failed

    fun next(): McpReconnectAttempt? {
        if (exhausted) return null
        retryCount++
        return McpReconnectAttempt(retryCount, 1_000L shl (retryCount - 1))
    }

    /** A successful connection or an explicit retry/config change starts a fresh outage budget. */
    fun reset() {
        retryCount = 0
    }
}
