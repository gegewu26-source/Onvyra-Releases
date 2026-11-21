package com.constx.onvyra.data.ai.mcp

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import com.constx.onvyra.AppScope
import com.constx.onvyra.data.datastore.SettingsStore
import com.constx.onvyra.data.files.FilesManager
import com.constx.onvyra.data.files.saveUploadFromBytes
import com.constx.onvyra.data.model.Assistant
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * MCP 子系统的公共入口。
 *
 * 这里仅协调配置、OAuth、连接注册表与 UI 内容转换；单个服务器的连接状态机由
 * [McpSessionRegistry] 管理，OAuth 协议细节由 [McpOAuthCoordinator] 管理。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        // Reconnection is owned by the registry; never implicitly replay a tool POST.
        .retryOnConnectionFailure(false)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE) { useRegistryReconnectBudget() }
    }

    private val statusStore = McpStatusStore()
    private val oauthCallbackServer = OAuthLoopbackCallbackServer(
        port = MCP_OAUTH_CALLBACK_PORT,
        callbackPath = MCP_OAUTH_CALLBACK_PATH,
    )
    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        oauthClient = OAuthHttpClient(okHttpClient),
        discoveryClient = McpOAuthDiscoveryClient(okHttpClient),
        callbackServer = oauthCallbackServer,
        authorizationLauncher = CustomTabsOAuthAuthorizationLauncher,
        updateStatus = statusStore::update,
    )
    private val sessionRegistry = McpSessionRegistry(
        settingsStore = settingsStore,
        appScope = appScope,
        httpClient = httpClient,
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
    )

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .distinctUntilChanged()
                .collect(sessionRegistry::reconcile)
        }
    }

    val syncingStatus: StateFlow<Map<Uuid, McpStatus>>
        get() = statusStore.status

    fun getClient(config: McpServerConfig): Client? = sessionRegistry.getClient(config.id)

    fun getStatus(config: McpServerConfig): Flow<McpStatus> = sessionRegistry.getStatus(config.id)

    fun getAllAvailableTools(assistant: Assistant): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        return settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        val result = try {
            sessionRegistry.callTool(serverId, toolName, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val name = settingsStore.settingsFlow.value.mcpServers.find { it.id == serverId }?.commonOptions?.name.orEmpty()
            return listOf(UIMessagePart.Text(buildJsonObject {
                put("tool_error", true)
                put("error_code", if (e is McpClientUnavailableException) "mcp_not_connected" else "mcp_call_failed")
                put("server", name)
                put("tool", toolName)
                put("error", safeMcpError(e))
                put("execution_outcome", "unconfirmed")
                put("automatic_execution_retry", false)
                put("next_action", "Inspect mcp_status and the exact returned error. A connection failure does not prove a binary, dictionary or file is missing. Do not repeat a potentially state-changing operation without checking its outcome.")
            }.toString()))
        }
        return result.toMessageParts(::convertImageContentToFilePart)
    }

    suspend fun addClient(config: McpServerConfig) = sessionRegistry.addClient(config)

    suspend fun removeClient(config: McpServerConfig) = sessionRegistry.removeClient(config)

    suspend fun syncAll() = sessionRegistry.syncAll()

    suspend fun syncSelected(assistant: Assistant, force: Boolean = true) =
        sessionRegistry.syncSelected(assistant.mcpServers.toSet(), force)

    fun diagnosticStatus(assistant: Assistant, serverName: String = "", includeTools: Boolean = false): JsonObject = buildJsonObject {
        put("servers", buildJsonArray {
            settingsStore.settingsFlow.value.mcpServers.filter {
                it.id in assistant.mcpServers && (serverName.isBlank() || it.commonOptions.name == serverName)
            }.forEach { config ->
                add(buildJsonObject {
                    val status = statusStore.status.value[config.id] ?: McpStatus.Idle
                    put("name", config.commonOptions.name)
                    put("enabled", config.commonOptions.enable)
                    put("connection", when (status) {
                        McpStatus.Connected -> "connected"
                        McpStatus.Connecting -> "connecting"
                        is McpStatus.Reconnecting -> "reconnecting"
                        McpStatus.NeedsAuthorization, McpStatus.Authorizing -> "authorization_required"
                        is McpStatus.Error -> "error"
                        McpStatus.Idle -> "idle"
                    })
                    if (status is McpStatus.Error) put("error", safeMcpError(IllegalStateException(status.message)))
                    put("cached_tool_count", config.commonOptions.tools.size)
                    put("enabled_tool_count", config.commonOptions.tools.count { it.enable })
                    put("catalog_verified_this_connection", status == McpStatus.Connected)
                    put("filesystem", "Paths and installed binaries belong to this server's runtime; other servers may have different files and permissions.")
                    if (includeTools) {
                        put("server_instructions", config.commonOptions.serverInstructions)
                        put("tools", buildJsonArray {
                            config.commonOptions.tools.filter { it.enable }.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", "mcp__${config.commonOptions.name}__${tool.name}")
                                    put("description", tool.description.orEmpty())
                                    put("saved_argument_names", buildJsonArray {
                                        tool.validSavedArguments().keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })
        put("network_egress", "unverified; a connected MCP service or enabled VPN does not establish the egress of any tool")
        put("note", "Cached tool names are not a live connection test. Use each server's read-only environment/inventory tools for installed binaries and local files. A successful handshake is not proof of command permissions or target access.")
    }

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val extension = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$extension",
            mimeType = image.mimeType,
        )
        return UIMessagePart.Image(url = filesManager.getFile(entity).toUri().toString())
    }
}
