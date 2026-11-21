package com.constx.onvyra.data.ai.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import com.constx.onvyra.AppScope
import com.constx.onvyra.data.datastore.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpSessionRegistry"

/** 单个 MCP Server 的全部运行时状态。 */
private class McpSession(initialConfig: McpServerConfig) {
    @Volatile
    var config: McpServerConfig = initialConfig

    @Volatile
    var client: Client? = null

    @Volatile
    var connectedConfig: McpServerConfig? = null

    val lifecycleMutex = Mutex()
    var reconnectJob: Job? = null
    val reconnectBudget = McpReconnectBudget()
    @Volatile var catalogCheckedAt: Long = 0
}

private sealed interface ConnectResult {
    data object Success : ConnectResult
    data object Stale : ConnectResult
    data object NeedsAuthorization : ConnectResult
    data object Failed : ConnectResult
}

internal class McpClientUnavailableException(message: String) : IllegalStateException(message)

internal class McpStatusStore {
    private val _status = MutableStateFlow<Map<Uuid, McpStatus>>(emptyMap())
    val status: StateFlow<Map<Uuid, McpStatus>> = _status.asStateFlow()

    fun get(configId: Uuid): Flow<McpStatus> =
        status.map { it[configId] ?: McpStatus.Idle }.distinctUntilChanged()

    fun update(configId: Uuid, status: McpStatus) {
        _status.update { current -> current + (configId to status) }
    }

    fun remove(configId: Uuid) {
        _status.update { current -> current - configId }
    }
}

/**
 * MCP 连接运行时注册表。
 *
 * 每个 serverId 对应一个 [McpSession]，该 Session 的连接、同步、关闭和重连通过同一把 Mutex 串行执行。
 * Client 只有在 connect 与首次工具同步都成功后才对外可见。
 */
