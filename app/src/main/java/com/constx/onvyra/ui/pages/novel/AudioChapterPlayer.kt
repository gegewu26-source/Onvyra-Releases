package com.constx.onvyra.ui.pages.novel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.getKoin

/** The selected source supplies a real audio URL; the player never substitutes a text or TTS source. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun AudioChapterPlayer(url: String, title: String, cover: String, referer: String,
    headers: Map<String, String>, ink: Color, coverBook: NovelBook? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val client = remember {
        runCatching { getKoin().getOrNull<OkHttpClient>(named("readerHttpClient")) }.getOrNull()
            ?: OkHttpClient()
    }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var playing by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(true) }
    var position by remember(url) { mutableLongStateOf(0L) }
    var duration by remember(url) { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seek by remember(url) { mutableStateOf<Float?>(null) }
    val player = remember(context, url, headers) {
        val properties = buildMap {
            if (referer.isNotBlank()) put("Referer", referer)
            putAll(headers)
        }
        val network = OkHttpDataSource.Factory(client).setDefaultRequestProperties(properties)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, network)))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
                setHandleAudioBecomingNoisy(true)
            }
    }
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(exception: PlaybackException) {
                error = "音频加载失败：${exception.errorCodeName}，可重试或换源。"
            }
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        owner.lifecycle.addObserver(lifecycle)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        onDispose {
            owner.lifecycle.removeObserver(lifecycle)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        NovelNetworkImage(cover, coverBook?.coverReferer ?: referer, title, Modifier.width(120.dp).height(164.dp)
            .clip(RoundedCornerShape(8.dp)), requestHeaders = coverBook?.artworkHeaders ?: headers, coverBook = coverBook)
        Text(title, color = ink, fontSize = 18.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 20.dp))
        if (error != null) Text(error.orEmpty(), color = ink, textAlign = TextAlign.Center)
        if (buffering && error == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Slider(value = seek ?: if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { seek = it }, onValueChangeFinished = {
                seek?.let { player.seekTo((it * duration).toLong()) }; seek = null
            }, enabled = duration > 0)
        Text("${audioTime(position)} / ${audioTime(duration)}", color = ink.copy(alpha = .65f), fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { player.seekTo((position - 15_000).coerceAtLeast(0)) }) { Text("后退15秒") }
            Button(onClick = {
                if (error != null) { error = null; player.prepare() }
                if (playing) player.pause() else player.play()
            }) { Text(if (playing) "暂停" else if (error != null) "重试播放" else "播放") }
            TextButton(onClick = { player.seekTo(if (duration > 0) (position + 15_000).coerceAtMost(duration) else position + 15_000) }) {
                Text("前进15秒")
            }
        }
        TextButton(onClick = {
            speed = when (speed) { .75f -> 1f; 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> .75f }
            player.setPlaybackSpeed(speed)
        }) { Text("播放速度 ${speed}×") }
    }
}

private fun audioTime(value: Long): String {
    val seconds = value / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
