package com.constx.onvyra.ui.components.message

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.constx.onvyra.R
import com.constx.onvyra.data.media.VideoPlaybackKind
import com.constx.onvyra.data.media.VideoPlaybackSource
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

/** Owns playback outside AndroidView's hosts so fullscreen never creates a second player. */
@SuppressLint("SetJavaScriptEnabled", "UnsafeOptInUsageError")
internal class VideoPlaybackController(private val context: Context, private val httpClient: OkHttpClient) {
    var view: View? = null
        private set
    var player: ExoPlayer? = null
        private set
    var source: VideoPlaybackSource? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var bufferedPositionMs by mutableStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** Transport intent remains true while buffering, unlike isPlaying. */
    var playbackRequested by mutableStateOf(false)
        private set
    var playbackEnded by mutableStateOf(false)
        private set
    var canSeek by mutableStateOf(false)
        private set
    /** Display geometry, including non-square pixels and rotation, rather than a fixed movie box. */
    var displayAspectRatio by mutableStateOf(DEFAULT_VIDEO_ASPECT_RATIO)
        private set
    var qualityOptions: List<VideoQualityOption> by mutableStateOf(emptyList())
        private set
    var selectedQualityId: String? by mutableStateOf(null)
        private set
    private var currentQualityLabel: String? by mutableStateOf(null)
    val selectedQualityLabel: String get() = when {
        source?.kind == VideoPlaybackKind.EMBEDDED -> "平台画质"
        selectedQualityId != null -> qualityOptions.firstOrNull { it.id == selectedQualityId }?.label
            ?: currentQualityLabel ?: "原始画质"
        qualityOptions.size > 1 -> currentQualityLabel?.let { "自动($it)" } ?: "自动"
        else -> currentQualityLabel ?: qualityOptions.singleOrNull()?.label ?: "清晰度"
    }
    var onVideoTouched: (() -> Unit)? = null
    private var fullscreenState by mutableStateOf(false)
    val fullscreen: Boolean get() = fullscreenState
    private var speedState by mutableStateOf(1f)
    private var temporarySpeedState by mutableStateOf<Float?>(null)
    val speed: Float get() = temporarySpeedState ?: speedState
    val temporaryFastPlayback: Boolean get() = temporarySpeedState != null
    var webRateConfirmed: Boolean? by mutableStateOf(null)
        private set
    var released = false
        private set
    private var active = true
    private var customView: View? by mutableStateOf(null)
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val scriptToken = UUID.randomUUID().toString()
    private val script = videoControlScript(scriptToken)
    private val qualityOverrides = LinkedHashMap<String, TrackSelectionOverride>()
    private var webPollInFlight = false
    private var pictureTransform = VideoPictureTransform()
    private var pictureManuallyTransformed = false

    fun load(nextSource: VideoPlaybackSource, startPositionMs: Long = 0L, playWhenReady: Boolean = false) {
        assertMainThread()
        check(!released)
        require(isWebUrl(nextSource.url) && (nextSource.audioUrl == null || isWebUrl(nextSource.audioUrl))) {
            "只支持 HTTP 或 HTTPS 视频地址"
        }
        endTemporarySpeed()
        val media = if (nextSource.kind == VideoPlaybackKind.DIRECT) createNativeMedia(nextSource) else null
        val keepNative = nextSource.kind == VideoPlaybackKind.DIRECT && player != null && view is PlayerView
        val keepFullscreen = fullscreenState
        if (!keepNative) clearContent()
        source = nextSource
        error = null
        loading = true
        positionMs = startPositionMs.coerceAtLeast(0)
        durationMs = nextSource.durationSeconds?.takeIf { it.isFinite() && it > 0 }?.times(1000)?.toLong() ?: 0L
        bufferedPositionMs = 0L
        isPlaying = false
        playbackRequested = false
        playbackEnded = false
        canSeek = false
        // Keep native geometry through a source handover until the next real video size arrives.
        // Resetting to 16:9 in between visibly changes a fullscreen crop on every switch.
        if (!keepNative) displayAspectRatio = DEFAULT_VIDEO_ASPECT_RATIO
        qualityOverrides.clear()
        qualityOptions = emptyList()
        selectedQualityId = null
        currentQualityLabel = null
        webRateConfirmed = null
        webPollInFlight = false
        when (nextSource.kind) {
            VideoPlaybackKind.DIRECT -> loadNative(requireNotNull(media), startPositionMs, playWhenReady)
            VideoPlaybackKind.EMBEDDED -> loadWeb(nextSource)
        }
        fullscreenState = keepFullscreen
    }

