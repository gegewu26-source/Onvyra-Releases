package com.constx.onvyra.data.netdisk

import android.content.Context
import com.constx.onvyra.data.netdisk.providers.AliyunNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.IlanzouNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.LanzouNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.Pan123NetDiskProvider
import com.constx.onvyra.data.netdisk.providers.QuarkNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.UcNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.BaiduNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.XunleiNetDiskProvider
import com.constx.onvyra.data.netdisk.providers.Pan115NetDiskProvider
import com.constx.onvyra.data.netdisk.providers.C139NetDiskProvider
import com.constx.onvyra.data.netdisk.providers.GuangyaNetDiskProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.WeakHashMap

class NetDiskRepository(context: Context) {
    private val app = context.applicationContext
    private val credentials = credentialStore(app)
    private val transports = List(11) { NetDiskHttp() }
    private val providers: Map<NetDiskPlatform, NetDiskProvider> = listOf(
        LanzouNetDiskProvider(transports[0]), IlanzouNetDiskProvider(transports[1]), Pan123NetDiskProvider(transports[2]),
        AliyunNetDiskProvider(transports[3]), QuarkNetDiskProvider(transports[4]), UcNetDiskProvider(transports[5]),
        BaiduNetDiskProvider(transports[6]), XunleiNetDiskProvider(transports[7]), Pan115NetDiskProvider(transports[8]),
        C139NetDiskProvider(transports[9]), GuangyaNetDiskProvider(transports[10])
    ).associateBy { it.platform }

    init { cookieScopes.register(transports) }

    val platforms: List<NetDiskPlatform> = providers.keys.toList()

    fun detect(text: String, password: String = ""): NetDiskShare = NetDiskLinkParser.parse(text, password).also {
        if (it.platform !in providers) throw NetDiskException("unsupported_platform", "已识别为${it.platform.label}，本版本尚未接入该网盘。")
    }

    suspend fun open(text: String, password: String = ""): NetDiskSession = withContext(Dispatchers.IO) {
        val share = detect(text, password)
        cookieScopes.withCredentials({ credentials.get(share.platform) }) { credential ->
            providers.getValue(share.platform).open(share, credential)
        }
    }

    suspend fun list(session: NetDiskSession, directoryId: String = "", cursor: String? = null): NetDiskListing =
        withContext(Dispatchers.IO) {
            cookieScopes.withCredentials({ credentials.get(session.share.platform) }) { credential ->
                providers.getValue(session.share.platform).list(session, directoryId, cursor, credential)
            }
        }

    suspend fun resolve(session: NetDiskSession, file: NetDiskFile): NetDiskDownload {
        if (file.isDirectory) throw NetDiskException("file_required", "请先打开文件夹，再选择需要解析的文件。")
        return withContext(Dispatchers.IO) {
            cookieScopes.withCredentials({ credentials.get(session.share.platform) }) { credential ->
                providers.getValue(session.share.platform).resolve(session, file, credential).also(::validateDownload)
            }
        }
    }

    fun credential(platform: NetDiskPlatform): String = cookieScopes.read { credentials.get(platform) }

    fun saveCredential(platform: NetDiskPlatform, value: String) {
        // The chat tool and the page have independent transports but share saved credentials.
        cookieScopes.updateCredentials { credentials.put(platform, value.trim()) }
    }

    private fun validateDownload(link: NetDiskDownload) {
        val url = link.url.toHttpUrlOrNull()
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty())
            throw NetDiskException("invalid_download", "网盘没有返回有效的下载地址。")
        if (link.headers.any { (key, value) -> key.any(Char::isISOControl) || value.any { it == '\r' || it == '\n' } })
            throw NetDiskException("invalid_download_headers", "下载信息包含无效请求头，已停止下载。")
    }

    companion object {
        private val cookieScopes = NetDiskCookieScopes()
        @Volatile private var sharedCredentials: NetDiskCredentialStore? = null
        private fun credentialStore(context: Context): NetDiskCredentialStore = sharedCredentials ?: synchronized(this) {
            sharedCredentials ?: NetDiskCredentialStore(context).also { sharedCredentials = it }
        }
    }
}

/** Weak registrations avoid retaining a closed page's transports for the lifetime of the app. */
internal class NetDiskCookieScopes {
    private val transports = WeakHashMap<NetDiskHttp, Unit>()
    private val operations = HashSet<Job>()

    @Synchronized fun register(values: Iterable<NetDiskHttp>) {
        values.forEach { transports[it] = Unit }
    }

    @Synchronized fun updateCredentials(update: () -> Unit) {
        update()
        operations.toList().forEach { it.cancel(NetDiskCredentialsChangedCancellation()) }
        // clearCookies also retires in-flight response cookies from the previous account.
        transports.keys.forEach(NetDiskHttp::clearCookies)
    }

    @Synchronized fun <T> read(block: () -> T): T = block()

    suspend fun <T> withCredentials(read: () -> String, block: suspend (String) -> T): T = try {
        coroutineScope {
            val operation = currentCoroutineContext().job
            val credential = synchronized(this@NetDiskCookieScopes) {
                operations.add(operation)
                try { read() } catch (error: Throwable) { operations.remove(operation); throw error }
            }
            try {
                block(credential).also { currentCoroutineContext().ensureActive() }
            } finally {
                synchronized(this@NetDiskCookieScopes) { operations.remove(operation) }
            }
        }
    } catch (_: NetDiskCredentialsChangedCancellation) {
        throw NetDiskException("invalid_credential", "网盘账号已变更，请重新解析分享后重试。")
    }
}

private class NetDiskCredentialsChangedCancellation : CancellationException("Netdisk credentials changed")

internal fun safeNetDiskFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").trim().trim('.').take(180).ifBlank { "网盘文件" }
