package com.constx.onvyra.data.update

import android.content.Context
import com.constx.onvyra.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

internal sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data class Release(val release: AppUpdateRelease, val newer: Boolean) : AppUpdateUiState
    data class Message(val failed: Boolean) : AppUpdateUiState
}

/** Dedicated public download connection: no model-provider client, credentials or interceptors. */
internal class AppUpdateRepository private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("onvyra_app_updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val verifier = AppUpdateManifestVerifier(AppUpdateSource.PUBLIC_KEY_BASE64, AppUpdateSource.ORIGIN)
    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state = _state.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()
    private var lastAutomaticAttempt = 0L
    private var lastAutomaticFailed = false

    fun check(manual: Boolean = false) {
        if (_checking.value) return
        val now = System.currentTimeMillis()
        if (!manual && !shouldCheckAutomaticUpdate(lastAutomaticAttempt, lastAutomaticFailed, now)) return
        if (!manual) {
            lastAutomaticAttempt = now
        }
        _checking.value = true
        scope.launch {
            try {
                val release = runInterruptible(Dispatchers.IO) { fetch() }
                if (!manual) lastAutomaticFailed = false
                val installed = BuildConfig.VERSION_CODE.toLong()
                if (manual) {
                    _state.value = if (release.versionCode >= installed) AppUpdateUiState.Release(release, release.versionCode > installed)
                        else AppUpdateUiState.Message(failed = false)
                } else if (shouldShowAutomaticUpdate(release.versionCode, installed,
                        preferences.getLong("ignored_version", 0), preferences.getLong("deferred_version", 0),
                        preferences.getLong("deferred_until", 0), System.currentTimeMillis())) {
                    _state.value = AppUpdateUiState.Release(release, newer = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!manual) lastAutomaticFailed = true
                if (manual) _state.value = AppUpdateUiState.Message(failed = true)
            } finally {
                _checking.value = false
            }
        }
    }

    private fun fetch(): AppUpdateRelease {
        val request = Request.Builder().url(AppUpdateSource.MANIFEST_URL)
            .header("Accept", "application/json").header("Cache-Control", "no-cache")
            .header("User-Agent", "Onvyra-Update/${BuildConfig.VERSION_NAME}").get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update service unavailable")
            val body = response.body ?: throw IOException("Missing update document")
            if (body.contentLength() > AppUpdateManifestVerifier.MAX_ENVELOPE_BYTES) throw IOException("Update document too large")
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > AppUpdateManifestVerifier.MAX_ENVELOPE_BYTES) throw IOException("Update document too large")
                    bytes.write(buffer, 0, count)
                }
            }
            verifier.verify(bytes.toByteArray())
        }
    }

    fun dismiss() { _state.value = AppUpdateUiState.Idle }

    fun remindLater(release: AppUpdateRelease) {
        preferences.edit().putLong("deferred_version", release.versionCode)
            .putLong("deferred_until", System.currentTimeMillis() + UPDATE_REMINDER_DELAY_MS).apply()
        dismiss()
    }

    fun ignore(release: AppUpdateRelease) {
        preferences.edit().putLong("ignored_version", release.versionCode).apply()
        dismiss()
    }

    companion object {
        @Volatile private var instance: AppUpdateRepository? = null
        fun get(context: Context): AppUpdateRepository = instance ?: synchronized(this) {
            instance ?: AppUpdateRepository(context.applicationContext).also { instance = it }
        }
    }
}

/** Public verification material only; the signing private key is never shipped in the app. */
internal object AppUpdateSource {
    const val ORIGIN = "https://162.14.64.237"
    const val MANIFEST_URL = "$ORIGIN/onvyra-updates/latest.json"
    const val PUBLIC_KEY_BASE64 = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAoS1Yn+s3CtYfJLH3RBO62shVthm1/jzkcfQ7JkXQuO6bk16HVp5zonkc1ERgL5onhQKXm9XTmPn3lB9D1TRgzs8fVefbGJ8eU/HIrJUOjiq86lKRDbJWzdYN0agDLNcQuihuZR9FzePEjkpw0/u45YiTsrpd+miU0sN364WFbLD45EhxrB6T+heyil+8UpxFGyu4/WLwQ3buOYfbskqcjBhItryU0ONByOItQ5ZDNKvKN9IDAh1Qt4072FknY6/Hqh4hFWbfFivqvNgUL3+RvlAQLNwB0Q6VwmIrm3PGotYWVxM9aTczUleVTVoVjGDEOVFphuKWz24a14ooFFitQ83mqma1xSK/xqXhvvxdtzFU6iEwTyPoQ2j3sUnbYpDf/z/P7efYc5NLzpUz2EofDCRhKB+ztiq8+KJYSUIF1PGRsg94t1BfXr4YpeKErHPGgzrdFk/ZD3Gwns7ngBd+NcspB3KvWZdC5VtoF71R+ayIpchYwHHqF6hAH/aWzhPHAgMBAAE="
}