internal class McpSessionRegistry(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val httpClient: HttpClient,
    private val oauthCoordinator: McpOAuthCoordinator,
    private val statusStore: McpStatusStore,
) {
    private val sessions = ConcurrentHashMap<Uuid, McpSession>()

    fun getClient(configId: Uuid): Client? = sessions[configId]?.client

    fun getStatus(configId: Uuid): Flow<McpStatus> = statusStore.get(configId)

    fun reconcile(configs: List<McpServerConfig>) {
        val activeConfigs = configs
            .filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
            .associateBy { it.id }

        (sessions.keys - activeConfigs.keys).forEach { configId ->
            val detached = sessions.remove(configId) ?: return@forEach
            oauthCoordinator.forget(configId)
            statusStore.remove(configId)
            appScope.launch { closeSession(detached) }
        }

        activeConfigs.values.forEach { newConfig ->
            val existing = sessions[newConfig.id]
            if (existing == null) {
                val session = McpSession(newConfig)
                if (sessions.putIfAbsent(newConfig.id, session) == null) {
                    appScope.launch { addClient(newConfig) }
                }
                return@forEach
            }

            val mustReconnect = !hasSameConnectionParameters(existing.config, newConfig)
            existing.config = newConfig
            if (mustReconnect) {
                appScope.launch { addClient(newConfig) }
            }
        }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): CallToolResult {
        val session = sessions[serverId]
            ?: throw McpClientUnavailableException("No MCP session for server $serverId")
        val freshConfig = oauthCoordinator.ensureFreshToken(session.config)
        if (!hasSameConnectionParameters(session.connectedConfig, freshConfig)) {
            val changedConnection = session.client != null || !hasSameConnectionParameters(session.config, freshConfig)
            session.config = freshConfig
            // A model trying the same offline server is not a new manual retry.
            addClient(freshConfig, resetReconnectBudget = changedConnection)
        }

        val sdkClient = session.client
            ?: throw McpClientUnavailableException("MCP client $serverId is not connected")
        val config = session.connectedConfig ?: session.config
        Log.i(TAG, "Calling tool $toolName on $serverId (${config.commonOptions.name})")
        return try {
            sdkClient.callTool(
                request = CallToolRequest(
                    params = CallToolRequestParams(name = toolName, arguments = args),
                ),
                options = RequestOptions(timeout = 120.seconds),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (oauthCoordinator.needsAuthorization(config, e)) {
                statusStore.update(config.id, McpStatus.NeedsAuthorization)
            } else if (generateSequence<Throwable>(e) { it.cause }.any {
                    it is java.io.IOException || it.message.orEmpty().contains("timeout", ignoreCase = true)
                }) {
                requestReconnect(serverId, sourceClient = sdkClient)
            }
            throw e
        }
    }

    suspend fun addClient(configInput: McpServerConfig, resetReconnectBudget: Boolean = true) {
        // SettingsStore 是配置真源。旧任务排队后可能晚于新配置执行，不能再写回旧快照。
        val desiredConfig = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
        if (desiredConfig == null) {
            removeClient(configInput)
            return
        }
        if (!desiredConfig.commonOptions.enable || desiredConfig.commonOptions.name.isBlank()) {
            removeClient(desiredConfig)
            return
        }

        val session = sessions.computeIfAbsent(desiredConfig.id) { McpSession(desiredConfig) }
        session.config = desiredConfig
        val result = connectSession(
            session = session,
            requestedConfig = desiredConfig,
            cancelPendingReconnect = resetReconnectBudget,
            forceReconnect = false,
        )
        if (result == ConnectResult.Failed) {
            requestReconnect(desiredConfig.id, sourceClient = null, retryAfterFailure = true)
        }
    }

    suspend fun removeClient(config: McpServerConfig) {
        val session = sessions.remove(config.id)
        oauthCoordinator.forget(config.id)
        if (session != null) closeSession(session)
        statusStore.remove(config.id)
    }

    suspend fun syncAll() {
        syncSelected(settingsStore.settingsFlow.value.mcpServers.map { it.id }.toSet())
    }

    suspend fun syncSelected(ids: Set<Uuid>, force: Boolean = true) = supervisorScope {
        settingsStore.settingsFlow.value.mcpServers.filter {
            it.id in ids && it.commonOptions.enable && it.commonOptions.name.isNotBlank()
        }.map { config ->
            async {
                try {
                    val existing = sessions[config.id]
                    // Chat-time catalog checks must not restart a failed server's
                    // whole retry budget. The user's explicit refresh still can.
                    if (!force && existing != null &&
                        !existing.reconnectBudget.allowsAutomaticCheck(
                            reconnectScheduled = existing.reconnectJob?.isActive == true,
                            failed = statusStore.status.value[config.id] is McpStatus.Error,
                        )
                    ) return@async
                    if (!force && existing?.client != null &&
                        statusStore.status.value[config.id] == McpStatus.Connected &&
                        System.currentTimeMillis() - existing.catalogCheckedAt < 60_000
                    ) return@async
                    withTimeout(if (force) 25_000L else 8_000L) {
                        val session = sessions[config.id]
                        if (session == null) addClient(config, resetReconnectBudget = force)
                        else syncSession(session, resetReconnectBudget = force)
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    statusStore.update(config.id, McpStatus.Error("连接检查超时，请确认手机上的服务已启动"))
                }
            }
        }.awaitAll()
        Unit
    }

    private suspend fun connectSession(
        session: McpSession,
        requestedConfig: McpServerConfig,
        cancelPendingReconnect: Boolean,
        forceReconnect: Boolean,
    ): ConnectResult = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            if (sessions[requestedConfig.id] !== session) return@withLock ConnectResult.Stale
            if (!hasSameConnectionParameters(session.config, requestedConfig)) {
                return@withLock ConnectResult.Stale
            }

            if (cancelPendingReconnect) {
                session.reconnectJob?.cancel()
                session.reconnectJob = null
                session.reconnectBudget.reset()
            }
            if (!cancelPendingReconnect && !forceReconnect && session.client == null &&
                !session.reconnectBudget.allowsAutomaticCheck(
                    reconnectScheduled = session.reconnectJob?.isActive == true,
                    failed = statusStore.status.value[requestedConfig.id] is McpStatus.Error,
                )
            ) return@withLock ConnectResult.Stale

            val config = oauthCoordinator.ensureFreshToken(session.config)
            session.config = config
            if (!forceReconnect &&
                session.client != null &&
                hasSameConnectionParameters(session.connectedConfig, config)
            ) {
                return@withLock ConnectResult.Success
            }

            statusStore.update(config.id, McpStatus.Connecting)
            val oldClient = session.client
            session.client = null
            session.connectedConfig = null
            oldClient?.let { closeClient(it, config.commonOptions.name) }

            val sdkClient = createSdkClient(config)
            val transport = createTransport(config)
            installTransportCallbacks(config, sdkClient, transport)

            try {
                sdkClient.connect(transport)
                val syncedConfig = syncTools(session, sdkClient, config)
                if (sessions[config.id] !== session ||
                    !hasSameConnectionParameters(config, syncedConfig)
                ) {
                    closeClient(sdkClient, config.commonOptions.name)
                    return@withLock ConnectResult.Stale
                }

                session.config = syncedConfig
                session.connectedConfig = syncedConfig
                session.client = sdkClient
                session.reconnectBudget.reset()
                statusStore.update(config.id, McpStatus.Connected)
                Log.i(TAG, "Connected MCP server ${config.id} (${config.commonOptions.name})")
                ConnectResult.Success
            } catch (e: CancellationException) {
                closeClient(sdkClient, config.commonOptions.name)
                throw e
            } catch (e: Exception) {
                closeClient(sdkClient, config.commonOptions.name)
                Log.e(TAG, "Failed to connect MCP server ${config.id}", e)
                if (oauthCoordinator.needsAuthorization(config, e)) {
                    statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    ConnectResult.NeedsAuthorization
                } else {
                    statusStore.update(config.id, McpStatus.Error.from(e))
                    ConnectResult.Failed
                }
            }
        }
    }

    private suspend fun syncSession(session: McpSession, resetReconnectBudget: Boolean) {
        val config = session.config
        if (session.client == null) {
            addClient(config, resetReconnectBudget)
            return
        }

        var reconnectConfig: McpServerConfig? = null
        var failedSync = false
        withContext(Dispatchers.IO) {
            session.lifecycleMutex.withLock {
                val sdkClient = session.client ?: return@withLock
                val connectedConfig = session.connectedConfig ?: return@withLock
                statusStore.update(config.id, McpStatus.Connecting)
                try {
                    val syncedConfig = syncTools(session, sdkClient, session.config)
                    session.config = syncedConfig
                    if (hasSameConnectionParameters(connectedConfig, syncedConfig)) {
                        session.connectedConfig = syncedConfig
                        statusStore.update(config.id, McpStatus.Connected)
                    } else {
                        reconnectConfig = syncedConfig
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    session.client = null
                    session.connectedConfig = null
                    closeClient(sdkClient, config.commonOptions.name)
                    if (oauthCoordinator.needsAuthorization(config, e)) {
                        statusStore.update(config.id, McpStatus.NeedsAuthorization)
                    } else {
                        statusStore.update(config.id, McpStatus.Error.from(e))
                        reconnectConfig = session.config
                        failedSync = true
                    }
                }
            }
        }
        reconnectConfig?.let {
            if (failedSync && !resetReconnectBudget) {
                requestReconnect(it.id, sourceClient = null, retryAfterFailure = true)
            } else {
                addClient(it, resetReconnectBudget)
            }
        }
    }

    private suspend fun syncTools(
        session: McpSession,
        sdkClient: Client,
        connectionConfig: McpServerConfig,
    ): McpServerConfig {
        val serverTools = collectMcpPages { cursor ->
            val page = sdkClient.listTools(
                request = ListToolsRequest(params = PaginatedRequestParams(cursor = cursor)),
                options = RequestOptions(timeout = 20.seconds),
            )
            page.tools to page.nextCursor
        }.distinctBy { it.name }
        Log.i(TAG, "Synced ${serverTools.size} tools from ${connectionConfig.id}")
        var updatedConfig = connectionConfig
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { storedConfig ->
                    if (storedConfig.id != connectionConfig.id) return@map storedConfig
                    val tools = mergeTools(storedConfig.commonOptions.tools, serverTools)
                    storedConfig.clone(commonOptions = storedConfig.commonOptions.copy(
                        tools = tools,
                        serverInstructions = sdkClient.serverInstructions.orEmpty().take(12_000),
                    ))
                        .also { updatedConfig = it }
                }
            )
        }
        session.config = updatedConfig
        session.catalogCheckedAt = System.currentTimeMillis()
        return updatedConfig
    }

    private fun installTransportCallbacks(
        config: McpServerConfig,
        sdkClient: Client,
        transport: AbstractTransport,
    ) {
        transport.onClose {
            Log.i(TAG, "Transport closed for ${config.id} (${config.commonOptions.name})")
            requestReconnect(config.id, sdkClient)
        }
        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.id}: ${error.message}")
            requestReconnect(config.id, sdkClient)
        }
    }

    /** 合并重复的 onError/onClose 通知，并保证每个 Session 最多只有一个重连任务。 */
    private fun requestReconnect(
        configId: Uuid,
        sourceClient: Client?,
        retryAfterFailure: Boolean = false,
    ) {
        appScope.launch {
            val session = sessions[configId] ?: return@launch
            session.lifecycleMutex.withLock {
                if (sessions[configId] !== session) return@withLock
                if (sourceClient != null && session.client !== sourceClient) return@withLock
                if (!retryAfterFailure && statusStore.status.value[configId] != McpStatus.Connected) {
                    return@withLock
                }
                if (session.reconnectJob?.isActive == true) return@withLock

                val attempt = session.reconnectBudget.next()
                if (attempt == null) {
                    val failedClient = session.client
                    session.client = null
                    session.connectedConfig = null
                    failedClient?.let { closeClient(it, session.config.commonOptions.name) }
                    statusStore.update(configId, McpStatus.Error("连接断开，已重试两次，请手动重试"))
                    return@withLock
                }

                statusStore.update(configId, McpStatus.Reconnecting(attempt.number, MCP_MAX_RECONNECT_RETRIES))
                session.reconnectJob = appScope.launch {
                    reconnectAfterDelay(session, attempt.delayMs)
                }
            }
        }
    }

    private suspend fun reconnectAfterDelay(session: McpSession, delayMs: Long) {
        val runningJob = currentCoroutineContext().job
        var retry = false
        try {
            delay(delayMs)
            val latestConfig = settingsStore.settingsFlow.value.mcpServers.find {
                it.id == session.config.id &&
                    it.commonOptions.enable &&
                    it.commonOptions.name.isNotBlank()
            } ?: return
            session.config = latestConfig
            retry = connectSession(
                session = session,
                requestedConfig = latestConfig,
                cancelPendingReconnect = false,
                forceReconnect = true,
            ) == ConnectResult.Failed
        } catch (e: CancellationException) {
            throw e
        } finally {
            withContext(NonCancellable) {
                session.lifecycleMutex.withLock {
                    if (session.reconnectJob === runningJob) session.reconnectJob = null
                }
            }
        }

        if (retry) requestReconnect(session.config.id, sourceClient = null, retryAfterFailure = true)
    }

    private suspend fun closeSession(session: McpSession) = withContext(Dispatchers.IO) {
        session.lifecycleMutex.withLock {
            session.reconnectJob?.cancel()
            session.reconnectJob = null
            session.reconnectBudget.reset()
            val sdkClient = session.client
            session.client = null
            session.connectedConfig = null
            sdkClient?.let { closeClient(it, session.config.commonOptions.name) }
        }
    }

    private suspend fun closeClient(client: Client, serverName: String) {
        runCatching { client.close() }
            .onFailure { Log.w(TAG, "Failed to close MCP client $serverName", it) }
    }

    private fun createSdkClient(config: McpServerConfig): Client = Client(
        clientInfo = Implementation(name = config.commonOptions.name, version = "1.0")
    )

    private fun createTransport(config: McpServerConfig): AbstractTransport = when (config) {
        is McpServerConfig.SseTransportServer -> SseClientTransport(
            urlString = config.url,
            client = httpClient,
            requestBuilder = { appendResolvedHeaders(config) },
        )

        is McpServerConfig.StreamableHTTPServer -> StreamableHttpClientTransport(
            url = config.url,
            client = httpClient,
            reconnectionOptions = mcpTransportReconnectionOptions(),
            requestBuilder = { appendResolvedHeaders(config) },
        )
    }

    private fun HttpRequestBuilder.appendResolvedHeaders(config: McpServerConfig) {
        headers.appendAll(StringValues.build {
            config.resolvedHeaders().forEach { (name, value) -> append(name, value) }
        })
    }

}