    private fun createNativeMedia(source: VideoPlaybackSource): androidx.media3.exoplayer.source.MediaSource {
        val network = OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(source.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, network))
        val videoItem = MediaItem.Builder().setUri(source.url).apply {
            source.mimeType?.let(::setMimeType)
        }.build()
        val media = factory.createMediaSource(videoItem)
        return if (source.audioUrl != null) MergingMediaSource(media,
            factory.createMediaSource(MediaItem.fromUri(source.audioUrl))) else media
    }

    private fun loadNative(media: androidx.media3.exoplayer.source.MediaSource, startPositionMs: Long, shouldPlay: Boolean) {
        val native = player ?: ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    loading = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                    refreshPlaybackState()
                }
                override fun onPlayerError(playbackError: PlaybackException) {
                    loading = false
                    error = "视频暂时无法播放，可重试或尝试增强解析"
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && !active) pause()
                    refreshPlaybackState()
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    refreshPlaybackState()
                }
                override fun onTracksChanged(tracks: Tracks) = updateQualityOptions(tracks)
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    currentQualityLabel = videoResolutionHeight(videoSize.width, videoSize.height)?.let { "${it}p" }
                    @Suppress("DEPRECATION")
                    val aspectRatio = videoDisplayAspectRatio(
                        videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio,
                        videoSize.unappliedRotationDegrees,
                    )
                    if (aspectRatio != null) displayAspectRatio = aspectRatio
                    view?.post { if (!released) updatePictureTransform() }
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    refreshPlaybackState()
                }
            })
        }
        player = native
        native.apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setForceLowestBitrate(false).setForceHighestSupportedBitrate(false).build()
            setMediaSource(media, startPositionMs.coerceAtLeast(0))
            setPlaybackSpeed(speed)
            playWhenReady = shouldPlay && active
            prepare()
        }
        if (view !is PlayerView) {
            view = (LayoutInflater.from(context).inflate(R.layout.view_inline_video_player, null, false) as PlayerView).apply {
                tag = "inline-video-native-player"
                player = native
                useController = false
                // The content box always uses FIT. Only that texture-backed content is
                // transformed; the fixed viewport and the Compose controls never scale.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                observeVideoTouches(this)
                clipChildren = true
                clipToPadding = true
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePictureTransform() }
                findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.addOnLayoutChangeListener {
                        _, _, _, _, _, _, _, _, _ -> updatePictureTransform()
                }
            }
        }
        view?.post { if (!released) updatePictureTransform() }
        refreshPlaybackState()
    }

    private fun loadWeb(source: VideoPlaybackSource) {
        view = WebView(context).apply {
            tag = "inline-video-web-player"
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                displayZoomControls = false
                builtInZoomControls = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            observeVideoTouches(this)
            val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (startScript) WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isWebUrl(request.url.toString())

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val scheme = request.url.scheme
                    return if (scheme in setOf("https", "http", "data", "blob")) null
                    else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true
                    webRateConfirmed = null
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    if (!startScript) view.evaluateJavascript(script, null)
                    applyWebCommand(if (active) "activate" else "pause")
                    refreshPlaybackState()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failWeb()
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400) failWeb()
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failWeb()
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    if (this@VideoPlaybackController.view === view) this@VideoPlaybackController.view = null
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) { callback.onCustomViewHidden(); return }
                    customView = view
                    customViewCallback = callback
                    fullscreenState = true
                }

                override fun onHideCustomView() = hideCustomView()
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                    result.cancel(); return true
                }
            }
            loadUrl(source.url, source.headers)
            if (!active) onPause()
        }
    }

    private fun failWeb() {
        loading = false
        error = "平台播放器暂时无法播放，可尝试增强解析或打开原链接"
    }

    fun attachTo(host: FrameLayout) {
        assertMainThread()
        val target = customView ?: view ?: return
        if (target.parent !== host) {
            (target.parent as? ViewGroup)?.removeView(target)
            host.removeAllViews()
            host.addView(target, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    fun detachFrom(host: FrameLayout) {
        assertMainThread()
        // A retiring inline/fullscreen host must not remove the view from its new host.
        host.removeAllViews()
    }

    fun setFullscreen(value: Boolean) {
        assertMainThread()
        if (fullscreenState == value) return
        endTemporarySpeed()
        if (!value && customView != null) hideCustomView()
        fullscreenState = value
        resetPictureTransform()
    }

    /** Pinch and two-finger pan affect decoded pixels, never the player or control bounds. */
    fun transformPicture(zoomFactor: Float, panX: Float, panY: Float, focusX: Float, focusY: Float) {
        assertMainThread()
        if (released) return
        val native = view as? PlayerView ?: return
        val content = native.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        if (native.width <= 0 || native.height <= 0 || content.width <= 0 || content.height <= 0) return
        pictureManuallyTransformed = true
        pictureTransform = transformVideoPicture(pictureTransform, content.width.toFloat(), content.height.toFloat(),
            native.width.toFloat(), native.height.toFloat(), zoomFactor, panX, panY, focusX, focusY,
            maxOf(4f, videoCoverScale(content.width.toFloat(), content.height.toFloat(), native.width.toFloat(), native.height.toFloat()) * 4f))
        applyPictureTransform(content)
    }

    private fun resetPictureTransform() {
        pictureManuallyTransformed = false
        pictureTransform = VideoPictureTransform()
        updatePictureTransform()
        view?.post { if (!released) updatePictureTransform() }
    }

    private fun updatePictureTransform() {
        if (released) return
        val native = view as? PlayerView ?: return
        val content = native.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        if (native.width <= 0 || native.height <= 0 || content.width <= 0 || content.height <= 0) return
        val cover = videoCoverScale(content.width.toFloat(), content.height.toFloat(), native.width.toFloat(), native.height.toFloat())
        pictureTransform = if (!pictureManuallyTransformed) VideoPictureTransform(if (fullscreen) cover else 1f)
            else clampVideoPicture(pictureTransform, content.width.toFloat(), content.height.toFloat(),
                native.width.toFloat(), native.height.toFloat(), maxOf(4f, cover * 4f))
        applyPictureTransform(content)
    }

    private fun applyPictureTransform(content: View) {
        content.pivotX = content.width / 2f
        content.pivotY = content.height / 2f
        content.scaleX = pictureTransform.scale
        content.scaleY = pictureTransform.scale
        content.translationX = pictureTransform.offsetX
        content.translationY = pictureTransform.offsetY
    }

    private fun hideCustomView() {
        val callback = customViewCallback
        customViewCallback = null
        customView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        customView = null
        fullscreenState = false
        callback?.onCustomViewHidden()
    }

    fun setSpeed(value: Float) {
        assertMainThread()
        if (released || !value.isFinite()) return
        temporarySpeedState = null
        speedState = value.coerceIn(0.25f, 4f)
        player?.setPlaybackSpeed(speed)
        webRateConfirmed = null
        applyWebCommand("rate")
    }

    /** A held gesture never changes the selected permanent rate or starts paused audio. */
    fun beginTemporarySpeed(): Boolean {
        assertMainThread()
        if (released || !active || source?.kind != VideoPlaybackKind.DIRECT) return false
        refreshPlaybackState()
        if (!playbackRequested) return false
        if (temporarySpeedState == null) temporarySpeedState = maxOf(2f, speedState)
        player?.setPlaybackSpeed(speed)
        return true
    }

    fun endTemporarySpeed() {
        assertMainThread()
        if (temporarySpeedState == null) return
        temporarySpeedState = null
        if (!released) player?.setPlaybackSpeed(speedState)
    }

    private fun applyWebCommand(command: String) {
        val web = view as? WebView ?: return
        web.evaluateJavascript(videoCommandScript(command, speed), null)
        if (command == "rate") web.postDelayed({
            if (!released && view === web) {
                web.evaluateJavascript("!!(window.__rikVideo && window.__rikVideo.confirmed)") { result ->
                    if (!released && view === web) webRateConfirmed = result == "true"
                }
            }
        }, 500)
    }

    /** No automatic resume: a user can scroll past videos without restarting audio. */
    fun setActive(value: Boolean) {
        assertMainThread()
        active = value
        (view as? WebView)?.let { if (value) it.onResume() else it.onPause() }
        if (!value) pause() else applyWebCommand("activate")
    }

    fun pause() {
        assertMainThread()
        endTemporarySpeed()
        player?.pause()
        applyWebCommand("pause")
        isPlaying = false
        playbackRequested = false
    }

    /** UI polls at a modest interval while visible; all player/WebView access stays on Main. */
    fun refreshPlaybackState() {
        assertMainThread()
        if (released) return
        player?.let { native ->
            val knownDuration = native.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
            if (knownDuration != null) durationMs = knownDuration
            positionMs = native.currentPosition.coerceAtLeast(0)
            bufferedPositionMs = native.bufferedPosition.coerceAtLeast(0)
            isPlaying = native.isPlaying
            playbackEnded = native.playbackState == Player.STATE_ENDED
            playbackRequested = native.playWhenReady && !playbackEnded
            canSeek = native.isCurrentMediaItemSeekable && durationMs > 0 &&
                native.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            native.videoFormat?.let { format ->
                videoResolutionHeight(format.width, format.height)?.let { currentQualityLabel = "${it}p" }
            }
            return
        }
        val web = view as? WebView ?: return
        if (webPollInFlight) return
        webPollInFlight = true
        web.evaluateJavascript("window.__rikVideo ? JSON.stringify(window.__rikVideo.playback || null) : null") { raw ->
            if (released || view !== web) return@evaluateJavascript
            webPollInFlight = false
            val snapshot = runCatching {
                val decoded = JSONTokener(raw).nextValue()
                if (decoded is String) JSONObject(decoded) else null
            }.getOrNull() ?: return@evaluateJavascript
            if (!snapshot.optBoolean("hasMedia")) return@evaluateJavascript
            positionMs = snapshot.optDouble("position", 0.0).finiteMilliseconds()
            durationMs = snapshot.optDouble("duration", 0.0).finiteMilliseconds()
            bufferedPositionMs = snapshot.optDouble("buffered", 0.0).finiteMilliseconds()
            isPlaying = snapshot.optBoolean("playing")
            playbackRequested = isPlaying
            canSeek = snapshot.optBoolean("seekable") && durationMs > 0
            videoDisplayAspectRatio(snapshot.optInt("width"), snapshot.optInt("height"))?.let {
                displayAspectRatio = it
            }
        }
    }

    fun seekTo(requestedPositionMs: Long) {
        assertMainThread()
        if (released || !canSeek) return
        val requested = requestedPositionMs.coerceIn(0, durationMs)
        player?.let {
            it.seekTo(requested)
            positionMs = requested
            return
        }
        (view as? WebView)?.evaluateJavascript(videoSeekScript(requested / 1000.0), null)
    }

    fun togglePlay() {
        assertMainThread()
        if (released || !active) return
        player?.let { native ->
            // ENDED can still have playWhenReady=true. Test it before the pause branch,
            // otherwise the first replay press merely pauses at the end of the movie.
            if (native.playbackState == Player.STATE_ENDED) {
                native.seekTo(0)
                native.play()
            } else if (native.playWhenReady) pause() else native.play()
            refreshPlaybackState()
            return
        }
        applyWebCommand(if (isPlaying) "pause" else "play")
    }

    fun setQuality(id: String?) {
        assertMainThread()
        val native = player ?: return
        val override = if (id == null) null else qualityOverrides[id] ?: return
        val builder = native.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .setForceLowestBitrate(false).setForceHighestSupportedBitrate(false)
        if (override != null) builder.setOverrideForType(override)
        native.trackSelectionParameters = builder.build()
        selectedQualityId = id
    }

    private fun updateQualityOptions(tracks: Tracks) {
        qualityOverrides.clear()
        val descriptions = buildList {
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
                repeat(group.length) { trackIndex ->
                    if (group.isTrackSupported(trackIndex)) {
                        val format = group.getTrackFormat(trackIndex)
                        val id = "$groupIndex:$trackIndex:${group.mediaTrackGroup.id}:${format.id.orEmpty()}"
                        qualityOverrides[id] = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                        add(VideoTrackDescription(id, format.width, format.height, format.bitrate))
                    }
                }
            }
        }
        qualityOptions = videoQualityOptions(descriptions)
        if (selectedQualityId != null && selectedQualityId !in qualityOverrides) selectedQualityId = null
        refreshPlaybackState()
    }

    /** Observe a short tap without swallowing platform-player gestures, forms, or native controls. */
    @SuppressLint("ClickableViewAccessibility")
    private fun observeVideoTouches(target: View) {
        var downTime = 0L
        var downX = 0f
        var downY = 0f
        var multiplePointers = false
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downTime = event.eventTime; downX = event.rawX; downY = event.rawY; multiplePointers = false }
                MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true
                MotionEvent.ACTION_UP -> {
                    if (!multiplePointers && downTime > 0 && event.eventTime - downTime <= 500 &&
                        kotlin.math.abs(event.rawX - downX) <= slop && kotlin.math.abs(event.rawY - downY) <= slop) onVideoTouched?.invoke()
                    downTime = 0
                }
                MotionEvent.ACTION_CANCEL -> downTime = 0
            }
            false
        }
    }

    private fun clearContent() {
        endTemporarySpeed()
        hideCustomView()
        player?.release()
        player = null
        when (val oldView = view) {
            is WebView -> {
                oldView.stopLoading()
                oldView.webChromeClient = null
                oldView.webViewClient = WebViewClient()
                oldView.loadUrl("about:blank")
                (oldView.parent as? ViewGroup)?.removeView(oldView)
                oldView.removeAllViews()
                oldView.destroy()
            }
            is PlayerView -> {
                oldView.player = null
                (oldView.parent as? ViewGroup)?.removeView(oldView)
            }
        }
        view = null
        positionMs = 0
        durationMs = 0
        bufferedPositionMs = 0
        isPlaying = false
        playbackRequested = false
        playbackEnded = false
        canSeek = false
        pictureTransform = VideoPictureTransform()
        pictureManuallyTransformed = false
        displayAspectRatio = DEFAULT_VIDEO_ASPECT_RATIO
        qualityOverrides.clear()
        qualityOptions = emptyList()
        selectedQualityId = null
        currentQualityLabel = null
        webPollInFlight = false
    }

    fun release() {
        assertMainThread()
        if (released) return
        released = true
        onVideoTouched = null
        clearContent()
        source = null
    }
}

private fun isWebUrl(value: String): Boolean = Uri.parse(value).scheme in setOf("http", "https")
private fun assertMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Video playback requires the Android main thread" }
}
private fun Double.finiteMilliseconds(): Long = if (isFinite() && this > 0) (this * 1000).toLong() else 0L
