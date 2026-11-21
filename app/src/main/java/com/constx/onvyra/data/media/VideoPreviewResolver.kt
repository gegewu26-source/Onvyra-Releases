package com.constx.onvyra.data.media

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import com.constx.onvyra.security.PrivateMediaAccess
import com.constx.onvyra.security.PrivateMediaAccessDeniedException

/** Keeps public embeds lightweight; the bundled extractor is initialized only on request. */
internal class VideoPreviewResolver(context: Context, httpClient: OkHttpClient) {
    private val appContext = context.applicationContext
    private val publicResolver = VideoLinkResolver(httpClient)
    private val pageReader = VideoWebPageReader(httpClient)
    private val externalSiteClient = ExternalVideoSiteClient(httpClient, appContext)
    private val enhancedExtractor by lazy { EnhancedVideoExtractor(appContext) }

    suspend fun inspectPage(url: String): MovieWebPage {
        PrivateMediaAccess.requireAllowed(url = url)
        return pageReader.read(url).page.also {
            PrivateMediaAccess.requireAllowed(url = url)
            PrivateMediaAccess.requireAllowed(url = it.url)
        }
    }

    suspend fun resolve(link: VideoLink, enhanced: Boolean = false): VideoPlaybackSource {
        PrivateMediaAccess.requireAllowed(url = link.url)
        return resolveAllowed(link, enhanced).also { source ->
            PrivateMediaAccess.requireAllowed(url = link.url)
            PrivateMediaAccess.requireAllowed(url = source.url)
            source.audioUrl?.let { PrivateMediaAccess.requireAllowed(url = it) }
        }
    }

    private suspend fun resolveAllowed(link: VideoLink, enhanced: Boolean): VideoPlaybackSource {
        val url = link.url.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
            ?: throw IOException("视频网页地址无效")
        if (identifyExternalVideoSite(url.toString()) != null) {
            return externalSiteClient.resolveVideo(url.toString())
        }
        val parsed = parseVideoLink(url.toString())
        if (!enhanced) return if (parsed != null) publicResolver.resolve(parsed) else VideoPlaybackSource(
            url = url.toString(), kind = VideoPlaybackKind.EMBEDDED, title = "视频网页",
        )
        // Already-known media is verified with the actual playback network stack. Sending an
        // MP4/HLS URL through a webpage extractor adds startup cost and can trigger bot challenges.
        if (parsed?.platform == VideoPlatform.DIRECT) return publicResolver.verifyDirect(parsed)
        if (parsed == null || parsed.platform == VideoPlatform.OTHER) {
            // Read actual HTML media references first; generic pages need not initialize Python.
            try {
                val page = withTimeoutOrNull(10_000) { pageReader.read(url.toString()) }
                page?.page?.url?.let { PrivateMediaAccess.requireAllowed(url = it) }
                for (media in page?.mediaUrls.orEmpty().take(4)) {
                    PrivateMediaAccess.requireAllowed(url = media)
                    val direct = parseVideoLink(media)?.takeIf { it.platform == VideoPlatform.DIRECT } ?: continue
                    try {
                        return publicResolver.verifyDirect(direct).copy(title = page!!.page.title.takeIf { it.isNotBlank() })
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (denied: PrivateMediaAccessDeniedException) { throw denied }
                    catch (_: Exception) { /* Another actual media tag or the enhanced extractor can work. */ }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: PrivateMediaAccessDeniedException) { throw denied }
            catch (_: Exception) { /* Retain the existing extractor for dynamic/video-platform pages. */ }
        }
        val canonical = try {
            if (parsed != null) publicResolver.resolveLink(parsed) else VideoLink(url.toString(), VideoPlatform.OTHER)
        } catch (denied: PrivateMediaAccessDeniedException) { throw denied }
        catch (error: IOException) {
            // The extractor may handle a platform's cookie challenge or redirect itself.
            parsed ?: throw error
        }
        val originalUrl = canonicalExtractorUrl(canonical)
        PrivateMediaAccess.requireAllowed(url = originalUrl)
        // CookieManager returns only cookies matching this URL. The extractor writes these to an
        // exact-host, short-lived cookie jar; they are never sent as headers to unrelated CDNs.
        val cookies = withContext(Dispatchers.Main) {
            runCatching { CookieManager.getInstance().getCookie(originalUrl) }.getOrNull()
        }
        return enhancedExtractor.resolve(canonical, cookies)
    }
}