/** 只包含会影响实际连接的字段；工具开关和 Schema 变化不会触发重连。 */
internal data class McpConnectionKey(
    val transportType: String,
    val serverUrl: String,
    val clientName: String,
    val headers: List<Pair<String, String>>,
)

internal fun McpServerConfig.connectionKey(): McpConnectionKey = McpConnectionKey(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    },
    serverUrl = serverUrl,
    clientName = commonOptions.name,
    headers = resolvedHeaders(),
)

private fun hasSameConnectionParameters(
    left: McpServerConfig?,
    right: McpServerConfig?,
): Boolean = left != null && right != null && left.connectionKey() == right.connectionKey()

private fun McpServerConfig.resolvedHeaders(): List<Pair<String, String>> {
    // 设置页“添加请求头”后未填写会留下空名称，OkHttp 会直接抛出 "name is empty"
    val base = commonOptions.headers.filter { it.first.isNotBlank() }
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}

private fun mergeTools(storedTools: List<McpTool>, serverTools: List<Tool>): List<McpTool> {
    val toolsByName = storedTools.associateBy { it.name }
    return serverTools.map { serverTool ->
        toolsByName[serverTool.name]?.copy(
            description = serverTool.description,
            inputSchema = serverTool.inputSchema.toSchema(),
        ) ?: McpTool(
            name = serverTool.name,
            description = serverTool.description,
            enable = true,
            inputSchema = serverTool.inputSchema.toSchema(),
        )
    }
}
